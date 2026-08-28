LOAD spatial;
-- County geometry, simplified hard enough for a web map but still recognisable
COPY (
  SELECT slu_name, ne_name, landsdel, iso,
         ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.02)) AS gj
  FROM counties ORDER BY slu_name
) TO 'site/geo_counties.json' (FORMAT JSON, ARRAY true);

COPY (SELECT * FROM felling_age ORDER BY year, region)
     TO 'site/felling_age.json' (FORMAT JSON, ARRAY true);

COPY (SELECT * FROM site_index ORDER BY area, year)
     TO 'site/site_index.json' (FORMAT JSON, ARRAY true);

COPY (SELECT area, year, anom_annual, anom_growing, n_stations
      FROM climate_county ORDER BY area, year)
     TO 'site/climate_county.json' (FORMAT JSON, ARRAY true);

COPY (SELECT region, year, anom_annual, anom_growing, n_stations
      FROM climate_region WHERE year >= 1900 ORDER BY region, year)
     TO 'site/climate_region.json' (FORMAT JSON, ARRAY true);

COPY (SELECT station_id, name, lat, lon, slu_name, landsdel FROM station_county
      ORDER BY station_id) TO 'site/stations.json' (FORMAT JSON, ARRAY true);

COPY (SELECT area, year, anom_pct, n_stations FROM precip_county ORDER BY area, year)
     TO 'site/precip_county.json' (FORMAT JSON, ARRAY true);
COPY (SELECT region, year, anom_pct, n_stations FROM precip_region WHERE year>=1900
      ORDER BY region, year) TO 'site/precip_region.json' (FORMAT JSON, ARRAY true);
COPY (SELECT area, year, anom_days, n_stations FROM snow_county ORDER BY area, year)
     TO 'site/snow_county.json' (FORMAT JSON, ARRAY true);
COPY (SELECT region, year, anom_days, n_stations FROM snow_region ORDER BY region, year)
     TO 'site/snow_region.json' (FORMAT JSON, ARRAY true);
COPY (SELECT * FROM drivers ORDER BY area) TO 'site/drivers.json' (FORMAT JSON, ARRAY true);
COPY (SELECT * FROM stand_type ORDER BY area, stand_type, year)
     TO 'site/stand_type.json' (FORMAT JSON, ARRAY true);
COPY (SELECT * FROM felling_species WHERE species<>'Alla trädslag' ORDER BY region, species, year)
     TO 'site/felling_species.json' (FORMAT JSON, ARRAY true);
COPY (SELECT year, region, harvest_type, value FROM felling_type
      WHERE owner_group='Alla ägargrupper' AND measure LIKE 'Avverkad volym (%'
        AND harvest_type<>'Alla huggningsarter' ORDER BY region, harvest_type, year)
     TO 'site/felling_type.json' (FORMAT JSON, ARRAY true);
COPY (SELECT year, region, damage_type, share_pct FROM damage
      WHERE stand_type='All skog' AND damage_type<>'Inga skador' ORDER BY region, damage_type, year)
     TO 'site/damage.json' (FORMAT JSON, ARRAY true);
COPY (SELECT year, region, species, mm3sk FROM natural_loss
      WHERE protection LIKE 'Exkl%' AND species<>'Alla trädslag' ORDER BY region, species, year)
     TO 'site/natural_loss.json' (FORMAT JSON, ARRAY true);
