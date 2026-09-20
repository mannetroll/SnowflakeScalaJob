package eadlgddemo

import java.nio.file.{Files, Paths}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class ConfigTest extends AnyFunSuite {
  private val base = Map("account" -> "ORG-ACCOUNT", "user" -> "tester", "role" -> "demo_role",
    "warehouse" -> "demo_wh", "database" -> "sandbox", "schema" -> "demo", "password" -> "never-print-this")
  test("default acceptance scale and strict opt-in flags") {
    assert(DemoConfig.fromEnv(Map.empty).customerCount == 100000)
    assert(DemoConfig.fromEnv(Map.empty).reportingDate.toString == "2025-11-30")
    assert(DemoConfig.fromEnv(Map.empty).requireQueryProfiles)
    assert(!DemoConfig.liveEnabled(Map.empty))
    assert(!DemoConfig.liveEnabled(Map("RUN_SNOWFLAKE_IT" -> "false")))
    assert(DemoConfig.liveEnabled(Map("RUN_SNOWFLAKE_IT" -> "true")))
    intercept[IllegalArgumentException](DemoConfig.liveEnabled(Map("RUN_SNOWFLAKE_IT" -> "yes")))
    intercept[IllegalArgumentException](DemoConfig(customerCount = 0))
    intercept[IllegalArgumentException](DemoConfig(statementTimeoutSeconds = 0))
    intercept[IllegalArgumentException](DemoConfig.fromEnv(Map("REQUIRE_QUERY_PROFILES" -> "TRUE")))
  }
  test("identifiers and literal escaping prevent injected SQL") {
    assert(Sql.identifier("demo_123") == "DEMO_123")
    assert(Sql.literal("O'Brien") == "'O''Brien'")
    Vector("a.b", "a; DROP TABLE x", "\"CaseSensitive\"", "", "space name").foreach { s =>
      intercept[IllegalArgumentException](Sql.identifier(s))
    }
  }
  test("connection mapping matches Snowpark URL and JDBC names, environment overrides") {
    val c = ConnectionConfig.from(base, Map("SNOWFLAKE_DATABASE" -> "other_db"), Paths.get("."))
    assert(c.options("URL") == "ORG-ACCOUNT.snowflakecomputing.com")
    assert(c.options("DB") == "OTHER_DB")
    assert(c.qualified("test") == "OTHER_DB.DEMO.TEST")
    assert(!c.toString.contains("never-print-this"))
    val url = ConnectionConfig.from(base - "account", Map("SNOWFLAKE_URL" -> "jdbc:snowflake://custom.snowflakecomputing.com:443/"), Paths.get("."))
    assert(url.options("URL") == "custom.snowflakecomputing.com:443")
    intercept[IllegalArgumentException](ConnectionConfig.from(base - "warehouse", Map.empty, Paths.get(".")))
    intercept[IllegalArgumentException](ConnectionConfig.from(base, Map("SNOWFLAKE_URL" -> "https://host/?password=secret"), Paths.get(".")))
  }
  test("key paths resolve relative to the properties file and passphrases map correctly") {
    val dir = Files.createTempDirectory(Files.createDirectories(Paths.get("build/ead-lgd")), "config-")
    Files.write(dir.resolve("key.p8"), Array[Byte](1, 2))
    val c = ConnectionConfig.from(base + ("private_key_path" -> "key.p8"),
      Map("SNOWFLAKE_PRIVATE_KEY_PASSPHRASE" -> "test-passphrase"), dir)
    assert(c.options("PRIVATE_KEY_FILE") == dir.resolve("key.p8").normalize.toString.replace('\\', '/'))
    assert(c.options("PRIVATE_KEY_FILE_PWD") == "test-passphrase")
    assert(c.options("AUTHENTICATOR") == "snowflake_jwt")
    assert(!c.options.contains("PASSWORD"))
  }
  test("properties retain Windows path separators and fail clearly on malformed entries") {
    val resource = Paths.get(getClass.getResource("/config-fixture.properties").toURI)
    assert(ConnectionConfig.readProperties(resource)("private_key_path") == "secrets\\key.p8")
    assert(ConnectionConfig.readProperties(resource)("user") == "fixture-user")
  }
  test("cleanup preserves original exception and records the secondary failure") {
    val primary = new IllegalStateException("business failure")
    val secondary = new IllegalStateException("cleanup failure")
    val observed = intercept[IllegalStateException] {
      Lifecycle.protecting(throw primary)(throw secondary)
    }
    assert(observed eq primary)
    assert(observed.getSuppressed.toVector == Vector(secondary))
  }
  test("PUT file URIs preserve spaces and use the JDBC Windows drive convention") {
    assert(StageFiles.fromAbsolutePath("C:\\demo folder\\customers.parquet") == "file://C:/demo folder/customers.parquet")
    assert(StageFiles.fromAbsolutePath("/tmp/demo folder/customers.parquet") == "file:///tmp/demo folder/customers.parquet")
    intercept[IllegalArgumentException](StageFiles.fromAbsolutePath("/tmp/*.parquet"))
  }
}
