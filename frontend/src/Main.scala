package skog

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

object App:

  // ---- state ------------------------------------------------------------
  private val lang     = Var(initialLang)
  private val lsaBasis = Var("excl")
  private val distView = Var("loss")
  private val mapView  = Var("bonitet")
  private val mapYear  = Var(2023)
  private val climView = Var("temp")
  private val spView   = Var("stand")
  private val weights  = Var(Map("bonitet" -> 1.0, "temp" -> 1.0, "precip" -> 1.0, "snow" -> 0.0))
  private val drivers  = Var(Vector.empty[Driver])
  private val themeTick = Var(0)   // bumped on theme change to force re-render
  private val nStations = Var("—")
  private val nOffshore = Var("—")
  private val nAll = Var("—")

  private def initialLang: String =
    val stored =
      try Option(dom.window.localStorage.getItem("skog-lang")) catch case _: Throwable => None
    stored.filter(I18n.languages.contains).getOrElse("sv")

  private def t(key: String): Signal[String] = lang.signal.map(I18n.get(_, key))
  private def tNow(key: String): String = I18n.get(lang.now(), key)

  // ---- small view helpers ----------------------------------------------
  private def segmented(state: Var[String], options: Vector[(String, String)]) =
    div(cls := "seg",
      options.map { case (value, labelKey) =>
        button(
          tpe := "button",
          child.text <-- t(labelKey),
          aria.pressed <-- state.signal.map(v => (v == value).toString),
          onClick.mapTo(value) --> state
        )
      }
    )

  /** An ECharts instance bound to a Signal of options.
    *
    * The chart is created when the node mounts, updated whenever the signal
    * fires, resized with the window, and disposed on unmount - ECharts holds a
    * canvas and its own listeners, so leaving it undisposed leaks on re-render.
    */
  private def chart(height: Int, option: Signal[js.Object]): HtmlElement =
    var instance: Option[EChart] = None
    val resize: js.Function1[dom.Event, Unit] = _ => instance.foreach(_.resize())
    div(
      cls := "chart",
      styleAttr := s"height:${height}px",
      onMountUnmountCallback(
        mount = { ctx =>
          val c = ECharts.init(ctx.thisNode.ref, js.undefined,
            js.Dictionary("renderer" -> "canvas").asInstanceOf[js.Object])
          instance = Some(c)
          dom.window.addEventListener("resize", resize)
        },
        unmount = { _ =>
          dom.window.removeEventListener("resize", resize)
          instance.foreach(_.dispose())
          instance = None
        }
      ),
      option --> Observer[js.Object] { opt =>
        instance.foreach(_.setOption(opt, true))
      }
    )

  /** Chart driven by an async query. Re-runs whenever any input changes. */
  private def asyncChart(
      height: Int,
      inputs: Signal[Unit],
      load: () => Future[js.Object]
  ): HtmlElement =
    val out = Var[js.Object](js.Dictionary[js.Any]().asInstanceOf[js.Object])
    div(
      inputs --> Observer[Unit] { _ =>
        load().onComplete {
          case Success(o) => out.set(o)
          case Failure(e) => dom.console.error(s"query failed: ${e.getMessage}")
        }
      },
      chart(height, out.signal)
    )

  private def section(headKey: String, introKey: String, control: Option[HtmlElement],
                      body: HtmlElement*): HtmlElement =
    sectionTag(
      div(cls := "shead",
        div(h2(child.text <-- t(headKey)), p(child.text <-- t(introKey))),
        control
      ),
      body
    )

  private def panel(children: HtmlElement*): HtmlElement = div(cls := "panel", children)

  /** Label and caption keys per map metric.
    *
    * Spelled out rather than assembled as "m" + v.capitalize: a concatenated
    * key is the one thing K cannot make the compiler check, and these are the
    * keys most likely to be missed when a metric is added.
    */
  private def metricLabel(v: String): String = v match
    case "bonitet"  => K.mBonitet
    case "bchange"  => K.mBchange
    case "warming"  => K.mWarming
    case "precip"   => K.mPrecip
    case "snow"     => K.mSnow
    case "contorta" => K.mContorta
    case _          => K.mAge

  private def metricCaption(v: String): String = v match
    case "bonitet"  => K.capBonitet
    case "bchange"  => K.capBchange
    case "warming"  => K.capWarming
    case "precip"   => K.capPrecipM
    case "snow"     => K.capSnowM
    case "contorta" => K.capContorta
    case _          => K.capAge

  /** Unit key, decimals and whether the scale diverges, per map metric. */
  private def metricFormat(v: String): (String, Int, Boolean) = v match
    case "bonitet"  => (K.unitBon, 1, false)
    case "bchange"  => (K.unitPct, 1, true)
    case "warming"  => (K.unitC, 2, true)
    case "precip"   => (K.unitPrec, 1, true)
    case "snow"     => (K.unitDays, 1, true)
    case "contorta" => (K.unitShare, 1, false)
    case _          => (K.unitYears, 0, false)

  private def caption(sig: Signal[String]): HtmlElement =
    p(cls := "figcap", child.text <-- sig)

  // ---- composite index --------------------------------------------------
  private def zScores(ds: Vector[Driver]): Map[String, Map[String, Double]] =
    def z(get: Driver => Option[Double]): Map[String, Double] =
      val vals = ds.flatMap(get)
      if vals.isEmpty then Map.empty
      else
        val m  = vals.sum / vals.size
        val sd = math.sqrt(vals.map(v => (v - m) * (v - m)).sum / vals.size)
        val s  = if sd == 0 then 1.0 else sd
        ds.flatMap(d => get(d).map(v => d.area -> (v - m) / s)).toMap
    Map(
      "bonitet" -> z(_.dBonitetPct),
      "temp"    -> z(_.dTempC),
      "precip"  -> z(_.dPrecipPct),
      "snow"    -> z(_.dSnowDays)
    )

  /** Weighted mean of the available z-scores.
    *
    * A mean rather than a sum: with a sum, a county missing one driver is
    * pulled toward the middle and reads as unremarkable rather than unknown.
    */
  private def indexValues(ds: Vector[Driver], w: Map[String, Double]): Map[String, Double] =
    val z = zScores(ds)
    ds.flatMap { d =>
      // toVector before collect: collect on a Map returns a Map, so two drivers
      // yielding the same weighted z-score would silently collapse into one
      // entry and drop a contribution from both the sum and the divisor.
      val parts = w.toVector.collect {
        case (k, weight) if weight != 0.0 && z.get(k).exists(_.contains(d.area)) =>
          (weight * z(k)(d.area), math.abs(weight))
      }
      val wsum = parts.map(_._2).sum
      if wsum == 0 then None else Some(d.area -> parts.map(_._1).sum / wsum)
    }.toMap

  private def weightSlider(key: String, labelKey: String) =
    div(cls := "wrow",
      div(cls := "wtop",
        label(child.text <-- t(labelKey)),
        span(cls := "mono wv",
          child.text <-- weights.signal.map(w => f"${w(key)}%.2f"))
      ),
      input(
        tpe := "range", minAttr := "-2", maxAttr := "2", stepAttr := "0.25",
        defaultValue := weights.now()(key).toString,
        onInput.mapToValue --> Observer[String] { v =>
          v.toDoubleOption.foreach(d => weights.update(_.updated(key, d)))
        }
      )
    )

  // ---- page -------------------------------------------------------------
  def apply(): HtmlElement =
    div(cls := "wrap",

      div(cls := "langbar",
        div(cls := "lang",
          I18n.languages.map { l =>
            button(tpe := "button", l.toUpperCase,
              aria.pressed <-- lang.signal.map(v => (v == l).toString),
              onClick --> { _ =>
                lang.set(l)
                try dom.window.localStorage.setItem("skog-lang", l)
                catch case _: Throwable => ()
                dom.document.documentElement.setAttribute("lang", l)
              })
          }
        )
      ),

      headerTag(
        div(cls := K.eyebrow, child.text <-- t(K.eyebrow)),
        h1(child <-- t(K.title).map(html => foreignHtml(html))),
        p(cls := K.standfirst, child.text <-- t(K.standfirst)),
        div(cls := "src",
          Vector(K.srcAge, K.srcBon, K.srcClim, K.srcGeo).map { k =>
            span(child.text <-- t(k).combineWith(nStations.signal).map(
              (s, n) => s.replace("{n}", n)))
          }
        )
      ),

      tiles(),

      section(K.s1h, K.s1p,
        Some(segmented(lsaBasis, Vector("excl" -> K.lsaExcl, "incl" -> K.lsaIncl))),
        panel(
          asyncChart(400,
            lsaBasis.signal.combineWith(themeTick.signal).mapTo(()),
            () => Queries.fellingAge(lsaBasis.now()).map(s =>
              Charts.line(s, tNow(K.axYears), zeroBased = true))
          ),
          caption(t(K.s1cap).map(stripTags)),
          ageTable()
        )
      ),

      section(K.s2h, K.s2p,
        Some(segmented(distView, Vector(
          "loss" -> K.distLoss, "wind" -> K.distWind, "beetle" -> K.distBeetle))),
        panel(
          asyncChart(360,
            distView.signal.combineWith(themeTick.signal).mapTo(()),
            () =>
              val f = distView.now() match
                case "wind"   => Queries.damage("Vind / snö")
                case "beetle" => Queries.damage("Granbarkborre")
                case _        => Queries.naturalLoss
              val axis = if distView.now() == "loss" then K.axLoss else K.axShare
              f.map(s => Charts.line(s, tNow(axis), zeroBased = true,
                                     decimals = 1, showStorms = true))
          ),
          caption(lang.signal.combineWith(distView.signal).map { (_, v) =>
            tNow(v match
              case "wind"   => K.capWindNote
              case "beetle" => K.capBeetleNote
              case _        => K.capLossNote)
          })
        ),
        panel(
          div(cls := K.eyebrow, styleAttr := "margin-bottom:12px",
            child.text <-- t(K.salvageHead)),
          asyncChart(330, themeTick.signal.mapTo(()),
            () => Queries.salvage.map(s =>
              Charts.line(s, tNow(K.axLoss), zeroBased = true,
                          decimals = 1, showStorms = true))),
          caption(t(K.salvCap).map(stripTags))
        )
      ),

      section(K.s3h, K.s3p, None,
        panel(
          div(cls := "ctrls",
            segmented(mapView, Vector(
              "bonitet" -> K.mBonitet, "bchange" -> K.mBchange, "warming" -> K.mWarming,
              "precip" -> K.mPrecip, "snow" -> K.mSnow, "contorta" -> K.mContorta,
              "age" -> K.mAge)),
            div(cls := "slider",
              display <-- mapView.signal.map(v => if v == "bonitet" then "flex" else "none"),
              label(child.text <-- t(K.yearLbl)),
              input(tpe := "range", minAttr := "1985", maxAttr := "2023", stepAttr := "1",
                defaultValue := "2023",
                onInput.mapToValue --> Observer[String] { v =>
                  v.toIntOption.foreach(mapYear.set)
                }),
              span(cls := "yr mono", child.text <-- mapYear.signal.map(_.toString))
            )
          ),
          div(cls := "maprow",
            asyncChart(620,
              mapView.signal.combineWith(mapYear.signal).combineWith(themeTick.signal).mapTo(()),
              () =>
                val v = mapView.now()
                Queries.mapMetric(v, mapYear.now()).map { (vals, counts) =>
                  val (unitKey, dec, div_) = metricFormat(v)
                  Charts.choropleth(vals, tNow(metricLabel(v)), tNow(unitKey), dec, div_,
                                    counts, tNow(K.tipStations), tNow(K.tipNoData))
                }
            ),
            // Beside the map: the same values over time where there is a yearly
            // series, otherwise a ranked list - a heatmap of one column per
            // county would say nothing the map has not already said.
            div(
              asyncChart(620,
                mapView.signal.combineWith(themeTick.signal).mapTo(()),
                () =>
                  val v = mapView.now()
                  val (unitKey, dec, div_) = metricFormat(v)
                  Queries.heatmap(v).flatMap {
                    case Some((areas, years, cells)) =>
                      Future.successful(Charts.heatmap(areas, years, cells,
                        tNow(metricLabel(v)), tNow(unitKey), dec, div_))
                    case None =>
                      Queries.mapMetric(v, mapYear.now()).map { (vals, _) =>
                        Charts.ranked(vals, tNow(metricLabel(v)), tNow(unitKey), dec, div_)
                      }
                  }
              ),
              p(cls := "figcap",
                child.text <-- lang.signal.combineWith(mapView.signal).map { (_, v) =>
                  if Queries.hasYearlySeries(v) then tNow(K.capHeatmap) else tNow(K.capRanked)
                })
            )
          ),
          caption(lang.signal.combineWith(mapView.signal).map { (_, v) =>
            tNow(metricCaption(v))
          })
        )
      ),

      section(K.s4h, K.s4p, None,
        panel(
          div(cls := "weights",
            weightSlider("bonitet", K.wBonitet),
            weightSlider("temp", K.wTemp),
            weightSlider("precip", K.wPrecip),
            weightSlider("snow", K.wSnow)
          ),
          div(cls := "maprow",
            chart(620,
              drivers.signal.combineWith(weights.signal).combineWith(lang.signal)
                .combineWith(themeTick.signal).map { (ds, w, _, _) =>
                  Charts.choropleth(indexValues(ds, w), tNow(K.tipIndex), "", 2, true,
                                    noDataLabel = tNow(K.tipNoData))
                }),
            div(
              div(cls := K.eyebrow, styleAttr := "margin-bottom:10px",
                child.text <-- t(K.idxRank)),
              chart(560,
                drivers.signal.combineWith(weights.signal).combineWith(lang.signal)
                  .combineWith(themeTick.signal).map { (ds, w, _, _) =>
                    Charts.ranked(indexValues(ds, w), tNow(K.tipIndex), "", 2, true)
                  })
            )
          ),
          caption(t(K.idxCap).map(stripTags))
        )
      ),

      section(K.s5h, K.s5p, None,
        panel(
          asyncChart(360, themeTick.signal.mapTo(()),
            () => Queries.siteIndex.map(s =>
              Charts.line(s, tNow(K.axBon), zeroBased = true, decimals = 1))),
          caption(t(K.s5cap).map(stripTags))
        )
      ),

      section(K.s6h, K.s6p,
        Some(segmented(climView, Vector(
          "temp" -> K.cTemp, "prec" -> K.cPrec, "snow" -> K.cSnow))),
        panel(
          asyncChart(360,
            climView.signal.combineWith(themeTick.signal).mapTo(()),
            () =>
              val axis = climView.now() match
                case "prec" => K.axPrec
                case "snow" => K.axDays
                case _      => K.axTemp
              Queries.climate(climView.now()).map(s =>
                // anomalies are differences from a baseline, so zero is a real
                // reference here rather than an axis-cropping choice
                Charts.line(s, tNow(axis), zeroBased = false, decimals = 2))
          ),
          caption(lang.signal.combineWith(climView.signal).map { (_, v) =>
            tNow(v match
              case "prec" => K.capPrec
              case "snow" => K.capSnow
              case _      => K.capTemp)
          })
        )
      ),

      section(K.s7h, K.s7p,
        Some(segmented(spView, Vector("stand" -> K.spStand, "fell" -> K.spFell))),
        panel(
          asyncChart(360,
            spView.signal.combineWith(themeTick.signal).mapTo(()),
            () =>
              if spView.now() == "stand" then
                Queries.standType.map(s =>
                  Charts.line(s, tNow(K.axShare), zeroBased = true, decimals = 1))
              else
                Queries.fellingSpecies.map(s =>
                  Charts.line(s, tNow(K.axLoss), zeroBased = true, decimals = 1))
          ),
          caption(lang.signal.combineWith(spView.signal).map { (_, v) =>
            tNow(if v == "stand" then K.capStand else K.capFell)
          })
        )
      ),

      section(K.s8h, K.s8p, None,
        panel(
          asyncChart(420, themeTick.signal.mapTo(()),
            () => Queries.scatter.map(pts =>
              Charts.scatter(pts, tNow(K.axIdxX), tNow(K.axIdxY)))),
          caption(scatterCaption)
        )
      ),

      sectionTag(
        div(cls := "shead", div(h2(child.text <-- t(K.s9h)))),
        div(cls := "panel notes",
          children <-- lang.signal.combineWith(nStations.signal)
      .combineWith(nAll.signal).combineWith(nOffshore.signal).map { (l, _, _, _) =>
            I18n.notes(l).map { case (head, body) =>
              p(b(head), " ",
                body.replace("{n}", nStations.now())
                    .replace("{all}", nAll.now())
                    .replace("{off}", nOffshore.now()))
            }.toList
          }
        )
      ),

      footerTag(
        "© 2026 Örjan Lundberg · ",
        a(href := "https://github.com/oluies", "GitHub"), " · ",
        a(href := "https://www.linkedin.com/in/orjanlundberg/", "LinkedIn"), " · ",
        child.text <-- t(K.footerSource), " ",
        a(href := "https://github.com/oluies/skogsalderavverkning",
          "github.com/oluies/skogsalderavverkning"), " · ",
        child.text <-- t(K.footerBuilt), " ",
        a(href := "https://duckdb.org", "DuckDB"), ", ",
        a(href := "https://laminar.dev", "Scala.js + Laminar"), " & ",
        a(href := "https://echarts.apache.org", "ECharts")
      )
    )

  /** Pearson r plus a t statistic, so the caption states the strength of the
    * association rather than implying one exists.
    */
  private def scatterCaption: Signal[String] =
    drivers.signal.combineWith(lang.signal).map { (ds, l) =>
      val pts = ds.flatMap(d => (d.dTempC, d.dBonitetPct) match
        case (Some(w), Some(b)) => Some((w, b))
        case _                  => None)
      if pts.size < 3 then ""
      else
        val n  = pts.size
        val mx = pts.map(_._1).sum / n
        val my = pts.map(_._2).sum / n
        val sxy = pts.map { case (x, y) => (x - mx) * (y - my) }.sum
        val sxx = pts.map { case (x, _) => (x - mx) * (x - mx) }.sum
        val syy = pts.map { case (_, y) => (y - my) * (y - my) }.sum
        val r  = sxy / math.sqrt(sxx * syy)
        val tv = math.abs(r) * math.sqrt((n - 2) / (1 - r * r))
        val head = I18n.get(l, K.scatterLead)
          .replace("{n}", n.toString)
          .replace("{r}", f"$r%.2f")
          .replace("{t}", f"$tv%.2f")
          .replace("{df}", (n - 2).toString)
        head + " " + I18n.get(l, K.scatterTail)
    }

  /** The chart as a table.
    *
    * An ECharts canvas exposes nothing to a screen reader or to text search, so
    * without this the numbers behind the headline chart are unreachable.
    */
  private def ageTable(): HtmlElement =
    val rows = Var(Vector.empty[(Double, Map[String, Double])])
    val cols = Theme.regions :+ "Hela landet"
    detailsTag(
      onMountCallback(_ => Queries.fellingAgeTable.onComplete {
        case Success(r) => rows.set(r)
        case Failure(e) => dom.console.error(s"felling age table query failed: ${e.getMessage}")
      }),
      summaryTag(child.text <-- t(K.showTable)),
      div(cls := "plot",
        table(cls := "dtable",
          thead(tr(th(child.text <-- t(K.yearLbl)), cols.map(c => th(c)))),
          tbody(
            children <-- rows.signal.map(_.map { (year, byRegion) =>
              tr(
                td(cls := "mono", f"$year%.0f"),
                cols.map(c => td(cls := "mono",
                  byRegion.get(c).map(v => f"$v%.0f").getOrElse("–")))
              )
            }.toList)
          )
        )
      )
    )

  private def tiles(): HtmlElement =
    val data = Var(Vector.empty[(String, Double, Double)])
    div(cls := "tiles",
      onMountCallback(_ => Queries.ageChange.foreach(data.set)),
      children <-- data.signal.combineWith(lang.signal).map { (rows, _) =>
        rows.map { case (region, first, last) =>
          val delta = last - first
          val unit  = tNow(K.unitYears)
          div(cls := "tile",
            div(cls := "rg",
              span(cls := "swatch",
                styleAttr := s"background:${Theme.regionColor(region)}"), region),
            div(cls := "big mono",
              (if delta > 0 then "+" else "") + f"$delta%.0f" + " " + unit),
            div(cls := "sub", f"$first%.0f → $last%.0f $unit")
          )
        }.toList
      }
    )

  /** Captions are authored with a little inline markup for emphasis; the
    * Laminar text nodes take plain text, so strip the tags rather than
    * injecting unsanitised HTML.
    */
  private def stripTags(s: String): String =
    s.replaceAll("<[^>]*>", "")

  private def foreignHtml(html: String): HtmlElement =
    val el = span()
    el.ref.innerHTML = html   // page-authored constant, not user input
    el

  def start(): Unit =
    dom.document.documentElement.setAttribute("lang", lang.now())
    val mount = dom.document.getElementById("app")
    render(mount, apply())
    Queries.meta.onComplete {
      case Success(m) =>
        m.get("stations_temp").foreach(v => nStations.set(v.toInt.toString))
        m.get("stations_all").foreach(v => nAll.set(v.toInt.toString))
        m.get("stations_offshore").foreach(v => nOffshore.set(v.toInt.toString))
      case Failure(e) =>
        // otherwise the page quietly renders "SMHI, — stationer" with no clue why
        dom.console.error(s"meta query failed: ${e.getMessage}")
    }
    Queries.drivers.onComplete {
      case Success(d) => drivers.set(d)
      case Failure(e) => dom.console.error(s"drivers query failed: ${e.getMessage}")
    }
    // Re-render charts when the OS theme flips: ECharts bakes the resolved
    // colours into its canvas, so the CSS cascade alone cannot restyle it.
    val mq = dom.window.matchMedia("(prefers-color-scheme: dark)")
    mq.addEventListener("change", (_: dom.Event) => themeTick.update(_ + 1))
