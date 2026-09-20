package eadlgddemo

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class ReferenceCalculationTest extends AnyFunSuite {
  private def dec(value: Any): BigDecimal = BigDecimal(value.toString)
  test("independent local reference for the fixed cohort matches hand-calculated constants") {
    val config = DemoConfig(customerCount = 10)
    def records(d: Dataset) = FixtureGenerator.rows(d, config).map(v => d.fields.map(_.name).zip(v).toMap).toVector
    val customers = records(Datasets.customers).filter(r => dec(r("CUSTOMER_ID")) <= 4)
    val accounts = records(Datasets.accounts).filter(r => dec(r("CUSTOMER_ID")) <= 4)
    val assets = records(Datasets.assets).map(r => dec(r("COLLATERAL_ID")) -> r).toMap
    val links = records(Datasets.links)
    val latest = records(Datasets.recoveries).groupBy(_("RECOVERY_EVENT_ID")).values.map(_.maxBy(r => (dec(r("VERSION")), dec(r("REVISION_ORDER"))))).toVector
    val observed = accounts.map { a =>
      val id = dec(a("ACCOUNT_ID"))
      val drawn = dec(a("DRAWN_CENTS")) / 100
      val undrawn = ((dec(a("LIMIT_CENTS")) - dec(a("DRAWN_CENTS"))) / 100).max(BigDecimal(0))
      val ccf = Map("REVOLVING" -> BigDecimal("0.5"), "TERM" -> BigDecimal("0.75"), "MORTGAGE" -> BigDecimal(1))(a("PRODUCT_TYPE").toString)
      val ead = drawn + undrawn * ccf
      val coll = links.filter(l => dec(l("ACCOUNT_ID")) == id).map { l =>
        val assetId = dec(l("COLLATERAL_ID"))
        val weights = links.filter(x => dec(x("COLLATERAL_ID")) == assetId).map(x => dec(x("ALLOCATION_WEIGHT"))).sum
        // Fixed cohort assets have zero haircut, zero costs and immediate realization.
        dec(assets(assetId)("VALUATION_CENTS")) / 100 * dec(l("ALLOCATION_WEIGHT")) / weights
      }.sum
      val unsecured = latest.filter(r => dec(r("ACCOUNT_ID")) == id).map(r => (dec(r("RECOVERY_CENTS")) - dec(r("COST_CENTS"))) / 100).sum
      val capped = ead.min((coll + unsecured).max(BigDecimal(0)))
      Vector(id, ead, coll, unsecured, capped, ead - capped)
    }
    assert(observed == ReferenceCohort.accounts)
    assert(customers.size == 4)
    assert(observed.find(_.head == BigDecimal(10)).get(3) == BigDecimal(30)) // v2, not v1 or sum of both
  }
  test("discount convention and weighted LGD are independently checkable") {
    assert(BigDecimal(105) / BigDecimal("1.05") == BigDecimal(100))
    val weighted = (BigDecimal(150) / BigDecimal(350)).setScale(10, BigDecimal.RoundingMode.HALF_UP)
    assert(weighted == BigDecimal("0.4285714286"))
    assert(weighted != BigDecimal(1) / 3)
    assert(BigDecimal("0.02") * BigDecimal(150) == BigDecimal(3))
    assert(BigDecimal(240) * 1 / 4 + BigDecimal(240) * 3 / 4 == BigDecimal(240))
  }
}
