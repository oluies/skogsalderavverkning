"""Flatten JSON-stat2 files from SLU Skogsstatistik into tidy CSVs.

JSON-stat2 stores one flat `value` array in row-major order over the
dimensions listed in `id`, with sizes in `size`. We expand that back into
one row per cell, with a column per dimension label.
"""
import csv, json, sys
from itertools import product

def flatten(path, out):
    d = json.load(open(path))
    dims = d["id"]
    sizes = d["size"]
    labels = []
    for k in dims:
        cat = d["dimension"][k]["category"]
        idx = cat.get("index")
        lab = cat.get("label", {})
        if isinstance(idx, dict):
            keys = sorted(idx, key=idx.get)
        elif isinstance(idx, list):
            keys = idx
        else:
            keys = list(lab)
        labels.append([lab.get(kk, kk) for kk in keys])

    val = d["value"]
    # JSON-stat allows the value collection to be a sparse dict keyed by index
    if isinstance(val, dict):
        n = 1
        for s in sizes:
            n *= s
        val = [val.get(str(i)) for i in range(n)]

    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(dims + ["value"])
        for i, combo in enumerate(product(*labels)):
            v = val[i] if i < len(val) else None
            w.writerow(list(combo) + ["" if v is None else v])
    print(f"{path} -> {out}  dims={dims} sizes={sizes} cells={len(val)}")

if __name__ == "__main__":
    flatten("data/raw/fig49.json",   "data/raw/fig49.csv")
    flatten("data/raw/tab311a.json", "data/raw/tab311a.csv")
    flatten("data/raw/tab311b.json", "data/raw/tab311b.csv")
