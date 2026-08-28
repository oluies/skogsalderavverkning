"""Pre-deploy checks.

Guards the things that have actually gone wrong in this project:
  1. a translation key defined in one language but not the other
     (this shipped once as a literal "undefined" in a tooltip)
  2. a string key referenced by the app but defined in neither table
  3. build inputs missing, or a Scala.js bundle that never linked
  4. a parquet file the DuckDB loader expects but that was never exported
Exits non-zero listing every failure, not just the first.
"""
import os, re, sys

failures = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  — ' + detail) if detail else ''}")
    if not ok:
        failures.append(name)


i18n = open("frontend/src/I18n.scala", encoding="utf-8").read()


def keys_in(table):
    m = re.search(rf"val {table}: Map\[String, String\] = Map\((.*?)\n  \)", i18n, re.S)
    if not m:
        return set()
    return set(re.findall(r'"([A-Za-z0-9_]+)"\s*->', m.group(1)))


sv, en = keys_in("sv"), keys_in("en")
check("sv/en translation keys match", bool(sv) and sv == en,
      "" if sv == en else f"sv-only={sorted(sv - en)} en-only={sorted(en - sv)}")

# Keys the app asks for by literal name must exist somewhere.
app_src = "".join(
    open(os.path.join("frontend/src", f), encoding="utf-8").read()
    for f in os.listdir("frontend/src") if f.endswith(".scala") and f != "I18n.scala"
)
referenced = set(re.findall(r'\bt(?:Now)?\("([A-Za-z0-9_]+)"\)', app_src))
# keys built by concatenation (map metric captions) are checked separately
metrics = re.findall(r'"(bonitet|bchange|warming|precip|snow|contorta|age)"\s*->', app_src)
for v in set(metrics):
    referenced.add("m" + v.capitalize())
    referenced.add("cap" + v.capitalize() + ("M" if v in ("precip", "snow") else ""))
unknown = sorted(referenced - sv)
check("all referenced string keys defined", not unknown, f"undefined: {unknown}")

# Build inputs
for path in ("site/shell.html", "site/js/duckdb-loader.js"):
    check(f"{path} present", os.path.exists(path))

bundle = "site/js/app.js"
if not os.path.exists(bundle):
    check("Scala.js bundle built", False, "run scala-cli package first")
else:
    size = os.path.getsize(bundle)
    check("Scala.js bundle built", size > 50_000, f"{size:,} bytes")

# Every table the loader registers needs a parquet file on disk.
loader = open("site/js/duckdb-loader.js", encoding="utf-8").read()
m = re.search(r"const TABLES = \[(.*?)\];", loader, re.S)
tables = re.findall(r'"([a-z_]+)"', m.group(1)) if m else []
missing = [t for t in tables if not os.path.exists(f"site/data/{t}.parquet")]
check("parquet files present for every registered table", not missing,
      f"missing: {missing}" if missing else f"{len(tables)} tables")
check("county geometry present", os.path.exists("site/data/counties.json"))

print()
if failures:
    sys.exit(f"{len(failures)} check(s) failed: {', '.join(failures)}")
print("all checks passed")
