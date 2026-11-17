"""Snowflake Streamlit app for profile-derived CTAS table lineage."""

import json
from pathlib import Path

import streamlit as st
import streamlit.components.v1 as components

from lineage_app.lineage_model import table_lineage
from lineage_app.lineage_view import graph_html


SAMPLE = Path("ctas/01c879d8-0005-bcf4-0001-91ae0049f532/query-profile.json")


st.set_page_config(layout="wide")
st.title("CTAS table lineage")
st.caption("A Snowflake query-profile view of source tables, output table, and joins. Works on Standard Edition.")

with st.sidebar:
    st.header("Profile")
    mode = st.radio("Source", ["Saved example", "Upload profile JSON"], label_visibility="collapsed")
    if mode == "Upload profile JSON":
        uploaded = st.file_uploader("Final CTAS profile", type="json")
        st.caption("Upload the E1-FINAL_CTAS-<query-id>.json file written by the Snowpark job.")
    else:
        uploaded = None
    st.divider()
    st.info("Table sources come from TableScan operators. Source-to-output arrows are inferred; native GET_LINEAGE is not used.")

if mode == "Saved example":
    profile = json.loads(SAMPLE.read_text())
else:
    if uploaded is None:
        st.info("Upload a final CTAS query-profile JSON file to draw its lineage.")
        st.stop()
    try:
        profile = json.loads(uploaded.getvalue())
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        st.error(f"The uploaded file is not valid JSON: {exc}")
        st.stop()

try:
    lineage = table_lineage(profile)
except ValueError as exc:
    st.error(str(exc))
    st.stop()

st.markdown(f"**Query ID:** `{lineage['query_id']}`")
one, two, three, four = st.columns(4)
one.metric("Source tables", len(lineage["sources"]))
two.metric("Output tables", 1)
three.metric("Table scans", sum(source["scans"] for source in lineage["sources"]))
four.metric("Join operators", len(lineage["joins"]))

components.html(graph_html(lineage), height=712, scrolling=False)

st.subheader("Joins in the CTAS step")
st.caption("The profile records these join conditions, but it does not assign each table-lineage edge to a specific join.")
st.dataframe(
    [
        {
            "Operator": f"Join [{join['operator']}]",
            "Type": join["type"],
            "Condition": join["condition"],
            "Input rows": join["input_rows"],
            "Output rows": join["output_rows"],
        }
        for join in lineage["joins"]
    ],
    use_container_width=True,
)
