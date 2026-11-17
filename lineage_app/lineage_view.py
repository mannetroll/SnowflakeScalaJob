"""Self-contained interactive SVG for Streamlit in Snowflake components v1."""

import html
import json


def _xml(value):
    return html.escape(str(value), quote=True)


def graph_html(lineage):
    sources = lineage["sources"]
    target = lineage["target"]
    source_y = [35 + index * 106 for index in range(len(sources))]
    target_y = (source_y[0] + source_y[-1]) / 2 - 10
    height = max(570, source_y[-1] + 105)
    edges = []
    cards = []
    for index, source in enumerate(sources):
        name = source["name"]
        y = source_y[index]
        target_port = target_y + 24 + index * 68 / max(len(sources) - 1, 1)
        edges.append(
            f'<path class="edge" data-source="{_xml(name)}" '
            f'd="M 329 {y + 38} C 490 {y + 38}, 520 {target_port}, 713 {target_port}" '
            'marker-end="url(#arrow)"/>'
        )
        cards.append(
            f'<g class="node source" data-name="{_xml(name)}" tabindex="0" role="button" '
            f'aria-label="{_xml(name)}; {source["scans"]} scans">'
            f'<title>{_xml(name)}</title>'
            f'<rect x="32" y="{y}" width="297" height="77" rx="10"/>'
            f'<rect class="accent" x="32" y="{y}" width="6" height="77" rx="3"/>'
            f'<text class="name" x="51" y="{y + 30}">{_xml(name.rsplit(".", 1)[-1])}</text>'
            f'<text class="schema" x="51" y="{y + 53}">{_xml(name.rsplit(".", 1)[0])}</text>'
            f'<text class="scan" x="310" y="{y + 67}" text-anchor="end">{source["scans"]} scans</text>'
            '</g>'
        )
    rows = lineage["rows_inserted"]
    row_label = f"{rows:,.0f} rows inserted" if rows is not None else "CTAS output"
    cards.append(
        f'<g class="node output" data-name="{_xml(target)}" tabindex="0" role="button" '
        f'aria-label="{_xml(target)}; {row_label}">'
        f'<title>{_xml(target)}</title>'
        f'<rect x="725" y="{target_y}" width="302" height="108" rx="12"/>'
        f'<rect class="accent" x="725" y="{target_y}" width="7" height="108" rx="3"/>'
        f'<text class="name" x="748" y="{target_y + 39}">{_xml(target.rsplit(".", 1)[-1])}</text>'
        f'<text class="schema" x="748" y="{target_y + 63}">{_xml(target.rsplit(".", 1)[0])}</text>'
        f'<text class="rows" x="748" y="{target_y + 90}">{_xml(row_label)}</text>'
        '</g>'
    )
    details = {
        source["name"]: {
            "kind": "Source table",
            "scans": source["scans"],
            "columns": source["columns"],
        }
        for source in sources
    }
    details[target] = {"kind": "CTAS output", "rows": row_label}
    data = json.dumps(details).replace("<", "\\u003c").replace(">", "\\u003e")
    target_json = json.dumps(target).replace("<", "\\u003c").replace(">", "\\u003e")
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><style>
* {{ box-sizing: border-box; }}
body {{ margin:0; font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif; color:#243247; }}
.shell {{ border:1px solid #dce3ec; border-radius:16px; overflow:hidden; background:#fbfcfe; }}
.toolbar {{ display:flex; align-items:center; gap:8px; padding:12px 16px; background:white; border-bottom:1px solid #dce3ec; }}
.toolbar strong {{ margin-right:auto; font-size:16px; }}
.toolbar button {{ border:1px solid #cad6e7; background:#fff; border-radius:8px; padding:5px 11px; cursor:pointer; color:#3c5d8c; font-size:15px; }}
.toolbar button:hover {{ background:#eaf2ff; }}
.layout {{ display:grid; grid-template-columns:minmax(0,1fr) 280px; min-height:585px; }}
.canvas {{ overflow:hidden; background:#fbfcfe; }}
svg {{ display:block; width:100%; height:585px; touch-action:none; cursor:grab; }}
svg:active {{ cursor:grabbing; }}
.edge {{ fill:none; stroke:#8aaddc; stroke-width:2.2; transition:stroke .15s,stroke-width .15s; }}
.edge.active {{ stroke:#2876db; stroke-width:3.5; }}
.node {{ cursor:pointer; }}
.node rect:first-of-type {{ fill:#fff; stroke:#bfd0e5; stroke-width:1.5; }}
.node .accent {{ fill:#4287f7; }}
.node.output rect:first-of-type {{ fill:#f1faf4; stroke:#79ba91; }}
.node.output .accent {{ fill:#32a263; }}
.node.selected rect:first-of-type {{ stroke:#2369c7; stroke-width:3; }}
.node .name {{ fill:#25354d; font-size:16px; font-weight:650; }}
.node .schema {{ fill:#6a7b92; font-size:11px; }}
.node .scan {{ fill:#4e7cc1; font-size:11px; }}
.node .rows {{ fill:#2b8852; font-size:13px; font-weight:650; }}
.detail {{ border-left:1px solid #dce3ec; background:white; padding:21px; overflow:auto; max-height:585px; }}
.eyebrow {{ color:#56749e; font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:.08em; }}
#detail-title {{ overflow-wrap:anywhere; font-size:19px; line-height:1.35; margin:10px 0 16px; }}
#detail-meta {{ color:#4f6481; font-size:14px; line-height:1.6; }}
#detail-columns {{ margin-top:19px; color:#4f6481; font-size:13px; line-height:1.6; overflow-wrap:anywhere; }}
.foot {{ padding:9px 16px; background:#f3f7fc; color:#63758e; font-size:12px; border-top:1px solid #dce3ec; }}
@media(max-width:700px) {{ .layout {{ grid-template-columns:1fr; }} .detail {{ border-left:0; border-top:1px solid #dce3ec; }} }}
</style></head><body>
<div class="shell"><div class="toolbar"><strong>Table lineage · Step {lineage["step"]}</strong>
<button id="zoom-in" title="Zoom in">+</button><button id="zoom-out" title="Zoom out">−</button>
<button id="reset" title="Reset view">Reset</button></div>
<div class="layout"><div class="canvas"><svg id="diagram" viewBox="0 0 1060 {height}" role="img" aria-label="{len(sources)} source tables feeding one CTAS output">
<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto"><path d="M0 0 L10 5 L0 10 Z" fill="#6b9ee3"/></marker></defs>
<g id="graph">{''.join(edges)}{''.join(cards)}</g></svg></div>
<aside class="detail"><div class="eyebrow" id="detail-kind">Select a table</div>
<h2 id="detail-title">Click a node in the graph</h2><div id="detail-meta">Drag to pan. Use the mouse wheel or buttons to zoom.</div>
<div id="detail-columns"></div></aside></div>
<div class="foot">Sources are observed TableScan operators. The arrows to the CTAS target are inferred from the query plan, not native GET_LINEAGE.</div></div>
<script>
const details = {data};
const svg = document.getElementById('diagram');
const graph = document.getElementById('graph');
let scale = 1, tx = 0, ty = 0, dragging = false, lastX = 0, lastY = 0;
function render() {{ graph.setAttribute('transform', `translate(${{tx}} ${{ty}}) scale(${{scale}})`); }}
function zoom(factor, x=530, y={height / 2}) {{
  const next = Math.max(0.6, Math.min(3, scale * factor));
  tx = x - (x - tx) * next / scale;
  ty = y - (y - ty) * next / scale;
  scale = next; render();
}}
document.getElementById('zoom-in').onclick = () => zoom(1.25);
document.getElementById('zoom-out').onclick = () => zoom(0.8);
document.getElementById('reset').onclick = () => {{ scale=1; tx=0; ty=0; render(); }};
svg.addEventListener('wheel', event => {{
  event.preventDefault();
  const box = svg.getBoundingClientRect();
  zoom(event.deltaY < 0 ? 1.12 : 1/1.12,
       (event.clientX-box.left)*1060/box.width,
       (event.clientY-box.top)*{height}/box.height);
}}, {{passive:false}});
svg.addEventListener('pointerdown', event => {{
  if (event.target.closest('.node')) return;
  dragging=true; lastX=event.clientX; lastY=event.clientY; svg.setPointerCapture(event.pointerId);
}});
svg.addEventListener('pointermove', event => {{
  if (!dragging) return;
  const box=svg.getBoundingClientRect();
  tx += (event.clientX-lastX)*1060/box.width;
  ty += (event.clientY-lastY)*{height}/box.height;
  lastX=event.clientX; lastY=event.clientY; render();
}});
svg.addEventListener('pointerup', () => {{ dragging=false; }});
function select(name) {{
  document.querySelectorAll('.node').forEach(node => node.classList.toggle('selected', node.dataset.name===name));
  document.querySelectorAll('.edge').forEach(edge => edge.classList.toggle('active', edge.dataset.source===name));
  const item=details[name];
  document.getElementById('detail-kind').textContent=item.kind;
  document.getElementById('detail-title').textContent=name;
  document.getElementById('detail-meta').textContent=item.scans ? `${{item.scans}} TableScan operators in this CTAS profile` : item.rows;
  document.getElementById('detail-columns').textContent=item.columns ? `Scanned columns: ${{item.columns.join(', ')}}` : '';
}}
document.querySelectorAll('.node').forEach(node => {{
  node.addEventListener('click', () => select(node.dataset.name));
  node.addEventListener('keydown', event => {{ if (event.key==='Enter' || event.key===' ') {{ event.preventDefault(); select(node.dataset.name); }} }});
}});
select({target_json});
</script></body></html>"""
