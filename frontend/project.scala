//> using scala 3.7.3
//> using platform js
//> using jsVersion 1.20.1

// No module kind: ECharts and the DuckDB loader are reached as globals rather
// than imported, so the app needs no bundler and no import map. That also lets
// the Closure Compiler run, which ES module output would rule out.
//> using jsModuleKind none

//> using dep com.raquo::laminar::17.2.1
