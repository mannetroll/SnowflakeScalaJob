package eadlgddemo

import java.nio.file.Path
import com.snowflake.snowpark.{HasCachedResult, Session}
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

object MaterializationSql {
  private val objectPattern = "([\\\"A-Za-z_][\\\"A-Za-z0-9_\\.]*)"
  private val create = ("(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:SCOPED\\s+)?(?:TEMPORARY|TEMP|TRANSIENT)\\s+)?TABLE\\s+" + objectPattern).r
  private val insert = ("(?is)^\\s*INSERT\\s+(?:OVERWRITE\\s+)?INTO\\s+" + objectPattern).r
  def createdTarget(sql: String): Option[String] = create.findFirstMatchIn(sql).map(_.group(1))
  def dataTarget(sql: String): Option[String] = {
    if (!sql.toUpperCase(java.util.Locale.ROOT).matches("(?s).*\\bSELECT\\b.*")) None
    else insert.findFirstMatchIn(sql).map(_.group(1)).orElse {
      if (sql.toUpperCase(java.util.Locale.ROOT).matches("(?s).*\\bAS\\s+(?:SELECT|WITH)\\b.*")) createdTarget(sql)
      else None
    }
  }
  def canonical(name: String): String = name.replace("\"", "").toUpperCase(java.util.Locale.ROOT)
}

final case class PhaseEvidence(execution: String, phase: String, tag: String,
    materializedObjects: Vector[String], dataQueryIds: Vector[String],
    statements: Vector[Map[String, Any]], expectedBusinessRows: Long, businessRows: Option[Long],
    profiles: Vector[Map[String, Any]])

/** A dedicated single-threaded session is mandatory. No LAST_QUERY_ID guesses. */
final class QueryProfiler(session: Session, connection: ConnectionConfig, runId: String,
    dir: Path, registry: ObjectRegistry) {
  private val evidence = ArrayBuffer.empty[PhaseEvidence]
  private var execution = "SETUP"
  private val sessionId = {
    phase("OBSERVABILITY")
    Sql.scalar(session, "SELECT CURRENT_SESSION()").toLong
  }
  private var loadBoundary: Option[String] = None
  flush()
  def phases: Vector[PhaseEvidence] = evidence.toVector
  def useExecution(value: String): Unit = { execution = Sql.identifier(value) }
  def tag(phase: String): String =
    s"""{"job":"eadlgddemo","run":"$runId","execution":"$execution","phase":"${Sql.identifier(phase)}"}"""
  def phase(value: String): Unit = session.setQueryTag(tag(value))
  def serverTime(): String = session.sql("SELECT TO_CHAR(CURRENT_TIMESTAMP(), 'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM')").collect().head.getString(0)
  def inputsLoaded(): String = {
    phase("LOADS_COMPLETE")
    val at = serverTime()
    loadBoundary = Some(at)
    flush()
    at
  }
  def checkpoint(label: String, expectedRows: Long)(cache: => HasCachedResult): HasCachedResult = {
    require(loadBoundary.nonEmpty, "All five inputs must be loaded and validated before CP1")
    capture(label, expectedRows, None)(cache)
  }
  def finalCtas(sql: String, target: String, expectedRows: Long): Unit = {
    capture("FINAL_CTAS", expectedRows, Some(target)) {
      session.sql(sql).collect()
      registry.add("TABLE", target, output = true)
    }
  }
  private def capture[A](label: String, expectedRows: Long, knownTarget: Option[String])(body: => A): A = {
    phase("OBSERVABILITY")
    val start = serverTime()
    val actionTag = tag(label)
    phase(label)
    var primary: Throwable = null
    try body catch { case NonFatal(e) => primary = e; throw e }
    finally {
      try {
        phase("OBSERVABILITY")
        val statements = history(start, actionTag, knownTarget)
        val created = statements.flatMap(r => MaterializationSql.createdTarget(r("QUERY_TEXT").toString)).distinct
        val allowed = knownTarget.map(Vector(_)).getOrElse(created)
        val data = statements.filter(r => MaterializationSql.dataTarget(r("QUERY_TEXT").toString)
          .exists(t => allowed.exists(a => sameObject(a, t))))
        val targets = data.flatMap(r => MaterializationSql.dataTarget(r("QUERY_TEXT").toString)).distinct
        targets.filter(t => created.exists(sameObject(_, t))).foreach(t => registry.add("TABLE", qualify(t), knownTarget.nonEmpty))
        // Write partial evidence even if the action/history/profile acceptance failed.
        val entry = PhaseEvidence(execution, label, actionTag, targets.map(qualify),
          data.map(_("QUERY_ID").toString), statements, expectedRows, None, Vector.empty)
        evidence += entry
        flush()
        require(data.nonEmpty, s"No data-materialization query identified for $execution/$label; inspect query-manifest.json")
        require(data.forall(_("EXECUTION_STATUS").toString.equalsIgnoreCase("SUCCESS")), s"Failed materialization in $label")
        val actual = targets.map(t => Sql.scalar(session, s"SELECT COUNT(*) FROM ${qualify(t)}").toLong)
        require(actual.forall(_ == expectedRows), s"$label row count $actual differs from $expectedRows")
        val profiles = data.map(r => exportProfile(label, r("QUERY_ID").toString))
        evidence.update(evidence.size - 1, entry.copy(businessRows = Some(actual.head), profiles = profiles))
        flush()
      } catch {
        case NonFatal(e) =>
          try Json.write(dir.resolve(s"$execution-$label-observability-error.json"), Map("error" -> e.toString))
          catch { case NonFatal(diagnostic) => e.addSuppressed(diagnostic) }
          if (primary != null) primary.addSuppressed(e) else throw e
      }
    }
  }
  private def qualify(name: String): String = {
    val plain = MaterializationSql.canonical(name)
    if (plain.contains(".")) { plain.split("\\.").foreach(Sql.identifier); plain }
    else connection.qualified(plain)
  }
  private def sameObject(a: String, b: String): Boolean = qualify(a) == qualify(b)
  private def history(start: String, actionTag: String, target: Option[String]): Vector[Map[String, Any]] = {
    val sql = s"""SELECT QUERY_ID, QUERY_TEXT, QUERY_TAG, QUERY_TYPE, EXECUTION_STATUS,
       ERROR_CODE, ERROR_MESSAGE, START_TIME, END_TIME, TOTAL_ELAPSED_TIME,
       COMPILATION_TIME, EXECUTION_TIME, ROWS_PRODUCED, ROWS_WRITTEN_TO_RESULT, ROWS_INSERTED, BYTES_SCANNED
       FROM TABLE(${connection.database}.INFORMATION_SCHEMA.QUERY_HISTORY_BY_SESSION(
         SESSION_ID => $sessionId, END_TIME_RANGE_START => TO_TIMESTAMP_LTZ(${Sql.literal(start)}),
         END_TIME_RANGE_END => CURRENT_TIMESTAMP(), RESULT_LIMIT => 10000,
         INCLUDE_CLIENT_GENERATED_STATEMENT => TRUE))
       WHERE SESSION_ID = $sessionId AND QUERY_TAG = ${Sql.literal(actionTag)}
       ORDER BY START_TIME, QUERY_ID"""
    var rows = Vector.empty[Map[String, Any]]
    var attempts = 0
    var done = false
    var previousIds = Set.empty[String]
    while (attempts < 8 && !done) {
      rows = Json.rows(session.sql(sql))
      val created = rows.flatMap(r => MaterializationSql.createdTarget(r("QUERY_TEXT").toString))
      val allowed = target.map(Vector(_)).getOrElse(created)
      val hasData = rows.exists(r => MaterializationSql.dataTarget(r("QUERY_TEXT").toString)
        .exists(t => allowed.exists(sameObject(_, t))) && r.get("END_TIME").exists(_ != null))
      val ids = rows.map(_("QUERY_ID").toString).toSet
      // Wait for a stable history snapshot after observing data work. A native action
      // may emit multiple statements; do not stop at the first visible query ID.
      done = hasData && ids == previousIds
      previousIds = ids
      attempts += 1
      if (!done && attempts < 8) Thread.sleep(1500)
    }
    rows
  }
  private def exportProfile(label: String, id: String): Map[String, Any] = {
    require(id.matches("[0-9a-fA-F-]{36}"), "Invalid query ID from query history")
    val path = dir.resolve(s"profiles/$execution-$label-$id.json")
    var operators = Vector.empty[Map[String, Any]]
    var error = "Operator statistics returned no rows"
    var attempts = 0
    while (attempts < 4 && operators.isEmpty) {
      try operators = Json.rows(session.sql(s"SELECT * FROM TABLE(GET_QUERY_OPERATOR_STATS(${Sql.literal(id)}))"))
      catch { case NonFatal(e) => error = e.toString }
      attempts += 1
      if (operators.isEmpty && attempts < 4) Thread.sleep(1000)
    }
    // Preserve every returned field, including parents, operator statistics and timing variants.
    val result = Map("queryId" -> id, "verified" -> operators.nonEmpty,
      "error" -> (if (operators.nonEmpty) null else error), "operators" -> operators,
      "artifact" -> path.toString)
    Json.write(path, result)
    result - "operators"
  }
  def profilesVerified: Boolean = evidence.size == 8 && evidence.forall(e =>
    e.dataQueryIds.nonEmpty && e.profiles.size == e.dataQueryIds.size && e.profiles.forall(_("verified") == true))
  def enforceProfiles(required: Boolean): Unit = if (required && !profilesVerified) {
    val reasons = evidence.flatMap(_.profiles).filter(_("verified") != true).map(_("error")).mkString("; ")
    throw new IllegalStateException(s"Profiling acceptance failed: $reasons. See profiles/; correctness-only requires explicit REQUIRE_QUERY_PROFILES=false")
  }
  private def flush(): Unit = {
    Json.write(dir.resolve("query-manifest.json"), Map("runId" -> runId, "sessionId" -> sessionId,
      "allFiveLoadsValidatedAtServerTime" -> loadBoundary.orNull, "profilesVerified" -> profilesVerified,
      "phases" -> evidence.toVector))
    val statements = evidence.flatMap(e => e.dataQueryIds.map(id =>
        s"-- ${e.execution} ${e.phase}; materialized ${e.materializedObjects.mkString(", ")}; verified business rows ${e.businessRows.map(_.toString).getOrElse("unverified")}\n" +
        s"SELECT * FROM TABLE(GET_QUERY_OPERATOR_STATS('$id'));\n" +
        s"SELECT * FROM TABLE(${connection.database}.INFORMATION_SCHEMA.QUERY_HISTORY_BY_SESSION(SESSION_ID => $sessionId, RESULT_LIMIT => 10000, INCLUDE_CLIENT_GENERATED_STATEMENT => TRUE)) WHERE QUERY_ID = '$id';"))
    Json.writeText(dir.resolve("sql/inspect-queries.sql"), statements.mkString("\n\n") + "\n")
  }
}
