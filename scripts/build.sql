-- ---------------------------------------------------------------------------
-- skogsalderavverkning: build the analysis database
--   sources: SLU Skogsstatistik (PxWeb), Natural Earth admin-1, SMHI metobs
-- ---------------------------------------------------------------------------
-- Stop on the first error: the guards below are only useful if they halt the
-- build rather than scrolling past in a long log.
.bail on

INSTALL spatial; LOAD spatial;

-- === 1. Felling age (SLU figur 4.9) ========================================
CREATE OR REPLACE TABLE felling_age AS
SELECT
    CAST("År (Femårsmedelvärde)" AS INT)                                  AS year,
    "Landsdel"                                                            AS region,
    CASE WHEN "Bestånd dominerade av trädslag utan definerad LSÅ, ex. contorta"
              LIKE 'Inkl%' THEN 'incl' ELSE 'excl' END                    AS lsa_basis,
    CAST(value AS DOUBLE)                                                 AS age_years
FROM read_csv('data/raw/fig49.csv', header=true, all_varchar=true)
WHERE value <> '';

-- === 2. Site productivity / ståndort (SLU tabell 3.11a) ====================
-- "Bonitet" = site productivity class in m3sk/ha/yr; the closest published
-- proxy for ståndort. Kept at both county and landsdel resolution.
CREATE OR REPLACE TABLE bonitet AS
SELECT
    CAST("År (Femårsmedelvärde)" AS INT)  AS year,
    "Län"                                 AS area,
    "Ägargrupp"                           AS owner_group,
    "Tabellinnehåll"                      AS measure,
    "Bonitet"                             AS bonitet_class,
    CAST(value AS DOUBLE)                 AS value
FROM read_csv('data/raw/tab311a.csv', header=true, all_varchar=true)
WHERE value <> '';

-- Mean site productivity per area/year (all owners, all classes)
CREATE OR REPLACE TABLE site_index AS
SELECT year, area, value AS medelbonitet
FROM bonitet
WHERE measure = 'Medelbonitet (m³sk/ha, år)'
  AND owner_group = 'Alla ägargrupper'
  AND bonitet_class = 'Alla bonitetsklasser';

-- Distribution of forest area across productivity classes
CREATE OR REPLACE TABLE bonitet_dist AS
SELECT year, area, bonitet_class, value AS share_pct
FROM bonitet
WHERE measure = 'Andel av skogsmarksareal (%)'
  AND owner_group = 'Alla ägargrupper'
  AND bonitet_class <> 'Alla bonitetsklasser';

-- === 3. Geometry: counties, and landsdelar built by dissolving them ========
-- Full-resolution geometry. Simplification happens only at export time:
-- ST_Simplify is not topology preserving, so simplifying here would open gaps
-- between neighbouring counties and shave the coastline, silently dropping any
-- weather station that fell in the gap from every climate aggregate.
CREATE OR REPLACE TABLE county_geo AS
SELECT name AS ne_name, iso_3166_2 AS iso, geom
FROM ST_Read('data/geo/ne10/ne_10m_admin_1_states_provinces.shp')
WHERE admin = 'Sweden';

-- SLU county name  ->  Natural Earth name, plus the landsdel it belongs to
CREATE OR REPLACE TABLE county_map (slu_name VARCHAR, ne_name VARCHAR, landsdel VARCHAR);
INSERT INTO county_map VALUES
 ('Norrbottens län','Norrbotten','N Norrland'),
 ('Västerbottens län','Västerbotten','N Norrland'),
 ('Jämtlands län','Jämtland','S Norrland'),
 ('Västernorrlands län','Västernorrland','S Norrland'),
 ('Gävleborgs län','Gävleborg','S Norrland'),
 ('Dalarnas län','Dalarna','Svealand'),
 ('Värmlands län','Värmland','Svealand'),
 ('Örebro län','Orebro','Svealand'),
 ('Västmanlands län','Västmanland','Svealand'),
 ('Uppsala län','Uppsala','Svealand'),
 ('Stockholms län','Stockholm','Svealand'),
 ('Södermanlands län','Södermanland','Svealand'),
 ('Östergötlands län','Östergötland','Götaland'),
 ('Jönköpings län','Jönköping','Götaland'),
 ('Kronobergs län','Kronoberg','Götaland'),
 ('Kalmar län','Kalmar','Götaland'),
 ('Gotlands län','Gotland','Götaland'),
 ('Blekinge län','Blekinge','Götaland'),
 ('Skåne län','Skåne','Götaland'),
 ('Hallands län','Halland','Götaland'),
 ('Västra Götalands län','Västra Götaland','Götaland');

CREATE OR REPLACE TABLE counties AS
SELECT m.slu_name, m.ne_name, m.landsdel, g.iso, g.geom
FROM county_map m JOIN county_geo g USING (ne_name);

-- Guard: the join above is on hardcoded Natural Earth spellings. If a future
-- Natural Earth release renames a county it would vanish from the map, the
-- spatial join and the scatter with no error, so fail loudly instead.
CREATE OR REPLACE TEMP TABLE _county_check AS
SELECT list(slu_name) FILTER (ne_name NOT IN (SELECT ne_name FROM county_geo)) AS unmatched
FROM county_map;
SELECT CASE WHEN (SELECT count(*) FROM counties) = 21
            THEN 'counties OK: 21'
            ELSE error('county_map/Natural Earth join broke; unmatched: '
                       || coalesce((SELECT unmatched::VARCHAR FROM _county_check), 'none'))
       END AS county_guard;

CREATE OR REPLACE TABLE regions AS
SELECT landsdel AS region, ST_Union_Agg(geom) AS geom
FROM counties GROUP BY landsdel;

-- === 4. Climate: SMHI station temperatures, joined to counties spatially ===
CREATE OR REPLACE TABLE stations AS
SELECT station_id, name, lat, lon,
       ST_Point(lon, lat) AS geom
FROM read_csv('data/raw/smhi_stations.csv', header=true)
WHERE lat IS NOT NULL AND lon IS NOT NULL;

-- A station either falls inside a county, or - for the many that sit right on
-- the coastline or on an island the boundary data generalises away - is snapped
-- to the nearest county within 11 km. Stations further out are genuinely
-- offshore (sea and lighthouse stations) and are deliberately left unassigned
-- rather than attributed to a county's land climate.
--
-- Distances are measured in SWEREF99 TM, not in degrees. A degree of longitude
-- is about half a degree of latitude at these latitudes (0.1 deg is 11.1 km
-- north-south but 5.7 km east-west at 59N, less further north), so a degree
-- threshold is an ellipse rather than a circle - and, worse, ranking candidate
-- counties by degree distance can pick the further one for a station sitting
-- between two. always_xy is required: EPSG:4326 is officially lat/lon, and
-- without it the coordinates come through transposed.
CREATE OR REPLACE MACRO snap_metres() AS 11000;
CREATE OR REPLACE MACRO to_sweref(g) AS ST_Transform(g, 'EPSG:4326', 'EPSG:3006', true);

CREATE OR REPLACE TABLE counties_proj AS
SELECT slu_name, landsdel, to_sweref(geom) AS geom FROM counties;

CREATE OR REPLACE TABLE station_county AS
WITH inside AS (
  -- containment is topological, so it is unaffected by the projection
  SELECT s.station_id, s.name, s.lat, s.lon, c.slu_name, c.landsdel
  FROM stations s JOIN counties c ON ST_Within(s.geom, c.geom)
), coastal AS (
  SELECT s.station_id, s.name, s.lat, s.lon,
         c.slu_name, c.landsdel,
         row_number() OVER (PARTITION BY s.station_id
                            ORDER BY ST_Distance(to_sweref(s.geom), c.geom)) AS rk
  FROM stations s JOIN counties_proj c
    ON ST_DWithin(to_sweref(s.geom), c.geom, snap_metres())
  WHERE s.station_id NOT IN (SELECT station_id FROM inside)
)
SELECT station_id, name, lat, lon, slu_name, landsdel FROM inside
UNION ALL
SELECT station_id, name, lat, lon, slu_name, landsdel FROM coastal WHERE rk = 1;

-- Report stations that fell outside every county polygon, so losses are visible
SELECT (SELECT count(*) FROM stations) AS stations_total,
       (SELECT count(*) FROM station_county) AS stations_joined,
       (SELECT count(*) FROM stations) - (SELECT count(*) FROM station_county)
         AS stations_unjoined;

CREATE OR REPLACE TABLE monthly AS
SELECT station_id,
       CAST(ym[1:4] AS INT) AS year,
       CAST(ym[6:7] AS INT) AS month,
       temp_c
FROM read_csv('data/raw/smhi_monthly.csv', header=true)
WHERE temp_c IS NOT NULL;

-- Station-year aggregates: only years where the station reported all 12 months
-- (and Apr-Sep for the growing season) so the mean is not seasonally biased.
CREATE OR REPLACE TABLE station_year AS
SELECT station_id, year,
       avg(temp_c)                                        AS t_annual,
       avg(temp_c) FILTER (month BETWEEN 4 AND 9)         AS t_growing,
       count(*)                                           AS n_months,
       count(*) FILTER (month BETWEEN 4 AND 9)            AS n_growing
FROM monthly GROUP BY 1, 2
HAVING count(*) = 12;

-- Per-station 1961-1990 normal, then anomalies. Averaging anomalies rather
-- than absolute temperatures removes the bias from a changing station network.
CREATE OR REPLACE TABLE station_norm AS
SELECT station_id,
       avg(t_annual)  AS norm_annual,
       avg(t_growing) AS norm_growing,
       count(*)       AS n_years
FROM station_year WHERE year BETWEEN 1961 AND 1990
GROUP BY 1 HAVING count(*) >= 20;

CREATE OR REPLACE TABLE station_anom AS
SELECT y.station_id, y.year,
       y.t_annual  - n.norm_annual  AS anom_annual,
       y.t_growing - n.norm_growing AS anom_growing
FROM station_year y JOIN station_norm n USING (station_id);

CREATE OR REPLACE TABLE climate_county AS
SELECT sc.slu_name AS area, a.year,
       round(avg(a.anom_annual), 3)  AS anom_annual,
       round(avg(a.anom_growing), 3) AS anom_growing,
       count(*)                      AS n_stations
FROM station_anom a JOIN station_county sc USING (station_id)
GROUP BY 1, 2 HAVING count(*) >= 1;   -- anomalies are spatially coherent;
                                      -- n_stations is surfaced in the UI

CREATE OR REPLACE TABLE climate_region AS
SELECT sc.landsdel AS region, a.year,
       round(avg(a.anom_annual), 3)  AS anom_annual,
       round(avg(a.anom_growing), 3) AS anom_growing,
       count(*)                      AS n_stations
FROM station_anom a JOIN station_county sc USING (station_id)
GROUP BY 1, 2 HAVING count(*) >= 3;

-- ===========================================================================
-- 5. Precipitation and snow  (same anomaly method as temperature)
-- ===========================================================================
-- The station network differs per parameter, so use the union station list.
CREATE OR REPLACE TABLE stations_all AS
SELECT station_id, name, lat, lon, ST_Point(lon, lat) AS geom
FROM read_csv('data/raw/smhi_stations_all.csv', header=true)
WHERE lat IS NOT NULL AND lon IS NOT NULL;

CREATE OR REPLACE TABLE station_county_all AS
WITH inside AS (
  SELECT s.station_id, s.name, c.slu_name, c.landsdel
  FROM stations_all s JOIN counties c ON ST_Within(s.geom, c.geom)
), coastal AS (
  SELECT s.station_id, s.name, c.slu_name, c.landsdel,
         row_number() OVER (PARTITION BY s.station_id
                            ORDER BY ST_Distance(to_sweref(s.geom), c.geom)) AS rk
  FROM stations_all s JOIN counties_proj c
    ON ST_DWithin(to_sweref(s.geom), c.geom, snap_metres())
  WHERE s.station_id NOT IN (SELECT station_id FROM inside)
)
SELECT station_id, name, slu_name, landsdel FROM inside
UNION ALL
SELECT station_id, name, slu_name, landsdel FROM coastal WHERE rk = 1;

-- --- precipitation: annual totals, complete years only ---
CREATE OR REPLACE TABLE precip_year AS
SELECT station_id, CAST(ym[1:4] AS INT) AS year, sum(precip_mm) AS p_annual
FROM read_csv('data/raw/smhi_precip_monthly.csv', header=true)
WHERE precip_mm IS NOT NULL
GROUP BY 1,2 HAVING count(*) = 12;

CREATE OR REPLACE TABLE precip_norm AS
SELECT station_id, avg(p_annual) AS norm_p
FROM precip_year WHERE year BETWEEN 1961 AND 1990
GROUP BY 1 HAVING count(*) >= 20 AND avg(p_annual) > 0;

-- Precipitation anomaly is expressed as percent of normal, which is the
-- convention: 50 mm means something different in Norrbotten than in Halland.
CREATE OR REPLACE TABLE precip_anom AS
SELECT y.station_id, y.year, 100*(y.p_annual/n.norm_p - 1) AS anom_pct
FROM precip_year y JOIN precip_norm n USING (station_id);

CREATE OR REPLACE TABLE precip_county AS
SELECT sc.slu_name AS area, a.year, round(avg(a.anom_pct),2) AS anom_pct, count(*) AS n_stations
FROM precip_anom a JOIN station_county_all sc USING (station_id)
GROUP BY 1,2;

CREATE OR REPLACE TABLE precip_region AS
SELECT sc.landsdel AS region, a.year, round(avg(a.anom_pct),2) AS anom_pct, count(*) AS n_stations
FROM precip_anom a JOIN station_county_all sc USING (station_id)
GROUP BY 1,2 HAVING count(*) >= 3;

-- --- snow: cover days per Jul-Jun snow year, near-complete seasons only ---
-- Snow-cover days over a FIXED December-March window, labelled by the January
-- year. A whole-season "cover days" count is biased by how long each station
-- reports; pinning the window and requiring each of the four months to be
-- near-complete makes baseline and recent winters comparable.
CREATE OR REPLACE TABLE snow_monthly AS
SELECT station_id, year, month, n_obs, snow_days, max_depth_m
FROM read_csv('data/raw/smhi_snow_monthly.csv', header=true)
WHERE month IN (12, 1, 2, 3) AND n_obs >= 25;

CREATE OR REPLACE TABLE snow_winter AS
SELECT station_id,
       CASE WHEN month = 12 THEN year + 1 ELSE year END AS winter,
       sum(snow_days) AS cover_days,
       max(max_depth_m) AS max_depth_m,
       count(*) AS n_months
FROM snow_monthly
GROUP BY 1, 2 HAVING count(*) = 4;     -- all of Dec, Jan, Feb, Mar present

CREATE OR REPLACE TABLE snow_norm AS
SELECT station_id, avg(cover_days) AS norm_days, avg(max_depth_m) AS norm_depth
FROM snow_winter WHERE winter BETWEEN 1961 AND 1990
GROUP BY 1 HAVING count(*) >= 20;

CREATE OR REPLACE TABLE snow_anom AS
SELECT w.station_id, w.winter AS year,
       w.cover_days  - n.norm_days  AS anom_days,
       w.max_depth_m - n.norm_depth AS anom_depth
FROM snow_winter w JOIN snow_norm n USING (station_id);

CREATE OR REPLACE TABLE snow_county AS
SELECT sc.slu_name AS area, a.year,
       round(avg(a.anom_days),2)  AS anom_days,
       round(avg(a.anom_depth),3) AS anom_depth,
       count(*) AS n_stations
FROM snow_anom a JOIN station_county_all sc USING (station_id)
GROUP BY 1,2;

CREATE OR REPLACE TABLE snow_region AS
SELECT sc.landsdel AS region, a.year,
       round(avg(a.anom_days),2)  AS anom_days,
       round(avg(a.anom_depth),3) AS anom_depth,
       count(*) AS n_stations
FROM snow_anom a JOIN station_county_all sc USING (station_id)
GROUP BY 1,2 HAVING count(*) >= 3;

-- ===========================================================================
-- 6. Tree species
-- ===========================================================================
-- Stand types by county, including Contortaskog (SLU tabell 3.1a)
CREATE OR REPLACE TABLE stand_type AS
SELECT CAST("År (Femårsmedelvärde)" AS INT) AS year,
       "Län" AS area, "Beståndstyp" AS stand_type,
       CAST(value AS DOUBLE) AS share_pct
FROM read_csv('data/raw/bestandstyper.csv', header=true, all_varchar=true)
WHERE "Tabellinnehåll" LIKE 'Andel%' AND value <> '';

-- Felling volume by species and region (SLU tabell 4.1)
CREATE OR REPLACE TABLE felling_species AS
SELECT CAST("År" AS INT) AS year, "Landsdel" AS region,
       "Trädslag" AS species, CAST(value AS DOUBLE) AS mm3sk
FROM read_csv('data/raw/avv_tradslag_landsdel.csv', header=true, all_varchar=true)
WHERE value <> '';

-- Felling volume and area by harvest type (SLU tabell 4.6) - lets us separate
-- final felling from thinning, and derive volume per hectare
CREATE OR REPLACE TABLE felling_type AS
SELECT CAST("År" AS INT) AS year, "Landsdel" AS region,
       "Ägargrupp" AS owner_group, "Tabellinnehåll" AS measure,
       "Huggningsart" AS harvest_type, CAST(value AS DOUBLE) AS value
FROM read_csv('data/raw/avv_huggningsarter.csv', header=true, all_varchar=true)
WHERE value <> '';

-- ===========================================================================
-- 7. Disturbance: storms, snow damage, bark beetle
-- ===========================================================================
-- Share of forest area carrying each damage type (SLU tabell 3.38)
CREATE OR REPLACE TABLE damage AS
SELECT CAST("År (Femårsmedelvärde)" AS INT) AS year,
       "Landsdel" AS region, "Beståndstyp" AS stand_type,
       "Skadetyp" AS damage_type, CAST(value AS DOUBLE) AS share_pct
FROM read_csv('data/raw/skadetyper.csv', header=true, all_varchar=true)
WHERE value <> '';

-- Natural losses - the standing volume that died rather than being harvested.
-- Storm years show up here (SLU tabell 3.32).
CREATE OR REPLACE TABLE natural_loss AS
SELECT CAST("År (Femårsmedelvärde)" AS INT) AS year,
       "Landsdel" AS region, "Trädslag" AS species,
       "Skyddade områden" AS protection, CAST(value AS DOUBLE) AS mm3sk
FROM read_csv('data/raw/naturlig_avgang.csv', header=true, all_varchar=true)
WHERE value <> '';

-- ===========================================================================
-- 8. Composite index components, per county
-- ===========================================================================
-- Each driver is expressed as one number per county: recent period minus the
-- 1961-1990 normal (bonitet uses its own first/last decade, since its series
-- starts in 1985). Standardisation into z-scores happens in the page, so the
-- weighting stays visible and adjustable rather than baked in here.
CREATE OR REPLACE TABLE drivers AS
WITH b AS (
  SELECT area,
         avg(medelbonitet) FILTER (year BETWEEN 1985 AND 1994) AS b_early,
         avg(medelbonitet) FILTER (year BETWEEN 2014 AND 2023) AS b_late
  FROM site_index GROUP BY 1
), t AS (
  SELECT area, avg(anom_annual) AS d_temp
  FROM climate_county WHERE year BETWEEN 2011 AND 2024 GROUP BY 1
), p AS (
  SELECT area, avg(anom_pct) AS d_precip
  FROM precip_county WHERE year BETWEEN 2011 AND 2024 GROUP BY 1
), s AS (
  SELECT area, avg(anom_days) AS d_snow
  FROM snow_county WHERE year BETWEEN 2011 AND 2024 GROUP BY 1
), c AS (
  SELECT area, avg(share_pct) FILTER (year >= 2019) AS contorta_pct
  FROM stand_type WHERE stand_type = 'Contortaskog' GROUP BY 1
)
SELECT cm.slu_name AS area, cm.landsdel,
       -- centroid latitude, so the heatmap can order counties north to south
       round((SELECT ST_Y(ST_Centroid(c.geom)) FROM counties c
              WHERE c.slu_name = cm.slu_name), 4) AS lat,
       round(b.b_early,2) AS bonitet_early, round(b.b_late,2) AS bonitet_late,
       round(100*(b.b_late/b.b_early - 1),2) AS d_bonitet_pct,
       round(t.d_temp,3)   AS d_temp_c,
       round(p.d_precip,2) AS d_precip_pct,
       round(s.d_snow,2)   AS d_snow_days,
       round(c.contorta_pct,2) AS contorta_pct
FROM county_map cm
LEFT JOIN b ON b.area = cm.slu_name
LEFT JOIN t ON t.area = cm.slu_name
LEFT JOIN p ON p.area = cm.slu_name
LEFT JOIN s ON s.area = cm.slu_name
LEFT JOIN c ON c.area = cm.slu_name;
