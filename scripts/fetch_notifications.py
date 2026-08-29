"""Download Skogsstyrelsen's felling notifications (avverkningsanmalan).

Open INSPIRE data, no key. One GeoPackage of polygons, each with the date the
notification came in - which is what makes an event study possible: the series
records intent six weeks before anything is cut, and the geometry says where.

The feed is a rolling window; today it reaches back to autumn 2021.
"""
import io, os, urllib.request, zipfile

URL = ("https://geodpags.skogsstyrelsen.se/geodataport/data/sksAvverkAnm_gpkg.zip")
OUT = "data/geo"
DEST = f"{OUT}/sksAvverkAnm.gpkg"

if __name__ == "__main__":
    if os.path.exists(DEST):
        print(f"  {DEST}: already present, skipping")
    else:
        os.makedirs(OUT, exist_ok=True)
        print("  downloading felling notifications (~63 MB) ...", flush=True)
        req = urllib.request.Request(URL, headers={"User-Agent": "skogsalder/1.0"})
        raw = urllib.request.urlopen(req, timeout=900).read()
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            z.extractall(OUT)
        print(f"    -> {DEST} ({os.path.getsize(DEST):,} bytes)")
