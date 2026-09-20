package eadlgddemo

import java.nio.file.{Files, Paths}
import org.apache.avro.generic.GenericRecord
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class FixtureGeneratorTest extends AnyFunSuite {
  private def dir() = Files.createTempDirectory(Files.createDirectories(Paths.get("build/ead-lgd")), "offline-")
  private def number(r: GenericRecord, key: String): Long = r.get(key).toString.toLong

  test("all five outputs are readable real Parquet with stable checksums and an accurate manifest") {
    val firstDir = dir()
    val config = DemoConfig(customerCount = 30)
    val first = FixtureGenerator.generate(config, firstDir)
    val second = FixtureGenerator.generate(config, dir())
    assert(first.files.size == 5)
    assert(first.files.map(_.sha256) == second.files.map(_.sha256))
    first.files.foreach { file =>
      val bytes = Files.readAllBytes(file.path)
      assert(new String(bytes.take(4), "US-ASCII") == "PAR1")
      assert(new String(bytes.takeRight(4), "US-ASCII") == "PAR1")
      val rows = LocalParquet.read(file.path)
      assert(rows.size == file.rowCount)
      assert(Files.size(file.path) == file.sizeBytes)
      assert(FixtureGenerator.checksum(file.path) == file.sha256)
      assert(rows.map(r => file.dataset.key.map(k => r.get(k).toString)).distinct.size == rows.size)
      assert(rows.forall(r => file.dataset.fields.forall(f => r.get(f.name) != null)))
    }
    val manifest = Json.mapper.readTree(firstDir.resolve("fixture-manifest.json").toFile)
    assert(manifest.get("seed").asLong() == config.seed)
    assert(manifest.get("files").size() == 5)
    assert(manifest.get("customerCount").asInt() == 30)
    assert(manifest.get("files").get(0).get("rows").asInt() == 30)
    val changed = FixtureGenerator.generate(config.copy(seed = 17), dir())
    assert(first.files.map(_.sha256) != changed.files.map(_.sha256))
  }
  test("generated relationships, shared assets, missing cases and versioned recovery keys are intentional") {
    val manifest = FixtureGenerator.generate(DemoConfig(customerCount = 30), dir())
    val data = manifest.files.map(f => f.dataset.name -> LocalParquet.read(f.path)).toMap
    val accounts = data("ACCOUNTS").map(r => number(r, "ACCOUNT_ID") -> number(r, "CUSTOMER_ID")).toMap
    val customers = data("CUSTOMERS").map(number(_, "CUSTOMER_ID")).toSet
    val assets = data("COLLATERAL_ASSETS").map(number(_, "COLLATERAL_ID")).toSet
    val links = data("ACCOUNT_COLLATERAL")
    assert(accounts.size == 90 && customers.size == 30)
    assert(accounts.values.forall(customers))
    assert(links.forall(r => accounts.contains(number(r, "ACCOUNT_ID")) && assets(number(r, "COLLATERAL_ID")) && number(r, "ALLOCATION_WEIGHT") > 0))
    assert(links.groupBy(number(_, "COLLATERAL_ID")).values.forall(rs => rs.map(r => accounts(number(r, "ACCOUNT_ID"))).distinct.size == 1))
    assert(links.groupBy(number(_, "COLLATERAL_ID")).values.exists(_.size > 1))
    assert(links.groupBy(number(_, "ACCOUNT_ID")).values.exists(_.size > 1))
    assert(links.map(number(_, "ACCOUNT_ID")).toSet.size < accounts.size)
    val recoveries = data("EXPECTED_RECOVERIES")
    assert(recoveries.forall(r => accounts.contains(number(r, "ACCOUNT_ID"))))
    assert(recoveries.map(number(_, "ACCOUNT_ID")).toSet.size < accounts.size)
    assert(recoveries.groupBy(number(_, "RECOVERY_EVENT_ID")).values.exists(_.size == 2))
  }
  test("default streamed cardinalities remain at the requested scale") {
    val config = DemoConfig()
    val counts = Datasets.all.map(d => d.name -> FixtureGenerator.rows(d, config).length).toMap
    assert(counts("CUSTOMERS") == 100000)
    assert(counts("ACCOUNTS") == 300000)
    assert(math.abs(counts("COLLATERAL_ASSETS") - 180000) < 20)
    assert(math.abs(counts("ACCOUNT_COLLATERAL") - 360000) < 30)
    assert(counts("EXPECTED_RECOVERIES") > 570000 && counts("EXPECTED_RECOVERIES") < 620000)
  }
}
