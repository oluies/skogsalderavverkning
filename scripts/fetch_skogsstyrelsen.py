"""Fetch roundwood prices from Skogsstyrelsen's statistics database.

SLU inventories the forest; it does not publish what the wood sells for. Prices
per assortment (which is per species for sawlogs and pulpwood) and per landsdel
come from Skogsstyrelsen, and they are the missing half of any argument about
why stands are felled when they are.

Their PxWeb rejects the default urllib user agent, so send a browser one.
"""
import json, os, time, urllib.request

BASE = ("https://pxweb.skogsstyrelsen.se/api/v1/sv/"
        "Skogsstyrelsens%20statistikdatabas")
OUT = "data/raw"
UA = {"User-Agent": ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"),
      "Content-Type": "application/json",
      "Accept": "application/json"}

TABLES = {
    # regional prices per assortment: the long series, and the current one that
    # continues it after the table was restructured in 2019
    "prices_region_1995_2021": "Rundvirkespriser/"
        "%C3%84ldre%20tabeller%20som%20inte%20uppdateras/JO0303_1.px",
    "prices_region_2019_2025": "Rundvirkespriser/JO0303_1ny.px",
    # national, inflation-adjusted to 2022 money, back to 1967/68
    "prices_real_1967_2022": "Ekonomi/4_%20Prisutveckling%20pa%20leveransvirke.px",
}


def fetch(path, body=None, tries=6):
    url = f"{BASE}/{path}"
    last = None
    for _ in range(tries):
        try:
            data = json.dumps(body).encode() if body else None
            req = urllib.request.Request(url, data=data, headers=UA)
            return json.loads(urllib.request.urlopen(req, timeout=180).read())
        except Exception as exc:
            last = exc
            time.sleep(6)
    raise RuntimeError(f"{path}: {last}")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, path in TABLES.items():
        dest = f"{OUT}/{name}.json"
        if os.path.exists(dest):
            print(f"  {name}: already present, skipping")
            continue
        meta = fetch(path)
        # select everything: these tables are small
        query = [{"code": v["code"],
                  "selection": {"filter": "item", "values": v["values"]}}
                 for v in meta["variables"]]
        d = fetch(path, {"query": query, "response": {"format": "json-stat2"}})
        json.dump(d, open(dest, "w"), ensure_ascii=False)
        print(f"  {name} -> {dest} ({len(d.get('value', []))} cells)")
        time.sleep(3)
    print("done")
