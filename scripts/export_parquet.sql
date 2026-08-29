-- Parquet for duckdb-wasm to query directly in the browser.
-- One tidy long table per subject; the app does the shaping in SQL.
LOAD spatial;
SET preserve_insertion_order = false;

COPY (SELECT year, region, lsa_basis, age_years FROM felling_age)
  TO 'site/data/felling_age.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT year, area, medelbonitet FROM site_index)
  TO 'site/data/site_index.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT area, year, anom_annual, anom_growing, n_stations FROM climate_county)
  TO 'site/data/climate_county.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT region, year, anom_annual, anom_growing, n_stations FROM climate_region WHERE year >= 1900)
  TO 'site/data/climate_region.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT area, year, anom_pct, n_stations FROM precip_county)
  TO 'site/data/precip_county.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT region, year, anom_pct, n_stations FROM precip_region WHERE year >= 1900)
  TO 'site/data/precip_region.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT area, year, anom_days, n_stations FROM snow_county)
  TO 'site/data/snow_county.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT region, year, anom_days, n_stations FROM snow_region)
  TO 'site/data/snow_region.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT * FROM drivers) TO 'site/data/drivers.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, area, stand_type, share_pct FROM stand_type)
  TO 'site/data/stand_type.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, region, species, mm3sk FROM felling_species WHERE species <> 'Alla trädslag')
  TO 'site/data/felling_species.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, region, harvest_type, value FROM felling_type
      WHERE owner_group = 'Alla ägargrupper' AND measure LIKE 'Avverkad volym (%'
        AND harvest_type <> 'Alla huggningsarter')
  TO 'site/data/felling_type.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, region, damage_type, share_pct FROM damage
      WHERE stand_type = 'All skog' AND damage_type <> 'Inga skador')
  TO 'site/data/damage.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, region, species, mm3sk FROM natural_loss
      WHERE protection LIKE 'Exkl%' AND species <> 'Alla trädslag')
  TO 'site/data/natural_loss.parquet' (FORMAT parquet, COMPRESSION zstd);

-- Figures the page quotes in prose. Exported rather than hardcoded, because
-- the station count moves whenever the spatial join changes.
-- stations_temp is the parameter-22 network the temperature anomalies use;
-- precipitation and snow join through the larger union network, so the page
-- quotes both rather than implying one number covers all three series.
COPY (
  SELECT 'stations_temp' AS k, (SELECT count(*) FROM station_county)::BIGINT AS v
  UNION ALL SELECT 'stations_all', (SELECT count(*) FROM station_county_all)::BIGINT
  UNION ALL SELECT 'stations_offshore',
    ((SELECT count(*) FROM stations) - (SELECT count(*) FROM station_county))::BIGINT
) TO 'site/data/meta.parquet' (FORMAT parquet, COMPRESSION zstd);

-- County geometry stays GeoJSON: ECharts registerMap consumes it directly.
COPY (SELECT slu_name, landsdel, iso,
             ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.02)) AS gj
      FROM counties ORDER BY slu_name)
  TO 'site/data/counties.json' (FORMAT JSON, ARRAY true);

COPY (SELECT direction, kn, kn_label, partner, year, msek FROM wood_trade)
  TO 'site/data/wood_trade.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT region, assortment, year, kr_m3fub FROM prices_region)
  TO 'site/data/prices_region.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT assortment, year, kr_m3fub_2022 FROM prices_real)
  TO 'site/data/prices_real.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT region3, assortment, year, kr_m3fub, src FROM prices_long)
  TO 'site/data/prices_long.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT region3, pct_diff, n_pairs FROM prices_splice_check)
  TO 'site/data/prices_splice_check.parquet' (FORMAT parquet, COMPRESSION zstd);

COPY (SELECT year, region, owner_group, measure, v FROM notifications)
  TO 'site/data/notifications.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, county, measure, v FROM denied_felling)
  TO 'site/data/denied_felling.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT year, region, measure, v FROM protection)
  TO 'site/data/protection.parquet' (FORMAT parquet, COMPRESSION zstd);
