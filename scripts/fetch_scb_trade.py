"""Fetch Swedish foreign trade in wood from SCB, by commodity and partner.

Answers what SLU cannot: how much wood Sweden imports and exports, and from
whom. Russia matters here because roundwood imports from Russia were
substantial until the EU sanctioned them in 2022 (and the trend turned earlier,
after 2014); losing that supply is one of the pressures on domestic felling.

SCB's PxWeb takes a POST with the selection and returns JSON-stat2.
"""
import json, os, time, urllib.request

BASE = "https://api.scb.se/OV0104/v1/doris/sv/ssd/HA/HA0201/HA0201B"
OUT = "data/raw"

# KN groups worth separating: roundwood is the raw material that competes with
# domestic felling; sawn goods and pulp are what Sweden sells.
GOODS = {
    "44":   "Trä och varor av trä (totalt)",
    "4401": "Brännved, flis och spån",
    "4403": "Rundvirke (obearbetat)",
    "4407": "Sågade trävaror",
    "47":   "Massa av trä",
    "48":   "Papper och papp",
}
PARTNERS = ["TOT", "RU", "FI", "EE", "LV", "LT", "NO", "DE", "BY"]
YEARS = [str(y) for y in range(1995, 2026)]


def post(table, body, tries=6):
    url = f"{BASE}/{table}"
    last = None
    for _ in range(tries):
        try:
            req = urllib.request.Request(
                url, data=json.dumps(body).encode(),
                headers={"Content-Type": "application/json"})
            return json.loads(urllib.request.urlopen(req, timeout=180).read())
        except Exception as exc:
            last = exc
            time.sleep(8)
    raise RuntimeError(f"{table}: {last}")


def query(contents):
    return {
        "query": [
            {"code": "VarugruppKN", "selection":
                {"filter": "item", "values": list(GOODS)}},
            {"code": "Handelspartner", "selection":
                {"filter": "item", "values": PARTNERS}},
            {"code": "ContentsCode", "selection":
                {"filter": "item", "values": [contents]}},
            {"code": "Tid", "selection": {"filter": "item", "values": YEARS}},
        ],
        "response": {"format": "json-stat2"},
    }


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    # the value series (tkr): D8 for imports, C5 for exports
    for name, table, contents in [
        ("scb_import_wood", "ImpTotalKNAr", "HA0201D8"),
        ("scb_export_wood", "ExpTotalKNAr", "HA0201C5"),
    ]:
        dest = f"{OUT}/{name}.json"
        if os.path.exists(dest):
            print(f"  {name}: already present, skipping")
            continue
        print(f"  fetching {name} ...", flush=True)
        d = post(table, query(contents))
        with open(dest, "w") as fh:
            json.dump(d, fh, ensure_ascii=False)
        print(f"    -> {dest} ({len(d.get('value', []))} cells)")
        time.sleep(3)
    print("done")
