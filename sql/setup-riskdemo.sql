-- One-time setup authorized for this demonstration.
-- Execute under the user-approved role with CREATE DATABASE permission.
-- The Scala job itself does not provision databases, schemas or warehouses.
-- Verify the existing warehouse is X-Small before starting the live ScalaTest.
USE ROLE SYSADMIN;
SHOW WAREHOUSES LIKE 'SCALING_BENCH_WH';

CREATE DATABASE IF NOT EXISTS RISKDEMO;
CREATE SCHEMA IF NOT EXISTS RISKDEMO.EAD_LGD_DEMO;

USE DATABASE RISKDEMO;
USE SCHEMA EAD_LGD_DEMO;
USE WAREHOUSE SCALING_BENCH_WH;

SELECT CURRENT_ROLE(), CURRENT_WAREHOUSE(), CURRENT_DATABASE(), CURRENT_SCHEMA();
