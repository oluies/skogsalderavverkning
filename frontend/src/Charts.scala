package skog

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** ECharts option builders.
  *
  * Chart rules that hold across the page are applied here once: zero-based
  * value axes so magnitudes read at their true proportion, a legend whenever
  * there is more than one series, direct end-labels so identity is never
  * carried by colour alone, and recessive grid and axis lines.
  */
object Charts:

  private def obj(pairs: (String, js.Any)*): js.Object =
    js.Dictionary[js.Any](pairs*).asInstanceOf[js.Object]

  /** f-interpolator can't take a runtime precision, so go through toFixed. */
  private def fixed(v: Double, decimals: Int): String =
    js.Dynamic.global.Number(v).applyDynamic("toFixed")(decimals).toString

  private def textStyle(color: String, size: Int = 11) =
    obj("color" -> color, "fontFamily" -> "IBM Plex Mono, ui-monospace, monospace",
        "fontSize" -> size)

  private def axisLine(color: String) =
    obj("lineStyle" -> obj("color" -> color))

  private def splitLine(color: String) =
    obj("show" -> true, "lineStyle" -> obj("color" -> color, "width" -> 1))

  /** Marks for the storm years. The SLU series are five-year means, so these
    * locate the event, not the peak - the caption says so.
    */
  private val storms = Vector(2005 -> "Gudrun", 2007 -> "Per",
                              2011 -> "Dagmar", 2013 -> "Ivar")

  private def stormMarks(): js.Object =
    obj(
      "silent" -> true,
      "symbol" -> "none",
      "label" -> obj("show" -> true, "position" -> "insideEndTop",
                     "color" -> Theme.ink3, "fontSize" -> 10),
      "lineStyle" -> obj("color" -> Theme.ink3, "type" -> "dashed", "width" -> 1),
      "data" -> storms.map { case (x, name) =>
        obj("xAxis" -> x, "name" -> name,
            "label" -> obj("formatter" -> name)): js.Any
      }.toJSArray
    )

  /** Shared line-chart option.
    *
    * @param zeroBased start the value axis at zero. On by default: a cropped
    *                  axis exaggerates the change, which is the whole question
    *                  this page is about.
    */
  def line(
      series: Vector[Series],
      yName: String,
      zeroBased: Boolean = true,
      decimals: Int = 0,
      showStorms: Boolean = false,
      valueSuffix: String = ""
  ): js.Object =
    // "{value}" would render 2004 as "2,004"; years are plain integers.
    val yearFormatter: js.Function1[js.Any, String] = (v: js.Any) =>
      js.Dynamic.global.Math.round(js.Dynamic.global.Number(v)).toString
    val valueFormatter: js.Function1[js.Any, String] = (v: js.Any) =>
      val d = js.Dynamic.global.Number(v).asInstanceOf[Double]
      if d.isNaN then "–"
      else js.Dynamic.global.Number(d).applyDynamic("toFixed")(decimals).toString + valueSuffix
    obj(
      "animation" -> false,
      "backgroundColor" -> "transparent",
      "grid" -> obj("left" -> 58, "right" -> 132, "top" -> 40, "bottom" -> 40),
      "tooltip" -> obj(
        "trigger" -> "axis",
        "backgroundColor" -> Theme.panel,
        "borderColor" -> Theme.rule2,
        "borderWidth" -> 1,
        "textStyle" -> obj("color" -> Theme.ink, "fontSize" -> 12.5),
        "valueFormatter" -> valueFormatter
      ),
      "legend" -> obj(
        "show" -> (series.size > 1),
        "bottom" -> 0,
        "itemWidth" -> 14, "itemHeight" -> 3, "itemGap" -> 16,
        "textStyle" -> obj("color" -> Theme.ink2, "fontSize" -> 12.5,
                           "fontFamily" -> "IBM Plex Sans, sans-serif")
      ),
      "xAxis" -> obj(
        "type" -> "value",
        "min" -> "dataMin", "max" -> "dataMax",
        "axisLabel" -> obj("formatter" -> yearFormatter, "color" -> Theme.ink3,
                           "fontFamily" -> "IBM Plex Mono, monospace", "fontSize" -> 11),
        "axisLine" -> axisLine(Theme.rule2),
        "axisTick" -> obj("show" -> false),
        "splitLine" -> obj("show" -> false)
      ),
      "yAxis" -> obj(
        "type" -> "value",
        "name" -> yName,
        "nameLocation" -> "end",
        "nameGap" -> 14,
        "nameTextStyle" -> obj("color" -> Theme.ink3, "align" -> "left",
                               "fontFamily" -> "IBM Plex Mono, monospace", "fontSize" -> 10.5),
        "min" -> (if zeroBased then (0: js.Any) else ("dataMin": js.Any)),
        "scale" -> !zeroBased,
        "axisLabel" -> obj("color" -> Theme.ink3,
                           "fontFamily" -> "IBM Plex Mono, monospace", "fontSize" -> 11),
        "axisLine" -> obj("show" -> false),
        "axisTick" -> obj("show" -> false),
        "splitLine" -> splitLine(Theme.rule)
      ),
      "series" -> series.zipWithIndex.map { case (s, i) =>
        val base = js.Dictionary[js.Any](
          "name" -> s.name,
          "type" -> "line",
          "showSymbol" -> false,
          "symbolSize" -> 8,
          "lineStyle" -> obj("width" -> 2, "color" -> s.color),
          "itemStyle" -> obj("color" -> s.color),
          "emphasis" -> obj("focus" -> "series"),
          // direct end-label, so identity never rests on colour alone
          "endLabel" -> obj("show" -> true, "color" -> s.color,
                            "fontFamily" -> "IBM Plex Sans, sans-serif",
                            "fontSize" -> 11.5, "fontWeight" -> 500,
                            "formatter" -> s.name, "distance" -> 8),
          // series converge (the whole point of the felling-age chart), so let
          // ECharts shift overlapping end-labels apart rather than stacking them
          "labelLayout" -> obj("moveOverlap" -> "shiftY"),
          "data" -> s.data.map(p => js.Array[js.Any](p.x, p.y): js.Any).toJSArray
        )
        if showStorms && i == 0 then base("markLine") = stormMarks()
        base.asInstanceOf[js.Object]: js.Any
      }.toJSArray
    )

  /** Choropleth over the registered "sweden" map. */
  def choropleth(
      values: Map[String, Double],
      label: String,
      unit: String,
      decimals: Int,
      diverging: Boolean,
      counts: Map[String, Double] = Map.empty,
      countsLabel: String = "",
      noDataLabel: String = "–"
  ): js.Object =
    val nums = values.values.toVector
    val (lo, hi) =
      if nums.isEmpty then (0.0, 1.0)
      else if diverging then
        val m = nums.map(math.abs).max
        (-m, m)
      else (nums.min, nums.max)

    val pieces =
      if diverging then
        Vector(-1.0, -0.66, -0.33, 0.0, 0.33, 0.66, 1.0).map(t => Theme.diverging(t))
      else Theme.seq

    val mapTooltip: js.Function1[js.Dynamic, String] = (p: js.Dynamic) =>
      val v = p.value
      val absent = js.isUndefined(v) || v == null ||
                   js.Dynamic.global.Number(v).asInstanceOf[Double].isNaN
      if absent then
        // no unit here: "no data days vs 1961-90" is not a sentence
        s"<b>${p.name}</b><br>$label: <b>$noDataLabel</b>"
      else
        val shown = js.Dynamic.global.Number(v).applyDynamic("toFixed")(decimals).toString
        val coverage = counts.get(p.name.toString) match
          case Some(n) if countsLabel.nonEmpty =>
            s"<br><span style='opacity:.7'>$countsLabel: ${n.toInt}</span>"
          case _ => ""
        s"<b>${p.name}</b><br>$label: <b>$shown $unit</b>$coverage"

    obj(
      "animation" -> false,
      "backgroundColor" -> "transparent",
      "tooltip" -> obj(
        "trigger" -> "item",
        "backgroundColor" -> Theme.panel,
        "borderColor" -> Theme.rule2, "borderWidth" -> 1,
        "textStyle" -> obj("color" -> Theme.ink, "fontSize" -> 12.5),
        "formatter" -> mapTooltip
      ),
      "visualMap" -> obj(
        "type" -> "continuous",
        "min" -> lo, "max" -> hi,
        "calculable" -> false,
        "orient" -> "horizontal",
        "left" -> "center", "bottom" -> 0,
        "itemWidth" -> 12, "itemHeight" -> 140,
        "text" -> js.Array(fixed(hi, decimals), fixed(lo, decimals)),
        "textStyle" -> obj("color" -> Theme.ink3, "fontSize" -> 11,
                           "fontFamily" -> "IBM Plex Mono, monospace"),
        "inRange" -> obj("color" -> pieces.toJSArray)
      ),
      "series" -> js.Array[js.Any](obj(
        "type" -> "map",
        "map" -> "sweden",
        "roam" -> false,
        "top" -> 10, "bottom" -> 46,
        "label" -> obj("show" -> false),
        "itemStyle" -> obj("borderColor" -> Theme.panel, "borderWidth" -> 0.8,
                           "areaColor" -> Theme.panel2),
        "emphasis" -> obj(
          "label" -> obj("show" -> false),
          "itemStyle" -> obj("borderColor" -> Theme.ink, "borderWidth" -> 1.6)
        ),
        "data" -> values.toVector.map { case (k, v) =>
          obj("name" -> k, "value" -> v): js.Any
        }.toJSArray
      ))
    )

  /** County scatter: warming against change in site productivity. */
  def scatter(
      points: Vector[(String, String, Double, Double)],
      xName: String,
      yName: String
  ): js.Object =
    val byRegion = points.groupBy(_._2)
    val scatterTooltip: js.Function1[js.Dynamic, String] = (p: js.Dynamic) =>
      val d = p.value.asInstanceOf[js.Array[js.Any]]
      val w = js.Dynamic.global.Number(d(0)).applyDynamic("toFixed")(2)
      val b = js.Dynamic.global.Number(d(1)).applyDynamic("toFixed")(1)
      s"<b>${d(2)}</b><br>+$w °C<br>$b %"
    obj(
      "animation" -> false,
      "backgroundColor" -> "transparent",
      "grid" -> obj("left" -> 62, "right" -> 28, "top" -> 40, "bottom" -> 56),
      "tooltip" -> obj(
        "trigger" -> "item",
        "backgroundColor" -> Theme.panel,
        "borderColor" -> Theme.rule2, "borderWidth" -> 1,
        "textStyle" -> obj("color" -> Theme.ink, "fontSize" -> 12.5),
        "formatter" -> scatterTooltip
      ),
      "legend" -> obj("show" -> true, "bottom" -> 0, "itemGap" -> 16,
                      "textStyle" -> obj("color" -> Theme.ink2, "fontSize" -> 12.5,
                                         "fontFamily" -> "IBM Plex Sans, sans-serif")),
      "xAxis" -> obj(
        "type" -> "value", "scale" -> true,
        "name" -> xName, "nameLocation" -> "end", "nameGap" -> 26,
        "nameTextStyle" -> obj("color" -> Theme.ink3, "align" -> "right",
                               "fontFamily" -> "IBM Plex Mono, monospace", "fontSize" -> 10.5),
        "axisLabel" -> obj("color" -> Theme.ink3, "fontSize" -> 11,
                           "fontFamily" -> "IBM Plex Mono, monospace",
                           "formatter" -> "+{value}"),
        "axisLine" -> axisLine(Theme.rule2),
        "axisTick" -> obj("show" -> false),
        "splitLine" -> splitLine(Theme.rule)
      ),
      "yAxis" -> obj(
        "type" -> "value", "scale" -> true,
        "name" -> yName, "nameLocation" -> "end", "nameGap" -> 14,
        "nameTextStyle" -> obj("color" -> Theme.ink3, "align" -> "left",
                               "fontFamily" -> "IBM Plex Mono, monospace", "fontSize" -> 10.5),
        "axisLabel" -> obj("color" -> Theme.ink3, "fontSize" -> 11,
                           "fontFamily" -> "IBM Plex Mono, monospace",
                           "formatter" -> "{value} %"),
        "axisLine" -> obj("show" -> false),
        "axisTick" -> obj("show" -> false),
        "splitLine" -> splitLine(Theme.rule)
      ),
      "series" -> Theme.regions.flatMap { r =>
        byRegion.get(r).map { ps =>
          obj(
            "name" -> r,
            "type" -> "scatter",
            "symbolSize" -> 13,
            "itemStyle" -> obj("color" -> Theme.regionColor(r),
                               "borderColor" -> Theme.panel, "borderWidth" -> 2),
            "data" -> ps.map { case (area, _, w, b) =>
              js.Array[js.Any](w, b, area.replace(" län", "")): js.Any
            }.toJSArray
          ): js.Any
        }
      }.toJSArray
    )

  /** County x year heatmap, shown beside the map.
    *
    * The map answers "where, this year"; this answers "where, over time" - the
    * same values, the same colour scale, one more dimension.
    */
  def heatmap(
      areas: Vector[String],
      years: Vector[Int],
      cells: Vector[(Int, Int, Double)],
      label: String,
      unit: String,
      decimals: Int,
      diverging: Boolean
  ): js.Object =
    val vals = cells.map(_._3)
    val (lo, hi) =
      if vals.isEmpty then (0.0, 1.0)
      else if diverging then
        val m = vals.map(math.abs).max
        (-m, m)
      else (vals.min, vals.max)
    val ramp =
      if diverging then Vector(-1.0, -0.5, 0.0, 0.5, 1.0).map(t => Theme.diverging(t))
      else Theme.seq

    val tip: js.Function1[js.Dynamic, String] = (p: js.Dynamic) =>
      val d = p.value.asInstanceOf[js.Array[js.Any]]
      val yr = years(js.Dynamic.global.Number(d(0)).asInstanceOf[Double].toInt)
      val ar = areas(js.Dynamic.global.Number(d(1)).asInstanceOf[Double].toInt)
      val v = fixed(js.Dynamic.global.Number(d(2)).asInstanceOf[Double], decimals)
      s"<b>$ar</b><br>$yr<br>$label: <b>$v $unit</b>"

    obj(
      "animation" -> false,
      "backgroundColor" -> "transparent",
      "grid" -> obj("left" -> 116, "right" -> 16, "top" -> 10, "bottom" -> 52),
      "tooltip" -> obj(
        "trigger" -> "item",
        "backgroundColor" -> Theme.panel,
        "borderColor" -> Theme.rule2, "borderWidth" -> 1,
        "textStyle" -> obj("color" -> Theme.ink, "fontSize" -> 12.5),
        "formatter" -> tip
      ),
      "xAxis" -> obj(
        "type" -> "category",
        "data" -> years.map(y => y.toString: js.Any).toJSArray,
        "axisLabel" -> obj("color" -> Theme.ink3, "fontSize" -> 10,
                           "fontFamily" -> "IBM Plex Mono, monospace", "interval" -> "auto"),
        "axisLine" -> obj("lineStyle" -> obj("color" -> Theme.rule2)),
        "axisTick" -> obj("show" -> false),
        "splitArea" -> obj("show" -> false)
      ),
      "yAxis" -> obj(
        "type" -> "category",
        // north at the top: rows arrive ordered by centroid latitude, and the
        // category axis draws its first entry at the bottom
        "data" -> areas.reverse.map(a => (a.replace(" län", "")): js.Any).toJSArray,
        "axisLabel" -> obj("color" -> Theme.ink2, "fontSize" -> 10.5,
                           "fontFamily" -> "IBM Plex Sans, sans-serif"),
        "axisLine" -> obj("show" -> false),
        "axisTick" -> obj("show" -> false),
        "splitArea" -> obj("show" -> false)
      ),
      "visualMap" -> obj(
        "type" -> "continuous",
        "min" -> lo, "max" -> hi,
        "calculable" -> false, "show" -> false,
        "inRange" -> obj("color" -> ramp.toJSArray)
      ),
      "series" -> js.Array[js.Any](obj(
        "type" -> "heatmap",
        "data" -> cells.map { case (x, y, v) =>
          // y is flipped to match the reversed category axis
          js.Array[js.Any](x, areas.length - 1 - y, v): js.Any
        }.toJSArray,
        "progressive" -> 0,
        "itemStyle" -> obj("borderColor" -> Theme.panel, "borderWidth" -> 0.5),
        "emphasis" -> obj("itemStyle" -> obj("borderColor" -> Theme.ink, "borderWidth" -> 1.2))
      ))
    )

  /** Ranked horizontal bars, for the metrics that are one number per county. */
  def ranked(
      values: Map[String, Double],
      label: String,
      unit: String,
      decimals: Int,
      diverging: Boolean
  ): js.Object =
    val sorted = values.toVector.sortBy(_._2)
    val nums = sorted.map(_._2)
    val m = if nums.isEmpty then 1.0 else nums.map(math.abs).max
    val tip: js.Function1[js.Dynamic, String] = (p: js.Dynamic) =>
      s"<b>${p.name}</b><br>$label: <b>${fixed(
        js.Dynamic.global.Number(p.value).asInstanceOf[Double], decimals)} $unit</b>"
    val barColor: js.Function1[js.Dynamic, String] = (p: js.Dynamic) =>
      val v = js.Dynamic.global.Number(p.value).asInstanceOf[Double]
      if diverging then Theme.diverging(v / m)
      else
        val seq = Theme.seq
        val lo = nums.min
        val hi = nums.max
        val t = if hi == lo then 0.5 else (v - lo) / (hi - lo)
        seq(math.max(0, math.min(seq.length - 1, (t * seq.length * 0.999).toInt)))

    val barLabel: js.Function1[js.Dynamic, String] = (p: js.Dynamic) =>
      fixed(js.Dynamic.global.Number(p.value).asInstanceOf[Double], decimals)

    obj(
      "animation" -> false,
      "backgroundColor" -> "transparent",
      "grid" -> obj("left" -> 116, "right" -> 46, "top" -> 10, "bottom" -> 24),
      "tooltip" -> obj(
        "trigger" -> "item",
        "backgroundColor" -> Theme.panel, "borderColor" -> Theme.rule2, "borderWidth" -> 1,
        "textStyle" -> obj("color" -> Theme.ink, "fontSize" -> 12.5),
        "formatter" -> tip
      ),
      "xAxis" -> obj("type" -> "value", "show" -> false),
      "yAxis" -> obj(
        "type" -> "category",
        "data" -> sorted.map(t => (t._1.replace(" län", "")): js.Any).toJSArray,
        "axisLabel" -> obj("color" -> Theme.ink2, "fontSize" -> 10.5,
                           "fontFamily" -> "IBM Plex Sans, sans-serif"),
        "axisLine" -> obj("show" -> false),
        "axisTick" -> obj("show" -> false)
      ),
      "series" -> js.Array[js.Any](obj(
        "type" -> "bar",
        "barWidth" -> "62%",
        "itemStyle" -> obj(
          "borderRadius" -> js.Array(0, 3, 3, 0),
          "color" -> barColor
        ),
        "label" -> obj("show" -> true, "position" -> "right",
                       "color" -> Theme.ink2, "fontSize" -> 10.5,
                       "fontFamily" -> "IBM Plex Mono, monospace",
                       "formatter" -> barLabel),
        "data" -> sorted.map(_._2).map(v => (v: js.Any)).toJSArray
      ))
    )
