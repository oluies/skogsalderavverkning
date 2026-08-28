"""Pre-deploy checks for the published page.

Guards the three ways this page has actually broken:
  1. site/payload.json drifting from the copy inlined into site/index.html
  2. a translation key present in one language but not the other
  3. the page script failing to parse
Exits non-zero with a description of the first failure in each category.
"""
import json, re, shutil, subprocess, sys

PAGE = "site/index.html"
DATA = "site/payload.json"
page = open(PAGE, encoding="utf-8").read()
failures = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  — ' + detail) if detail else ''}")
    if not ok:
        failures.append(name)


# 1. inlined payload must match payload.json
m = re.search(r'<script id="payload" type="application/json">(.*?)</script>', page, re.S)
if not m:
    check("payload tag present", False, "no <script id=\"payload\"> in the page")
else:
    inlined = m.group(1).strip()
    onfile = open(DATA, encoding="utf-8").read().strip()
    same = json.loads(inlined) == json.loads(onfile)
    check("payload in sync with payload.json", same,
          "" if same else "run scripts/inline_payload.py")

# 2. the two string tables must define exactly the same keys
blk = page[page.index("const STR = {"):]


def keys_for(tag):
    i = blk.index(tag + ":{")
    j = i + len(tag) + 1
    depth = 0
    for k in range(j, len(blk)):
        if blk[k] == "{":
            depth += 1
        elif blk[k] == "}":
            depth -= 1
            if depth == 0:
                return set(re.findall(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:", blk[j:k], re.M))
    raise ValueError(f"unterminated {tag} block")


sv, en = keys_for("sv"), keys_for("en")
check("sv/en translation keys match", sv == en,
      "" if sv == en else f"sv-only={sorted(sv-en)} en-only={sorted(en-sv)}")

# 3. the page script must parse
scripts = re.findall(r"<script>\n(.*?)</script>", page, re.S)
if not scripts:
    check("page script found", False)
elif not shutil.which("node"):
    print("SKIP  page script parses — node not available")
else:
    open("/tmp/_verify.js", "w").write(scripts[-1])
    r = subprocess.run(["node", "--check", "/tmp/_verify.js"],
                       capture_output=True, text=True)
    check("page script parses", r.returncode == 0, r.stderr.strip().split("\n")[0]
          if r.returncode else "")

print()
if failures:
    sys.exit(f"{len(failures)} check(s) failed: {', '.join(failures)}")
print("all checks passed")
