"""Assemble dist/ for GitHub Pages.

The deployed app is the Scala.js one: site/shell.html is already a complete
document, so this step copies it in as index.html together with the compiled
bundle, the DuckDB-WASM loader and the parquet data.

The Scala.js bundle must be built first:

    cd frontend && scala-cli --power package . -o ../site/js/app.js --js -f --js-mode release
"""
import os, shutil, sys

OUT = "dist"
REQUIRED = [
    ("site/shell.html", "index.html"),
    ("site/js/app.js", "js/app.js"),
    ("site/js/duckdb-loader.js", "js/duckdb-loader.js"),
]

missing = [src for src, _ in REQUIRED if not os.path.exists(src)]
if missing:
    sys.exit("missing build inputs: " + ", ".join(missing) +
             "\nBuild the Scala.js bundle first (see this file's docstring).")

if os.path.isdir(OUT):
    shutil.rmtree(OUT)
os.makedirs(OUT, exist_ok=True)

for src, dest in REQUIRED:
    target = os.path.join(OUT, dest)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    shutil.copy2(src, target)
    print(f"  {src} -> {target} ({os.path.getsize(target):,} bytes)")

shutil.copytree("site/data", os.path.join(OUT, "data"))
n = len(os.listdir(os.path.join(OUT, "data")))
print(f"  site/data -> {OUT}/data ({n} files)")

# Pages would otherwise run the output through Jekyll
open(os.path.join(OUT, ".nojekyll"), "w").close()

total = sum(
    os.path.getsize(os.path.join(root, f))
    for root, _, files in os.walk(OUT) for f in files
)
print(f"dist total: {total:,} bytes")
