// pdf-parse.test.mjs — the PDF note/ask parser leg (scripts/tests, node --test).
//
// What this covers that the Scala suite cannot cheaply cover on its own:
//   · the parser script's own output contract (reading order, tables, media with
//     page attribution, relative image links);
//   · the failure vocabulary (exit code 3 + one JSON line, one code per cause);
//   · the JVM/script version parity fact (MARKDOWN_VERSION in Python vs
//     PdfParser.MarkdownVersion in Scala) — the same number in two languages.
//
// The fixtures are real PDFs written byte by byte by an independent writer
// (scripts/tests/pdf_fixture.py: stdlib only, plus pypdf for the encrypted
// one). No PDF is committed and no AGPL writer is involved.
//
// ── Dependency gate (why this file has a `skip` path at all) ────────────────
//
// The parser leg is Python (pdfplumber + pypdf). That dependency set is a
// MACHINE-local artifact: the repository never installs into an interpreter and
// a stock `python3` does not carry it. A repo gate must therefore not turn "this
// checkout has not bootstrapped the venv yet" into a red — that would make a
// local artifact a repository dependency, and `npm run check:static` (this file
// rides the `script-tests` profile) would be red on every fresh checkout and in
// CI. The rule, in full:
//
//   · probe fails, NO knob   ⇒ SKIP, loudly, carrying the bootstrap line. The
//     skip is printed (node:test reports it and the reason names the fix), so an
//     absent capability is never mistaken for a passing one.
//   · probe fails, knob SET  ⇒ HARD FAIL. Someone pointed `STAND_PDF_PYTHON` at
//     an interpreter that cannot do the job; that is a real fault, and a skip
//     would hide exactly the breakage the knob was set to find.
//   · probe succeeds         ⇒ RUN, with or without the knob.
//
// `STAND_PDF_PYTHON` is the single knob (default `python3`) — the same one the
// Scala leg reads via `PdfParser.pythonBin()`.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const PARSER = join(ROOT, 'src', 'main', 'resources', 'pdf', 'pdf_parse.py');
const FIXTURE = join(ROOT, 'scripts', 'tests', 'pdf_fixture.py');
const SCALA_PARSER = join(ROOT, 'src', 'main', 'scala', 'nebflow', 'core', 'pdf', 'PdfParser.scala');

const KNOB = 'STAND_PDF_PYTHON';
const PYTHON = process.env[KNOB] || 'python3';
const KNOB_SET = Boolean(process.env[KNOB]);

/** The one line that makes the parser runnable on a fresh checkout. */
const BOOTSTRAP = 'scripts/tests/pdf_python_env.sh && export STAND_PDF_PYTHON="$HOME/.cache/stand/pdf-venv/bin/python3"';

function probe(argv) {
  try {
    execFileSync(PYTHON, argv, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
    return { ok: true, detail: '' };
  } catch (e) {
    const tail = String(e.stderr || e.message || '').trim().split('\n').filter(Boolean).pop() || 'unknown error';
    return { ok: false, detail: e.code === 'ENOENT' ? `interpreter "${PYTHON}" is not executable` : tail };
  }
}

let probed = null;
function parserDeps() {
  if (!probed) probed = probe(['-c', 'import pdfplumber, pypdf']);
  return probed;
}

/**
 * Skip verdict for the parser dependency set: `null` = run, string = skip.
 * The knob-set-but-broken case deliberately returns `null` so the test RUNS and
 * fails on [[requireParserDeps]] instead of being skipped away.
 */
function parserSkipReason() {
  const p = parserDeps();
  if (p.ok) return null;
  if (KNOB_SET) return null;
  return `pdfplumber/pypdf unavailable (${p.detail}) and ${KNOB} is unset — PDF parser tests skipped. Bootstrap: ${BOOTSTRAP}`;
}

/** Runs inside a gated test body: the hard failure for a pointed-at-but-broken interpreter. */
function requireParserDeps() {
  const p = parserDeps();
  if (p.ok) return;
  assert.fail(
    `${KNOB}="${PYTHON}" is set but pdfplumber/pypdf are unavailable (${p.detail}) — ` +
    `refusing to skip an interpreter that was explicitly pointed at. Fix it with: ${BOOTSTRAP}`,
  );
}

const PARSER_SKIP = parserSkipReason();
if (PARSER_SKIP) console.warn(`[pdf-parse.test] ${PARSER_SKIP}`);

const tmp = mkdtempSync(join(tmpdir(), 'pdf-parse-test-'));
const fixtures = join(tmp, 'fixtures');
mkdirSync(fixtures, { recursive: true });

/**
 * Build the fixtures on demand. Lazy on purpose: generating them spawns the
 * interpreter, and a skipped run must not do that (nor throw) at import time —
 * a module-level `execFileSync` is what made a missing venv look like a broken
 * parser.
 */
let fixturesBuilt = false;
function ensureFixtures() {
  if (!fixturesBuilt) {
    execFileSync(PYTHON, [FIXTURE, fixtures]);
    fixturesBuilt = true;
  }
  return fixtures;
}

/** Run the parser and return {status, stdout, json}. Never throws on exit 3. */
function runParser(args) {
  try {
    const stdout = execFileSync(PYTHON, [PARSER, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    return { status: 0, stdout, lines: stdout.trim().split('\n') };
  } catch (e) {
    const stdout = String(e.stdout || '');
    return { status: e.status, stdout, lines: stdout.trim().split('\n').filter(Boolean) };
  }
}

function jsonLine(lines) {
  const line = lines.filter(l => l.trim().startsWith('{')).pop();
  return line ? JSON.parse(line) : null;
}

test('parser: reading order, tables, media attribution and relative links', { skip: PARSER_SKIP ?? false }, () => {
  requireParserDeps();
  const fx = ensureFixtures();
  const out = join(tmp, 'entry-ok');
  const r = runParser(['--input', join(fx, 'simple.pdf'), '--out', out, '--name', 'simple.pdf']);
  assert.equal(r.status, 0, `parser must succeed: ${r.stdout}`);
  const verdict = jsonLine(r.lines);
  assert.equal(verdict.ok, true);
  assert.equal(verdict.page_count, 3);

  // Layout is the contract: meta.json + index.json + pages/pNNNN.md +
  // media/pNNNN-i.ext.
  assert.ok(existsSync(join(out, 'meta.json')));
  assert.ok(existsSync(join(out, 'index.json')), 'the entry must carry the page index');
  assert.deepEqual(readdirSync(join(out, 'pages')).sort(), ['p0001.md', 'p0002.md', 'p0003.md']);
  assert.deepEqual(readdirSync(join(out, 'media')), ['p0003-0.png']);

  const meta = JSON.parse(readFileSync(join(out, 'meta.json'), 'utf8'));
  assert.equal(meta.schema, 1);
  assert.equal(meta.source_name, 'simple.pdf');
  assert.equal(meta.pages, undefined);
  assert.equal(meta.page_count, 3);
  assert.deepEqual(meta.page_names, ['p0001.md', 'p0002.md', 'p0003.md']);
  assert.equal(meta.parser.name, 'pdfplumber');
  assert.match(meta.parser.version, /^\d+\./);
  assert.equal(meta.markdown_version, 2);
  assert.equal(meta.image_count, 1);
  assert.equal(meta.media.length, 1);
  assert.equal(meta.media[0], 'media/p0003-0.png');
  assert.ok(typeof meta.generated_at_ms === 'number' && meta.generated_at_ms > 0);
  assert.match(meta.source_sha256, /^[0-9a-f]{64}$/);

  // The page index is the ask leg's discovery surface: term -> ascending pages.
  const index = JSON.parse(readFileSync(join(out, 'index.json'), 'utf8'));
  assert.equal(index.schema, 1);
  assert.equal(index.markdown_version, 2);
  assert.equal(index.page_count, 3);
  assert.deepEqual(index.terms.r2c2, [2], 'the table cell term must resolve to page 2');
  assert.deepEqual(index.terms.first, [1]);
  assert.deepEqual(index.terms.page, [1, 2, 3]);
  assert.equal(Object.keys(index.terms).every(t => t === t.toLowerCase() && t.length >= 2), true);

  // Reading order comes from geometry, not from insertion order: the fixture
  // writes "Second paragraph" BEFORE "First paragraph".
  const p1 = readFileSync(join(out, 'pages', 'p0001.md'), 'utf8');
  assert.ok(p1.startsWith('# Page 1\n'), 'each page file starts with its page heading');
  assert.ok(p1.indexOf('First paragraph') < p1.indexOf('Second paragraph'), `reading order wrong:\n${p1}`);

  // The table appears once, as Markdown, in reading order between its neighbours.
  const p2 = readFileSync(join(out, 'pages', 'p0002.md'), 'utf8');
  assert.match(p2, /\| r1c1 \| r1c2 \| r1c3 \|/);
  assert.match(p2, /\| r3c1 \| r3c2 \| r3c3 \|/);
  assert.equal((p2.match(/r1c1/g) || []).length, 1, 'table text must not be duplicated as body text');
  assert.ok(p2.indexOf('Page two intro') < p2.indexOf('| r1c1'));
  assert.ok(p2.indexOf('| r3c3') < p2.indexOf('Page two outro'));
  assert.equal(meta.table_count, 1);

  // Media is attributed by page and linked RELATIVE to the page file.
  const p3 = readFileSync(join(out, 'pages', 'p0003.md'), 'utf8');
  assert.match(p3, /!\[page 3 image 1\]\(\.\.\/media\/p0003-0\.png\)/);
  assert.ok(existsSync(join(out, 'media', 'p0003-0.png')));
});

test('parser: failures are one JSON line with a distinct code, and write nothing', { skip: PARSER_SKIP ?? false }, () => {
  requireParserDeps();
  const fx = ensureFixtures();
  const cases = [
    ['encrypted.pdf', 'PDF_ENCRYPTED'],
    ['empty.pdf', 'PDF_EMPTY'],
    ['nope.pdf', 'PDF_NOT_FOUND'],
  ];
  for (const [name, code] of cases) {
    const out = join(tmp, `entry-${code}`);
    const r = runParser(['--input', join(fx, name), '--out', out]);
    assert.equal(r.status, 3, `${name} must exit 3, got ${r.status}: ${r.stdout}`);
    const verdict = jsonLine(r.lines);
    assert.ok(verdict, `${name} must print a JSON verdict: ${r.stdout}`);
    assert.equal(verdict.ok, false);
    assert.equal(verdict.error, code);
    assert.ok(verdict.message.length > 10, 'the failure must be readable, not just coded');
    assert.ok(!existsSync(join(out, 'meta.json')), 'a failed run must not publish a manifest');
  }
  // A non-PDF file is the "invalid" code, distinct from the others.
  const bogus = join(tmp, 'bogus.pdf');
  writeFileSync(bogus, 'this is not a pdf');
  const r = runParser(['--input', bogus, '--out', join(tmp, 'entry-invalid')]);
  assert.equal(r.status, 3);
  assert.equal(jsonLine(r.lines).error, 'PDF_INVALID');

  // A non-empty output directory is refused rather than merged into.
  const busy = join(tmp, 'entry-busy');
  mkdirSync(busy, { recursive: true });
  writeFileSync(join(busy, 'meta.json'), '{}');
  const rb = runParser(['--input', join(fx, 'simple.pdf'), '--out', busy]);
  assert.equal(rb.status, 3);
  assert.equal(jsonLine(rb.lines).error, 'OUT_NOT_EMPTY');
});

test('parser: usage errors exit 2', { skip: PARSER_SKIP ?? false }, () => {
  requireParserDeps();
  const r = runParser(['--input', join(ensureFixtures(), 'simple.pdf')]);
  assert.equal(r.status, 2);
});

// ── The three checks below read repository text only: no interpreter, no PDF,
//    no dependency — they run unconditionally, on every checkout. ────────────

test('version parity: MARKDOWN_VERSION in the script equals PdfParser.MarkdownVersion', () => {
  const py = readFileSync(PARSER, 'utf8');
  const scala = readFileSync(SCALA_PARSER, 'utf8');
  const pyVersion = Number(/^MARKDOWN_VERSION\s*=\s*(\d+)\s*$/m.exec(py)?.[1]);
  const scalaVersion = Number(/val MarkdownVersion:\s*Int\s*=\s*(\d+)/.exec(scala)?.[1]);
  assert.ok(Number.isInteger(pyVersion), 'MARKDOWN_VERSION must be declared in the parser script');
  assert.ok(Number.isInteger(scalaVersion), 'PdfParser.MarkdownVersion must be declared in Scala');
  assert.equal(pyVersion, scalaVersion, 'the two declarations are the same fact in two languages');
});

test('cache layout constants are the documented ones', () => {
  const cache = readFileSync(join(ROOT, 'src', 'main', 'scala', 'nebflow', 'core', 'pdf', 'PdfCache.scala'), 'utf8');
  assert.ok(/dataRoot\s*\/\s*"pdf-cache"/.test(cache), 'the cache root is <dataRoot>/pdf-cache');
  assert.ok(/f"p\$page%04d\.md"/.test(cache), 'page files are pages/p%04d.md');
  assert.ok(/"meta\.json"/.test(cache), 'the manifest is meta.json');
});

test('laziness is structural: only the ask arm may reach the parse entry points', () => {
  const routes = readFileSync(join(ROOT, 'src', 'main', 'scala', 'nebflow', 'gateway', 'PdfRoutes.scala'), 'utf8');
  // Slice the route table out of the file (the helpers below it are shared by
  // several arms, so an arm must be judged on its own body, not on the file).
  const tableStart = routes.indexOf('HttpRoutes.of[IO] {');
  const tableEnd = routes.indexOf('private def askAsync');
  assert.ok(tableStart > 0 && tableEnd > tableStart, 'the route table must be locatable');
  const table = routes.slice(tableStart, tableEnd);
  const arm = (from, to) => {
    const a = table.indexOf(from);
    const b = to ? table.indexOf(to) : table.length;
    assert.ok(a > 0 && b > a, `arm ${from} must be locatable`);
    return table.slice(a, b);
  };
  const open = arm('"pdf" / "open"', '"pdf" / "note"');
  const noteGet = arm('"pdf" / "note"', '"pdf" / "ask"');
  const page = arm('"pdf" / "page"', null);
  assert.ok(!/PdfAsk/.test(open), 'open must not reference the ask leg');
  assert.ok(!/ensure\(/.test(open), 'open must never call the cache parse entry point');
  assert.ok(!/PdfAsk/.test(noteGet), 'the note routes must not reference the ask leg');
  assert.ok(!/ensure\(/.test(noteGet), 'the note routes must never call the cache parse entry point');
  assert.ok(!/PdfAsk/.test(page), 'the page read must not reference the ask leg');
  assert.ok(!/ensure\(/.test(page), 'the page read must never call the cache parse entry point');
  // The ask arm is the one that does.
  const ask = arm('"pdf" / "ask"', '"pdf" / "page"');
  assert.ok(/askAsync\(/.test(ask), 'the ask route is the parse-triggering arm');
  assert.ok(!/PdfParser\.parse\b/.test(routes), 'the route layer must never call the parser directly');
});
