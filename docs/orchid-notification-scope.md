# Do orchid records cluster right after a felling notice?

**On terminology.** Three different populations get conflated in this debate and
this document keeps them apart. *Fridlysta* (protected) is the category with
legal effect on felling, and **all wild Swedish orchids are fridlysta** — that is
why they are the emblematic case. *Rödlistad* (red-listed) is a narrower
conservation-status category that overlaps it but is not the same set. What is
measured here is neither: it is **all of Orchidaceae**, because that is the
taxonomic unit GBIF filters on. Every figure below is Orchidaceae.

**Built.** Results are in the page's *Rapporteras arter efter avverkningsanmälan?*
section; this document records the design and what it can carry.

The control changed during the build. Rosaceae as a control *taxon* proved
impractical — GBIF rate-limits after a large pull, and the national fetch would
have taken hours — and a **spatial** control turned out to be better anyway: a
500 m ring around each notified polygon shares the neighbourhood, the recorders,
the season and the reporting trend, and needs no second download. It also tests
something sharper: whether reporting targets *the notified stand* or merely the
area around it.

## The question

A felling notification (*avverkningsanmälan*) is public and must be filed six
weeks before felling. The claim worth testing is that observations of protected
species — orchids being the emblematic case — cluster in the weeks *after* a
notification appears, on the notified ground.

## Is the data there? Yes, and it is open

**Treatment events — Skogsstyrelsen felling notifications.** INSPIRE Atom feed
→ `sksAvverkAnm_gpkg.zip`, 63 MB zipped / 158 MB GeoPackage, no key required.

| | |
|---|---|
| Records | 127,884 |
| Of which regeneration felling | 118,850 |
| Date range | 2021-08-29 → 2026-08-28 (rolling ~5-year window) |
| Event date | `Inkomdatum`, a full timestamp |
| Geometry | polygon, EPSG:3006 (SWEREF99 TM — already metric) |
| Also carries | county, municipality, `AnmaldHa`, `AvvHa`, `ArendeStatus`, `Avverktyp` |

**Outcome events — species observations via GBIF.** Artportalen is mirrored into
GBIF, which needs no API key.

| | |
|---|---|
| Orchidaceae, Sweden, georeferenced, 2021–2026 | 263,854 |
| Of which Artportalen | 228,991 |
| Coordinate uncertainty | median 10 m, p90 25 m, max 350 m (n=200 sample) |

The 10 m median matters: notified polygons average a few hectares, so the
coordinates are precise enough to place an observation inside or outside one.

But the number that actually decides feasibility is neither of the two totals —
it is how many observations fall **inside** a polygon at all, and that was not
checked at scope time. It should have been. Measured during the build: **8,384**
orchid records land inside a notified polygon within a year either side of its
notice, across 1,669 polygons and 532 recorders. That is enough to work with;
had it been a few hundred, the design would have needed rethinking before any of
it was written.

Precision is decided by the tail, not the median: a 4 ha stand has a ~110 m
equivalent radius, so a record accurate to 350 m can land in the wrong zone. The
build therefore reports the result split by coordinate band — see below.

**Implementation constraint.** GBIF's search API refuses `offset > 100001`, so
263k records cannot be paged in one query. Partition by year × county (each
partition lands well under the cap), or register for the download API.

## The design

A difference-in-differences event study, with the polygon as its own control.

- **Unit**: notification polygon × taxon × time window relative to `Inkomdatum`.
- **Outcome**: observation count inside the polygon per window.
- **Within-polygon comparison** (polygon fixed effects) — this is what removes
  the dominant confounder, that Artportalen coverage is wildly uneven in space.
  Comparing notified to un-notified *places* would measure recorder density;
  comparing a polygon to itself before and after does not.
- **Control**: a 500 m ring around each polygon. *(Changed from the originally
  planned Rosaceae control. The seasonality argument for it was weak anyway —
  orchids are findable only in flower, roughly May–July, while Rosaceae spans
  early-flowering shrubs to autumn fruiting, so the two are not matched on
  detectability. The ring is matched on everything by construction.)*

The estimand is the orchid pre→post change *minus* the Rosaceae pre→post change.
That difference is the whole point: a bare orchid increase after a notice proves
very little, because notified ground simply gets walked on more.

Birds are not usable as a control despite the volume — every wild bird is
protected under *artskyddsförordningen*, so they carry the same legal effect
being tested.

## What would break it

1. **Detection bias.** Notified ground attracts foresters, neighbours and NGOs.
   The ring absorbs the general part of this: it is the same neighbourhood, with
   the same recorders and the same season.
2. **Phenology.** Orchids are findable only in flower, roughly May–July.
   Notifications have their own seasonality. Windows must be day-of-year matched
   or the model must carry month effects, or the result is a season artefact.
3. **Selection into treatment.** A known orchid site may deter a notification in
   the first place, which biases the pre-period downward.
4. **Post-felling habitat change.** Once felled, the ground is a different
   habitat, so windows reaching past the felling measure something else — and
   the GeoPackage carries no felling date, only `ArendeStatus` and `AvvHa`, so
   this cannot be controlled for with the fields available. It is a live
   limitation, not a handled one.
5. **Reporting growth.** Artportalen submissions trend upward year on year; a
   naive pre/post comparison partly measures that trend.
6. **Five years only.** The notification feed is a rolling window, so there is no
   long history to fall back on.

## What a result would and would not mean

It **can** show whether reporting is temporally clustered after a notification,
beyond what general attention explains.

It **cannot** show intent, and cannot show that any report is false. A
notification is public precisely so that it can be checked, and a cluster of
observations afterwards is equally consistent with the system working as
designed. Any write-up has to say that in the same breath as the number, or the
number will be read as an accusation it cannot support.

## Effort

Roughly one to two days. The data acquisition and the spatial join are
straightforward — both sides land in DuckDB with the geometry already in
SWEREF99 TM. Nearly all the work is in the control design and the phenology
handling, which is also where the result lives or dies.

## Result

| Zone | Before | After | Change |
|---|---|---|---|
| Inside the notified polygon | 852 | 7,532 | **+784%** |
| 500 m ring outside it | 11,466 | 19,504 | +70% |

Both zones sit flat near 1.0× their own baseline through the whole year before
the notice — the parallel pre-trend the design needs. In the first month after,
the inside reaches **37× its own normal** and the ring 4×. The inside stays
4–7× for the rest of the year.

A rise begins in the 30 days *before* the notice (3.6× inside). That fits the
pre-felling survey: a consultant walks the stand, records what is there, and
the notification is filed afterwards.

**Recorders.** 532 people supply the 7,532 post-notice records inside notified
ground. Concentrated by volume — the top 20 account for 44%, the top 100 for
80% — but local in geography: 480 of 532 report in a single county, 37 in two,
and only twelve range across three or more. The widest covers eight.

## What this does not establish

It does not show intent, and it does not show that any record is false. A
notification is public precisely so the ground can be checked, and a pre-felling
survey is normal practice. Both "someone checks a stand that was just advertised
for felling" and "someone reports strategically to stop it" produce this shape.
The data separates *where* reporting happens from *why* it happens only in the
first sense.

One correction to an assumption made while scoping: a design flaw would have
manufactured this result on its own. The notification feed reaches back to
August 2021 and the observations to January 2021, so early notices lose part of
their before-window and late ones part of their after-window — 7,019 and 38,467
notices. The analysis keeps only notices with a full year of coverage either
side. The +784% survives that restriction.

## Sources and terms

- **Skogsstyrelsen, avverkningsanmälningar** — open INSPIRE data.
  <https://geodpags.skogsstyrelsen.se/geodataport/feeds/AvverkAnm.xml>
- **GBIF**, mirroring **Artportalen** (SLU Artdatabanken). Occurrence data are
  CC-BY or CC0 depending on the contributing dataset; Artportalen's records
  carry the observer's name as published. <https://www.gbif.org>

GBIF asks that analyses cite a download DOI so the exact extract is
reproducible. This build pages the search API rather than using the download
service, so it has no DOI — a real weakness for reproducibility, and the reason
each row is stored with its `gbif_url` so any record can be resolved
individually.

## Coordinate-precision sensitivity

The headline holds, and strengthens, on the precise records:

| Coordinate accuracy | Inside, before → after | Ring, before → after |
|---|---|---|
| ≤ 50 m | 782 → 7,379 (+844%) | 8,609 → 17,705 (+106%) |
| 51–250 m | 15 → 71 | 592 → 553 |
| > 250 m | 52 → 81 | 2,234 → 1,196 |

The effect is carried by the well-located records. The imprecise tail is noise
in both directions, which is what it should look like if the join is doing its
job rather than manufacturing the result.
