-- ---------------------------------------------------------------------------
-- Do species observations cluster after a felling notification?
--
-- Difference-in-differences event study. The polygon is its own control, since
-- comparing notified to un-notified PLACES would measure where recorders live
-- rather than what they do. Orchidaceae can stop a felling through species
-- protection; Rosaceae cannot, and absorbs the general "notified ground gets
-- walked on more" effect that would otherwise be mistaken for the signal.
-- ---------------------------------------------------------------------------
INSTALL spatial; LOAD spatial;
.bail on

CREATE OR REPLACE TABLE notices AS
SELECT Beteckn AS case_id, Lan AS county,
       CAST(Inkomdatum AS DATE) AS notice_date,
       AnmaldHa AS notified_ha,
       geom                                   -- already EPSG:3006, metric
FROM ST_Read('data/geo/sksAvverkAnm.gpkg')
WHERE Avverktyp = 'Föryngringsavverkning'
  AND Inkomdatum IS NOT NULL;

CREATE OR REPLACE TABLE obs AS
SELECT * FROM (
  SELECT 'orchid' AS taxon, gbif_id, species, CAST(event_date AS DATE) AS obs_date,
         recorder, uncertainty_m,
         ST_Transform(ST_Point(lon, lat), 'EPSG:4326', 'EPSG:3006', true) AS geom
  FROM read_csv('data/raw/gbif_orchid.csv', header=true)
  UNION ALL
  SELECT 'control', gbif_id, species, CAST(event_date AS DATE),
         recorder, gbif_url, uncertainty_m,
         ST_Transform(ST_Point(lon, lat), 'EPSG:4326', 'EPSG:3006', true)
  FROM read_csv('data/raw/gbif_control.csv', header=true)
) WHERE obs_date IS NOT NULL;

-- Observations falling inside a notified polygon, with their event time.
-- A year either side: the pre and post windows then span the same seasons, so
-- orchid phenology cannot by itself produce a pre/post difference.
CREATE OR REPLACE TABLE joined AS
SELECT o.taxon, o.gbif_id, o.species, o.recorder, o.gbif_url, o.obs_date,
       n.case_id, n.county, n.notice_date, n.notified_ha,
       date_diff('day', n.notice_date, o.obs_date) AS days_from_notice
FROM obs o JOIN notices n ON ST_Within(o.geom, n.geom)
WHERE abs(date_diff('day', n.notice_date, o.obs_date)) <= 365;

-- Event-time profile: counts per 30-day bin per taxon.
CREATE OR REPLACE TABLE event_profile AS
SELECT taxon,
       CAST(floor(days_from_notice / 30.0) AS INT) * 30 AS bin_day,
       count(*) AS n_obs,
       count(DISTINCT case_id) AS n_polygons,
       count(DISTINCT recorder) AS n_recorders
FROM joined GROUP BY 1, 2;

-- The headline difference-in-differences.
CREATE OR REPLACE TABLE did AS
WITH w AS (
  SELECT taxon,
         count(*) FILTER (days_from_notice BETWEEN -365 AND -1) AS pre,
         count(*) FILTER (days_from_notice BETWEEN 0 AND 365)   AS post
  FROM joined GROUP BY taxon
)
SELECT taxon, pre, post,
       round(100.0 * (post - pre) / nullif(pre, 0), 1) AS pct_change
FROM w;

-- Recorder concentration. If a handful of people supply most of the in-polygon
-- observations, the event study is describing those people, not a population -
-- which is worth knowing before any of it is read as a general pattern.
--
-- The local table carries the observer name as published with the record, and a
-- link to the GBIF occurrence, so any row can be checked at source. Only the
-- aggregates below are exported: how concentrated the reporting is, and how far
-- a single recorder ranges. A ranked list of named individuals is not produced -
-- it would read as an accusation that this design cannot support, since a
-- cluster after a public notice is equally consistent with people checking
-- ground that was just advertised as about to be cut.
CREATE OR REPLACE TABLE recorder_concentration AS
WITH r AS (
  SELECT taxon, recorder, count(*) AS n,
         count(DISTINCT county) AS counties,
         count(DISTINCT case_id) AS polygons
  FROM joined WHERE days_from_notice BETWEEN 0 AND 365 AND recorder <> ''
  GROUP BY 1, 2
), ranked AS (
  SELECT *, row_number() OVER (PARTITION BY taxon ORDER BY n DESC) AS rk,
         sum(n) OVER (PARTITION BY taxon) AS total
  FROM r
)
SELECT taxon,
       count(*)                                        AS n_recorders,
       max(total)                                      AS n_obs,
       round(100.0 * sum(n) FILTER (rk <= 10) / max(total), 1)  AS pct_top10,
       round(100.0 * sum(n) FILTER (rk <= 100) / max(total), 1) AS pct_top100,
       max(counties)                                   AS max_counties_one_recorder
FROM ranked GROUP BY taxon;

-- How widely does a single recorder range? Aggregate distribution only.
CREATE OR REPLACE TABLE recorder_reach AS
SELECT taxon, counties, count(*) AS n_recorders
FROM (
  SELECT taxon, recorder, count(DISTINCT county) AS counties
  FROM joined WHERE recorder <> '' GROUP BY 1, 2
) GROUP BY 1, 2;
