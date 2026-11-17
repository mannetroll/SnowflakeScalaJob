# Final CTAS capture

Query ID: `01c879d8-0005-bcf4-0001-91ae0049f532`

- `query-profile.json` contains the verified Snowflake operator profile for the final CTAS.
- `query-profile.svg` visualizes all 72 Step 2 operators, their recorded parent links, timing bars, and profile statistics. Hover over an operator to inspect its saved details. Regenerate it with `python3 render-query-profile.py`.
- `profile-table-lineage.json` and `.graphml` show five observed `TableScan` sources and their inferred edges to `RISKDEMO.EAD_LGD_DEMO.EAD_OUTPUT`.
- `profile-table-lineage.svg` presents those five source-to-output edges with source scan counts from `query-profile.json`. Regenerate it with `python3 render-table-lineage.py`.
- `expected-table-lineage.json` and `.graphml` contain the job's expected table dependencies.
- `executed-ctas.sql` is the statement that produced the output table. `inspect-lineage.sql` contains optional Snowflake `GET_LINEAGE` queries.

The lineage files are derived from the query profile or job definition. They are not results from Snowflake's native `GET_LINEAGE`, which is unavailable on this Standard Edition account.
