"""Wrap site/index.html into a standalone HTML document in dist/.

site/index.html is authored as an Artifact *fragment*: the Artifact host supplies
the <!doctype>, <html>, <head> and <body> at publish time, so the file starts
straight at <title>. Served raw by GitHub Pages that would render in quirks
mode, so this step adds the document shell Pages needs, without touching the
source the Artifact publish path depends on.
"""
import os, re, shutil

SRC = "site/index.html"
OUT = "dist"

DESCRIPTION = ("How Sweden's average age at final felling has changed by region, "
               "against site productivity, storms, tree species and climate. "
               "Built from SLU Riksskogstaxeringen and SMHI open data.")

fragment = open(SRC, encoding="utf-8").read()

m = re.search(r"<title>(.*?)</title>", fragment, re.S)
title = m.group(1).strip() if m else "Sweden's Felling Age"
# the shell carries the title; drop the fragment's own tag so it appears once
fragment = re.sub(r"<title>.*?</title>\s*", "", fragment, count=1, flags=re.S)

# The fragment opens with the font <link>s and the <style> block. Those belong
# in <head>: left in <body> they still apply, but only after the parser has
# already painted, which shows as a flash of unstyled content.
head_extra = ""
while True:
    m = re.match(r"\s*(<link\b[^>]*>|<style\b.*?</style>)\s*", fragment, re.S)
    if not m:
        break
    head_extra += m.group(1) + "\n"
    fragment = fragment[m.end():]

doc = f"""<!doctype html>
<html lang="sv">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="{DESCRIPTION}">
<meta name="color-scheme" content="light dark">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{DESCRIPTION}">
<meta property="og:type" content="website">
<title>{title}</title>
{head_extra}</head>
<body>
{fragment}
</body>
</html>
"""

os.makedirs(OUT, exist_ok=True)
with open(f"{OUT}/index.html", "w", encoding="utf-8") as fh:
    fh.write(doc)
# Pages would otherwise run the output through Jekyll
open(f"{OUT}/.nojekyll", "w").close()
print(f"wrote {OUT}/index.html ({len(doc)} bytes), title={title!r}")
