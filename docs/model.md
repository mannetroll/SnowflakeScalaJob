# Educational model and reference cohort

All amounts are EUR. These invented assumptions exercise the pipeline; they are
not regulatory factors or calibrated estimates.

CP1 uses CCF 0.50 for revolving, 0.75 for term and 1.00 for mortgages:

```text
undrawn = max(limit − drawn, 0)
ead = drawn + ccf × undrawn
```

Over-limit drawn remains intact. Money is NUMBER(24,6), CCF NUMBER(9,6). Invalid
required values fail input validation.

CP2 normalizes positive weights within each asset. Asset net value is
`max(valuation × (1 − haircut) − realization_cost, 0)`. Discount using
`PV = net / (1 + annual_rate)^(months/12)`. Every allocation uses the same asset
discount rate (default 5%, configurable). Snowflake POWER approximates the factor
with floating-point arithmetic; immediately ROUND to six places and cast PV to
NUMBER(24,6) before allocation/aggregation. Fractions are NUMBER(18,12); allocated
PVs round/cast to six places. Rounding uses Snowflake's HALF_AWAY_FROM_ZERO default.
Per-asset conservation permits `link_count × 0.000001` EUR error; fraction sums
permit `link_count × 10^-12`. Aggregate before joining CP1; missing collateral
and link count become zero. Shared assets are not duplicated in full per account.

CP3 selects each event's latest version before aggregation. Unsecured cash flows
exclude collateral proceeds. Discount `(recovery − cost)` using the account's
annual effective rate and months/12; round/cast each PV to six places before
summing. Negative net unsecured flows are allowed; total capacity is floored at
zero. Missing events become zero PV/count:

```text
capacity = max(collateral_pv + unsecured_pv, 0)
capped_recovery = min(ead, capacity)
lgd_amount = ead − capped_recovery
lgd = 0 if ead = 0, else lgd_amount / ead
```

LGD and weighted LGD are NUMBER(18,10). Aggregate money uses exact Snowflake
NUMBER(38,6) sums, with no Double accumulation in Scala. Customer LGD is
`SUM(lgd_amount) / SUM(ead)`, zero when total EAD is zero. PD is basis points /
10,000 as NUMBER(18,10). Illustrative expected loss is `PD × SUM(lgd_amount)`,
rounded/cast to NUMBER(24,6). Six-place money intentionally preserves sub-cent
amounts for inspectable conservation.

After CP3, group by customer/date, join CUSTOMERS, and consume a ROW_NUMBER ranking
by EAD descending then account ID ascending. Rank one supplies the highest-EAD
account, including zero-EAD ties. Quality counts identify zero EAD, over-limit,
no collateral, no unsecured events and capacity exceeding EAD. Present zero-PV
events differ from missing events. CTAS executes this lazy plan through a view.

## Fixed cohort

Customers 1–4 each have revolving/term/mortgage accounts in order, 2% PD, zero
account discount rates and immediate cash flows. Assets have no haircuts/costs.
Expected values are independent of seed, date and collateral discount rate.

| Account | Customer | Drawn / limit | EAD | Collateral PV | Unsecured PV | Capped | LGD amount |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 0 / 0 | 0 | 0 | 0 | 0 | 0 |
| 2 | 1 | 0 / 0 | 0 | 0 | 0 | 0 | 0 |
| 3 | 1 | 0 / 0 | 0 | 0 | 0 | 0 | 0 |
| 4 | 2 | 150 / 100 | 150 | 0 | 0 | 0 | 150 |
| 5 | 2 | 100 / 100 | 100 | 0 | 100 | 100 | 0 |
| 6 | 2 | 100 / 100 | 100 | 0 | 250 | 100 | 0 |
| 7 | 3 | 100 / 100 | 100 | 60 | 0 | 60 | 40 |
| 8 | 3 | 100 / 100 | 100 | 180 | 0 | 100 | 0 |
| 9 | 3 | 100 / 100 | 100 | 0 | 0 | 0 | 100 |
| 10 | 4 | 100 / 100 | 100 | 50 | 30 | 80 | 20 |
| 11 | 4 | 100 / 100 | 100 | 0 | 40 | 40 | 60 |
| 12 | 4 | 100 / 100 | 100 | 0 | 0 | 0 | 100 |

Accounts 7/8 share EUR 240 at weights 1:3 (EUR 60/180). Account 10 has one
unsecured event, version 1 = EUR 10 and version 2 = EUR 30; use EUR 30, not their sum.

| Customer | EAD | Capped | LGD amount | Weighted LGD | Expected loss | Top account |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| 2 | 350 | 200 | 150 | 0.4285714286 | 3 | 4 |
| 3 | 300 | 160 | 140 | 0.4666666667 | 2.8 | 7 |
| 4 | 300 | 120 | 180 | 0.6000000000 | 3.6 | 10 |

The live validator uses these hand-calculated constants. Offline tests separately
calculate the cohort using Scala collections and test one-year 5% discounting
(EUR 105 → EUR 100). Live checks collect only this small cohort and scalar results.
