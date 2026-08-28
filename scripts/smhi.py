"""Shared client for the SMHI open data metobs API.

Both fetchers use the same retry policy and the same CSV shape, so the parsing
quirks (metadata preamble, header detection, comma decimals) live here once.
"""
import csv, io, json, time, urllib.request

BASE = "https://opendata-download-metobs.smhi.se/api/version/1.0/parameter"

# The header row SMHI emits varies by parameter and vintage.
_HEADER_FIRST_FIELDS = ("Datum", "Från Datum Tid (UTC)", "Från Datum")
_VALUE_COLUMNS = ("Lufttemperatur", "Nederbördsmängd", "Snödjup")


class FetchError(RuntimeError):
    pass


def get(url, tries=4, timeout=120):
    """GET bytes, retrying with backoff. Returns None only after exhausting tries."""
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "skogsalder/1.0"})
            return urllib.request.urlopen(req, timeout=timeout).read()
        except Exception as exc:
            last = exc
            if attempt < tries - 1:
                time.sleep(2 * (attempt + 1))
    return None


def stations(param):
    """Station list for a parameter. Raises rather than returning None."""
    raw = get(f"{BASE}/{param}.json")
    if raw is None:
        raise FetchError(f"could not fetch station list for parameter {param}")
    return json.loads(raw)["station"]


def observations(param, station_id):
    """Yield (date, value) for a station's corrected archive.

    Raises FetchError when the download itself failed, so callers can tell a
    failed fetch apart from a station that genuinely has no observations.
    """
    raw = get(f"{BASE}/{param}/station/{station_id}/period/corrected-archive/data.csv")
    if raw is None:
        raise FetchError(f"param {param} station {station_id}: download failed")
    lines = raw.decode("utf-8-sig", errors="replace").splitlines()
    start = None
    for i, ln in enumerate(lines):
        if ln.split(";")[0].strip().strip('"') in _HEADER_FIRST_FIELDS:
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
    vi = next((cols.index(c) for c in _VALUE_COLUMNS if c in cols), di + 1)
    for row in rdr:
        if len(row) <= max(di, vi):
            continue
        d, v = row[di].strip(), row[vi].strip().replace(",", ".")
        if len(d) < 7 or not v:
            continue
        try:
            yield d, float(v)
        except ValueError:
            continue
