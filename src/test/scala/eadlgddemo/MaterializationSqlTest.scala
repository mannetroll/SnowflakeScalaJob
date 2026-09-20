package eadlgddemo

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class MaterializationSqlTest extends AnyFunSuite {
  test("native empty CREATE and data INSERT are distinguished from metadata and counts") {
    assert(MaterializationSql.createdTarget("CREATE TEMPORARY TABLE SNOWPARK_TEMP_TABLE_ABC (ID NUMBER)").contains("SNOWPARK_TEMP_TABLE_ABC"))
    assert(MaterializationSql.dataTarget("CREATE TEMPORARY TABLE SNOWPARK_TEMP_TABLE_ABC (ID NUMBER)").isEmpty)
    assert(MaterializationSql.dataTarget("INSERT INTO SNOWPARK_TEMP_TABLE_ABC SELECT * FROM (SELECT ID FROM INPUT)").contains("SNOWPARK_TEMP_TABLE_ABC"))
    Vector("SELECT COUNT(*) FROM X", "ALTER SESSION SET QUERY_TAG='x'", "SELECT * FROM TABLE(INFORMATION_SCHEMA.QUERY_HISTORY_BY_SESSION())", "CREATE TEMP VIEW X AS SELECT * FROM T").foreach { sql =>
      assert(MaterializationSql.dataTarget(sql).isEmpty)
    }
  }
  test("CTAS targets and quoted identifiers can be scoped without LAST_QUERY_ID") {
    assert(MaterializationSql.dataTarget("CREATE TRANSIENT TABLE DB.SCHEMA.RUN_OUTPUT AS SELECT * FROM FINAL_VIEW").contains("DB.SCHEMA.RUN_OUTPUT"))
    assert(MaterializationSql.dataTarget("CREATE SCOPED TEMPORARY TABLE \"SNOWPARK_TEMP_TABLE_ABC\" AS SELECT * FROM X").contains("\"SNOWPARK_TEMP_TABLE_ABC\""))
    assert(MaterializationSql.canonical("\"DB\".\"S\".\"T\"") == "DB.S.T")
  }
}
