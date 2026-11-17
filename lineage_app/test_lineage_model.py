import json
import unittest
from pathlib import Path

from lineage_app.lineage_model import parse_query_id, table_lineage
from lineage_app.lineage_view import graph_html


ROOT = Path(__file__).resolve().parent.parent
CAPTURE = ROOT / "ctas/01c879d8-0005-bcf4-0001-91ae0049f532"


class LineageModelTest(unittest.TestCase):
    def test_query_id_validation(self):
        self.assertEqual(parse_query_id(" 01C879D8-0005-BCF4-0001-91AE0049F532 "),
                         "01c879d8-0005-bcf4-0001-91ae0049f532")
        with self.assertRaises(ValueError):
            parse_query_id("x'); DROP DATABASE RISKDEMO; --")

    def test_archived_ctas_matches_saved_lineage(self):
        profile = json.loads((CAPTURE / "query-profile.json").read_text())
        saved = json.loads((CAPTURE / "profile-table-lineage.json").read_text())
        lineage = table_lineage(profile)

        self.assertEqual(lineage["query_id"], saved["queryId"])
        self.assertEqual(lineage["target"], saved["edges"][0]["target"])
        self.assertEqual({source["name"] for source in lineage["sources"]},
                         {edge["source"] for edge in saved["edges"]})
        self.assertEqual(sum(source["scans"] for source in lineage["sources"]), 15)
        self.assertEqual(len(lineage["joins"]), 16)
        self.assertEqual(lineage["rows_inserted"], 100000)

    def test_graph_escapes_table_names(self):
        malicious = {
            "step": 2,
            "target": "DB.SCHEMA.OUT",
            "rows_inserted": 1,
            "sources": [{"name": "DB.SCHEMA.<script>alert(1)</script>", "scans": 1, "columns": []}],
        }
        markup = graph_html(malicious)
        self.assertNotIn("<script>alert(1)</script>", markup)
        self.assertIn("&lt;script&gt;", markup)


if __name__ == "__main__":
    unittest.main()
