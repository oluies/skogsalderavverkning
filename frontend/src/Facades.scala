package skog

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.concurrent.Future
import org.scalajs.dom

/** Minimal ECharts facade. ECharts is loaded as a classic script from cdnjs, so
  * it arrives as a global rather than a module; only the handful of members
  * this app uses are typed.
  */
@js.native
@JSGlobal("echarts")
object ECharts extends js.Object:
  def init(el: dom.Element, theme: js.UndefOr[String], opts: js.Object): EChart = js.native
  def registerMap(name: String, geoJson: js.Object): Unit = js.native

@js.native
trait EChart extends js.Object:
  def setOption(option: js.Object, notMerge: Boolean): Unit = js.native
  def resize(): Unit = js.native
  def dispose(): Unit = js.native
  def on(event: String, handler: js.Function1[js.Dynamic, Unit]): Unit = js.native

/** The DuckDB-WASM handle published by site/js/duckdb-loader.js.
  *
  * It is a global rather than an import so the Scala side needs no bundler: the
  * loader resolves duckdb-wasm from jsDelivr as an ES module and hands back a
  * promise plus a query function returning plain row objects.
  */
@js.native
trait SkogDb extends js.Object:
  val version: String              = js.native
  val ready: js.Promise[js.Any]    = js.native
  def counties(): js.Array[js.Dynamic] = js.native
  def query(sql: String): js.Promise[js.Array[js.Dynamic]] = js.native

object SkogDb:
  private def handle: js.UndefOr[SkogDb] =
    js.Dynamic.global.SkogDb.asInstanceOf[js.UndefOr[SkogDb]]

  def isPresent: Boolean = handle.isDefined

  def ready: Future[Unit] =
    import scala.scalajs.js.Thenable.Implicits.*
    import scala.concurrent.ExecutionContext.Implicits.global
    handle.toOption match
      case Some(db) => db.ready.map(_ => ())
      case None     => Future.failed(new RuntimeException("SkogDb not present"))

  def counties: js.Array[js.Dynamic] =
    handle.toOption.map(_.counties()).getOrElse(js.Array())

  /** Register the county polygons with ECharts as the map named "sweden".
    *
    * ECharts choropleths draw a *registered* map, so this must run before the
    * first map chart renders - otherwise the series has geometry to colour but
    * no geometry to draw, and the panel comes up blank.
    */
  def registerSwedenMap(): Int =
    val features = counties.map { c =>
      val raw = c.selectDynamic("gj")
      // DuckDB's JSON writer may hand this back already parsed, or as text
      val geometry =
        if js.typeOf(raw) == "string" then js.JSON.parse(raw.asInstanceOf[String])
        else raw
      js.Dictionary[js.Any](
        "type" -> "Feature",
        "properties" -> js.Dictionary[js.Any](
          "name" -> c.selectDynamic("slu_name"),
          "landsdel" -> c.selectDynamic("landsdel")
        ),
        "geometry" -> geometry
      ).asInstanceOf[js.Object]: js.Any
    }
    val fc = js.Dictionary[js.Any](
      "type" -> "FeatureCollection",
      "features" -> features
    ).asInstanceOf[js.Object]
    ECharts.registerMap("sweden", fc)
    features.length

  def query(sql: String): Future[js.Array[js.Dynamic]] =
    import scala.scalajs.js.Thenable.Implicits.*
    handle.toOption match
      case Some(db) => db.query(sql)
      case None     => Future.failed(new RuntimeException("SkogDb not present"))
