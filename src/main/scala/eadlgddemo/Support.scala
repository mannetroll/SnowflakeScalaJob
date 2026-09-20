package eadlgddemo

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.snowflake.snowpark.{DataFrame, Session}
import com.snowflake.snowpark.types.Variant
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

object Json {
  val mapper: ObjectMapper = new ObjectMapper().registerModule(DefaultScalaModule)
  def text(value: Any): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
  def write(path: Path, value: Any): Unit = writeText(path, text(value) + "\n")
  def writeText(path: Path, value: String): Unit = {
    Files.createDirectories(path.toAbsolutePath.getParent)
    Files.write(path, value.getBytes(UTF_8))
  }
  def rows(df: DataFrame): Vector[Map[String, Any]] = {
    val names = df.schema.fields.map(_.name.replace("\"", "").toUpperCase)
    df.collect().toVector.map(r => names.indices.map { i =>
      names(i) -> (r.get(i) match {
        case null => null
        case n: java.lang.Number => n
        case b: java.lang.Boolean => b
        case v: Variant => v.asJsonNode()
        case a: Array[_] => a.toVector
        case s: Seq[_] => s
        case m: scala.collection.Map[_, _] => m
        case x => x.toString
      })
    }.toMap)
  }
}

object Sql {
  def identifier(s: String): String = {
    require(s.matches("[A-Za-z_][A-Za-z0-9_]{0,199}"), "Use simple unquoted SQL identifiers")
    s.toUpperCase(java.util.Locale.ROOT)
  }
  def literal(s: String): String = "'" + s.replace("'", "''") + "'"
  def number(value: Any): BigDecimal = BigDecimal(value.toString)
  def scalar(session: Session, query: String): BigDecimal = number(session.sql(query).collect().head.get(0))
}

/** Register only objects successfully created by this run. No prefix/wildcard deletion. */
final class ObjectRegistry(session: Session, dir: Path, keepOutputs: Boolean) {
  private val objects = ArrayBuffer.empty[(String, String, Boolean)]
  def add(kind: String, name: String, output: Boolean = false): Unit = {
    require(Set("TABLE", "VIEW", "STAGE")(kind))
    require(name.split("\\.").forall(p => p.matches("\"?[A-Za-z_][A-Za-z0-9_]*\"?")))
    if (!objects.exists(x => x._1 == kind && x._2 == name)) objects += ((kind, name, output))
    Json.writeText(dir.resolve("sql/cleanup.sql"), objects.reverse.map { case (k, n, _) =>
      s"DROP $k IF EXISTS $n;"
    }.mkString("-- Exact objects created by this run. Temporary objects disappear at session close.\n", "\n", "\n"))
  }
  def cleanup(): Unit = {
    var error: Throwable = null
    objects.reverse.filterNot(x => keepOutputs && x._3).foreach { case (kind, name, _) =>
      try session.sql(s"DROP $kind IF EXISTS $name").collect()
      catch { case NonFatal(e) => if (error == null) error = e else error.addSuppressed(e) }
    }
    if (error != null) throw error
  }
}

object Lifecycle {
  /** Cleanup failures never replace the business failure. */
  def protecting[A](body: => A)(cleanup: => Unit): A = {
    var primary: Throwable = null
    try body catch { case NonFatal(e) => primary = e; throw e }
    finally {
      try cleanup catch {
        case NonFatal(e) => if (primary != null) primary.addSuppressed(e) else throw e
      }
    }
  }
}
