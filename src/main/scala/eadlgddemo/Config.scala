package eadlgddemo

import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate
import java.util.UUID
import com.snowflake.snowpark.Session
import scala.collection.JavaConverters._

final case class DemoConfig(
    customerCount: Int = 100000,
    seed: Long = 20251130L,
    reportingDate: LocalDate = LocalDate.parse("2025-11-30"),
    keepObjects: Boolean = false,
    requireQueryProfiles: Boolean = true,
    collateralDiscountBps: Int = 500,
    statementTimeoutSeconds: Int = 1800) {
  require(customerCount >= 10 && customerCount <= 1000000, "CUSTOMER_COUNT must be 10..1000000")
  require(collateralDiscountBps >= 0 && collateralDiscountBps <= 10000, "COLLATERAL_DISCOUNT_BPS must be 0..10000")
  require(statementTimeoutSeconds >= 60 && statementTimeoutSeconds <= 86400, "STATEMENT_TIMEOUT_SECONDS must be 60..86400")
}
object DemoConfig {
  def bool(env: Map[String, String], key: String, default: Boolean): Boolean = env.get(key) match {
    case None => default
    case Some("true") => true
    case Some("false") => false
    case _ => throw new IllegalArgumentException(s"$key must be exactly true or false")
  }
  def liveEnabled(env: Map[String, String] = sys.env): Boolean = bool(env, "RUN_SNOWFLAKE_IT", false)
  def fromEnv(env: Map[String, String] = sys.env): DemoConfig = DemoConfig(
    env.getOrElse("CUSTOMER_COUNT", "100000").toInt,
    env.getOrElse("DATA_SEED", "20251130").toLong,
    LocalDate.parse(env.getOrElse("REPORTING_DATE", "2025-11-30")),
    bool(env, "KEEP_OBJECTS", false), bool(env, "REQUIRE_QUERY_PROFILES", true),
    env.getOrElse("COLLATERAL_DISCOUNT_BPS", "500").toInt,
    env.getOrElse("STATEMENT_TIMEOUT_SECONDS", "1800").toInt)
  def runId(): String = "R" + UUID.randomUUID().toString.replace("-", "").toUpperCase
  def directory(runId: String): Path = Paths.get("build", "ead-lgd", Sql.identifier(runId))
}

// Deliberately not a case class: default toString must never expose passwords.
final class ConnectionConfig(val options: Map[String, String], val database: String, val schema: String) {
  override def toString: String = "ConnectionConfig(<redacted>)"
  def qualified(name: String): String = s"$database.$schema.${Sql.identifier(name)}"
}
object ConnectionConfig {
  def readProperties(path: Path): Map[String, String] = {
    if (!Files.exists(path)) return Map.empty
    Files.readAllLines(path).asScala.zipWithIndex.flatMap { case (raw, i) =>
      val line = raw.trim
      if (line.isEmpty || line.startsWith("#") || line.startsWith("!")) None
      else {
        val at = line.indexOf('=')
        require(at > 0, s"Invalid properties entry at line ${i + 1}; expected key=value")
        val value = line.substring(at + 1).trim
        if (value.isEmpty) None else Some(line.substring(0, at).trim.toLowerCase -> value)
      }
    }.toMap
  }
  def load(env: Map[String, String] = sys.env): ConnectionConfig = {
    val path = Paths.get(env.getOrElse("SNOWFLAKE_CONFIG", "snowflake.properties")).toAbsolutePath
    from(readProperties(path), env, path.getParent)
  }
  def from(props: Map[String, String], env: Map[String, String], base: Path): ConnectionConfig = {
    def value(k: String): Option[String] = env.get("SNOWFLAKE_" + k.toUpperCase).filter(_.nonEmpty).orElse(props.get(k))
    def required(k: String): String = value(k).getOrElse(throw new IllegalArgumentException(s"Missing SNOWFLAKE_${k.toUpperCase} / $k"))
    val db = Sql.identifier(required("database"))
    val schema = Sql.identifier(required("schema"))
    val account = value("account")
    account.foreach(a => require(a.matches("[A-Za-z0-9_.-]+"), "Invalid Snowflake account identifier"))
    val url = value("url").getOrElse(s"https://${required("account")}.snowflakecomputing.com")
      .stripPrefix("jdbc:snowflake://")
    val https = if (url.startsWith("https://")) url else "https://" + url
    val uri = new java.net.URI(https)
    require(uri.getScheme == "https" && uri.getHost != null && uri.getUserInfo == null &&
      uri.getQuery == null && uri.getFragment == null && Set("", "/")(uri.getPath),
      "SNOWFLAKE_URL must be an HTTPS/JDBC host URL without credentials or query parameters")
    // Snowpark 1.21 prepends jdbc:snowflake:// itself; it expects host[:port].
    val snowparkHost = uri.getHost + (if (uri.getPort < 0) "" else ":" + uri.getPort)
    var options = Map("URL" -> snowparkHost, "USER" -> required("user"), "ROLE" -> Sql.identifier(required("role")),
      "WAREHOUSE" -> Sql.identifier(required("warehouse")), "DB" -> db, "SCHEMA" -> schema)
    account.foreach(a => options += "ACCOUNT" -> a)
    value("private_key_path") match {
      case Some(file) =>
        val expanded = if (file.startsWith("~/")) Paths.get(System.getProperty("user.home"), file.drop(2)) else Paths.get(file)
        val key = (if (expanded.isAbsolute) expanded else base.resolve(expanded)).normalize()
        require(Files.isRegularFile(key), "Configured private key file does not exist")
        options ++= Map("AUTHENTICATOR" -> "snowflake_jwt", "PRIVATE_KEY_FILE" -> key.toString.replace('\\', '/'))
        value("private_key_passphrase").foreach(p => options += "PRIVATE_KEY_FILE_PWD" -> p)
      case None =>
        val authenticator = value("authenticator").getOrElse("snowflake")
        require(Set("snowflake", "externalbrowser")(authenticator.toLowerCase), "Supported authentication: key pair, snowflake password, externalbrowser")
        options += "AUTHENTICATOR" -> authenticator
        if (authenticator.equalsIgnoreCase("snowflake")) options += "PASSWORD" -> required("password")
    }
    new ConnectionConfig(options, db, schema)
  }
}
object SessionFactory {
  def open(connection: ConnectionConfig, config: DemoConfig): Session = {
    val session = Session.builder.configs(connection.options).create
    try {
      session.sql(s"ALTER SESSION SET USE_CACHED_RESULT = FALSE, STATEMENT_TIMEOUT_IN_SECONDS = ${config.statementTimeoutSeconds}, TIMEZONE = 'UTC'").collect()
      session
    } catch { case scala.util.control.NonFatal(e) =>
      try session.close() catch { case scala.util.control.NonFatal(close) => e.addSuppressed(close) }
      throw e
    }
  }
}
