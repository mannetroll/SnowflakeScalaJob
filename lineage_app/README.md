# CTAS table-lineage app

The app draws table lineage from a saved `GET_QUERY_OPERATOR_STATS` profile, so it works on Snowflake Standard Edition without additional warehouse monitoring privileges. It ships with the archived final CTAS query profile. For a new run, upload the `E1-FINAL_CTAS-<query-id>.json` file from `build/ead-lgd/DEMO/profiles/`. The graph shows distinct `TableScan` source tables feeding the `CreateTableAsSelect` target; source-to-target edges are inferred from that one query plan. Join conditions appear in the table below the graph. This does not reproduce Snowflake's native table or column lineage service.

The graph is a self-contained SVG embedded through `st.components.v1.html`. Click a table for scanned columns and scan counts; drag to pan, and use the wheel or toolbar to zoom. No external JavaScript or package is needed.

From the repository root, deploy to `RISKDEMO.EAD_LGD_DEMO.EAD_LINEAGE_APP` on the existing `SCALING_BENCH_WH` warehouse:

```bash
.venv/bin/snow streamlit deploy ead_lineage --replace --open
```

The deployment settings and exact artifact list are in `snowflake.yml`. The Snowpark job saves profiles during the 14-day window in which `GET_QUERY_OPERATOR_STATS` can retrieve them. Once saved, you can upload a profile JSON later without querying that function from the app.
