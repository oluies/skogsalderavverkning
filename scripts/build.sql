-- ---------------------------------------------------------------------------
-- skogsalderavverkning: build the analysis database
--   sources: SLU Skogsstatistik (PxWeb), Natural Earth admin-1, SMHI metobs
-- ---------------------------------------------------------------------------
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
CREATE OR REPLACE TABLE county_geo AS
SELECT name AS ne_name, iso_3166_2 AS iso, ST_Simplify(geom, 0.01) AS geom
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

CREATE OR REPLACE TABLE regions AS
SELECT landsdel AS region, ST_Union_Agg(geom) AS geom
FROM counties GROUP BY landsdel;

-- === 4. Climate: SMHI station temperatures, joined to counties spatially ===
CREATE OR REPLACE TABLE stations AS
SELECT station_id, name, lat, lon,
       ST_Point(lon, lat) AS geom
FROM read_csv('data/raw/smhi_stations.csv', header=true)
WHERE lat IS NOT NULL AND lon IS NOT NULL;

CREATE OR REPLACE TABLE station_county AS
SELECT s.station_id, s.name, s.lat, s.lon, c.slu_name, c.landsdel
FROM stations s JOIN counties c ON ST_Within(s.geom, c.geom);

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
