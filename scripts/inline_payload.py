"""Inline site/payload.json into the <script id="payload"> tag in site/index.html.

Keeping this a script rather than a manual step means the page can never serve
a stale payload after make_payload.py has run.
"""
import re, sys

PAGE = "site/index.html"
DATA = "site/payload.json"

page = open(PAGE).read()
payload = open(DATA).read().strip()

pat = re.compile(r'(<script id="payload" type="application/json">).*?(</script>)', re.S)
if not pat.search(page):
    sys.exit(f"{PAGE}: no <script id=\"payload\"> tag found")

new = pat.sub(lambda m: m.group(1) + payload + m.group(2), page, count=1)
if new == page:
    print("payload unchanged")
else:
    open(PAGE, "w").write(new)
    print(f"inlined {len(payload)} bytes of payload into {PAGE}")
