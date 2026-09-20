package eadlgddemo

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
final class EadLgdSnowflakeSingleRunTest extends AnyFunSuite {
  test("complete job: five real Parquet loads, three checkpoints and one final CTAS") {
    // Opt in before reading credentials, generating fixtures or opening a session.
    if (!DemoConfig.liveEnabled()) cancel("Live Snowflake test skipped: set RUN_SNOWFLAKE_IT=true to opt in")
    val config = DemoConfig.fromEnv()
    val result = DemoRunner.run(config, ConnectionConfig.load(), verifyDeterminism = false)

    assert(result.outputs.size == 1)
    val manifest = Json.mapper.readTree(result.directory.resolve("query-manifest.json").toFile)
    val phases = manifest.get("phases").elements().asScala.toVector
    assert(phases.map(_.get("phase").asText()) == Vector(
      "CP1_ACCOUNT_EAD", "CP2_ACCOUNT_COLLATERAL", "CP3_ACCOUNT_LGD", "FINAL_CTAS"))
    assert(phases.forall(_.get("execution").asText() == "E1"))
    assert(phases.forall(_.get("dataQueryIds").size() >= 1))
    assert(phases.take(3).forall(_.get("businessRows").asLong() == config.customerCount.toLong * 3))
    assert(phases.last.get("businessRows").asLong() == config.customerCount)
    val validation = Json.mapper.readTree(result.directory.resolve("validation-summary.json").toFile)
    assert(validation.get("correctnessVerified").asBoolean())
    if (config.requireQueryProfiles) assert(result.profilesVerified)
  }
}
