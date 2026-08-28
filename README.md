# skogsalderavverkning

How the average age at final felling has changed across Sweden, joined against site
productivity (*bonitet*, the published stand-in for *ståndort*), recorded climate, tree
species, and storm disturbance.

**Stack:** DuckDB (build-time *and* in the browser via DuckDB-WASM), Scala.js + Laminar
for the reactive UI, and ECharts for the charts and the choropleth. The page is bilingual
(Swedish/English), defaulting to Swedish.

The build-time half uses DuckDB + `spatial`: the county↔region mapping and the
station→county assignment are spatial joins in SQL. The browser half ships 15 zstd
parquet files that DuckDB-WASM registers as views, so every chart is fed by a real query
rather than a precomputed blob — the ten-year moving means, for instance, are a window
function over calendar years, not a JavaScript loop.

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
# 1. data
python3 scripts/fetch_slu.py                       # SLU tables + Natural Earth boundaries
python3 scripts/fetch_smhi.py                      # temperature
python3 scripts/fetch_smhi_precip_snow.py          # precipitation + snow
python3 scripts/flatten_jsonstat.py                # JSON-stat2 -> tidy CSV
duckdb data/skog.duckdb < scripts/build.sql        # tables + spatial joins
duckdb data/skog.duckdb < scripts/export_parquet.sql   # parquet for the browser

# 2. frontend  (subshell, so steps 3+ still run from the repo root)
python3 scripts/gen_keys.py                        # Keys.scala from the sv table
(cd frontend && scala-cli --power package . -o ../site/js/app.js --js -f --js-mode release)

# 3. assemble + check
python3 scripts/verify.py
python3 -m unittest discover -s tests
python3 scripts/build_pages.py                     # -> dist/
```

Serve `dist/` over HTTP (not `file://` — DuckDB-WASM needs a real origin for its
worker):

```sh
cd dist && python3 -m http.server 8899
```

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
islands are snapped to the nearest county within 11 km — measured in SWEREF99 TM, not in
degrees, since a degree of longitude is roughly half a degree of latitude here and a
degree threshold would be an ellipse that also mis-ranks candidate counties. 21 genuinely
offshore stations are left unassigned rather than attributed to a county's land climate.
Together these changes took the temperature join from 876 to 954 stations and gave all 21
counties coverage. The figures quoted on the page come from `meta.parquet`, so they do not
go stale when the join changes.

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

## Licence and attribution

The MIT licence in `LICENSE` covers **the code in this repository only** — the
scripts, the SQL and the page. It does not relicense the underlying data.

The published page and `site/data/` (the parquet files and `counties.json`) contain
figures derived from three third-party open datasets, each of which keeps its own terms and needs
attribution when reused:

- **SLU Skogsstatistik / Riksskogstaxeringen** — Swedish official forest
  statistics, Sveriges lantbruksuniversitet. <https://skogsstatistik.slu.se>
- **SMHI open data** (metobs parameters 2, 8, 22, 23) — Sveriges meteorologiska
  och hydrologiska institut, published under Creative Commons Erkännande 4.0
  (CC BY 4.0). <https://opendata.smhi.se>
- **Natural Earth** 10m admin-1 boundaries — public domain.
  <https://www.naturalearthdata.com>

If you reuse the figures, cite SLU and SMHI rather than this repository. Note
that the numbers here are *derived* — five-year means restated, anomalies
computed against a 1961–1990 baseline, stations aggregated to counties — so they
are not interchangeable with the publishers' own series.

## Deployment

`.github/workflows/pages.yml` builds and deploys on every push to `main` that
touches `frontend/`, `site/`, `build_pages.py`, `verify.py` or the workflow
itself. The same paths on a pull request build and check without deploying, so a
Dependabot action bump gets CI signal before merge rather than after.

CI compiles the Scala.js bundle with scala-cli (Coursier cached on
`frontend/project.scala`), runs `verify.py` and the tests, then assembles
`dist/` and publishes it. It does **not** refetch the data: a full refresh pulls
roughly 30 MB from rate-limited APIs, so the committed `site/data/*.parquet` is
the deployed artifact. To refresh, run the build locally and commit the result.

Translation keys are referenced through the generated `K` object, so a deleted
translation is a compile error rather than literal key text on the page. That
question used to be asked by a regex in `verify.py`, which got it wrong twice —
first matching only `t("...")` and so missing half the keys, then intersecting
the candidates with the key set and so reporting nothing at all. The compiler
has neither failure mode. Regenerate `Keys.scala` with `scripts/gen_keys.py`
after adding or removing a string.

`scripts/verify.py` fails the build on:

- a translation key defined in `sv` but not `en`, or the reverse — this once
  shipped as a literal `undefined` in a tooltip
- `Keys.scala` drifting from the tables, or a key defined but never used
- a Scala.js bundle that did not link, or missing build inputs
- a parquet file missing for any table `duckdb-loader.js` registers

`scripts/build_pages.py` assembles `dist/` from `site/shell.html` (already a
complete document), the compiled bundle, the loader and `site/data/`.


## Notes on the browser stack

**Scala.js links with `moduleKind none`.** ECharts and the DuckDB loader are reached as
globals rather than imported, so the app needs no bundler and no import map — and that
lets the Closure Compiler run, which ES-module output would rule out. The dev output is
2.6 MB across 17 files; the release bundle is a single 432 KB file.

**ECharts needs the map registered before first render.** A choropleth draws a
*registered* map, so the county polygons are turned into a GeoJSON FeatureCollection and
registered as `"sweden"` during boot. Skip that and the series has geometry to colour but
none to draw, and the panel comes up blank.

**DuckDB-WASM costs 32.6 MB on first load** to query 192 KB of parquet. It is cached
afterwards, and the boot banner covers the wait, but it is a real trade: the data is
small and the engine is not. It buys genuine SQL in the browser, which is the point of
the stack. If that ratio ever stops being worth it, the alternative is to precompute the
chart series at build time and load DuckDB lazily only for ad-hoc queries.

**It cannot run inside the Claude Artifact sandbox.** That CSP blocks the wasm fetch and
the blob-URL worker, so this app is Pages-only. A second hand-rolled page used to be kept
for that target; carrying two frontends cost more than it returned, and it was retired —
`git log -- site/index.html` has it if it is ever wanted back.

## Dependency updates

- **Dependabot** (`.github/dependabot.yml`) watches the GitHub Actions weekly and groups
  them into one PR.
- **Scala Steward** (`.github/workflows/scala-steward.yml`) watches the `//> using dep`
  directives in `frontend/project.scala`, which Dependabot does not understand.

Scala Steward runs with `GITHUB_TOKEN`, which is enough to open PRs but does not trigger
workflows on them; swap in a PAT or GitHub App token if you want CI to run on its PRs.
