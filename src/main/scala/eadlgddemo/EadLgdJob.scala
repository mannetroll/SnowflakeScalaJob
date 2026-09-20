package eadlgddemo

import java.nio.file.Path
import com.snowflake.snowpark.{DataFrame, HasCachedResult, Session, Window}
import com.snowflake.snowpark.functions._

final case class JobInput(loaded: LoadedInputs, config: DemoConfig, runId: String, execution: String)
final case class JobResult(finalTable: String, execution: String,
    cp1: HasCachedResult, cp2: HasCachedResult, cp3: HasCachedResult,
    allocations: DataFrame, collateralAccounts: DataFrame, deduplicatedRecoveries: DataFrame,
    accountRecoveries: DataFrame, checkpoints: Vector[PhaseEvidence], artifactDirectory: Path)

/** Educational EUR model. Every business expression is a Snowpark server-side plan. */
final class EadLgdJob(session: Session, connection: ConnectionConfig,
    profiler: QueryProfiler, registry: ObjectRegistry, dir: Path) {
  def run(input: JobInput): JobResult = {
    val loaded = input.loaded
    val config = input.config
    val execution = Sql.identifier(input.execution)
    profiler.useExecution(execution)
    profiler.phase("BUILD_PLAN")
    val accounts = session.table(loaded("ACCOUNTS"))
    val customers = session.table(loaded("CUSTOMERS"))
    val customerAttrs = customers.select("CUSTOMER_ID", "SEGMENT", "COUNTRY", "RATING", "PD_BPS")
    val accountEadDf = accounts.join(customerAttrs, Seq("CUSTOMER_ID"))
      .withColumn("REPORTING_DATE", sqlExpr("DATEADD(day, REPORTING_DAY, DATE '1970-01-01')"))
      .withColumn("DRAWN_BALANCE", sqlExpr("CAST(DRAWN_CENTS / 100.0 AS NUMBER(24,6))"))
      .withColumn("CREDIT_LIMIT", sqlExpr("CAST(LIMIT_CENTS / 100.0 AS NUMBER(24,6))"))
      .withColumn("CCF", sqlExpr("CAST(CASE PRODUCT_TYPE WHEN 'REVOLVING' THEN 0.50 WHEN 'TERM' THEN 0.75 WHEN 'MORTGAGE' THEN 1.00 END AS NUMBER(9,6))"))
      .withColumn("UNDRAWN", sqlExpr("CAST(GREATEST(CREDIT_LIMIT - DRAWN_BALANCE, 0) AS NUMBER(24,6))"))
      .withColumn("EAD", sqlExpr("CAST(DRAWN_BALANCE + CCF * UNDRAWN AS NUMBER(24,6))"))
      .withColumn("OVER_LIMIT", sqlExpr("IFF(DRAWN_BALANCE > CREDIT_LIMIT, 1, 0)"))
    val cp1 = profiler.checkpoint("CP1_ACCOUNT_EAD", config.customerCount.toLong * 3) {
      accountEadDf.cacheResult()
    }

    profiler.phase("BUILD_PLAN")
    val linked = session.table(loaded("ACCOUNT_COLLATERAL"))
      .join(session.table(loaded("COLLATERAL_ASSETS")), Seq("COLLATERAL_ID"))
      .withColumn("WEIGHT_SUM", sum(col("ALLOCATION_WEIGHT")).over(Window.partitionBy(col("COLLATERAL_ID"))))
      .withColumn("ALLOCATION_FRACTION", sqlExpr("CAST(CAST(ALLOCATION_WEIGHT AS NUMBER(24,12)) / WEIGHT_SUM AS NUMBER(18,12))"))
      .withColumn("ASSET_PV", sqlExpr(s"""CAST(ROUND(
        GREATEST(VALUATION_CENTS / 100.0 * (1 - HAIRCUT_BPS / 10000.0) - REALIZATION_COST_CENTS / 100.0, 0)
        / POWER(1 + ${config.collateralDiscountBps} / 10000.0, MONTHS_TO_REALIZATION / 12.0), 6) AS NUMBER(24,6))"""))
    val allocations = linked.withColumn("ALLOCATED_PV", sqlExpr("CAST(ROUND(ASSET_PV * ALLOCATION_FRACTION, 6) AS NUMBER(24,6))"))
    val collateralAccounts = allocations.groupBy(col("ACCOUNT_ID")).agg(
      sum(col("ALLOCATED_PV")).as("COLLATERAL_PV"), count(lit(1)).as("COLLATERAL_LINK_COUNT"))
    val accountCollateralDf = cp1.join(collateralAccounts, Seq("ACCOUNT_ID"), "left")
      .withColumn("COLLATERAL_PV", sqlExpr("CAST(COALESCE(COLLATERAL_PV, 0) AS NUMBER(24,6))"))
      .withColumn("COLLATERAL_LINK_COUNT", coalesce(col("COLLATERAL_LINK_COUNT"), lit(0)))
    val cp2 = profiler.checkpoint("CP2_ACCOUNT_COLLATERAL", config.customerCount.toLong * 3) {
      accountCollateralDf.cacheResult()
    }

    profiler.phase("BUILD_PLAN")
    // These projected unsecured cash flows EXCLUDE collateral proceeds.
    val deduplicated = session.table(loaded("EXPECTED_RECOVERIES"))
      .withColumn("VERSION_RANK", row_number().over(Window.partitionBy(col("RECOVERY_EVENT_ID"))
        .orderBy(col("VERSION").desc, col("REVISION_ORDER").desc, col("ACCOUNT_ID").asc)))
      .filter(col("VERSION_RANK") === lit(1)).drop("VERSION_RANK")
    val discounted = deduplicated.join(cp2.select("ACCOUNT_ID", "DISCOUNT_BPS"), Seq("ACCOUNT_ID"))
      .withColumn("EVENT_PV", sqlExpr("""CAST(ROUND((RECOVERY_CENTS - COST_CENTS) / 100.0
        / POWER(1 + DISCOUNT_BPS / 10000.0, MONTHS_TO_RECOVERY / 12.0), 6) AS NUMBER(24,6))"""))
    val accountRecoveries = discounted.groupBy(col("ACCOUNT_ID")).agg(
      sum(col("EVENT_PV")).as("UNSECURED_RECOVERY_PV"), count(lit(1)).as("RECOVERY_EVENT_COUNT"))
    val accountLgdDf = cp2.join(accountRecoveries, Seq("ACCOUNT_ID"), "left")
      .withColumn("UNSECURED_RECOVERY_PV", sqlExpr("CAST(COALESCE(UNSECURED_RECOVERY_PV, 0) AS NUMBER(24,6))"))
      .withColumn("RECOVERY_EVENT_COUNT", coalesce(col("RECOVERY_EVENT_COUNT"), lit(0)))
      .withColumn("RECOVERY_CAPACITY", sqlExpr("CAST(GREATEST(COLLATERAL_PV + UNSECURED_RECOVERY_PV, 0) AS NUMBER(24,6))"))
      .withColumn("CAPPED_RECOVERY", sqlExpr("CAST(LEAST(EAD, RECOVERY_CAPACITY) AS NUMBER(24,6))"))
      .withColumn("LGD_AMOUNT", sqlExpr("CAST(EAD - CAPPED_RECOVERY AS NUMBER(24,6))"))
      .withColumn("LGD", sqlExpr("CAST(COALESCE(LGD_AMOUNT / NULLIF(EAD, 0), 0) AS NUMBER(18,10))"))
    val cp3 = profiler.checkpoint("CP3_ACCOUNT_LGD", config.customerCount.toLong * 3) {
      accountLgdDf.cacheResult()
    }

    profiler.phase("FINAL_PLAN")
    // All three operations below survive into the final CTAS: aggregation, joins,
    // and a used ROW_NUMBER window. No collection/materialization of this result.
    val totals = cp3.groupBy(col("CUSTOMER_ID"), col("REPORTING_DATE")).agg(
      count(lit(1)).as("ACCOUNT_COUNT"), sum(col("DRAWN_BALANCE")).as("DRAWN_BALANCE"),
      sum(col("UNDRAWN")).as("UNDRAWN_AMOUNT"), sum(col("EAD")).as("TOTAL_EAD"),
      sum(col("CAPPED_RECOVERY")).as("CAPPED_RECOVERY"), sum(col("LGD_AMOUNT")).as("LGD_AMOUNT"),
      sum(sqlExpr("IFF(EAD = 0, 1, 0)")).as("ZERO_EAD_ACCOUNT_COUNT"),
      sum(col("OVER_LIMIT")).as("OVER_LIMIT_ACCOUNT_COUNT"),
      sum(sqlExpr("IFF(COLLATERAL_LINK_COUNT = 0, 1, 0)")).as("MISSING_COLLATERAL_ACCOUNT_COUNT"),
      sum(sqlExpr("IFF(RECOVERY_EVENT_COUNT = 0, 1, 0)")).as("MISSING_RECOVERY_ACCOUNT_COUNT"),
      sum(sqlExpr("IFF(RECOVERY_CAPACITY > EAD, 1, 0)")).as("EXCESS_RECOVERY_ACCOUNT_COUNT"))
    val top = cp3.withColumn("EAD_RANK", row_number().over(Window.partitionBy(col("CUSTOMER_ID"))
      .orderBy(col("EAD").desc, col("ACCOUNT_ID").asc)))
      .filter(col("EAD_RANK") === lit(1))
      .select(col("CUSTOMER_ID"), col("ACCOUNT_ID").as("HIGHEST_EAD_ACCOUNT_ID"))
    val finalDf = totals.join(customerAttrs, Seq("CUSTOMER_ID"))
      .join(top, Seq("CUSTOMER_ID"))
      .withColumn("EAD_WEIGHTED_LGD", sqlExpr("CAST(COALESCE(LGD_AMOUNT / NULLIF(TOTAL_EAD, 0), 0) AS NUMBER(18,10))"))
      .withColumn("SYNTHETIC_PD", sqlExpr("CAST(PD_BPS / 10000.0 AS NUMBER(18,10))"))
      .withColumn("ILLUSTRATIVE_EXPECTED_LOSS", sqlExpr("CAST(ROUND(SYNTHETIC_PD * LGD_AMOUNT, 6) AS NUMBER(24,6))"))
      .withColumn("CURRENCY", lit("EUR")).drop("PD_BPS")
    val view = connection.qualified(s"EAD_${input.runId}_${execution}_FINAL_VIEW")
    finalDf.createOrReplaceTempView(view)
    registry.add("VIEW", view)
    val output = connection.qualified(s"EAD_${input.runId}_${execution}_OUTPUT")
    val sql = s"CREATE TRANSIENT TABLE $output AS SELECT * FROM $view"
    Json.writeText(dir.resolve(s"sql/$execution-executed-ctas.sql"), sql + ";\n")
    val combinedPath = dir.resolve("sql/executed-ctas.sql")
    val existing = if (java.nio.file.Files.exists(combinedPath)) new String(java.nio.file.Files.readAllBytes(combinedPath), java.nio.charset.StandardCharsets.UTF_8) else ""
    Json.writeText(combinedPath, existing + s"-- $execution\n$sql;\n")
    profiler.finalCtas(sql, output, config.customerCount)
    JobResult(output, execution, cp1, cp2, cp3, allocations, collateralAccounts, deduplicated,
      accountRecoveries, profiler.phases.filter(_.execution == execution), dir)
  }
}
