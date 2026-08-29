package skog

import org.scalajs.dom

/** Entry point.
  *
  * The page needs DuckDB-WASM ready before the first query. Where it cannot
  * start - the Claude Artifact sandbox blocks the wasm and worker fetches with
  * its CSP - the failure is reported in place rather than leaving a blank page.
  */
@main def main(): Unit =
  import scala.concurrent.ExecutionContext.Implicits.global
  val status = dom.document.getElementById("boot")

  def fail(msg: String): Unit =
    dom.console.error(msg)
    Option(status).foreach { el =>
      el.textContent = msg
      el.asInstanceOf[dom.html.Element].style.display = "block"
    }

  if !I18n.missingKeys.isEmpty then
    dom.console.warn(s"i18n keys defined in only one language: ${I18n.missingKeys.mkString(", ")}")

  if !SkogDb.isPresent then
    fail("DuckDB-WASM did not load: window.SkogDb is missing.")
  else
    SkogDb.ready.onComplete {
      case scala.util.Success(_) =>
        // Anything thrown here would otherwise be swallowed by this Future and
        // leave the page sitting on "Laddar..." with an empty console.
        try
          val n = SkogDb.registerSwedenMap()
          if n == 0 then dom.console.warn("no county geometry registered; maps will be blank")
          Option(status).foreach(_.asInstanceOf[dom.html.Element].style.display = "none")
          App.start()
        catch
          case e: Throwable =>
            fail(s"The page failed to start: ${e}")
            throw e
      case scala.util.Failure(e) =>
        fail(s"DuckDB-WASM failed to start: ${e.getMessage}")
    }
