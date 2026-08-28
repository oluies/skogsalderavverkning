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

# Keys the app asks for must exist. Most are NOT written as t("key") - they are
# passed as parameters to section(), segmented(), weightSlider() and the source
# list, which is roughly half of them. Scanning only t("...") let a deleted key
# pass CI and render as the literal key text in an <h2>.
app_src = "".join(
    open(os.path.join("frontend/src", f), encoding="utf-8").read()
    for f in os.listdir("frontend/src") if f.endswith(".scala") and f != "I18n.scala"
)

# Two directions, two scans - they cannot share one set.
#
# For "is every referenced key defined" the candidates must NOT be filtered by
# the key set first: intersecting before comparing makes the answer empty by
# construction, which is exactly how an earlier version of this check silently
# stopped working. So extract from the shapes that actually reach the lookup
# and compare those against the tables.
strict = set()
strict |= set(re.findall(r'\bt(?:Now)?\(\s*"([A-Za-z0-9_]+)"\s*\)', app_src))
strict |= set(re.findall(r'\bI18n\.get\([^,]+,\s*"([A-Za-z0-9_]+)"\s*\)', app_src))
# section("head", "intro", ...) and weightSlider("key", "labelKey")
for args in re.findall(r'\bsection\(\s*"([A-Za-z0-9_]+)"\s*,\s*"([A-Za-z0-9_]+)"', app_src):
    strict |= set(args)
strict |= set(re.findall(r'\bweightSlider\(\s*"[A-Za-z0-9_]+"\s*,\s*"([A-Za-z0-9_]+)"', app_src))
# value -> labelKey pairs in segmented(...) option vectors
for block in re.findall(r'\bsegmented\((.*?)\)\)', app_src, re.S):
    strict |= set(re.findall(r'->\s*"([A-Za-z0-9_]+)"', block))
# the source list, Vector("srcAge", "srcBon", ...).map { k => t(k) }
for vec in re.findall(r'Vector\(((?:\s*"src[A-Za-z0-9_]+"\s*,?)+)\)', app_src):
    strict |= set(re.findall(r'"([A-Za-z0-9_]+)"', vec))
# arms of a `match` feeding tNow, and (key, ...) tuples feeding tNow
strict |= set(re.findall(r'=>\s*"((?:cap|ax|unit|m|w|c|sp|dist|tip|s\d)[A-Za-z0-9_]*)"', app_src))
strict |= set(re.findall(r'\(\s*"((?:cap|ax|unit)[A-Za-z0-9_]+)"\s*,', app_src))
# arms of an if-expression feeding tNow: tNow(if x then "capStand" else "capFell")
strict |= set(re.findall(r'(?:then|else)\s*"((?:cap|ax|unit|m|w|c|sp|dist|tip|s\d)[A-Za-z0-9_]*)"', app_src))

# Keys built by concatenation never appear as a literal at all.
synthesised = set()
for v in set(re.findall(r'case "(bonitet|bchange|warming|precip|snow|contorta|age)"', app_src)) | \
         set(re.findall(r'"(bonitet|bchange|warming|precip|snow|contorta|age)"\s*->', app_src)):
    synthesised |= {"m" + v.capitalize(),
                    "cap" + v.capitalize() + ("M" if v in ("precip", "snow") else "")}
strict |= synthesised

unknown = sorted(strict - sv)
check("all referenced string keys defined", not unknown, f"undefined: {unknown}")

# For the reverse direction a broad scan is right: a key used through any shape
# at all counts as used, and a false "unused" report would be the annoying one.
broad = (set(re.findall(r'"([A-Za-z0-9_]+)"', app_src)) & (sv | en)) | synthesised
unused = sorted(sv - broad)
check("no unused string keys", not unused, f"unused: {unused}")

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
# Without this the check disables itself silently: an unparsed TABLES yields an
# empty list, no missing files, and a green "0 tables".
check("TABLES parsed from the loader", bool(tables), f"{len(tables)} names")
missing = [t for t in tables if not os.path.exists(f"site/data/{t}.parquet")]
check("parquet files present for every registered table", not missing,
      f"missing: {missing}" if missing else f"{len(tables)} tables")
check("county geometry present", os.path.exists("site/data/counties.json"))

print()
if failures:
    sys.exit(f"{len(failures)} check(s) failed: {', '.join(failures)}")
print("all checks passed")
