package nebflow.gateway

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 * The JS-face gate (imgref rework r1, 2026-09-18 · verifier defect A).
 *
 * WHY this exists: the imgref batch shipped a module-level syntax error in
 * `web/js/viewers/html.js` (three unescaped backticks inside a template-literal
 * comment closed the literal early) — the module, and everything that imports
 * it, stopped loading in the browser. NOTHING in the Scala test suite could see
 * it: munit never parses JavaScript, and the browser rig of that batch only
 * collected `console` errors, not `pageerror`s. The repo's own esbuild gate
 * (`npm run build`) does catch it — it simply was never run.
 *
 * WHAT it checks, per file under `src/main/resources/web/js`:
 *   · PARSE  — every module is instantiated by the real V8 ES-module parser
 *              (`vm.SourceTextModule`). A syntax error anywhere in the tree is
 *              reported with its own message; this is the class of defect above.
 *   · FRAME  — every template literal that carries an injected `<script>` body is
 *              evaluated and the EMITTED text is compiled as a classic script.
 *              Reason: a template literal silently drops a lone backslash
 *              (`\+` → `+`), so a frame script written `.replace(/\+/g, ' ')`
 *              reaches the iframe as `.replace(/+/g, ' ')` — a SyntaxError that
 *              kills that frame script while the module still parses fine. Pass
 *              PARSE cannot see it; the browser only shows it as a frame-level
 *              `pageerror` (which is exactly how it was found).
 *   · LINK   — the graph reachable from `js/main.js` is linked with a linker
 *              that resolves relative specifiers against the filesystem, so a
 *              missing module or an import of a name that is not exported fails
 *              here instead of 404ing in a browser.
 *
 * It is deliberately a HARD gate, not a skip: `node` is this repo's frontend
 * toolchain (package.json — esbuild bundle + checkJs gate + the Playwright
 * suites), so a missing `node` fails the build with the command it needs rather
 * than quietly passing. A gate that can skip itself is not a gate.
 *
 * Manual equivalent (same verdict, run from the repo root):
 *   node --experimental-vm-modules <script> src/main/resources/web/js
 *   npm run build          # the esbuild bundle gate, catches this too
 */
class WebJsModuleSyntaxSpec extends FunSuite:

  /** The SOURCE tree — what the dev server serves and what the bundle is built
    * from (the classpath copy under `target/` is derived from it). */
  private val jsDir: Path =
    Paths.get(os.pwd.toString).resolve("src").resolve("main").resolve("resources").resolve("web").resolve("js")

  /** The CHECKED= floor: a path mistake (or an empty tree) must fail loudly
    * instead of turning this gate into a vacuous green. The tree has held
    * ~97 modules since the viewers split; keep the floor well below that so
    * ordinary module-count drift never false-fails. */
  private val minChecked = 50

  test("every web/js module parses as an ES module and the graph from main.js links"):
    assert(
      Files.isDirectory(jsDir),
      s"the web/js source tree is not where this gate expects it: $jsDir (cwd=${os.pwd})"
    )

    val script = Files.createTempFile("nf-webjs-esm-", ".mjs")
    try
      Files.write(script, WebJsModuleSyntaxSpec.checker.getBytes(StandardCharsets.UTF_8))
      val nodeRunEither =
        try
          Right(
            os
              .proc("node", "--experimental-vm-modules", script.toString, jsDir.toString)
              .call(check = false, stdin = os.Pipe, mergeErrIntoOut = true)
          )
        catch case e: Throwable => Left(e)
      val nodeRun = nodeRunEither.fold(
        e =>
          fail(
            s"could not run node (${e.getClass.getSimpleName}: ${e.getMessage}). " +
              "The JS-face gate needs node on PATH (it is this repo's frontend toolchain: " +
              "`npm install` + `npm run build`). Equivalent manual gate: `npm run build`."
          ),
        identity
      )
      val out = nodeRun.out.text()
      val checked =
        """CHECKED=(\d+)""".r.findFirstMatchIn(out).map(_.group(1).toInt).getOrElse(0)
      println(s"WEBJS-GATE dir=$jsDir rc=${nodeRun.exitCode}")
      out.linesIterator.foreach(l => println(s"WEBJS-GATE | $l"))
      assertEquals(nodeRun.exitCode, 0, s"the JS-face gate failed:\n$out")
      assert(
        checked >= minChecked,
        s"the gate only saw $checked module(s) (< $minChecked) — it is not looking at the real tree"
      )
    finally
      Files.deleteIfExists(script)

object WebJsModuleSyntaxSpec:

  /** The checker, run as `node --experimental-vm-modules <this> <jsDir>`.
    *
    * Plain (non-interpolated) Scala string on purpose: the JS uses template
    * literals and `${…}`, which an `s"""…"""` interpolator would rewrite. */
  val checker: String =
    """// ESM parse + link check over a web/js tree (real V8 parser — no regexes).
// usage: node --experimental-vm-modules check-web-esm.mjs <jsDir>
// exit 0 = every module parses and the graph from main.js links; 1 otherwise.
import vm from 'node:vm';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join, resolve, relative } from 'node:path';

const root = resolve(process.argv[2]);
const files = [];
(function walk(d) {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.js')) files.push(p);
  }
})(root);
files.sort();

const cache = new Map();
const problems = [];
const skipped = [];
const moduleFor = (file) => {
  if (cache.has(file)) return cache.get(file);
  const m = new vm.SourceTextModule(readFileSync(file, 'utf8'), {
    identifier: pathToFileURL(file).href,
  });
  cache.set(file, m);
  return m;
};

// pass 1 — parse EVERY module (this is the pass that catches a module-level
// syntax error such as an unescaped backtick inside a template literal)
for (const f of files) {
  try {
    moduleFor(f);
  } catch (e) {
    problems.push(`SYNTAX-FAIL ${relative(root, f)}: ${e.name}: ${e.message}`);
  }
}

// pass 2 — FRAME SCRIPTS: the viewers build injected <script> bodies by
// assembling template literals. A well-formed-looking source line can still
// emit BROKEN JavaScript, because a template literal drops a lone backslash
// (`\+` evaluates to `+`, so a regex written `/\+/g` in the source reaches the
// iframe as `/+/g`). Pass 1 cannot see that: the module itself parses fine.
// So: evaluate every template literal that carries a <script> body and compile
// the emitted text as a classic script — exactly what the iframe does.
const literalRe = /(?<!\\)\x60((?:[^\x60\\]|\\.)*)\x60/g;
for (const f of files) {
  const src = readFileSync(f, 'utf8');
  literalRe.lastIndex = 0;
  let m;
  const seen = new Set();
  while ((m = literalRe.exec(src)) !== null) {
    const body = m[1];
    if (!body.includes('<script')) continue;
    const line = src.slice(0, m.index).split('\n').length;
    if (seen.has(line)) continue;
    seen.add(line);
    let emitted;
    try {
      emitted = vm.runInThisContext('\x60' + body + '\x60');
    } catch (e) {
      // a template literal with interpolation needs runtime values; nothing to
      // compile statically — say so loudly rather than silently passing, but do
      // not fail on it (it is not a defect of the file)
      skipped.push(`FRAME-SCRIPT-SKIP ${relative(root, f)}:${line} (needs runtime values: ${e.message})`);
      continue;
    }
    const bodyRe = /<script[^>]*>([\s\S]*?)<\/script>/g;
    let s;
    let idx = 0;
    while ((s = bodyRe.exec(emitted)) !== null) {
      idx++;
      try {
        new vm.Script(s[1], { filename: `${relative(root, f)}:${line}#frame-script-${idx}` });
      } catch (e) {
        problems.push(
          `FRAME-SCRIPT-FAIL ${relative(root, f)}:${line} (#${idx}): ${e.name}: ${e.message}`
        );
      }
    }
  }
}

// pass 3 — link the graph reachable from main.js: a relative import that does
// not resolve, or an import of a name the target does not export, fails here
// instead of 404ing (or silently undefined-ing) in a browser
const linker = async (specifier, referencing) => {
  if (!specifier.startsWith('.')) {
    throw new Error(`unresolvable bare specifier "${specifier}" (referenced by ${referencing.identifier})`);
  }
  const target = resolve(fileURLToPath(referencing.identifier), '..', specifier);
  try {
    return moduleFor(target);
  } catch (e) {
    throw new Error(`cannot load "${specifier}" (${target}): ${e.message}`);
  }
};
const entry = join(root, 'main.js');
if (!cache.has(entry)) {
  problems.push(`ENTRY-MISSING ${entry}`);
} else {
  try {
    await cache.get(entry).link(linker);
  } catch (e) {
    problems.push(`LINK-FAIL main.js: ${e.message}`);
  }
}

for (const p of problems) console.log(p);
for (const s of skipped) console.log(s);
console.log(
  `CHECKED=${files.length} LINKED=${cache.size} PROBLEMS=${problems.length} SKIPPED=${skipped.length}`
);
process.exit(problems.length === 0 ? 0 : 1);
"""
