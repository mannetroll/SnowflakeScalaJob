package eadlgddemo

import java.nio.file.Path
import com.snowflake.snowpark.{DataFrame, Session}
import scala.collection.mutable.ArrayBuffer

/** Constants calculated by hand; these do not call the business transformation. */
object ReferenceCohort {
  // account ID, EAD, collateral PV, unsecured PV, capped recovery, LGD amount
  val accounts: Vector[Vector[BigDecimal]] = Vector(
    Vector(1, 0, 0, 0, 0, 0), Vector(2, 0, 0, 0, 0, 0), Vector(3, 0, 0, 0, 0, 0),
    Vector(4, 150, 0, 0, 0, 150), Vector(5, 100, 0, 100, 100, 0), Vector(6, 100, 0, 250, 100, 0),
    Vector(7, 100, 60, 0, 60, 40), Vector(8, 100, 180, 0, 100, 0), Vector(9, 100, 0, 0, 0, 100),
    Vector(10, 100, 50, 30, 80, 20), Vector(11, 100, 0, 40, 40, 60), Vector(12, 100, 0, 0, 0, 100)
  ).map(_.map(BigDecimal(_)))
  // customer ID, total EAD, capped recovery, LGD amount, expected loss, top account
  val customers: Vector[Vector[BigDecimal]] = Vector(
    Vector("1", "0", "0", "0", "0", "1"),
    Vector("2", "350", "200", "150", "3", "4"),
    Vector("3", "300", "160", "140", "2.8", "7"),
    Vector("4", "300", "120", "180", "3.6", "10")
  ).map(_.map(BigDecimal(_)))
}

final class ResultValidator(session: Session, connection: ConnectionConfig, profiler: QueryProfiler,
    registry: ObjectRegistry, runId: String, dir: Path, expectedExecutions: Set[String]) {
  private val checks = ArrayBuffer.empty[Map[String, Any]]
  private var validatedExecutions = Set.empty[String]
  private var correctnessComplete = false
  private var deterministic = false
  private def persist(): Unit = Json.write(dir.resolve("validation-summary.json"), Map(
    "correctnessVerified" -> correctnessComplete, "determinismVerified" -> deterministic,
    "expectedExecutions" -> expectedExecutions.toVector.sorted,
    "validatedExecutions" -> validatedExecutions.toVector.sorted,
    "checks" -> checks.toVector))
  private def check(label: String, actual: BigDecimal, expected: BigDecimal = 0): Unit = {
    checks += Map("check" -> label, "actual" -> actual, "expected" -> expected, "passed" -> (actual == expected))
    persist()
    require(actual == expected, s"Validation failed: $label, expected $expected, got $actual")
  }
  private def zero(label: String, sql: String): Unit = check(label, Sql.scalar(session, sql))
  private def view(result: JobResult, label: String, df: DataFrame): String = {
    val name = connection.qualified(s"EAD_${runId}_${result.execution}_CHECK_$label")
    df.createOrReplaceTempView(name)
    registry.add("VIEW", name)
    name
  }
  def validate(result: JobResult, inputs: LoadedInputs, config: DemoConfig): Unit = {
    require(expectedExecutions(result.execution), "Unexpected job execution for validation")
    profiler.useExecution(result.execution)
    profiler.phase("VALIDATION")
    val accountCount = config.customerCount.toLong * 3
    val c1 = view(result, "CP1", result.cp1)
    val c2 = view(result, "CP2", result.cp2)
    val c3 = view(result, "CP3", result.cp3)
    Vector(c1, c2, c3).zipWithIndex.foreach { case (name, i) =>
      check(s"${result.execution} CP${i + 1} account rows", Sql.scalar(session, s"SELECT COUNT(*) FROM $name"), BigDecimal(accountCount))
      zero(s"CP${i + 1} account uniqueness", s"SELECT COUNT(*) FROM (SELECT ACCOUNT_ID FROM $name GROUP BY ACCOUNT_ID HAVING COUNT(*) <> 1)")
      zero(s"CP${i + 1} no lost or orphan accounts", s"SELECT COUNT(*) FROM (SELECT ACCOUNT_ID FROM ${inputs("ACCOUNTS")} MINUS SELECT ACCOUNT_ID FROM $name)")
      zero(s"CP${i + 1} EAD domains", s"SELECT COUNT(*) FROM $name WHERE EAD IS NULL OR EAD < 0 OR DRAWN_BALANCE < 0 OR UNDRAWN < 0 OR EAD < DRAWN_BALANCE")
    }
    zero("EAD formula independently from landing inputs", s"""SELECT COUNT(*) FROM $c1 p JOIN ${inputs("ACCOUNTS")} a USING (ACCOUNT_ID)
      WHERE p.EAD <> CAST(a.DRAWN_CENTS / 100.0 + CASE a.PRODUCT_TYPE WHEN 'REVOLVING' THEN 0.5
      WHEN 'TERM' THEN 0.75 ELSE 1 END * GREATEST(a.LIMIT_CENTS-a.DRAWN_CENTS,0)/100.0 AS NUMBER(24,6))""")
    val allocations = view(result, "ALLOCATIONS", result.allocations)
    val collAccounts = view(result, "COLLATERAL", result.collateralAccounts)
    check("collateral join cardinality equals link count", Sql.scalar(session, s"SELECT COUNT(*) FROM $allocations"), BigDecimal(inputs.counts("ACCOUNT_COLLATERAL")))
    zero("collateral aggregation one row per account", s"SELECT COUNT(*) FROM (SELECT ACCOUNT_ID FROM $collAccounts GROUP BY ACCOUNT_ID HAVING COUNT(*) <> 1)")
    zero("collateral aggregate cardinality bounded", s"SELECT IFF(COUNT(*) <= $accountCount, 0, 1) FROM $collAccounts")
    zero("allocation fractions sum to one", s"SELECT COUNT(*) FROM (SELECT COLLATERAL_ID FROM $allocations GROUP BY COLLATERAL_ID HAVING ABS(SUM(ALLOCATION_FRACTION)-1) > COUNT(*) * 0.000000000001)")
    zero("collateral conservation per asset within rounding bound", s"""SELECT COUNT(*) FROM (SELECT COLLATERAL_ID FROM $allocations
      GROUP BY COLLATERAL_ID HAVING MIN(ASSET_PV) <> MAX(ASSET_PV)
        OR ABS(SUM(ALLOCATED_PV) - MAX(ASSET_PV)) > COUNT(*) * 0.000001)""")
    zero("allocated collateral conserved into CP2", s"SELECT ABS((SELECT SUM(ALLOCATED_PV) FROM $allocations) - (SELECT SUM(COLLATERAL_PV) FROM $c2))")
    val dedup = view(result, "DEDUP", result.deduplicatedRecoveries)
    val recAccounts = view(result, "RECOVERIES", result.accountRecoveries)
    zero("dedup row count equals event count", s"SELECT ABS((SELECT COUNT(*) FROM $dedup) - (SELECT COUNT(DISTINCT RECOVERY_EVENT_ID) FROM ${inputs("EXPECTED_RECOVERIES")}))")
    zero("dedup retains maximum version", s"SELECT COUNT(*) FROM $dedup d WHERE EXISTS (SELECT 1 FROM ${inputs("EXPECTED_RECOVERIES")} r WHERE r.RECOVERY_EVENT_ID=d.RECOVERY_EVENT_ID AND r.VERSION>d.VERSION)")
    zero("dedup event uniqueness", s"SELECT COUNT(*) FROM (SELECT RECOVERY_EVENT_ID FROM $dedup GROUP BY RECOVERY_EVENT_ID HAVING COUNT(*) <> 1)")
    zero("recovery aggregation bounded by accounts", s"SELECT IFF(COUNT(*) <= $accountCount,0,1) FROM $recAccounts")
    zero("recovery aggregation unique", s"SELECT COUNT(*) FROM (SELECT ACCOUNT_ID FROM $recAccounts GROUP BY ACCOUNT_ID HAVING COUNT(*) <> 1)")
    zero("every selected event aggregated exactly once", s"SELECT ABS((SELECT SUM(RECOVERY_EVENT_COUNT) FROM $recAccounts) - (SELECT COUNT(*) FROM $dedup))")
    zero("unsecured recoveries conserved into CP3", s"SELECT ABS((SELECT SUM(UNSECURED_RECOVERY_PV) FROM $recAccounts) - (SELECT SUM(UNSECURED_RECOVERY_PV) FROM $c3))")
    zero("CP3 LGD and cap domains", s"""SELECT COUNT(*) FROM $c3 WHERE LGD IS NULL OR LGD NOT BETWEEN 0 AND 1
      OR CAPPED_RECOVERY IS NULL OR CAPPED_RECOVERY < 0 OR CAPPED_RECOVERY > EAD OR LGD_AMOUNT < 0
      OR LGD_AMOUNT + CAPPED_RECOVERY <> EAD OR (EAD=0 AND LGD<>0)
      OR CAPPED_RECOVERY <> LEAST(EAD,GREATEST(COLLATERAL_PV+UNSECURED_RECOVERY_PV,0))""")
    val out = result.finalTable
    check("unique customer output count", Sql.scalar(session, s"SELECT COUNT(*) FROM $out"), BigDecimal(config.customerCount))
    zero("customer date uniqueness", s"SELECT COUNT(*) FROM (SELECT CUSTOMER_ID, REPORTING_DATE FROM $out GROUP BY CUSTOMER_ID, REPORTING_DATE HAVING COUNT(*) <> 1)")
    zero("all customers output", s"SELECT COUNT(*) FROM (SELECT CUSTOMER_ID FROM ${inputs("CUSTOMERS")} MINUS SELECT CUSTOMER_ID FROM $out)")
    zero("final domains and account counts", s"SELECT COUNT(*) FROM $out WHERE ACCOUNT_COUNT<>3 OR TOTAL_EAD<0 OR EAD_WEIGHTED_LGD IS NULL OR EAD_WEIGHTED_LGD NOT BETWEEN 0 AND 1 OR CAPPED_RECOVERY NOT BETWEEN 0 AND TOTAL_EAD OR CURRENCY<>'EUR' OR REPORTING_DATE<>DATE ${Sql.literal(config.reportingDate.toString)}")
    zero("customer account money reconciliations", s"""SELECT COUNT(*) FROM $out c JOIN (
      SELECT CUSTOMER_ID, SUM(EAD) E, SUM(CAPPED_RECOVERY) R, SUM(LGD_AMOUNT) L,
        SUM(DRAWN_BALANCE) D, SUM(UNDRAWN) U FROM $c3 GROUP BY CUSTOMER_ID
      ) a USING (CUSTOMER_ID) WHERE c.TOTAL_EAD<>a.E OR c.CAPPED_RECOVERY<>a.R OR c.LGD_AMOUNT<>a.L
        OR c.DRAWN_BALANCE<>a.D OR c.UNDRAWN_AMOUNT<>a.U""")
    zero("weighted LGD and illustrative expected loss", s"SELECT COUNT(*) FROM $out WHERE EAD_WEIGHTED_LGD<>CAST(COALESCE(LGD_AMOUNT/NULLIF(TOTAL_EAD,0),0) AS NUMBER(18,10)) OR ILLUSTRATIVE_EXPECTED_LOSS<>ROUND(SYNTHETIC_PD*LGD_AMOUNT,6)")
    zero("customer attributes and PD match landing inputs", s"""SELECT COUNT(*) FROM $out o JOIN ${inputs("CUSTOMERS")} c USING (CUSTOMER_ID)
      WHERE o.SEGMENT<>c.SEGMENT OR o.COUNTRY<>c.COUNTRY OR o.RATING<>c.RATING OR o.SYNTHETIC_PD<>CAST(c.PD_BPS/10000.0 AS NUMBER(18,10))""")
    zero("quality flags reconcile to account evidence", s"""SELECT COUNT(*) FROM $out o JOIN (
      SELECT CUSTOMER_ID, COUNT_IF(EAD=0) Z, SUM(OVER_LIMIT) V, COUNT_IF(COLLATERAL_LINK_COUNT=0) C,
        COUNT_IF(RECOVERY_EVENT_COUNT=0) R, COUNT_IF(RECOVERY_CAPACITY>EAD) E FROM $c3 GROUP BY CUSTOMER_ID
      ) a USING (CUSTOMER_ID) WHERE o.ZERO_EAD_ACCOUNT_COUNT<>a.Z OR o.OVER_LIMIT_ACCOUNT_COUNT<>a.V
        OR o.MISSING_COLLATERAL_ACCOUNT_COUNT<>a.C OR o.MISSING_RECOVERY_ACCOUNT_COUNT<>a.R OR o.EXCESS_RECOVERY_ACCOUNT_COUNT<>a.E""")
    zero("top account belongs to customer and is maximum with deterministic tie", s"""SELECT COUNT(*) FROM $out c
      LEFT JOIN $c3 a ON c.HIGHEST_EAD_ACCOUNT_ID=a.ACCOUNT_ID
      WHERE a.ACCOUNT_ID IS NULL OR a.CUSTOMER_ID<>c.CUSTOMER_ID OR EXISTS (
        SELECT 1 FROM $c3 b WHERE b.CUSTOMER_ID=c.CUSTOMER_ID AND
          (b.EAD>a.EAD OR (b.EAD=a.EAD AND b.ACCOUNT_ID<a.ACCOUNT_ID)))""")
    val observedAccounts = session.sql(s"SELECT ACCOUNT_ID,EAD,COLLATERAL_PV,UNSECURED_RECOVERY_PV,CAPPED_RECOVERY,LGD_AMOUNT FROM $c3 WHERE CUSTOMER_ID<=4 ORDER BY ACCOUNT_ID").collect().toVector.map(r => (0 until 6).map(i => Sql.number(r.get(i))).toVector)
    check("hand-calculated account cohort", if (observedAccounts == ReferenceCohort.accounts) 0 else 1)
    val observedCustomers = session.sql(s"SELECT CUSTOMER_ID,TOTAL_EAD,CAPPED_RECOVERY,LGD_AMOUNT,ILLUSTRATIVE_EXPECTED_LOSS,HIGHEST_EAD_ACCOUNT_ID FROM $out WHERE CUSTOMER_ID<=4 ORDER BY CUSTOMER_ID").collect().toVector.map(r => (0 until 6).map(i => Sql.number(r.get(i))).toVector)
    check("hand-calculated customer cohort", if (observedCustomers == ReferenceCohort.customers) 0 else 1)
    validatedExecutions += result.execution
    correctnessComplete = validatedExecutions == expectedExecutions
    persist()
  }
  def compare(first: JobResult, second: JobResult): Unit = {
    require(correctnessComplete && validatedExecutions(first.execution) && validatedExecutions(second.execution),
      "Validate both independent job executions before comparing them")
    profiler.useExecution("COMPARISON")
    profiler.phase("VALIDATION")
    require(first.finalTable != second.finalTable && (first.cp1 ne second.cp1))
    // All output columns are business columns. Counts and uniqueness were checked above,
    // so set differences cannot conceal duplicated rows.
    zero("E1 minus E2 business rows", s"SELECT COUNT(*) FROM (SELECT * FROM ${first.finalTable} MINUS SELECT * FROM ${second.finalTable})")
    zero("E2 minus E1 business rows", s"SELECT COUNT(*) FROM (SELECT * FROM ${second.finalTable} MINUS SELECT * FROM ${first.finalTable})")
    val firstIds = first.checkpoints.flatMap(_.dataQueryIds).toSet
    val secondIds = second.checkpoints.flatMap(_.dataQueryIds).toSet
    check("independent data query IDs", if (firstIds.intersect(secondIds).isEmpty && firstIds.size >= 4 && secondIds.size >= 4) 0 else 1)
    val firstObjects = first.checkpoints.flatMap(_.materializedObjects).toSet
    val secondObjects = second.checkpoints.flatMap(_.materializedObjects).toSet
    check("independent materialized objects", if (firstObjects.intersect(secondObjects).isEmpty && firstObjects.size >= 4 && secondObjects.size >= 4) 0 else 1)
    deterministic = true
    correctnessComplete = true
    persist()
  }
}
