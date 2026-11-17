#!/usr/bin/env python3
"""Render the archived Snowflake operator profile as a standalone SVG."""

import json
from collections import defaultdict
from pathlib import Path
from xml.sax.saxutils import escape


DIRECTORY = Path(__file__).resolve().parent
PROFILE = json.loads((DIRECTORY / "query-profile.json").read_text())
OPERATORS = {o["OPERATOR_ID"]: o for o in PROFILE["operators"] if o["STEP_ID"] == 2}
CHILDREN = defaultdict(list)
for operator_id, operator in OPERATORS.items():
    parents = json.loads(operator["PARENT_OPERATORS"]) if operator["PARENT_OPERATORS"] else []
    for parent in parents:
        CHILDREN[parent].append(operator_id)

for children in CHILDREN.values():
    children.sort()


def attributes(operator):
    return json.loads(operator["OPERATOR_ATTRIBUTES"])


def statistics(operator):
    return json.loads(operator["OPERATOR_STATISTICS"])


def timing(operator):
    return json.loads(operator["EXECUTION_TIME_BREAKDOWN"])


def percentage(operator):
    return timing(operator).get("overall_percentage", 0) * 100


def short_text(value, limit=24):
    value = str(value).replace("\n", " ")
    return value if len(value) <= limit else value[: limit - 1] + "…"


def subtitle(operator):
    info = attributes(operator)
    kind = operator["OPERATOR_TYPE"]
    if "table_name" in info:
        return info["table_name"].split(".")[-1]
    if kind == "Join":
        return info.get("join_type", "")
    if kind == "Filter":
        return info.get("filter_condition", "")
    if kind == "Aggregate":
        return ", ".join(info.get("grouping_keys", [])) or ", ".join(info.get("functions", []))
    if kind == "WindowFunction":
        return ", ".join(info.get("functions", []))
    if kind == "JoinFilter":
        return f"Join {info.get('join_id', '')}"
    return ""


def xml(value):
    return escape(str(value), {'"': "&quot;"})


def element(tag, content="", **attrs):
    fields = " ".join(f'{key.rstrip("_").replace("_", "-")}="{xml(value)}"' for key, value in attrs.items())
    return f"<{tag} {fields}>{content}</{tag}>" if fields else f"<{tag}>{content}</{tag}>"


CARD_W = 140
CARD_H = 53
LEVEL_GAP = 99
LEAF_GAP = 151
GRAPH_LEFT = 82
GRAPH_TOP = 278
POSITIONS = {}


def place_tree(operator_id, depth, next_leaf):
    children = CHILDREN[operator_id]
    if children:
        centers = [place_tree(child, depth + 1, next_leaf) for child in children]
        center = sum(centers) / len(centers)
    else:
        center = GRAPH_LEFT + next_leaf[0] * LEAF_GAP + CARD_W / 2
        next_leaf[0] += 1
    POSITIONS[operator_id] = (center - CARD_W / 2, GRAPH_TOP + depth * LEVEL_GAP)
    return center


place_tree(0, 0, [0])
MAIN_BOTTOM = max(y for _, y in POSITIONS.values()) + CARD_H
DETACHED_TOP = MAIN_BOTTOM + 138


def place_detached(operator_id, left, top, leaf_spacing=160):
    leaves = [0]

    def visit(current, depth):
        children = CHILDREN[current]
        if children:
            centers = [visit(child, depth + 1) for child in children]
            center = sum(centers) / len(centers)
        else:
            center = left + leaves[0] * leaf_spacing + CARD_W / 2
            leaves[0] += 1
        POSITIONS[current] = (center - CARD_W / 2, top + depth * LEVEL_GAP)
        return center

    visit(operator_id, 0)


place_detached(27, 210, DETACHED_TOP + 85)
for root, x in ((26, 960), (52, 1190), (61, 1420)):
    place_detached(root, x, DETACHED_TOP + 85)

CHART_W = 2290
SIDEBAR_X = 2370
SIDEBAR_W = 800
CANVAS_W = SIDEBAR_X + SIDEBAR_W + 42
CANVAS_H = max(y for _, y in POSITIONS.values()) + CARD_H + 112
parts = []
parts.append(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS_W}" height="{CANVAS_H}" '
    f'viewBox="0 0 {CANVAS_W} {CANVAS_H}" role="img" '
    f'aria-label="Snowflake query profile for {xml(PROFILE["queryId"])}">'
)
parts.append("""<defs>
  <marker id="arrow" markerWidth="6" markerHeight="6" refX="5.3" refY="3" orient="auto">
    <path d="M0 0 L6 3 L0 6 Z" fill="#9aa9bc"/>
  </marker>
</defs>
<style>
  text { font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif; }
  .title { fill:#20252d; font-size:42px; font-weight:700; }
  .muted { fill:#68768e; }
  .card-title { fill:#263244; font-size:11px; font-weight:650; }
  .card-sub { fill:#697992; font-size:9px; }
  .section { fill:#263244; font-size:27px; font-weight:700; }
  .side-row { fill:#526786; font-size:19px; }
  .side-value { fill:#263244; font-size:19px; }
</style>""")
parts.append(f'<rect width="{CANVAS_W}" height="{CANVAS_H}" fill="#ffffff"/>')
parts.append(element("text", "Query profile", x=62, y=78, class_="title"))
parts.append(element("text", PROFILE["queryId"], x=62, y=122, fill="#5b6c86", font_size="25"))
parts.append(element("text", "Step 2 of 2  ·  72 operators  ·  100,000 rows inserted", x=62, y=167, fill="#697992", font_size="18"))
parts.append('<rect x="61" y="203" width="172" height="43" rx="12" fill="#e7f0ff" stroke="#90b6fa"/>')
parts.append(element("text", "Step 2", x=111, y=231, fill="#2463c5", font_size="18", font_weight="650"))
parts.append(element("text", "Operator data flows upward toward Result [0]", x=260, y=230, fill="#697992", font_size="15"))
parts.append(f'<rect x="54" y="259" width="{CHART_W}" height="{MAIN_BOTTOM - 259 + 55}" rx="22" fill="#fbfcfe" stroke="#dce3ec"/>')
parts.append(f'<rect x="54" y="{DETACHED_TOP}" width="{CHART_W}" height="{CANVAS_H - DETACHED_TOP - 47}" rx="22" fill="#fbfcfe" stroke="#dce3ec"/>')
parts.append(element("text", "Operators without a recorded parent", x=88, y=DETACHED_TOP + 46, fill="#526786", font_size="22", font_weight="650"))
parts.append(element("text", "These groups are separate in the saved operator statistics.", x=88, y=DETACHED_TOP + 72, fill="#697992", font_size="14"))

for operator_id, operator in OPERATORS.items():
    x, y = POSITIONS[operator_id]
    parents = json.loads(operator["PARENT_OPERATORS"]) if operator["PARENT_OPERATORS"] else []
    for parent in parents:
        px, py = POSITIONS[parent]
        source_x, source_y = x + CARD_W / 2, y
        target_x, target_y = px + CARD_W / 2, py + CARD_H
        midpoint = (source_y + target_y) / 2
        parts.append(
            f'<path d="M {source_x:.1f} {source_y:.1f} C {source_x:.1f} {midpoint:.1f}, '
            f'{target_x:.1f} {midpoint:.1f}, {target_x:.1f} {target_y + 5:.1f}" '
            'fill="none" stroke="#aab7c8" stroke-width="1.35" marker-end="url(#arrow)"/>'
        )

for operator_id, operator in OPERATORS.items():
    x, y = POSITIONS[operator_id]
    kind = operator["OPERATOR_TYPE"]
    percent = percentage(operator)
    accent = "#3788f8" if percent else "#d4dce7"
    fill = "#fff8e9" if kind == "Aggregate" and percent >= 10 else "#ffffff"
    tooltip = json.dumps({
        "operator": f"{kind} [{operator_id}]",
        "attributes": attributes(operator),
        "statistics": statistics(operator),
        "execution_time_breakdown": timing(operator),
    }, indent=2)
    parts.append(f'<g><title>{xml(tooltip)}</title>')
    parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{CARD_W}" height="{CARD_H}" rx="6" fill="{fill}" stroke="#d4dce7"/>')
    parts.append(element("text", short_text(f"{kind} [{operator_id}]", 17), x=f"{x + 9:.1f}", y=f"{y + 16:.1f}", class_="card-title"))
    parts.append(element("text", f"{percent:.1f}%", x=f"{x + CARD_W - 8:.1f}", y=f"{y + 16:.1f}", fill="#697992", font_size="9", text_anchor="end"))
    parts.append(element("text", short_text(subtitle(operator), 24), x=f"{x + 9:.1f}", y=f"{y + 31:.1f}", class_="card-sub"))
    parts.append(f'<rect x="{x + 9:.1f}" y="{y + 39:.1f}" width="{CARD_W - 18}" height="4" rx="2" fill="#e1e7ef"/>')
    if percent:
        parts.append(f'<rect x="{x + 9:.1f}" y="{y + 39:.1f}" width="{(CARD_W - 18) * min(percent / 20, 1):.1f}" height="4" rx="2" fill="{accent}"/>')
    parts.append("</g>")


def panel(y, height, title):
    parts.append(f'<rect x="{SIDEBAR_X}" y="{y}" width="{SIDEBAR_W}" height="{height}" rx="18" fill="#ffffff" stroke="#d6dee8" stroke-width="1.5"/>')
    parts.append(element("text", title, x=SIDEBAR_X + 45, y=y + 61, class_="section"))


panel(259, 665, "Most expensive nodes (7 of 72)")
most_expensive = sorted(OPERATORS.values(), key=lambda o: (-percentage(o), o["OPERATOR_ID"]))[:7]
for row, operator in enumerate(most_expensive):
    y = 388 + row * 74
    parts.append(f'<line x1="{SIDEBAR_X + 43}" y1="{y + 23}" x2="{SIDEBAR_X + SIDEBAR_W - 43}" y2="{y + 23}" stroke="#dce3ec"/>')
    parts.append(element("text", f'{operator["OPERATOR_TYPE"]} [{operator["OPERATOR_ID"]}]', x=SIDEBAR_X + 45, y=y, class_="side-row"))
    parts.append(element("text", f"{percentage(operator):.1f}%", x=SIDEBAR_X + SIDEBAR_W - 46, y=y, class_="side-value", text_anchor="end"))

panel(958, 435, "Profile overview · Step 2")
time_totals = defaultdict(float)
for operator in OPERATORS.values():
    for category, value in timing(operator).items():
        if category != "overall_percentage":
            time_totals[category] += value * 100
processing = time_totals["processing"]
remote_io = time_totals["remote_disk_io"]
unattributed = 100 - processing - remote_io
bar_x, bar_y, bar_w = SIDEBAR_X + 45, 1067, SIDEBAR_W - 90
parts.append(f'<rect x="{bar_x}" y="{bar_y}" width="{bar_w}" height="15" rx="7.5" fill="#f59f4b"/>')
parts.append(f'<path d="M {bar_x + 7.5} {bar_y} H {bar_x + bar_w * processing / 100} V {bar_y + 15} H {bar_x + 7.5} A 7.5 7.5 0 0 1 {bar_x} {bar_y + 7.5} A 7.5 7.5 0 0 1 {bar_x + 7.5} {bar_y}" fill="#4287f7"/>')
parts.append(f'<rect x="{bar_x + bar_w * processing / 100:.1f}" y="{bar_y}" width="{bar_w * remote_io / 100:.1f}" height="15" fill="#20c6cf"/>')
for row, (label, value, color) in enumerate((
    ("Processing", processing, "#4287f7"),
    ("Remote disk I/O", remote_io, "#20c6cf"),
    ("Unattributed", unattributed, "#f59f4b"),
)):
    y = 1140 + row * 70
    parts.append(f'<circle cx="{SIDEBAR_X + 51}" cy="{y - 6}" r="5" fill="{color}"/>')
    parts.append(element("text", label, x=SIDEBAR_X + 67, y=y, class_="side-row"))
    parts.append(element("text", f"{value:.1f}%", x=SIDEBAR_X + SIDEBAR_W - 45, y=y, class_="side-value", text_anchor="end"))

panel(1428, 334, "Statistics")
ctas = OPERATORS[1]
inserted = statistics(ctas)["dml"]["number_of_rows_inserted"]
for row, (label, value) in enumerate((
    ("Rows inserted", f"{inserted:,}"),
    ("Step 1", "CREATE TABLE [0]"),
    ("Profile status", "Verified" if PROFILE.get("verified") else "Unverified"),
)):
    y = 1540 + row * 72
    parts.append(f'<line x1="{SIDEBAR_X + 44}" y1="{y + 22}" x2="{SIDEBAR_X + SIDEBAR_W - 44}" y2="{y + 22}" stroke="#dce3ec"/>')
    parts.append(element("text", label, x=SIDEBAR_X + 45, y=y, class_="side-row"))
    parts.append(element("text", value, x=SIDEBAR_X + SIDEBAR_W - 45, y=y, class_="side-value", text_anchor="end"))

parts.append(element("text", "Source: query-profile.json · GET_QUERY_OPERATOR_STATS", x=SIDEBAR_X + 45, y=1830, fill="#697992", font_size="16"))
parts.append(element("text", "Hover over a node for its full saved attributes and statistics.", x=SIDEBAR_X + 45, y=1860, fill="#697992", font_size="15"))
parts.append("</svg>")
(DIRECTORY / "query-profile.svg").write_text("\n".join(parts) + "\n")
