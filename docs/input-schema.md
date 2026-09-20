# Input schemas and keys

`Datasets` in `Fixtures.scala` defines all schemas. Every column is required:
Parquet non-nullable and landing NOT NULL. Missing collateral/recovery means absent
relationship/event rows, never NULL amounts. Query validation supplements DDL.

| Encoding | Physical Parquet | Snowflake landing type |
| --- | --- | --- |
| long | INT64 | NUMBER(18,0) |
| int | INT32 | NUMBER(10,0) |
| string | BINARY (UTF8) | VARCHAR(40) |

Money is integer EUR cents; rates are integer basis points. REPORTING_DAY is raw
INT32 days since 1970-01-01, explicitly converted with DATEADD in Snowflake.

| Dataset | Column | Encoding / domain |
| --- | --- | --- |
| CUSTOMERS | CUSTOMER_ID | long; positive unique ID |
| | SEGMENT | string; RETAIL, SME, PRIVATE |
| | COUNTRY | string; SE, DE, FR |
| | RATING | int; 1–9 |
| | PD_BPS | int; 0–10,000 |
| | REPORTING_DAY | int; configured date |
| ACCOUNTS | ACCOUNT_ID | long; positive unique ID |
| | CUSTOMER_ID | long; foreign key to CUSTOMERS |
| | PRODUCT_TYPE | string; REVOLVING, TERM, MORTGAGE |
| | DRAWN_CENTS | long; nonnegative |
| | LIMIT_CENTS | long; nonnegative; may be below drawn |
| | DISCOUNT_BPS | int; 0–10,000 |
| | REPORTING_DAY | int; configured date |
| COLLATERAL_ASSETS | COLLATERAL_ID | long; positive unique ID |
| | ASSET_TYPE | string; PROPERTY, VEHICLE |
| | VALUATION_CENTS | long; nonnegative |
| | HAIRCUT_BPS | int; 0–10,000 |
| | REALIZATION_COST_CENTS | long; nonnegative |
| | MONTHS_TO_REALIZATION | int; 0–120 |
| ACCOUNT_COLLATERAL | ACCOUNT_ID | long; foreign key to ACCOUNTS |
| | COLLATERAL_ID | long; foreign key to COLLATERAL_ASSETS |
| | ALLOCATION_WEIGHT | int; positive |
| EXPECTED_RECOVERIES | RECOVERY_EVENT_ID | long; positive event ID |
| | VERSION | int; positive |
| | ACCOUNT_ID | long; foreign key to ACCOUNTS |
| | RECOVERY_CENTS | long; nonnegative projected unsecured cash |
| | COST_CENTS | long; nonnegative projected cost |
| | MONTHS_TO_RECOVERY | int; 0–120 |
| | REVISION_ORDER | long; positive deterministic ordering |

Physical keys: CUSTOMERS `(CUSTOMER_ID)`, ACCOUNTS `(ACCOUNT_ID)`, assets
`(COLLATERAL_ID)`, links `(ACCOUNT_ID, COLLATERAL_ID)`, recoveries
`(RECOVERY_EVENT_ID, VERSION)`. After selecting versions, the logical recovery key
is `(RECOVERY_EVENT_ID)`. An event belongs to one account. Window order is VERSION
descending, REVISION_ORDER descending, ACCOUNT_ID ascending; input-key validation
makes the highest version unique.

All assets are linked, and shared assets secure only one customer's accounts.
Some accounts have multiple assets; others have none. Ordinary customers have
two assets except every fifth customer has one, with two links per asset. Every
29th ordinary account has no recovery; others have two events, with an extra
version for every 97th event. Customers 1–4 override these patterns for reference
cases. No local random or clock state affects business values.

Checkpoint key: `(ACCOUNT_ID)`. Final business key:
`(CUSTOMER_ID, REPORTING_DATE)`. Every account and customer appears exactly once
at its output grain. Ownership, date equality, domains, keys and foreign keys are
validated before business transformation.
