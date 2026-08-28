"""Fetch SMHI monthly mean air temperature (parameter 22) for all stations.

Writes data/raw/smhi_stations.csv and data/raw/smhi_monthly.csv.
Exits non-zero if any station could not be downloaded, so a partial fetch can
never silently become a partial climate record.
"""
import csv, sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "scripts")
import smhi

PARAM = 22
OUT = "data/raw"

stations = smhi.stations(PARAM)
print(f"stations: {len(stations)}", flush=True)

with open(f"{OUT}/smhi_stations.csv", "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["station_id", "name", "lat", "lon", "height", "active", "from_ms", "to_ms"])
    for s in stations:
        w.writerow([s["id"], s["name"], s.get("latitude"), s.get("longitude"),
                    s.get("height"), s.get("active"), s.get("from"), s.get("to")])

failures = []


def one(s):
    try:
        return [(s["id"], d[:7], v) for d, v in smhi.observations(PARAM, s["id"])]
    except smhi.FetchError as e:
        failures.append(str(e))
        return []


rows = []
with ThreadPoolExecutor(max_workers=8) as ex:
    for i, res in enumerate(ex.map(one, stations)):
        rows.extend(res)
        if (i + 1) % 150 == 0:
            print(f"  {i+1}/{len(stations)} stations, {len(rows)} rows", flush=True)

with open(f"{OUT}/smhi_monthly.csv", "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["station_id", "ym", "temp_c"])
    w.writerows(rows)

print(f"DONE rows={len(rows)} failures={len(failures)}", flush=True)
if failures:
    for f in failures[:10]:
        print("  FAILED:", f, file=sys.stderr)
    sys.exit(1)
