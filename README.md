# skogsalderavverkning

How the average age at final felling has changed across Sweden, joined against site
productivity (*bonitet*, the published stand-in for *ståndort*), recorded climate, tree
species, and storm disturbance.

Everything is built with DuckDB + the `spatial` extension. The map, the county↔region
mapping and the station→county assignment are all spatial joins done in SQL. The page
is bilingual (Swedish/English) and self-contained — one HTML file with the data inlined.

## Sources

| What | Source | Resolution |
|---|---|---|
| Age at final felling | SLU figur 4.9 | landsdel, 2004–2022 |
| Site productivity (bonitet) | SLU tabell 3.11a | county, 1985–2023 |
| Felling by species | SLU tabell 4.1 | landsdel, 1984–2023 |
| Felling by harvest type | SLU tabell 4.6 | landsdel, 1984–2023 |
| Natural losses | SLU tabell 3.32 | landsdel × species, 1995–2023 |
| Damage by type (wind/snow, bark beetle) | SLU tabell 3.38 | landsdel, 2006–2023 |
| Stand types incl. *Contortaskog* | SLU tabell 3.1a | county, 2005–2023 |
| Temperature (monthly mean) | SMHI metobs param 22 | 975 stations |
| Precipitation (monthly sum) | SMHI metobs param 23 | 2169 stations |
| Snow depth (daily) | SMHI metobs param 8 | 1897 stations |
| Boundaries | Natural Earth 10m admin-1 | 21 counties |

SLU's PxWeb API root is `https://skogsstatistik.slu.se/api/v1/sv/OffStat`
(note: *not* under `/pxweb/`, which the browser URL suggests but which 404s). It
rate-limits aggressively — `fetch_slu.py` backs off and retries.

## Build

Runs from a clean checkout; every fetch step is idempotent and skips what it already has.

```sh
python3 scripts/fetch_slu.py                    # SLU tables + Natural Earth boundaries
python3 scripts/fetch_smhi.py                   # temperature
python3 scripts/fetch_smhi_precip_snow.py       # precipitation + snow
python3 scripts/flatten_jsonstat.py             # JSON-stat2 -> tidy CSV
duckdb data/skog.duckdb < scripts/build.sql     # tables + spatial joins
duckdb data/skog.duckdb < scripts/export.sql    # JSON for the page
python3 scripts/make_payload.py                 # bundle -> site/payload.json
python3 scripts/inline_payload.py               # inline it into site/index.html
```

`scripts/smhi.py` is the shared SMHI client. Both fetchers exit non-zero if any station
download failed, so a partial fetch cannot silently become a partial climate record.

## Method notes

**Anomalies, not absolutes.** Sweden's station network has changed shape over a century,
so averaging raw station values conflates climate with which stations happened to report
— raw yearly means make 2000 look colder than 1980. Each station is instead differenced
against its own 1961–1990 normal (requiring ≥20 qualifying years) and the anomalies are
averaged. Temperature and precipitation use complete 12-month years; precipitation is
expressed as percent of normal.

**Snow uses a fixed December–March window.** "Days with snow cover" across a whole season
measures how long a station *reports* as much as how long snow lies — most stations only
report through the winter half-year (median 93 observations per season). Pinning the
window and requiring all four months to be near-complete makes baseline and recent
winters comparable. It also costs coverage: only 10 of 21 counties qualify.

**Geometry is simplified for display only.** `ST_Simplify` is not topology preserving, so
using a simplified boundary for the station spatial join opens gaps between counties and
shaves the coastline, silently dropping stations. The build joins against full-resolution
geometry and simplifies only at export. Stations on the coastline or on generalised-away
islands are snapped to the nearest county within ~11 km; 29 genuinely offshore stations
are left unassigned rather than attributed to a county's land climate. This recovered 70
stations (876 → 946) and gave all 21 counties temperature coverage.

## The ståndort caveat

SLU does **not** publish felling age broken down by ståndort or site index — figur 4.9 is
only dimensioned by *landsdel* and by whether stands lacking a defined LSÅ
(*lägsta slutavverkningsålder*) are counted. A true felling-age-by-ståndort
cross-tabulation would need the underlying Riksskogstaxeringen plot records, which are not
in the open API. This project therefore *joins* the regional age series to county-level
bonitet — two different resolutions, not one cross-tabulation.

Note also that Swedish `ståndortsbonitering` derives site productivity from site factors
that themselves include climate terms, so rising bonitet in the north is partly a real
growth signal and partly definitional. It is not a clean measurement of climate impact.

## On the storms

Gudrun (January 2005) threw roughly 75 million m³sk, mostly in Götaland, and the data
shows it: spruce natural losses rise seven-fold and wind/snow damage covers 13% of
Götaland's forest area. But it does **not** move the felling age, because the salvage was
booked as *övriga huggningsarter* — that category nearly triples from 3.7 to 10.7 million
m³sk a year while *slutavverkning* holds flat at 12–13, and final felling's volume per
hectare stays near 277 m³sk/ha throughout. Figure 4.9 is largely insulated from windthrow
by construction, which makes the northern decline more likely to be a real management
shift than a storm artefact.

## The composite index

The index standardises each driver (bonitet change, warming, precipitation, snow) to a
z-score across the 21 counties and combines them as a weighted *mean* of the available
components — not a sum, which would pull a county missing a driver toward the middle.
Weights are adjustable in the page precisely because there is no objectively correct
weighting; the index shows where drivers coincide, and establishes no causal chain.
