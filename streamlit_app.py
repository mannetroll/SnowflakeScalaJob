"""Snowflake Streamlit app for profile-derived CTAS table lineage."""

import streamlit as st
import streamlit.components.v1 as components

from lineage_app.lineage_model import fetch_profile, parse_query_id, recent_ctas, table_lineage
from lineage_app.lineage_view import graph_html
from snowflake.snowpark.context import get_active_session

st.set_page_config(layout="wide")
st.title("CTAS table lineage")
st.caption("A Snowflake query-profile view of source tables, output table, and joins. Works on Standard Edition.")
session = get_active_session()
if "query_id" not in st.session_state:
    st.session_state["query_id"] = ""

with st.sidebar:
    st.header("Final CTAS profile")
    st.subheader("Latest 5 CTAS")
    selected = False
    for run in recent_ctas(session):
        if st.button(f"{run['RUN_ID']} · {run['QUERY_ID']}", key=run["QUERY_ID"]):
            st.session_state["query_id"] = run["QUERY_ID"]
            selected = True
    query_id = st.text_input("Query ID", key="query_id", placeholder="Final CTAS query ID from the latest run")
    load = st.button("Load profile", type="primary") or selected
    st.caption("Completed query profiles are available for 14 days.")
    st.divider()
    st.info("Table sources come from TableScan operators. Source-to-output arrows are inferred; native GET_LINEAGE is not used.")

if not query_id.strip():
    st.info("Enter a final CTAS query ID and click Load profile.")
    st.stop()

if load:
    try:
        st.session_state["profile"] = fetch_profile(session, query_id)
    except Exception as exc:
        st.error(f"Could not load the query profile: {exc}")
        st.stop()

profile = st.session_state.get("profile")
try:
    selected_query_id = parse_query_id(query_id)
except ValueError as exc:
    st.error(str(exc))
    st.stop()
if profile is None or profile["queryId"] != selected_query_id:
    st.info("Enter a final CTAS query ID and click Load profile.")
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
