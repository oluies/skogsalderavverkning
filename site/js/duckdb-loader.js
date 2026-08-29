// DuckDB-WASM loader.
//
// Exposes window.SkogDb to the Scala.js app rather than being imported by it,
// so the Scala side needs no bundler and no import-map: it just awaits a
// promise on a global.
//
// Note this cannot work inside a Claude Artifact - that sandbox's CSP blocks
// the wasm/worker fetches. There is no fallback: `SkogDb.ready` rejects and
// Boot.scala reports the failure in the page instead of rendering blank.
// On GitHub Pages this runs for real.
const DUCKDB_VERSION = "1.32.0";
const CDN = `https://cdn.jsdelivr.net/npm/@duckdb/duckdb-wasm@${DUCKDB_VERSION}`;

// Tables the app queries, loaded once and registered as views.
const TABLES = [
  "felling_age", "site_index", "climate_county", "climate_region",
  "precip_county", "precip_region", "snow_county", "snow_region",
  "drivers", "stand_type", "felling_species", "felling_type",
  "damage", "natural_loss", "meta",
  "wood_trade", "prices_region", "prices_real", "prices_long",
];

async function boot() {
  const duckdb = await import(`${CDN}/+esm`);
  const bundles = duckdb.getJsDelivrBundles();
  const bundle = await duckdb.selectBundle(bundles);

  // The worker script must come from a same-origin blob: constructing a Worker
  // straight from a cross-origin URL is refused by the browser.
  const workerUrl = URL.createObjectURL(
    new Blob([`importScripts("${bundle.mainWorker}");`], { type: "text/javascript" })
  );
  const worker = new Worker(workerUrl);
  const logger = new duckdb.ConsoleLogger(duckdb.LogLevel.WARNING);
  const db = new duckdb.AsyncDuckDB(logger, worker);
  await db.instantiate(bundle.mainModule, bundle.pthreadWorker);
  URL.revokeObjectURL(workerUrl);

  const conn = await db.connect();

  // Register each parquet file as a view over its URL.
  const base = new URL("data/", document.baseURI).href;
  await Promise.all(TABLES.map(async (t) => {
    const url = `${base}${t}.parquet`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`${t}.parquet: HTTP ${res.status}`);
    await db.registerFileBuffer(`${t}.parquet`, new Uint8Array(await res.arrayBuffer()));
    await conn.query(
      `CREATE OR REPLACE VIEW "${t}" AS SELECT * FROM read_parquet('${t}.parquet')`
    );
  }));

  const counties = await (await fetch(`${base}counties.json`)).json();
  return { db, conn, counties };
}

let handle = null;
const ready = boot().then((h) => { handle = h; return h; });

window.SkogDb = {
  version: DUCKDB_VERSION,
  ready,
  counties: () => handle.counties,
  // Returns plain row objects so the Scala side never touches Arrow internals.
  query: async (sql) => {
    const h = handle || (await ready);
    const table = await h.conn.query(sql);
    return table.toArray().map((row) => {
      const o = row.toJSON();
      // Arrow can hand back BigInt for integer columns; JSON/JS numbers are
      // what the charts want, and every count here is far inside 2^53.
      for (const k of Object.keys(o)) {
        if (typeof o[k] === "bigint") o[k] = Number(o[k]);
      }
      return o;
    });
  },
};
