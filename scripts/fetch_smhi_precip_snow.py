"""Fetch SMHI monthly precipitation (parameter 23) and daily snow depth (param 8).

Also writes the union station list that build.sql spatially joins against: the
three parameters have different station networks, so neither fetcher's own list
covers the others.

Snow depth is daily over ~1900 stations, so it is aggregated per station and
month here rather than written out raw. Per month rather than per season,
because "days with snow cover" over a whole season is biased by how long each
station reports: one reporting Oct-Apr and one reporting Nov-Mar are not
comparable. Keeping months lets the build pin a fixed December-March window.

Exits non-zero if any station download failed.
"""
import csv, sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "scripts")
import smhi

OUT = "data/raw"
failures = []


def stations_union():
    """Union of the station lists for the parameters this project reads.

    build.sql joins every station to a county through this one file. Each
    parameter has its own network - temperature 975 stations, precipitation
    2169, snow 1897 - so a per-parameter list would silently drop the others.
    """
    rows = {}
    for param in (22, 23, 8):
        found = 0
        for s in smhi.stations(param):
            if s.get("latitude") is None or s.get("longitude") is None:
                continue
            rows.setdefault(s["id"], [s["id"], s["name"], s["latitude"],
                                      s["longitude"], s.get("height")])
            found += 1
        print(f"  param {param}: {found} stations with coordinates", flush=True)
    path = f"{OUT}/smhi_stations_all.csv"
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["station_id", "name", "lat", "lon", "height"])
        w.writerows(rows.values())
    print(f"STATIONS DONE union={len(rows)} -> {path}", flush=True)


def _rows(param, station_id):
    try:
        return list(smhi.observations(param, station_id))
    except smhi.FetchError as e:
        failures.append(str(e))
        return None


def precip():
    sts = smhi.stations(23)
    print(f"precip stations: {len(sts)}", flush=True)

    def one(s):
        rows = _rows(23, s["id"])
        return [] if rows is None else [(s["id"], d[:7], v) for d, v in rows]

    out = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, res in enumerate(ex.map(one, sts)):
            out.extend(res)
            if (i + 1) % 200 == 0:
                print(f"  precip {i+1}/{len(sts)} -> {len(out)} rows", flush=True)
    with open(f"{OUT}/smhi_precip_monthly.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["station_id", "ym", "precip_mm"])
        w.writerows(out)
    print(f"PRECIP DONE rows={len(out)}", flush=True)


def snow():
    sts = smhi.stations(8)
    print(f"snow stations: {len(sts)}", flush=True)

    def one(s):
        rows = _rows(8, s["id"])
        if rows is None:
            return []
        acc = defaultdict(lambda: [0, 0, 0.0])   # (year, month) -> [obs, snowdays, max]
        for d, v in rows:
            try:
                y, m = int(d[0:4]), int(d[5:7])
            except ValueError:
                continue
            a = acc[(y, m)]
            a[0] += 1
            if v >= 0.01:                        # SMHI reports snow depth in metres
                a[1] += 1
            if v > a[2]:
                a[2] = v
        return [(s["id"], y, m, a[0], a[1], round(a[2], 3)) for (y, m), a in acc.items()]

    out = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, res in enumerate(ex.map(one, sts)):
            out.extend(res)
            if (i + 1) % 200 == 0:
                print(f"  snow {i+1}/{len(sts)} -> {len(out)} rows", flush=True)
    with open(f"{OUT}/smhi_snow_monthly.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["station_id", "year", "month", "n_obs", "snow_days", "max_depth_m"])
        w.writerows(out)
    print(f"SNOW DONE rows={len(out)}", flush=True)


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "both"
    if which in ("both", "stations"):
        stations_union()
    if which in ("both", "precip"):
        precip()
    if which in ("both", "snow"):
        snow()
    print(f"failures={len(failures)}")
    if failures:
        for f in failures[:10]:
            print("  FAILED:", f, file=sys.stderr)
        sys.exit(1)
