# skogsalderavverkning

How the average age at final felling has changed across Sweden, joined against site
productivity (*bonitet*, the published stand-in for *ståndort*) and recorded warming.

Everything is built with DuckDB + the `spatial` extension. The map, the county↔region
mapping and the station→county assignment are all spatial joins done in SQL.

## Sources

| What | Source | Resolution |
|---|---|---|
| Age at final felling | SLU Skogsstatistik, figur 4.9 | landsdel, 2004–2022 |
| Site productivity (bonitet) | SLU Skogsstatistik, tabell 3.11a | county, 1985–2023 |
| Mean bonitet, protected/unprotected | SLU Skogsstatistik, tabell 3.11b | landsdel, 2005–2023 |
| Boundaries | Natural Earth 10m admin-1 | 21 counties |
| Temperature | SMHI open data, parameter 22 (monthly mean) | 971 stations, from 1722 |

SLU's PxWeb API root is `https://skogsstatistik.slu.se/api/v1/sv/OffStat`
(note: *not* under `/pxweb/`, which 404s). It rate-limits aggressively — the
fetch scripts back off and retry.

## Build

```sh
python3 scripts/fetch_smhi.py        # SMHI monthly temperatures -> data/raw/
python3 scripts/flatten_jsonstat.py  # JSON-stat2 -> tidy CSV
duckdb data/skog.duckdb < scripts/build.sql    # tables + spatial joins
duckdb data/skog.duckdb < scripts/export.sql   # JSON for the page
python3 scripts/make_payload.py      # bundle -> site/payload.json
```

Then inline `site/payload.json` into the `<script id="payload">` tag in `site/index.html`.

## Method notes

**Temperature anomalies, not absolutes.** Sweden's station network has changed shape
over a century, so averaging raw station temperatures conflates climate with which
stations happened to report. Each station is instead differenced against its own
1961–1990 normal (requiring ≥20 complete years in that window), and the anomalies are
averaged. Station-years are only used when all 12 months are present.

**County climate needs ≥1 qualifying station.** Anomalies are spatially coherent over
long distances, so one station is acceptable; the count is surfaced in the page tooltip.
Södermanland has no station with both a baseline and recent data and is blank.

## The ståndort caveat

SLU does **not** publish felling age broken down by ståndort or site index — figur 4.9
is only dimensioned by *landsdel* and by whether stands lacking a defined LSÅ
(*lägsta slutavverkningsålder*) are counted. A true felling-age-by-ståndort
cross-tabulation would need the underlying Riksskogstaxeringen plot records, which are
not in the open API. This project therefore *joins* the regional age series to
county-level bonitet — two different resolutions, not one cross-tabulation.

Note also that Swedish `ståndortsbonitering` derives site productivity from site factors
that themselves include climate terms, so rising bonitet in the north is partly a real
growth signal and partly definitional. It is not a clean measurement of climate impact.
