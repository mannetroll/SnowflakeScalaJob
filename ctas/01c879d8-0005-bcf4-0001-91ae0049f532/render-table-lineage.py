#!/usr/bin/env python3
"""Render profile-derived table lineage from the archived CTAS data."""

import json
from collections import Counter
from pathlib import Path
from xml.sax.saxutils import escape


DIRECTORY = Path(__file__).resolve().parent
LINEAGE = json.loads((DIRECTORY / "profile-table-lineage.json").read_text())
PROFILE = json.loads((DIRECTORY / "query-profile.json").read_text())
EDGES = LINEAGE["edges"]
SOURCES = list(dict.fromkeys(edge["source"] for edge in EDGES))
TARGET = EDGES[0]["target"]
SCANS = Counter(
    json.loads(operator["OPERATOR_ATTRIBUTES"])["table_name"]
    for operator in PROFILE["operators"]
    if operator["OPERATOR_TYPE"] == "TableScan"
)
CTAS = next(
    operator for operator in PROFILE["operators"]
    if operator["OPERATOR_TYPE"] == "CreateTableAsSelect"
)
ROWS_INSERTED = json.loads(CTAS["OPERATOR_STATISTICS"])["dml"]["number_of_rows_inserted"]


def xml(value):
    return escape(str(value), {'"': "&quot;"})


def text(value, x, y, css_class="", **attrs):
    extra = " ".join(f'{key.replace("_", "-")}="{xml(item)}"' for key, item in attrs.items())
    class_attribute = f' class="{css_class}"' if css_class else ""
    return f'<text x="{x}" y="{y}"{class_attribute} {extra}>{xml(value)}</text>'


WIDTH = 2190
HEIGHT = 1210
SOURCE_X = 122
SOURCE_W = 425
SOURCE_H = 100
SOURCE_Y = [350 + index * 145 for index in range(len(SOURCES))]
TARGET_X = 1110
TARGET_Y = 632
TARGET_W = 415
TARGET_H = 130
PANEL_X = 1643
PANEL_W = 500

parts = [
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" '
    f'viewBox="0 0 {WIDTH} {HEIGHT}" role="img" '
    f'aria-label="Profile-derived table lineage for {xml(LINEAGE["queryId"])}">',
    """<defs>
  <marker id="arrow" markerWidth="11" markerHeight="11" refX="9" refY="5.5" orient="auto">
    <path d="M0 0 L11 5.5 L0 11 Z" fill="#6b9ee3"/>
  </marker>
</defs>
<style>
  text { font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif; }
  .title { fill:#20252d; font-size:44px; font-weight:700; }
  .subtitle { fill:#65748b; font-size:22px; }
  .section { fill:#263244; font-size:27px; font-weight:700; }
  .table-name { fill:#243247; font-size:23px; font-weight:650; }
  .schema { fill:#6c7c93; font-size:15px; }
  .detail { fill:#4f6481; font-size:17px; }
  .metric { fill:#23344d; font-size:24px; font-weight:650; }
</style>""",
    f'<rect width="{WIDTH}" height="{HEIGHT}" fill="#ffffff"/>',
    text("Table lineage", 62, 78, "title"),
    text(LINEAGE["queryId"], 62, 124, "subtitle"),
    text("Final CTAS · 5 source tables · 1 output table", 62, 168, "subtitle"),
    '<rect x="62" y="207" width="252" height="42" rx="12" fill="#e7f0ff" stroke="#9cbcf1"/>',
    text("Profile-derived lineage", 81, 234, fill="#2764bd", font_size="17", font_weight="650"),
    text("TableScan sources feed the CTAS output", 339, 233, fill="#697992", font_size="17"),
    '<rect x="58" y="276" width="1525" height="860" rx="21" fill="#fbfcfe" stroke="#dce3ec"/>',
    text("SOURCE TABLES", 122, 323, fill="#637895", font_size="16", font_weight="700"),
    text("CTAS OUTPUT", 1110, 606, fill="#5f806f", font_size="16", font_weight="700"),
]

for index, source in enumerate(SOURCES):
    source_center = SOURCE_Y[index] + SOURCE_H / 2
    target_port = TARGET_Y + 30 + index * 17
    parts.append(
        f'<path d="M {SOURCE_X + SOURCE_W} {source_center} '
        f'C 815 {source_center}, 840 {target_port}, {TARGET_X - 12} {target_port}" '
        'fill="none" stroke="#8aaddc" stroke-width="2.7" marker-end="url(#arrow)"/>'
    )

for index, source in enumerate(SOURCES):
    y = SOURCE_Y[index]
    table_name = source.rsplit(".", 1)[-1]
    schema_name = source.rsplit(".", 1)[0]
    count = SCANS[source]
    parts.append(f'<g><title>{xml(source)} · {count} TableScan operators</title>')
    parts.append(f'<rect x="{SOURCE_X}" y="{y}" width="{SOURCE_W}" height="{SOURCE_H}" rx="11" fill="#ffffff" stroke="#b8cde6" stroke-width="1.7"/>')
    parts.append(f'<rect x="{SOURCE_X}" y="{y}" width="7" height="{SOURCE_H}" rx="3.5" fill="#4287f7"/>')
    parts.append(text(table_name, SOURCE_X + 25, y + 39, "table-name"))
    parts.append(text(schema_name, SOURCE_X + 25, y + 68, "schema"))
    parts.append(text(f"{count} scans", SOURCE_X + SOURCE_W - 20, y + 81, fill="#4e7cc1", font_size="15", text_anchor="end"))
    parts.append("</g>")

parts.append(f'<g><title>{xml(TARGET)} · {ROWS_INSERTED:,} rows inserted</title>')
parts.append(f'<rect x="{TARGET_X}" y="{TARGET_Y}" width="{TARGET_W}" height="{TARGET_H}" rx="13" fill="#f2fbf5" stroke="#77bc91" stroke-width="2"/>')
parts.append(f'<rect x="{TARGET_X}" y="{TARGET_Y}" width="8" height="{TARGET_H}" rx="4" fill="#32a263"/>')
parts.append(text(TARGET.rsplit(".", 1)[-1], TARGET_X + 30, TARGET_Y + 48, "table-name", font_size="28"))
parts.append(text(TARGET.rsplit(".", 1)[0], TARGET_X + 30, TARGET_Y + 79, "schema"))
parts.append(text(f"{ROWS_INSERTED:,} rows inserted", TARGET_X + 30, TARGET_Y + 109, fill="#2b8852", font_size="17", font_weight="650"))
parts.append("</g>")

parts.append(f'<rect x="{PANEL_X}" y="276" width="{PANEL_W}" height="860" rx="19" fill="#ffffff" stroke="#d6dee8" stroke-width="1.5"/>')
parts.append(text("Lineage summary", PANEL_X + 39, 337, "section"))
for index, (label, value) in enumerate((
    ("Source tables", len(SOURCES)),
    ("Output tables", 1),
    ("Inferred edges", len(EDGES)),
    ("TableScan operators", sum(SCANS.values())),
)):
    y = 421 + index * 91
    parts.append(text(label, PANEL_X + 40, y, "detail"))
    parts.append(text(value, PANEL_X + PANEL_W - 42, y, "metric", text_anchor="end"))
    parts.append(f'<line x1="{PANEL_X + 40}" y1="{y + 24}" x2="{PANEL_X + PANEL_W - 40}" y2="{y + 24}" stroke="#dce3ec"/>')

parts.append(text("Evidence", PANEL_X + 39, 804, "section"))
parts.append(text("Source tables: observed TableScan", PANEL_X + 40, 858, "detail"))
parts.append(text("operators in the final CTAS profile.", PANEL_X + 40, 886, "detail"))
parts.append(text("Arrows: inferred source-to-output", PANEL_X + 40, 935, "detail"))
parts.append(text("dependencies from the query plan.", PANEL_X + 40, 963, "detail"))
parts.append(text("Native GET_LINEAGE unavailable", PANEL_X + 40, 1032, fill="#a06421", font_size="17", font_weight="650"))
parts.append(text("on this Standard Edition account.", PANEL_X + 40, 1060, "detail"))
parts.append("</svg>")

(DIRECTORY / "profile-table-lineage.svg").write_text("\n".join(parts) + "\n")
