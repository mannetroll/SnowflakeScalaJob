-- Run after KEEP_OBJECTS=true. Requires Enterprise Edition and lineage privileges.
-- Snowflake lineage metadata may not be available immediately after CTAS.
-- Returned rows are observed edges; the JSON manifests contain plan-derived expectations only.
SELECT * FROM TABLE(SNOWFLAKE.CORE.GET_LINEAGE(
  'RISKDEMO.EAD_LGD_DEMO.EAD_OUTPUT', 'TABLE', 'UPSTREAM', 5));

SELECT * FROM TABLE(SNOWFLAKE.CORE.GET_LINEAGE(
  'RISKDEMO.EAD_LGD_DEMO.EAD_OUTPUT.TOTAL_EAD', 'COLUMN', 'UPSTREAM', 5));

SELECT * FROM TABLE(SNOWFLAKE.CORE.GET_LINEAGE(
  'RISKDEMO.EAD_LGD_DEMO.EAD_OUTPUT.LGD_AMOUNT', 'COLUMN', 'UPSTREAM', 5));
