"""Fetch SMHI monthly precipitation (param 23) and daily snow depth (param 8).

Snow depth is daily over ~900 stations, so it is aggregated to one row per
station per *snow year* (Jul-Jun, so a winter is not split across two rows)
inside this script rather than written out raw.

Writes data/raw/smhi_precip_monthly.csv and data/raw/smhi_snow_season.csv.
"""
import csv, io, sys, time, json, urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

BASE = "https://opendata-download-metobs.smhi.se/api/version/1.0/parameter"
OUT = "data/raw"


def get(url, tries=4):
    for a in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "skogsalder/1.0"})
            return urllib.request.urlopen(req, timeout=120).read()
        except Exception:
            if a == tries - 1:
                return None
            time.sleep(2 * (a + 1))
    return None


def rows_for(param, station_id):
    """Yield (date, value) from a station's corrected archive."""
    raw = get(f"{BASE}/{param}/station/{station_id}/period/corrected-archive/data.csv")
    if not raw:
        return
    lines = raw.decode("utf-8-sig", errors="replace").splitlines()
    start = None
    for i, ln in enumerate(lines):
        f0 = ln.split(";")[0].strip().strip('"')
        if f0 in ("Datum", "Från Datum Tid (UTC)", "Från Datum"):
            start = i
            break
    if start is None:
        return
    rdr = csv.reader(io.StringIO("\n".join(lines[start:])), delimiter=";")
    hdr = next(rdr, None)
    if not hdr:
        return
    cols = [c.strip() for c in hdr]
    try:
        di = cols.index("Datum")
    except ValueError:
        di = 1 if len(cols) > 1 else 0
    vi = None
    for cand in ("Nederbördsmängd", "Snödjup", "Lufttemperatur"):
        if cand in cols:
            vi = cols.index(cand)
            break
    if vi is None:
        vi = di + 1
    for r in rdr:
        if len(r) <= max(di, vi):
            continue
        d, v = r[di].strip(), r[vi].strip().replace(",", ".")
        if len(d) < 7 or not v:
            continue
        try:
            yield d, float(v)
        except ValueError:
            continue


def stations_for(param):
    return json.loads(get(f"{BASE}/{param}.json"))["station"]


# ---- precipitation: monthly sums, same shape as the temperature series ----
def precip():
    sts = stations_for(23)
    print(f"precip stations: {len(sts)}", flush=True)

    def one(s):
        return [(s["id"], d[:7], v) for d, v in rows_for(23, s["id"])]

    out = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, res in enumerate(ex.map(one, sts)):
            out.extend(res)
            if (i + 1) % 150 == 0:
                print(f"  precip {i+1}/{len(sts)} -> {len(out)} rows", flush=True)
    with open(f"{OUT}/smhi_precip_monthly.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["station_id", "ym", "precip_mm"])
        w.writerows(out)
    print(f"PRECIP DONE rows={len(out)}", flush=True)


# ---- snow: daily depth -> per station per snow year ----
def snow():
    sts = stations_for(8)
    print(f"snow stations: {len(sts)}", flush=True)

    def one(s):
        acc = defaultdict(lambda: [0, 0, 0.0])  # snowyear -> [obs, days_cover, maxdepth]
        for d, v in rows_for(8, s["id"]):
            try:
                y, m = int(d[0:4]), int(d[5:7])
            except ValueError:
                continue
            sy = y if m >= 7 else y - 1     # Jul-Jun snow year, labelled by its Jul
            a = acc[sy]
            a[0] += 1
            if v >= 0.01:                   # SMHI reports snow depth in metres
                a[1] += 1
            if v > a[2]:
                a[2] = v
        return [(s["id"], sy, a[0], a[1], round(a[2], 3)) for sy, a in acc.items()]

    out = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, res in enumerate(ex.map(one, sts)):
            out.extend(res)
            if (i + 1) % 150 == 0:
                print(f"  snow {i+1}/{len(sts)} -> {len(out)} rows", flush=True)
    with open(f"{OUT}/smhi_snow_season.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["station_id", "snow_year", "n_obs", "cover_days", "max_depth_m"])
        w.writerows(out)
    print(f"SNOW DONE rows={len(out)}", flush=True)


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "both"
    if which in ("both", "precip"):
        precip()
    if which in ("both", "snow"):
        snow()
