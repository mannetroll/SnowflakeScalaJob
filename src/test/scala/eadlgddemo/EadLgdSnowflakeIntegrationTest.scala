package eadlgddemo

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class EadLgdSnowflakeIntegrationTest extends AnyFunSuite {
  test("five Parquet loads, three consumed checkpoints, final CTAS, correctness, determinism and real profiles") {
    // Gate before configuration, fixture generation or Session creation. Missing configuration
    // after opt-in is a failure, never a cancellation.
    if (!DemoConfig.liveEnabled()) cancel("Live Snowflake test skipped: set RUN_SNOWFLAKE_IT=true to opt in")
    val config = DemoConfig.fromEnv()
    val result = DemoRunner.run(config, ConnectionConfig.load())
    assert(result.outputs.distinct.size == 2)
    assert(java.nio.file.Files.isRegularFile(result.directory.resolve("query-manifest.json")))
    if (config.requireQueryProfiles) assert(result.profilesVerified)
  }
}
