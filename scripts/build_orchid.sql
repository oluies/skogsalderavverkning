-- ---------------------------------------------------------------------------
-- Do orchid observations cluster on ground that was just notified for felling?
--
-- Identification is spatial, not taxonomic. Each notified polygon is compared
-- against a 500 m ring around it: same neighbourhood, same recorders, same
-- season, same year-on-year growth in reporting - but not the ground that was
-- advertised. If reporting simply follows attention to an area, both rise
-- together. If it targets the notified stand, only the inside rises.
--
-- Only notices whose full year either side falls inside the observation
-- coverage are used. Without that the early notices lose part of their
-- before-window and the late ones part of their after-window, which alone
-- would manufacture a post-notice spike.
-- ---------------------------------------------------------------------------
INSTALL spatial; LOAD spatial;
.bail on

CREATE OR REPLACE TABLE obs_orchid AS
SELECT gbif_id, species, CAST(event_date AS DATE) AS obs_date, recorder, gbif_url,
       ST_Transform(ST_Point(lon, lat), 'EPSG:4326', 'EPSG:3006', true) AS geom
FROM read_csv('data/raw/gbif_orchid.csv', header=true)
WHERE event_date IS NOT NULL;

CREATE OR REPLACE TABLE obs_window AS
SELECT min(obs_date) AS first_obs, max(obs_date) AS last_obs FROM obs_orchid;

CREATE OR REPLACE TABLE notices AS
SELECT Beteckn AS case_id, Lan AS county, CAST(Inkomdatum AS DATE) AS notice_date, geom
FROM ST_Read('data/geo/sksAvverkAnm.gpkg')
WHERE Avverktyp = 'Föryngringsavverkning' AND Inkomdatum IS NOT NULL
  AND CAST(Inkomdatum AS DATE)
      BETWEEN (SELECT first_obs + INTERVAL 366 DAY FROM obs_window)
          AND (SELECT last_obs  - INTERVAL 366 DAY FROM obs_window);

CREATE OR REPLACE TABLE rings AS
SELECT case_id, county, notice_date,
       ST_Difference(ST_Buffer(geom, 500), geom) AS geom
FROM notices;

CREATE OR REPLACE TABLE zoned AS
SELECT 'inside' AS zone, o.recorder, n.county, n.case_id,
       date_diff('day', n.notice_date, o.obs_date) AS days_from_notice
FROM obs_orchid o JOIN notices n ON ST_Within(o.geom, n.geom)
WHERE abs(date_diff('day', n.notice_date, o.obs_date)) <= 365
UNION ALL
SELECT 'ring', o.recorder, r.county, r.case_id,
       date_diff('day', r.notice_date, o.obs_date)
FROM obs_orchid o JOIN rings r ON ST_Within(o.geom, r.geom)
WHERE abs(date_diff('day', r.notice_date, o.obs_date)) <= 365;

-- Event-time profile, each zone scaled by its own before-period mean so the two
-- are comparable despite the ring covering much more ground.
CREATE OR REPLACE TABLE orchid_event AS
WITH b AS (
  SELECT zone, CAST(floor(days_from_notice / 30.0) AS INT) * 30 AS bin_day,
         count(*) AS n
  FROM zoned GROUP BY 1, 2
), base AS (
  SELECT zone, avg(n) AS pre_mean FROM b WHERE bin_day < 0 GROUP BY 1
)
SELECT b.zone, b.bin_day, b.n,
       round(b.n / base.pre_mean, 3) AS rel_to_baseline
FROM b JOIN base USING (zone)
WHERE b.bin_day BETWEEN -360 AND 330;

CREATE OR REPLACE TABLE orchid_summary AS
SELECT zone,
       count(*) FILTER (days_from_notice < 0)  AS pre,
       count(*) FILTER (days_from_notice >= 0) AS post,
       round(100.0 * count(*) FILTER (days_from_notice >= 0)
             / nullif(count(*) FILTER (days_from_notice < 0), 0) - 100, 1) AS pct_change
FROM zoned GROUP BY zone;

-- Who reports. Aggregates only: the local table carries the observer name as
-- published with the record and a link to the GBIF occurrence, so any row can
-- be checked at source, but no named ranking is produced. The design cannot
-- separate tactical reporting from someone walking a stand that was just
-- advertised for felling, so a list of names would carry an accusation the
-- evidence does not support.
CREATE OR REPLACE TABLE orchid_recorders AS
WITH r AS (
  SELECT recorder, count(*) AS n, count(DISTINCT county) AS counties
  FROM zoned WHERE zone = 'inside' AND days_from_notice BETWEEN 0 AND 365
    AND recorder <> '' GROUP BY 1
), ranked AS (
  SELECT *, row_number() OVER (ORDER BY n DESC) AS rk, sum(n) OVER () AS tot FROM r
)
SELECT count(*) AS n_recorders, max(tot) AS n_obs,
       round(100.0 * sum(n) FILTER (rk <= 5)   / max(tot), 1) AS pct_top5,
       round(100.0 * sum(n) FILTER (rk <= 20)  / max(tot), 1) AS pct_top20,
       round(100.0 * sum(n) FILTER (rk <= 100) / max(tot), 1) AS pct_top100
FROM ranked;

-- How far a single recorder ranges, as a distribution.
CREATE OR REPLACE TABLE orchid_reach AS
SELECT counties, count(*) AS n_recorders FROM (
  SELECT recorder, count(DISTINCT county) AS counties
  FROM zoned WHERE zone = 'inside' AND days_from_notice BETWEEN 0 AND 365
    AND recorder <> '' GROUP BY 1
) GROUP BY 1 ORDER BY 1;

COPY (SELECT zone, bin_day, n, rel_to_baseline FROM orchid_event)
  TO 'site/data/orchid_event.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT zone, pre, post, pct_change FROM orchid_summary)
  TO 'site/data/orchid_summary.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT counties, n_recorders FROM orchid_reach)
  TO 'site/data/orchid_reach.parquet' (FORMAT parquet, COMPRESSION zstd);
