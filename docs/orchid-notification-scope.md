# Do red-listed species get reported right after a felling notice?

Scope for an analysis that is **not yet built**. Everything below about data
availability was verified against the live sources, not assumed.

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
This was the assumption most likely to sink the study, and it holds.

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
- **Control taxon**: Rosaceae (327,941 georeferenced observations in the same
  window). A comparable plant family with a similar recorder community and
  similar seasonality, but **no legal effect on felling**.

The estimand is the orchid pre→post change *minus* the Rosaceae pre→post change.
That difference is the whole point: a bare orchid increase after a notice proves
very little, because notified ground simply gets walked on more.

Birds are not usable as a control despite the volume — every wild bird is
protected under *artskyddsförordningen*, so they carry the same legal effect
being tested.

## What would break it

1. **Detection bias.** Notified ground attracts foresters, neighbours and NGOs.
   The control taxon absorbs the general part of this, but only if orchid and
   Rosaceae recorders behave alike, which is an assumption, not a fact.
2. **Phenology.** Orchids are findable only in flower, roughly May–July.
   Notifications have their own seasonality. Windows must be day-of-year matched
   or the model must carry month effects, or the result is a season artefact.
3. **Selection into treatment.** A known orchid site may deter a notification in
   the first place, which biases the pre-period downward.
4. **Post-felling habitat change.** Once felled, the ground is a different
   habitat; windows extending past the actual felling measure something else.
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
