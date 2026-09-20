package eadlgddemo

import java.nio.file.Path
import com.snowflake.snowpark.Session
import scala.collection.mutable.ArrayBuffer

object StageFiles {
  def putUri(path: Path): String = fromAbsolutePath(path.toAbsolutePath.normalize().toString)
  private[eadlgddemo] def fromAbsolutePath(path: String): String = {
    // JDBC 3.24 strips file:// and then uses java.io.File. Its Windows form is
    // file://C:/..., not the RFC URI file:///C:/... (which leaves an invalid /C:).
    // Keep spaces literal and quote the URI for PUT; percent encoding is not decoded.
    val normalized = path.replace('\\', '/')
    require(!normalized.exists(c => "'*?[]".contains(c)),
      "JDBC PUT requires a local project path without apostrophes or wildcard characters")
    "file://" + normalized
  }
}

final case class LoadedInputs(tables: Map[String, String], counts: Map[String, Long],
    completedAtServerTime: String) {
  def apply(dataset: String): String = tables(dataset)
}

final class InputLoader(session: Session, connection: ConnectionConfig, profiler: QueryProfiler,
    registry: ObjectRegistry, runId: String, dir: Path) {
  def load(manifest: FixtureManifest): LoadedInputs = {
    require(manifest.files.map(_.dataset.name).toSet == Datasets.all.map(_.name).toSet && manifest.files.size == 5)
    val results = ArrayBuffer.empty[Map[String, Any]]
    val checks = ArrayBuffer.empty[Map[String, Any]]
    var boundary: String = null
    def persist(): Unit = Json.write(dir.resolve("load-results.json"), Map(
      "loads" -> results.toVector, "checks" -> checks.toVector, "allFiveLoadsCompletedAtServerTime" -> boundary))
    profiler.phase("FIXTURE_SETUP")
    val stage = connection.qualified(s"EAD_${runId}_STAGE")
    session.sql(s"CREATE TEMPORARY STAGE $stage").collect()
    registry.add("STAGE", stage)
    val tables = manifest.files.map(f => f.dataset.name -> connection.qualified(s"EAD_${runId}_${f.dataset.name}")).toMap
    Lifecycle.protecting {
      // Finish every upload before loading; the job is only called after every load validates.
      manifest.files.foreach { file =>
        require(FixtureGenerator.checksum(file.path) == file.sha256, s"Fixture changed: ${file.dataset.name}")
        val localUri = StageFiles.putUri(file.path)
        val uploaded = session.file.put(Sql.literal(localUri), s"@$stage/$runId/",
          Map("AUTO_COMPRESS" -> "FALSE", "OVERWRITE" -> "FALSE", "PARALLEL" -> "4"))
        results += Map("dataset" -> file.dataset.name, "operation" -> "PUT", "results" -> uploaded.toVector)
        persist()
        require(uploaded.length == 1 && uploaded.forall(r => r.status.equalsIgnoreCase("UPLOADED") &&
          r.sourceSizeBytes == file.sizeBytes && r.targetFileName.split('/').last == file.path.getFileName.toString),
          s"Incomplete or skipped upload for ${file.dataset.name}; see load-results.json")
      }
      manifest.files.foreach { file =>
        val table = tables(file.dataset.name)
        session.sql(s"CREATE TEMPORARY TABLE $table (${file.dataset.ddl})").collect()
        registry.add("TABLE", table)
        val copy = s"""COPY INTO $table FROM @$stage/$runId/
          FILES = (${Sql.literal(file.path.getFileName.toString)})
          FILE_FORMAT = (TYPE = PARQUET) MATCH_BY_COLUMN_NAME = CASE_SENSITIVE
          ON_ERROR = ABORT_STATEMENT FORCE = FALSE"""
        val copied = session.sql(copy).collect().toVector.map(r => Map[String, Any](
          "file" -> r.getString(0), "status" -> r.getString(1),
          "rowsParsed" -> Sql.number(r.get(2)).toLong, "rowsLoaded" -> Sql.number(r.get(3)).toLong,
          "errorsSeen" -> Sql.number(r.get(5)).toLong))
        results += Map("dataset" -> file.dataset.name, "operation" -> "COPY", "table" -> table,
          "sql" -> copy, "results" -> copied)
        persist()
        require(copied.size == 1 && copied.forall(r => r("status").toString.equalsIgnoreCase("LOADED") &&
          r("rowsParsed") == file.rowCount && r("rowsLoaded") == file.rowCount && r("errorsSeen") == 0L),
          s"COPY did not load every row of ${file.dataset.name}; see load-results.json")
      }
      profiler.phase("LOAD_VALIDATION")
      def check(label: String, query: String, expected: Long = 0L): Unit = {
        val actual = Sql.scalar(session, query).toLong
        checks += Map("check" -> label, "expected" -> expected, "actual" -> actual, "passed" -> (actual == expected))
        persist()
        require(actual == expected, s"Input validation failed: $label, expected $expected, got $actual")
      }
      manifest.files.foreach { f =>
        val table = tables(f.dataset.name)
        check(s"${f.dataset.name} rows", s"SELECT COUNT(*) FROM $table", f.rowCount)
        check(s"${f.dataset.name} required fields", s"SELECT COUNT(*) FROM $table WHERE " + f.dataset.fields.map(_.name + " IS NULL").mkString(" OR "))
        check(s"${f.dataset.name} unique physical key", s"SELECT COUNT(*) FROM (SELECT ${f.dataset.key.mkString(",")} FROM $table GROUP BY ${f.dataset.key.mkString(",")} HAVING COUNT(*) <> 1)")
      }
      val a = tables("ACCOUNTS"); val c = tables("CUSTOMERS"); val l = tables("ACCOUNT_COLLATERAL")
      val s = tables("COLLATERAL_ASSETS"); val r = tables("EXPECTED_RECOVERIES")
      check("account customer foreign key", s"SELECT COUNT(*) FROM $a a WHERE NOT EXISTS (SELECT 1 FROM $c c WHERE c.CUSTOMER_ID = a.CUSTOMER_ID)")
      check("link account foreign key", s"SELECT COUNT(*) FROM $l l WHERE NOT EXISTS (SELECT 1 FROM $a a WHERE a.ACCOUNT_ID = l.ACCOUNT_ID)")
      check("link collateral foreign key", s"SELECT COUNT(*) FROM $l l WHERE NOT EXISTS (SELECT 1 FROM $s s WHERE s.COLLATERAL_ID = l.COLLATERAL_ID)")
      check("recovery account foreign key", s"SELECT COUNT(*) FROM $r r WHERE NOT EXISTS (SELECT 1 FROM $a a WHERE a.ACCOUNT_ID = r.ACCOUNT_ID)")
      check("shared assets remain within a customer", s"SELECT COUNT(*) FROM (SELECT l.COLLATERAL_ID FROM $l l JOIN $a a USING (ACCOUNT_ID) GROUP BY l.COLLATERAL_ID HAVING COUNT(DISTINCT a.CUSTOMER_ID) <> 1)")
      check("all assets linked", s"SELECT COUNT(*) FROM $s s WHERE NOT EXISTS (SELECT 1 FROM $l l WHERE l.COLLATERAL_ID = s.COLLATERAL_ID)")
      check("three accounts per customer", s"SELECT COUNT(*) FROM (SELECT c.CUSTOMER_ID FROM $c c LEFT JOIN $a a USING (CUSTOMER_ID) GROUP BY c.CUSTOMER_ID HAVING COUNT(a.ACCOUNT_ID) <> 3)")
      check("customer domains", s"SELECT COUNT(*) FROM $c WHERE CUSTOMER_ID <= 0 OR RATING NOT BETWEEN 1 AND 9 OR PD_BPS NOT BETWEEN 0 AND 10000 OR SEGMENT NOT IN ('RETAIL','SME','PRIVATE') OR COUNTRY NOT IN ('SE','DE','FR') OR REPORTING_DAY <> ${manifest.config.reportingDate.toEpochDay}")
      check("account domains", s"SELECT COUNT(*) FROM $a WHERE ACCOUNT_ID <= 0 OR DRAWN_CENTS < 0 OR LIMIT_CENTS < 0 OR DISCOUNT_BPS NOT BETWEEN 0 AND 10000 OR PRODUCT_TYPE NOT IN ('REVOLVING','TERM','MORTGAGE') OR REPORTING_DAY <> ${manifest.config.reportingDate.toEpochDay}")
      check("asset domains", s"SELECT COUNT(*) FROM $s WHERE COLLATERAL_ID <= 0 OR VALUATION_CENTS < 0 OR REALIZATION_COST_CENTS < 0 OR HAIRCUT_BPS NOT BETWEEN 0 AND 10000 OR MONTHS_TO_REALIZATION NOT BETWEEN 0 AND 120 OR ASSET_TYPE NOT IN ('PROPERTY','VEHICLE')")
      check("positive weights", s"SELECT COUNT(*) FROM $l WHERE ALLOCATION_WEIGHT <= 0")
      check("recovery domains", s"SELECT COUNT(*) FROM $r WHERE RECOVERY_EVENT_ID <= 0 OR VERSION < 1 OR REVISION_ORDER < 1 OR RECOVERY_CENTS < 0 OR COST_CENTS < 0 OR MONTHS_TO_RECOVERY NOT BETWEEN 0 AND 120")
      check("recovery event belongs to one account", s"SELECT COUNT(*) FROM (SELECT RECOVERY_EVENT_ID FROM $r GROUP BY RECOVERY_EVENT_ID HAVING COUNT(DISTINCT ACCOUNT_ID) <> 1)")
      boundary = profiler.inputsLoaded()
      LoadedInputs(tables, manifest.files.map(f => f.dataset.name -> f.rowCount).toMap, boundary)
    } { persist() }
  }
}
