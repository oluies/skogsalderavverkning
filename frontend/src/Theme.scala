package skog

import org.scalajs.dom

/** Colour tokens are declared in CSS so light/dark and the viewer's theme
  * toggle are handled by the cascade; ECharts needs concrete values, so read
  * them back out at render time and re-render when the theme changes.
  */
object Theme:
  def cssVar(name: String): String =
    dom.window
      .getComputedStyle(dom.document.documentElement)
      .getPropertyValue(name)
      .trim

  def ink       = cssVar("--ink")
  def ink2      = cssVar("--ink-2")
  def ink3      = cssVar("--ink-3")
  def rule      = cssVar("--rule")
  def rule2     = cssVar("--rule-2")
  def panel     = cssVar("--panel")
  def panel2    = cssVar("--panel-2")
  def pos       = cssVar("--pos")
  def neg       = cssVar("--neg")

  /** Validated categorical slots, assigned in fixed order and never cycled. */
  def slots: Vector[String] =
    (1 to 6).toVector.map(i => cssVar(s"--s$i"))

  def slot(i: Int): String = slots(i % slots.length)

  /** Single-hue sequential ramp, light to dark (reversed under dark mode by
    * the CSS, so the lightest step always recedes toward the surface).
    */
  def seq: Vector[String] = (1 to 6).toVector.map(i => cssVar(s"--seq$i"))

  val regions: Vector[String] =
    Vector("N Norrland", "S Norrland", "Svealand", "Götaland")

  def regionColor(r: String): String =
    val i = regions.indexOf(r)
    slot(if i >= 0 then i else 0)

  /** Blend toward a pole for the diverging scale; t in [-1, 1]. */
  def diverging(t: Double): String =
    val c = if t >= 0 then pos else neg
    mix(panel, c, math.min(1.0, 0.18 + math.abs(t) * 0.82))

  def mix(base: String, over: String, a: Double): String =
    def parse(h: String): (Int, Int, Int) =
      val s = h.stripPrefix("#")
      val t = if s.length == 3 then s.flatMap(c => s"$c$c") else s
      (Integer.parseInt(t.substring(0, 2), 16),
       Integer.parseInt(t.substring(2, 4), 16),
       Integer.parseInt(t.substring(4, 6), 16))
    if !base.startsWith("#") || !over.startsWith("#") then over
    else
      val (r1, g1, b1) = parse(base)
      val (r2, g2, b2) = parse(over)
      def m(x: Int, y: Int) = math.round(x + (y - x) * a).toInt
      s"rgb(${m(r1, r2)},${m(g1, g2)},${m(b1, b2)})"
