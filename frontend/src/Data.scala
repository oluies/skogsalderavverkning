package skog

import scala.scalajs.js
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/** A point in a time series. */
final case class Pt(x: Double, y: Double)

/** One named series ready for ECharts. */
final case class Series(name: String, color: String, data: Vector[Pt])

final case class County(name: String, landsdel: String, geo: js.Dynamic)

final case class Driver(
    area: String,
    landsdel: String,
    lat: Option[Double],
    dBonitetPct: Option[Double],
    dTempC: Option[Double],
    dPrecipPct: Option[Double],
    dSnowDays: Option[Double],
    contortaPct: Option[Double]
)

object Decode:
  def opt(d: js.Dynamic, field: String): Option[Double] =
    val v = d.selectDynamic(field)
    if js.isUndefined(v) || v == null then None
    else
      // Arrow hands numbers back as Double, but a value that arrived via JSON
      // or as a BigInt-turned-string still needs coercing.
      val n = js.Dynamic.global.Number(v).asInstanceOf[Double]
      if n.isNaN then None else Some(n)

  def str(d: js.Dynamic, field: String): String =
    val v = d.selectDynamic(field)
    if js.isUndefined(v) || v == null then "" else v.toString

  def num(d: js.Dynamic, field: String): Double = opt(d, field).getOrElse(Double.NaN)

/** All queries the page runs. Keeping them here rather than inline in the view
  * makes the SQL surface reviewable in one place, and keeps the shaping in
  * DuckDB where it belongs instead of in JavaScript loops.
  */
object Queries:

  /** year -> value, for one filtered series. */
  private def pairs(rows: js.Array[js.Dynamic], x: String, y: String): Vector[Pt] =
    rows.toVector
      .flatMap { r =>
        (Decode.opt(r, x), Decode.opt(r, y)) match
          case (Some(a), Some(b)) => Some(Pt(a, b))
          case _                  => None
      }
      .sortBy(_.x)

  /** Group rows into one series per key, in a caller-supplied order. */
  private def grouped(
      rows: js.Array[js.Dynamic],
      keyField: String,
      x: String,
      y: String,
      order: Vector[String],
      color: String => String
  ): Vector[Series] =
    val byKey = rows.toVector.groupBy(r => Decode.str(r, keyField))
    order.flatMap { k =>
      byKey.get(k).map { rs =>
        Series(k, color(k),
          rs.flatMap { r =>
            (Decode.opt(r, x), Decode.opt(r, y)) match
              case (Some(a), Some(b)) => Some(Pt(a, b))
              case _                  => None
          }.sortBy(_.x))
      }
    }

  private def regionColor(k: String) = Theme.regionColor(k)

  def fellingAge(basis: String): Future[Vector[Series]] =
    SkogDb.query(
      s"""SELECT year, region, age_years
          FROM felling_age
          WHERE lsa_basis = '$basis' AND region <> 'Hela landet'
          ORDER BY region, year"""
    ).map(grouped(_, "region", "year", "age_years", Theme.regions, regionColor))

  def fellingAgeTable: Future[Vector[(Double, Map[String, Double])]] =
    SkogDb.query(
      """SELECT year, region, age_years FROM felling_age
         WHERE lsa_basis = 'excl' ORDER BY year"""
    ).map { rows =>
      rows.toVector
        .groupBy(r => Decode.num(r, "year"))
        .toVector.sortBy(_._1)
        .map { case (y, rs) =>
          y -> rs.map(r => Decode.str(r, "region") -> Decode.num(r, "age_years")).toMap
        }
    }

  /** First and last published year per region, for the headline tiles. */
  def ageChange: Future[Vector[(String, Double, Double)]] =
    SkogDb.query(
      """WITH b AS (
           SELECT region, min(year) AS y0, max(year) AS y1
           FROM felling_age WHERE lsa_basis = 'excl' GROUP BY region
         )
         SELECT b.region,
                (SELECT age_years FROM felling_age f
                  WHERE f.region = b.region AND f.year = b.y0 AND f.lsa_basis = 'excl') AS first_v,
                (SELECT age_years FROM felling_age f
                  WHERE f.region = b.region AND f.year = b.y1 AND f.lsa_basis = 'excl') AS last_v
         FROM b WHERE b.region <> 'Hela landet'"""
    ).map { rows =>
      val by = rows.toVector.map(r =>
        Decode.str(r, "region") -> (Decode.num(r, "first_v"), Decode.num(r, "last_v"))).toMap
      Theme.regions.flatMap(r => by.get(r).map { case (a, b) => (r, a, b) })
    }

  def siteIndex: Future[Vector[Series]] =
    SkogDb.query(
      s"""SELECT year, area, medelbonitet FROM site_index
          WHERE area IN (${Theme.regions.map(r => s"'$r'").mkString(",")})
          ORDER BY area, year"""
    ).map(grouped(_, "area", "year", "medelbonitet", Theme.regions, regionColor))

  /** Ten-year moving mean over *calendar years*, not over rows.
    *
    * ROWS BETWEEN would average the previous nine present rows, and these
    * series have gaps - a station network that thins out leaves whole years
    * missing. With ROWS, a point plotted at 1979 could be the mean of ten
    * observations spanning 1963-1979 while the caption calls it a ten-year
    * mean. RANGE windows on the year value itself, and the count lets us
    * refuse to plot a point backed by too few years.
    */
  private def smoothed(table: String, col: String, extraWhere: String): String =
    s"""SELECT region, year,
               avg($col) OVER w AS v,
               count($col) OVER w AS n_years
        FROM $table WHERE $extraWhere
        WINDOW w AS (PARTITION BY region ORDER BY year
                     RANGE BETWEEN 9 PRECEDING AND CURRENT ROW)"""

  def climate(kind: String): Future[Vector[Series]] =
    val (table, col, where) = kind match
      case "prec" => ("precip_region", "anom_pct",  "year >= 1900")
      case "snow" => ("snow_region",   "anom_days", "TRUE")
      case _      => ("climate_region", "anom_annual", "year >= 1900")
    // Require most of the decade to be present before drawing a point, so a
    // sparse stretch is left as a gap rather than smoothed into a trend.
    SkogDb.query(
      s"""SELECT region, year, v FROM (${smoothed(table, col, where)})
          WHERE n_years >= 8 ORDER BY region, year"""
    ).map(grouped(_, "region", "year", "v", Theme.regions, regionColor))

  def naturalLoss: Future[Vector[Series]] =
    SkogDb.query(
      """SELECT region, year, mm3sk FROM natural_loss
         WHERE species = 'Gran' AND region <> 'Hela landet' ORDER BY region, year"""
    ).map(grouped(_, "region", "year", "mm3sk", Theme.regions, regionColor))

  def damage(kind: String): Future[Vector[Series]] =
    SkogDb.query(
      s"""SELECT region, year, share_pct FROM damage
          WHERE damage_type = '$kind' AND region <> 'Hela landet'
          ORDER BY region, year"""
    ).map(grouped(_, "region", "year", "share_pct", Theme.regions, regionColor))

  val harvestTypes = Vector("Slutavverkning", "Gallring", "Övriga huggningsarter")

  def salvage: Future[Vector[Series]] =
    SkogDb.query(
      """SELECT region, year, harvest_type, value FROM felling_type
         WHERE region = 'Götaland' ORDER BY harvest_type, year"""
    ).map { rows =>
      grouped(rows, "harvest_type", "year", "value", harvestTypes,
        k => Theme.slot(harvestTypes.indexOf(k)))
    }

  val standTypes = Vector("Tallskog", "Granskog", "Barrblandskog", "Lövskog", "Contortaskog")

  def standType: Future[Vector[Series]] =
    SkogDb.query(
      """SELECT area, year, stand_type, share_pct FROM stand_type
         WHERE area = 'Hela landet' ORDER BY stand_type, year"""
    ).map { rows =>
      grouped(rows, "stand_type", "year", "share_pct", standTypes,
        k => Theme.slot(standTypes.indexOf(k)))
    }

  val species = Vector("Tall", "Gran", "Lövträd")

  def fellingSpecies: Future[Vector[Series]] =
    SkogDb.query(
      """SELECT region, year, species, mm3sk FROM felling_species
         WHERE region = 'Hela landet' ORDER BY species, year"""
    ).map { rows =>
      grouped(rows, "species", "year", "mm3sk", species,
        k => Theme.slot(species.indexOf(k)))
    }

  val tradePartners = Vector("Ryssland", "Finland", "Estland", "Lettland",
                             "Litauen", "Norge", "Tyskland")
  private val partnerCode = Map(
    "Ryssland" -> "RU", "Finland" -> "FI", "Estland" -> "EE", "Lettland" -> "LV",
    "Litauen" -> "LT", "Norge" -> "NO", "Tyskland" -> "DE")

  /** Wood imports by partner country, MSEK per year.
    *
    * Russia is the reason this is here: roundwood came from there until the EU
    * banned it in 2022, and the series shows both that and the earlier decline.
    */
  def tradeByPartner(direction: String): Future[Vector[Series]] =
    val codes = tradePartners.flatMap(partnerCode.get).map(c => s"'$c'").mkString(",")
    SkogDb.query(
      s"""SELECT partner, year, msek FROM wood_trade
          WHERE direction = '$direction' AND kn = '44' AND partner IN ($codes)
          ORDER BY partner, year"""
    ).map { rows =>
      val byCode = partnerCode.map(_.swap)
      val named = rows.toVector.map { r =>
        (byCode.getOrElse(Decode.str(r, "partner"), Decode.str(r, "partner")),
         Decode.opt(r, "year"), Decode.opt(r, "msek"))
      }
      tradePartners.zipWithIndex.flatMap { (name, i) =>
        val pts = named.collect {
          case (n, Some(y), Some(v)) if n == name => Pt(y, v)
        }.sortBy(_.x)
        if pts.isEmpty then None else Some(Series(name, Theme.slot(i), pts))
      }
    }

  /** Imports and exports of the main wood commodity groups, MSEK per year. */
  def tradeByGoods(direction: String): Future[Vector[Series]] =
    val groups = Vector("4403", "4407", "47", "48")
    SkogDb.query(
      s"""SELECT kn, kn_label, year, msek FROM wood_trade
          WHERE direction = '$direction' AND partner = 'TOT'
            AND kn IN (${groups.map(g => s"'$g'").mkString(",")})
          ORDER BY kn, year"""
    ).map { rows =>
      val recs = rows.toVector.map(r =>
        (Decode.str(r, "kn"), Decode.str(r, "kn_label"),
         Decode.opt(r, "year"), Decode.opt(r, "msek")))
      groups.zipWithIndex.flatMap { (g, i) =>
        val label = recs.find(_._1 == g).map(_._2).getOrElse(g)
        val pts = recs.collect { case (k, _, Some(y), Some(v)) if k == g => Pt(y, v) }
          .sortBy(_.x)
        if pts.isEmpty then None else Some(Series(label, Theme.slot(i), pts))
      }
    }

  /** Sawlogs first, then pulpwood: two different markets, and the section
    * splits on that. Pulpwood is the total rather than the softwood/hardwood
    * split: Skogsstyrelsen stopped publishing that split per landsdel after
    * 2023, and Gotaland's breakdown ends in 2022, so offering it would draw
    * lines that stop mid-chart while their neighbours run to 2025.
    */
  /** Assortment paired with its label key, so the two cannot drift apart the
    * way a positional zip against a Vector in another file can. */
  val assortmentLabels: Vector[(String, String)] = Vector(
    "Tallsågtimmer"    -> K.asTall,
    "Gransågtimmer"    -> K.asGran,
    "Massaved, totalt" -> K.asMassa)

  // Declared AFTER the Vector it reads. A forward reference between vals in a
  // Scala object initialises to null, and the resulting NPE is swallowed by the
  // Future that boot runs in - the page simply never mounts, with nothing in
  // the console.
  val assortments: Vector[String] = assortmentLabels.map(_._1)

  /** Roundwood price per landsdel for one assortment, kr/m3fub. */
  def pricesByRegion(assortment: String): Future[Vector[Series]] =
    SkogDb.query(
      s"""SELECT region, year, kr_m3fub FROM prices_region
          WHERE assortment = '$assortment' AND region <> 'Hela landet'
          ORDER BY region, year"""
    ).map(grouped(_, "region", "year", "kr_m3fub", Theme.regions, regionColor))

  val region3 = Vector("Nord", "Mellan", "Syd")

  /** The two Skogsstyrelsen price tables spliced into one 1995-2025 series.
    *
    * Coarser than prices_region on purpose: the old table only ever had three
    * regions, and pretending it resolves to landsdelar would invent detail.
    */
  def pricesLong(assortment: String): Future[Vector[Series]] =
    SkogDb.query(
      s"""SELECT region3, year, kr_m3fub FROM prices_long
          WHERE assortment = '$assortment' ORDER BY region3, year"""
    ).map { rows =>
      // same hues as the landsdel view for the geography they overlap, so
      // toggling between the two price views does not repaint the map of Sweden
      val hue = Map("Nord" -> 0, "Mellan" -> 2, "Syd" -> 3)
      grouped(rows, "region3", "year", "kr_m3fub", region3,
        k => Theme.slot(hue.getOrElse(k, 0)))
    }

  /** The real series carries its own assortments: it ends in 2022, before the
    * rename, and has no pulpwood total at all - so it cannot use `assortments`.
    */
  val realAssortmentLabels: Vector[(String, String)] = Vector(
    "Tallsågtimmer"  -> K.asTall,
    "Gransågtimmer"  -> K.asGran,
    "Massaved, barr" -> K.asMassaBarr,
    "Massaved, löv"  -> K.asMassaLov)
  val realAssortments: Vector[String] = realAssortmentLabels.map(_._1)

  /** National prices in 2022 money, back to 1967. */
  def pricesReal: Future[Vector[Series]] =
    SkogDb.query(
      """SELECT assortment, year, kr_m3fub_2022 FROM prices_real
         ORDER BY assortment, year"""
    ).map { rows =>
      val label = realAssortmentLabels.toMap
      grouped(rows, "assortment", "year", "kr_m3fub_2022", realAssortments,
        k => Theme.slot(realAssortments.indexOf(k)))
        .map(sx => sx.copy(name = translate(label.getOrElse(sx.name, sx.name))))
    }

  /** Notified felling area nationally, the forward-looking series.
    *
    * An owner must notify six weeks before felling, so a surge here would be
    * the fingerprint of felling ahead of an expected restriction. Split by
    * owner category because the claim is usually made about small private
    * owners specifically.
    */
  def notifications: Future[Vector[Series]] =
    // the total is plotted as well: it is the figure the caption quotes, and
    // leaving it out meant describing a line that was not on the chart
    val groups = Vector("Samtliga", "Enskilda ägare", "Övriga")
    SkogDb.query(
      """SELECT owner_group, year, v FROM notifications
         WHERE region = 'Hela landet' AND measure = 'ha'
         ORDER BY owner_group, year"""
    ).map { rows =>
      grouped(rows, "owner_group", "year", "v", groups,
        k => Theme.slot(groups.indexOf(k)))
    }

  /** Felling refused in montane forest, in hectares.
    *
    * Area only: the case count runs 5-188 while the area runs 53-8250, and on
    * one linear axis the count line sits flat on the baseline. The count is in
    * the caption instead, where it can be read.
    */
  def deniedFelling(areaLabel: String): Future[Vector[Series]] =
    SkogDb.query(
      """SELECT year, measure, v FROM denied_felling WHERE county = 'Hela Landet'
         ORDER BY year"""
    ).map { rows =>
      val recs = rows.toVector.map(r =>
        (Decode.str(r, "measure"), Decode.opt(r, "year"), Decode.opt(r, "v")))
      def pick(m: String, scale: Double) =
        recs.collect { case (k, Some(y), Some(v)) if k == m => Pt(y, v * scale) }.sortBy(_.x)
      Vector(Series(areaLabel, Theme.slot(3), pick("ha", 1.0))).filter(_.data.nonEmpty)
    }

  /** New habitat-protection orders per year: forest taken out of production. */
  def protection(areaLabel: String): Future[Vector[Series]] =
    SkogDb.query(
      """SELECT year, measure, v FROM protection WHERE region = 'Hela landet'
         ORDER BY year"""
    ).map { rows =>
      val recs = rows.toVector.map(r =>
        (Decode.str(r, "measure"), Decode.opt(r, "year"), Decode.opt(r, "v")))
      Vector(Series(areaLabel, Theme.slot(3),
        recs.collect { case ("ha", Some(y), Some(v)) => Pt(y, v) }.sortBy(_.x)))
        .filter(_.data.nonEmpty)
    }

  /** Set by the view so data-layer series can carry translated names. */
  private var translate: String => String = identity
  def setTranslator(f: String => String): Unit = translate = f

  /** Measured agreement between the two price tables in their overlap.
    *
    * Read from the data rather than written into the caption: these numbers
    * have gone stale twice already, once for each time the weighting changed.
    */
  def spliceCheck: Future[Vector[(String, Double)]] =
    SkogDb.query("SELECT region3, pct_diff FROM prices_splice_check ORDER BY region3")
      .map(_.toVector.flatMap { r =>
        Decode.opt(r, "pct_diff").map(v => Decode.str(r, "region3") -> v)
      })

  /** Orchid observations around a felling notice, inside the notified polygon
    * against a 500 m ring outside it, each scaled by its own before-period.
    */
  def orchidEvent: Future[Vector[Series]] =
    SkogDb.query(
      """SELECT zone, bin_day, rel_to_baseline FROM orchid_event
         ORDER BY zone, bin_day"""
    ).map { rows =>
      val zones = Vector("inside", "ring")
      val labels = Map("inside" -> insideLabel, "ring" -> ringLabel)
      grouped(rows, "zone", "bin_day", "rel_to_baseline", zones,
        k => Theme.slot(if k == "inside" then 3 else 0))
        .map(sx => sx.copy(name = labels.getOrElse(sx.name, sx.name)))
    }

  private var insideLabel = "Inne i anmälan"
  private var ringLabel = "500 m runt om"
  def setZoneLabels(a: String, b: String): Unit = { insideLabel = a; ringLabel = b }

  /** How many counties a single recorder covers, as a distribution. */
  def orchidReach: Future[Vector[(Int, Int)]] =
    SkogDb.query("SELECT counties, n_recorders FROM orchid_reach ORDER BY counties")
      .map(_.toVector.flatMap { r =>
        for a <- Decode.opt(r, "counties"); b <- Decode.opt(r, "n_recorders")
        yield (a.toInt, b.toInt)
      })

  /** Figures quoted in prose, keyed by name. */
  def meta: Future[Map[String, Double]] =
    SkogDb.query("SELECT k, v FROM meta").map { rows =>
      rows.toVector.flatMap(r => Decode.opt(r, "v").map(v => Decode.str(r, "k") -> v)).toMap
    }

  def drivers: Future[Vector[Driver]] =
    SkogDb.query("SELECT * FROM drivers ORDER BY area").map { rows =>
      rows.toVector.map { r =>
        Driver(
          Decode.str(r, "area"),
          Decode.str(r, "landsdel"),
          Decode.opt(r, "lat"),
          Decode.opt(r, "d_bonitet_pct"),
          Decode.opt(r, "d_temp_c"),
          Decode.opt(r, "d_precip_pct"),
          Decode.opt(r, "d_snow_days"),
          Decode.opt(r, "contorta_pct")
        )
      }
    }

  /** County values for one map metric, plus the station count backing each
    * where the metric comes from weather stations - a value resting on one
    * station should not look the same as one resting on forty. The count is
    * the mean over the same span as the value, not the maximum: a county whose
    * network thinned mid-period would otherwise advertise its best year.
    */
  def mapMetric(metric: String, year: Int): Future[(Map[String, Double], Map[String, Double])] =
    val sql = metric match
      case "bonitet" =>
        s"SELECT area, medelbonitet AS v FROM site_index WHERE year = $year"
      case "bchange" =>
        // same definition as the index and the scatter (decadal means), so the
        // three surfaces do not each label a different quantity "bonitet change"
        "SELECT area, d_bonitet_pct AS v FROM drivers"
      case "warming" =>
        """SELECT area, avg(anom_annual) AS v, round(avg(n_stations)) AS n FROM climate_county
           WHERE year BETWEEN 2011 AND 2024 GROUP BY area"""
      case "precip" =>
        """SELECT area, avg(anom_pct) AS v, round(avg(n_stations)) AS n FROM precip_county
           WHERE year BETWEEN 2011 AND 2024 GROUP BY area"""
      case "snow" =>
        """SELECT area, avg(anom_days) AS v, round(avg(n_stations)) AS n FROM snow_county
           WHERE year BETWEEN 2011 AND 2024 GROUP BY area"""
      case "contorta" =>
        "SELECT area, contorta_pct AS v FROM drivers"
      case _ =>
        // latest published year rather than a hardcoded one, so the map does
        // not silently blank when SLU publishes a new figur 4.9
        """SELECT d.area, f.age_years AS v
           FROM drivers d JOIN felling_age f
             ON f.region = d.landsdel AND f.lsa_basis = 'excl'
           WHERE f.year = (SELECT max(year) FROM felling_age WHERE lsa_basis = 'excl')"""
    SkogDb.query(sql).map { rows =>
      val vs = rows.toVector.flatMap { r =>
        Decode.opt(r, "v").map(v => Decode.str(r, "area") -> v)
      }.toMap
      val ns = rows.toVector.flatMap { r =>
        Decode.opt(r, "n").map(n => Decode.str(r, "area") -> n)
      }.toMap
      (vs, ns)
    }

  /** Where each heatmap-capable metric's yearly series comes from:
    * (table, value column, area column, extra predicate).
    *
    * One definition, because the caption text and the choice of chart are
    * driven by the same question - split across two literals they could
    * disagree, and the panel would then draw a heatmap under a caption saying
    * there is no time axis.
    */
  private val yearlySources: Map[String, (String, String, String, String)] = Map(
    "bonitet" -> ("site_index",     "medelbonitet", "area", ""),  // centred per row, see below
    "warming" -> ("climate_county", "anom_annual",  "area", "AND year >= 1900"),
    "precip"  -> ("precip_county",  "anom_pct",     "area", "AND year >= 1900"),
    "snow"    -> ("snow_county",    "anom_days",    "area", "")
  )

  /** Whether a map metric has a yearly series to draw a heatmap from. */
  def hasYearlySeries(metric: String): Boolean = yearlySources.contains(metric)

  /** County x year values for the heatmap beside the map.
    *
    * Only the metrics that actually have a yearly series: the map's other
    * metrics are a single number per county, where a heatmap would just be a
    * one-column strip. Rows come back ordered north to south by centroid
    * latitude, so the gradient reads geographically.
    */
  def heatmap(metric: String): Future[Option[(Vector[String], Vector[Int], Vector[(Int, Int, Double)])]] =
    yearlySources.get(metric) match
      case None => Future.successful(None)
      case Some((table, col, areaCol, extra)) =>
        // Each row is centred on its own county's mean.
        //
        // Without this the colour encodes the county's level, which the map
        // beside it already shows - and it swamps the time signal: counties
        // differ by 8.4 m3sk of bonitet while the largest change within any
        // one county over 38 years is 0.7, so a shared absolute scale spends
        // 92% of its range on the north-south gradient. Centring makes the
        // heatmap answer the question a time axis is for.
        //
        // The climate metrics are already anomalies against a fixed 1961-1990
        // baseline, but centring them too keeps every row on one diverging
        // scale and asks the same question of each: how has this county moved
        // relative to its own normal.
        val expr = s"t.$col - avg(t.$col) OVER (PARTITION BY t.$areaCol)"
        SkogDb.query(
          s"""SELECT t.$areaCol AS area, t.year AS year, $expr AS v, d.lat AS lat
              FROM $table t JOIN drivers d ON d.area = t.$areaCol
              WHERE t.$col IS NOT NULL $extra
              ORDER BY d.lat DESC, t.year"""
        ).map { rows =>
          val recs = rows.toVector.flatMap { r =>
            for
              v <- Decode.opt(r, "v")
              y <- Decode.opt(r, "year")
              lat <- Decode.opt(r, "lat")
            yield (Decode.str(r, "area"), y.toInt, v, lat)
          }
          if recs.isEmpty then None
          else
            val areas = recs.map(t => (t._1, t._4)).distinct.sortBy(-_._2).map(_._1)
            val years = recs.map(_._2).distinct.sorted
            val ai = areas.zipWithIndex.toMap
            val yi = years.zipWithIndex.toMap
            Some((areas, years, recs.map(t => (yi(t._2), ai(t._1), t._3))))
        }

  /** Warming against bonitet change, one row per county, for the scatter. */
  def scatter: Future[Vector[(String, String, Double, Double)]] =
    SkogDb.query(
      """SELECT area, landsdel, d_temp_c, d_bonitet_pct FROM drivers
         WHERE d_temp_c IS NOT NULL AND d_bonitet_pct IS NOT NULL"""
    ).map { rows =>
      rows.toVector.map { r =>
        (Decode.str(r, "area"), Decode.str(r, "landsdel"),
         Decode.num(r, "d_temp_c"), Decode.num(r, "d_bonitet_pct"))
      }
    }
