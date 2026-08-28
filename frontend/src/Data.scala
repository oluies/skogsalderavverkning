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

  /** Whether a map metric has a yearly series to draw a heatmap from. */
  def hasYearlySeries(metric: String): Boolean =
    Set("bonitet", "warming", "precip", "snow").contains(metric)

  /** County x year values for the heatmap beside the map.
    *
    * Only the metrics that actually have a yearly series: the map's other
    * metrics are a single number per county, where a heatmap would just be a
    * one-column strip. Rows come back ordered north to south by centroid
    * latitude, so the gradient reads geographically.
    */
  def heatmap(metric: String): Future[Option[(Vector[String], Vector[Int], Vector[(Int, Int, Double)])]] =
    val src = metric match
      case "bonitet" => Some(("site_index", "medelbonitet", "area", ""))
      case "warming" => Some(("climate_county", "anom_annual", "area", "AND year >= 1900"))
      case "precip"  => Some(("precip_county", "anom_pct", "area", "AND year >= 1900"))
      case "snow"    => Some(("snow_county", "anom_days", "area", ""))
      case _         => None
    src match
      case None => Future.successful(None)
      case Some((table, col, areaCol, extra)) =>
        SkogDb.query(
          s"""SELECT t.$areaCol AS area, t.year AS year, t.$col AS v, d.lat AS lat
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
