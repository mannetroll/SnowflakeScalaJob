# Project working conventions

- Bootstrap once: `ACCOUNTADMIN` creates `EAD_ROLE` and grants warehouse `USAGE, MONITOR`; `SYSADMIN` creates `RISKDEMO`, `EAD_LINEAGE`, their schemas, and the `CTAS_RUNS` ID table through the setup SQL scripts.
- Repeated runs use `EAD_ROLE` with secondary roles `NONE`. The Snowpark job creates the demo tables and reads `GET_QUERY_OPERATOR_STATS`; the Streamlit app is owned and deployed by `EAD_ROLE`.
- Before rerunning a retained demo, explicitly execute `sql/drop-demo-tables.sql` as `EAD_ROLE`. Keep DROP out of the job itself. Do not drop `EAD_LINEAGE` during demo cleanup.
- If `RISKDEMO` itself is dropped, rerun `sql/setup-riskdemo.sql` once as `SYSADMIN` before the next `EAD_ROLE` job.
- Keep the Streamlit app on `SYSTEM$WAREHOUSE_RUNTIME`; do not move it to container runtime.
- Local preview sets `EAD_LOCAL_DEV=1` in `streamlitstart.sh` and uses `st.connection("snowflake").session()`. The deployed warehouse app leaves the flag unset and uses `get_active_session()`.
- Store only final CTAS query IDs in `EAD_LINEAGE.PUBLIC.CTAS_RUNS`. The app reads operator profiles directly with `GET_QUERY_OPERATOR_STATS` and derives table arrows from `TableScan` operators. Do not copy whole profiles into the metadata table or claim native `GET_LINEAGE`.
- Generated artifacts belong in ignored `build/` or `output/`. Keep source, setup SQL, and the explicit DROP script in Git.
