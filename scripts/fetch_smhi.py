"""Fetch SMHI monthly mean air temperature (parameter 22) for all stations.

Writes data/raw/smhi_stations.csv and data/raw/smhi_monthly.csv.
"""
import csv, io, json, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

API = "https://opendata-download-metobs.smhi.se/api/version/1.0/parameter/22"
OUT = "data/raw"


def get(url, tries=4):
    for a in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "skogsalder/1.0"})
            return urllib.request.urlopen(req, timeout=90).read()
        except Exception as e:
            if a == tries - 1:
                return None
            time.sleep(2 * (a + 1))
    return None


stations = json.loads(get(f"{API}.json"))["station"]
print(f"stations: {len(stations)}", flush=True)

with open(f"{OUT}/smhi_stations.csv", "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["station_id", "name", "lat", "lon", "height", "active", "from_ms", "to_ms"])
    for s in stations:
        w.writerow([s["id"], s["name"], s.get("latitude"), s.get("longitude"),
                    s.get("height"), s.get("active"), s.get("from"), s.get("to")])


def one(s):
    """Return list of (station_id, yyyy-mm, value) from the corrected archive."""
    raw = get(f"{API}/station/{s['id']}/period/corrected-archive/data.csv")
    if not raw:
        return []
    text = raw.decode("utf-8-sig", errors="replace")
    # SMHI CSV: a metadata preamble, then a header row starting with "Från Datum"
    # or "Datum", then the observations. Find the last header-looking line.
    lines = text.splitlines()
    start = None
    for i, ln in enumerate(lines):
        f0 = ln.split(";")[0].strip().strip('"')
        if f0 in ("Datum", "Från Datum Tid (UTC)", "Från Datum"):
            start = i
            break
    if start is None:
        return []
    rdr = csv.reader(io.StringIO("\n".join(lines[start:])), delimiter=";")
    hdr = next(rdr, None)
    if not hdr:
        return []
    cols = [c.strip() for c in hdr]
    # date column: prefer an explicit "Datum"; else the last "Till"-style column
    try:
        di = cols.index("Datum")
    except ValueError:
        di = 1 if len(cols) > 1 else 0
    try:
        vi = cols.index("Lufttemperatur")
    except ValueError:
        vi = di + 1
    out = []
    for r in rdr:
        if len(r) <= max(di, vi):
            continue
        d, v = r[di].strip(), r[vi].strip().replace(",", ".")
        if len(d) < 7 or not v:
            continue
        try:
            out.append((s["id"], d[:7], float(v)))
        except ValueError:
            continue
    return out


rows = []
with ThreadPoolExecutor(max_workers=8) as ex:
    for i, res in enumerate(ex.map(one, stations)):
        rows.extend(res)
        if (i + 1) % 100 == 0:
            print(f"  {i+1}/{len(stations)} stations, {len(rows)} rows", flush=True)

with open(f"{OUT}/smhi_monthly.csv", "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["station_id", "ym", "temp_c"])
    w.writerows(rows)
print(f"DONE rows={len(rows)}", flush=True)
