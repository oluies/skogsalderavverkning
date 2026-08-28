"""Bundle the exported tables into one compact JS payload for the page."""
import json

def rd(p): return json.load(open(f"site/{p}"))

counties = [{"n": r["slu_name"], "l": r["landsdel"], "g": r["gj"] if isinstance(r["gj"], dict) else json.loads(r["gj"])}
            for r in rd("geo_counties.json")]

age = {}
for r in rd("felling_age.json"):
    age.setdefault(r["lsa_basis"], {}).setdefault(r["region"], {})[r["year"]] = r["age_years"]

site = {}
for r in rd("site_index.json"):
    site.setdefault(r["area"], {})[r["year"]] = r["medelbonitet"]

clim_c, clim_r = {}, {}
for r in rd("climate_county.json"):
    clim_c.setdefault(r["area"], {})[r["year"]] = [r["anom_annual"], r["anom_growing"], r["n_stations"]]
for r in rd("climate_region.json"):
    clim_r.setdefault(r["region"], {})[r["year"]] = [r["anom_annual"], r["anom_growing"]]

# County-level synthesis: bonitet change vs warming, for the scatter
def mean(d, lo, hi):
    v = [x for y, x in d.items() if lo <= int(y) <= hi and x is not None]
    return sum(v) / len(v) if v else None

scatter = []
for c in counties:
    n = c["n"]
    s, cl = site.get(n, {}), clim_c.get(n, {})
    b0 = mean(s, 1985, 1994)
    b1 = mean(s, 2014, 2023)
    w = mean({y: v[0] for y, v in cl.items()}, 2011, 2024)
    if b0 and b1 and w is not None:
        scatter.append({"n": n, "l": c["l"], "b0": round(b0, 2),
                        "b1": round(b1, 2), "d": round(b1 - b0, 3),
                        "pct": round(100 * (b1 / b0 - 1), 2), "w": round(w, 2)})

stations = len(rd("stations.json"))
payload = {"counties": counties, "age": age, "site": site,
           "climC": clim_c, "climR": clim_r, "scatter": scatter,
           "nStations": stations}
with open("site/payload.json", "w") as fh:
    json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
print("payload written; scatter rows:", len(scatter), "stations:", stations)
