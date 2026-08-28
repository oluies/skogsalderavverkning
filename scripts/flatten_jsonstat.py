"""Flatten JSON-stat2 files from SLU Skogsstatistik into tidy CSVs.

JSON-stat2 stores one flat `value` array in row-major order over the
dimensions listed in `id`, with sizes in `size`. We expand that back into
one row per cell, with a column per dimension label.
"""
import csv, json, sys
from itertools import product

def flatten(path, out):
    with open(path, encoding="utf-8") as fh:
        d = json.load(fh)
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

    n = 1
    for sz in sizes:
        n *= sz

    # The expansion below pairs cell i with the i-th tuple of product(*labels).
    # If a dimension's label list is short (a partial category.label, or a
    # missing one falling back to codes), every subsequent row is paired with
    # the wrong labels - silently, and the damage only shows up much later as an
    # empty filter result in build.sql. So assert the contract instead.
    got = [len(l) for l in labels]
    if got != list(sizes):
        raise ValueError(f"{path}: label counts {got} != declared sizes {list(sizes)}; "
                         f"dimensions {dims}")

    val = d["value"]
    # JSON-stat allows the value collection to be a sparse dict keyed by index
    if isinstance(val, dict):
        val = [val.get(str(i)) for i in range(n)]
    elif len(val) != n:
        raise ValueError(f"{path}: value array has {len(val)} cells, expected {n}")

    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(dims + ["value"])
        for i, combo in enumerate(product(*labels)):
            v = val[i] if i < len(val) else None
            w.writerow(list(combo) + ["" if v is None else v])
    print(f"{path} -> {out}  dims={dims} sizes={sizes} cells={len(val)}")

NAMES = ["fig49", "tab311a", "avv_tradslag_landsdel", "avv_huggningsarter",
         "naturlig_avgang", "skadetyper", "bestandstyper"]

if __name__ == "__main__":
    import os
    for n in NAMES:
        src = f"data/raw/{n}.json"
        if os.path.exists(src):
            flatten(src, f"data/raw/{n}.csv")
