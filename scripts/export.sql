LOAD spatial;
-- County geometry, simplified hard enough for a web map but still recognisable
COPY (
  SELECT slu_name, ne_name, landsdel, iso,
         ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.02)) AS gj
  FROM counties ORDER BY slu_name
) TO 'site/geo_counties.json' (FORMAT JSON, ARRAY true);

COPY (SELECT region, ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom,0.02)) AS gj
      FROM regions ORDER BY region) TO 'site/geo_regions.json' (FORMAT JSON, ARRAY true);

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

COPY (SELECT * FROM bonitet_dist WHERE area IN
        ('N Norrland','S Norrland','Svealand','Götaland','Hela landet')
      ORDER BY area, year) TO 'site/bonitet_dist.json' (FORMAT JSON, ARRAY true);

COPY (SELECT station_id, name, lat, lon, slu_name, landsdel FROM station_county
      ORDER BY station_id) TO 'site/stations.json' (FORMAT JSON, ARRAY true);
