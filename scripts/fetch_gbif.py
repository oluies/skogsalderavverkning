"""Fetch Swedish orchid and control-taxon observations from GBIF.

Artportalen reaches GBIF without an API key. Two taxa are pulled: Orchidaceae,
which can stop a felling through species protection, and Rosaceae as the
control - a comparable plant family with a similar recorder community and
similar seasonality but no legal effect on felling.

Observer names: GBIF exposes `recordedBy`, the name the observer published with
their record. It is kept verbatim here, together with a link to the GBIF
occurrence, so any row can be traced back to the public source and checked.
data/raw/ is gitignored, so this file stays local.

What does NOT follow from having the names is a published ranking of individuals
by how often they report near a felling notice. The aggregate question - whether
a few recorders account for most of the observations, and how widely they range
- is what decides whether an event-study result describes a population or a
handful of people, and that is answerable without naming anyone. Aggregates go
to the site; the names stay here, and the links let anyone resolve a record.

GBIF refuses offset > 100001, so the query is partitioned by year and month;
every partition lands far below the cap.
"""
import csv, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

API = "https://api.gbif.org/v1/occurrence/search"
OUT = "data/raw"
TAXA = {"orchid": 7689, "control": 5015}      # Orchidaceae, Rosaceae
YEARS = range(2021, 2027)
PAGE = 300

def get(params, tries=5):
    url = API + "?" + urllib.parse.urlencode(params)
    last = None
    for _ in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "skogsalder/1.0"})
            return json.loads(urllib.request.urlopen(req, timeout=120).read())
        except Exception as exc:
            last = exc
            time.sleep(3)
    raise RuntimeError(f"{params}: {last}")


def rows_for(taxon_key, year, month):
    off, out = 0, []
    while True:
        r = get({"country": "SE", "familyKey": taxon_key, "hasCoordinate": "true",
                 "year": year, "month": month, "limit": PAGE, "offset": off})
        for o in r.get("results", []):
            if o.get("decimalLatitude") is None or not o.get("eventDate"):
                continue
            out.append((
                o.get("gbifID"), o.get("family"), o.get("species") or "",
                o["eventDate"][:10],
                o["decimalLatitude"], o["decimalLongitude"],
                o.get("coordinateUncertaintyInMeters") or "",
                (o.get("recordedBy") or "").strip(),
                f"https://www.gbif.org/occurrence/{o.get('gbifID')}",
                o.get("datasetKey") or "",
            ))
        if r.get("endOfRecords"):
            return out
        off += PAGE
        if off + PAGE > 100000:
            # Silently returning here would make a truncated partition
            # indistinguishable from a complete one.
            raise RuntimeError(
                f"partition {year}-{month:02d} exceeds GBIF's offset cap "
                f"({r.get('count')} records); split it further")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for label, key in TAXA.items():
        dest = f"{OUT}/gbif_{label}.csv"
        if os.path.exists(dest):
            print(f"  {label}: already present, skipping")
            continue
        # One partition per (year, month): far below GBIF's offset cap, and
        # independent, so they can run concurrently. Serially this took hours.
        parts = [(y, m) for y in YEARS for m in range(1, 13)]
        # Write to a temporary file and rename only on success, so a failed
        # run cannot leave a partial CSV that the guard above then skips.
        tmp = dest + ".part"
        total = 0
        with open(tmp, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["gbif_id", "family", "species", "event_date",
                        "lat", "lon", "uncertainty_m", "recorder", "gbif_url",
                        "dataset"])
            with ThreadPoolExecutor(max_workers=16) as ex:
                for i, rows in enumerate(ex.map(lambda ym: rows_for(key, *ym), parts)):
                    w.writerows(rows)
                    total += len(rows)
                    if (i + 1) % 6 == 0:
                        print(f"  {label}: {i+1}/{len(parts)} partitions, "
                              f"{total} rows", flush=True)
        os.replace(tmp, dest)
        print(f"{label.upper()} DONE {total} rows -> {dest}", flush=True)
