# CTAS table-lineage app

The Streamlit app calls `GET_QUERY_OPERATOR_STATS` for the final CTAS query ID. It draws each distinct `TableScan` source feeding the `CreateTableAsSelect` output and lists the joins found in that CTAS step. These source-to-output edges are inferred from the query plan; they are not native Snowflake lineage.

The graph is a self-contained SVG embedded through `st.components.v1.html`. Click a table for scanned columns and scan counts; drag to pan, and use the wheel or toolbar to zoom.

The app lives in `EAD_LINEAGE.PUBLIC.EAD_LINEAGE_APP`, owned by `EAD_ROLE`, and uses `SCALING_BENCH_WH`. The demo job also runs as `EAD_ROLE`. Its `RISKDEMO` database can be dropped and recreated without deleting the app. The role has `USAGE` and `MONITOR` on the warehouse so it can execute the profile query. The sidebar lists the five latest final CTAS query IDs saved by the job; a query ID can also be entered manually. Snowflake retains completed query operator statistics for 14 days.

From the repository root, deploy with:

```bash
.venv/bin/snow streamlit deploy ead_lineage --replace \
  -c snowflake_scala_job --role EAD_ROLE --secondary-roles NONE \
  --database EAD_LINEAGE --schema PUBLIC
```

The deployment settings are in `snowflake.yml`. `SYSADMIN` creates `EAD_LINEAGE.PUBLIC.CTAS_RUNS` once through `sql/setup-ead-role.sql`. The job writes only query IDs there; the app reads the profile directly from Snowflake.
