"""Fetch the SLU Skogsstatistik tables this project uses, plus boundaries.

The PxWeb API root is https://skogsstatistik.slu.se/api/v1/sv/OffStat — note
it is NOT under /pxweb/, which the browser URL suggests but which 404s. The
server rate-limits aggressively, so every call backs off and retries.
"""
import json, os, sys, time, urllib.request, urllib.error, zipfile, io

BASE = "https://skogsstatistik.slu.se/api/v1/sv/OffStat"
OUT = "data/raw"
GEO = "data/geo"

TABLES = {
    "fig49":                 "/Avverkning/AVV_alder_slutavverkning_fig.px",
    "tab311a":               "/ProduktivSkogsmark/StandortVegetation/PS_Areal_boniteter_agargrupper_tab.px",
    "avv_tradslag_landsdel": "/Avverkning/AVV_arlig_avverkning_landsdelar_tab.px",
    "avv_huggningsarter":    "/Avverkning/AVV_arlig_avverkning_huggningsarter_landsdelar_agargrupper_tab.px",
    "naturlig_avgang":       "/ProduktivSkogsmark/Skogsskador/PS_Skador_Naturlig_avgang_tab.px",
    "skadetyper":            "/ProduktivSkogsmark/Skogsskador/PS_skador_areal_med_skador_tab.px",
    "bestandstyper":         "/ProduktivSkogsmark/Areal/PS_Areal_bestandstyper_tab_a.px",
}

NE_URL = ("https://naciscdn.org/naturalearth/10m/cultural/"
          "ne_10m_admin_1_states_provinces.zip")


def post(path, body, tries=8):
    url = BASE + path
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(
                url, data=json.dumps(body).encode(),
                headers={"Content-Type": "application/json"})
            d = json.loads(urllib.request.urlopen(req, timeout=180).read())
            if isinstance(d, dict) and "error" in d:
                raise RuntimeError(d["error"])
            return d
        except Exception as exc:
            last = exc
            time.sleep(8)
    raise RuntimeError(f"{path}: {last}")


def fetch_tables():
    os.makedirs(OUT, exist_ok=True)
    for name, path in TABLES.items():
        dest = f"{OUT}/{name}.json"
        if os.path.exists(dest):
            print(f"  {name}: already present, skipping")
            continue
        print(f"  fetching {name} ...", flush=True)
        d = post(path, {"query": [], "response": {"format": "json-stat2"}})
        with open(dest, "w") as fh:
            json.dump(d, fh, ensure_ascii=False)
        print(f"    -> {dest} ({len(d.get('value', []))} cells)")
        time.sleep(6)


def fetch_boundaries():
    shp = f"{GEO}/ne10/ne_10m_admin_1_states_provinces.shp"
    if os.path.exists(shp):
        print("  boundaries: already present, skipping")
        return
    os.makedirs(f"{GEO}/ne10", exist_ok=True)
    print("  downloading Natural Earth admin-1 ...", flush=True)
    raw = urllib.request.urlopen(NE_URL, timeout=300).read()
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        z.extractall(f"{GEO}/ne10")
    print(f"    -> {shp}")


if __name__ == "__main__":
    print("SLU tables:")
    fetch_tables()
    print("Boundaries:")
    fetch_boundaries()
    print("done")
