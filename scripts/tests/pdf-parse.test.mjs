// pdf-parse.test.mjs — the PDF note/ask parser leg (scripts/tests, node --test).
//
// What this covers that the Scala suite cannot cheaply cover on its own:
//   · the parser script's own output contract (reading order, tables, media with
//     page attribution, relative image links);
//   · the failure vocabulary (exit code 3 + one JSON line, one code per cause);
//   · the JVM/script version parity fact (MARKDOWN_VERSION in Python vs
//     PdfParser.MarkdownVersion in Scala) — the same number in two languages.
//
// The fixtures are real PDFs written by an independent writer
// (scripts/tests/pdf_fixture.py, PyMuPDF). No PDF is committed.

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
const PYTHON = process.env.STAND_PDF_PYTHON || 'python3';

const tmp = mkdtempSync(join(tmpdir(), 'pdf-parse-test-'));
const fixtures = join(tmp, 'fixtures');
mkdirSync(fixtures, { recursive: true });
execFileSync(PYTHON, [FIXTURE, fixtures]);

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

test('parser: reading order, tables, media attribution and relative links', () => {
  const out = join(tmp, 'entry-ok');
  const r = runParser(['--input', join(fixtures, 'simple.pdf'), '--out', out, '--name', 'simple.pdf']);
  assert.equal(r.status, 0, `parser must succeed: ${r.stdout}`);
  const verdict = jsonLine(r.lines);
  assert.equal(verdict.ok, true);
  assert.equal(verdict.page_count, 3);

  // Layout is the contract: meta.json + pages/pNNNN.md + media/pNNNN-i.ext.
  assert.ok(existsSync(join(out, 'meta.json')));
  assert.deepEqual(readdirSync(join(out, 'pages')).sort(), ['p0001.md', 'p0002.md', 'p0003.md']);
  assert.deepEqual(readdirSync(join(out, 'media')), ['p0003-0.png']);

  const meta = JSON.parse(readFileSync(join(out, 'meta.json'), 'utf8'));
  assert.equal(meta.schema, 1);
  assert.equal(meta.source_name, 'simple.pdf');
  assert.equal(meta.pages, undefined);
  assert.equal(meta.page_count, 3);
  assert.deepEqual(meta.page_names, ['p0001.md', 'p0002.md', 'p0003.md']);
  assert.equal(meta.parser.name, 'pymupdf');
  assert.match(meta.parser.version, /^\d+\./);
  assert.equal(meta.markdown_version, 1);
  assert.equal(meta.image_count, 1);
  assert.equal(meta.media.length, 1);
  assert.equal(meta.media[0], 'media/p0003-0.png');
  assert.ok(typeof meta.generated_at_ms === 'number' && meta.generated_at_ms > 0);
  assert.match(meta.source_sha256, /^[0-9a-f]{64}$/);

  // Reading order comes from geometry, not from insertion order: the fixture
  // inserts "Second paragraph" BEFORE "First paragraph".
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

test('parser: failures are one JSON line with a distinct code, and write nothing', () => {
  const cases = [
    ['encrypted.pdf', 'PDF_ENCRYPTED'],
    ['empty.pdf', 'PDF_EMPTY'],
    ['nope.pdf', 'PDF_NOT_FOUND'],
  ];
  for (const [name, code] of cases) {
    const out = join(tmp, `entry-${code}`);
    const r = runParser(['--input', join(fixtures, name), '--out', out]);
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
  const rb = runParser(['--input', join(fixtures, 'simple.pdf'), '--out', busy]);
  assert.equal(rb.status, 3);
  assert.equal(jsonLine(rb.lines).error, 'OUT_NOT_EMPTY');
});

test('parser: usage errors exit 2', () => {
  const r = runParser(['--input', join(fixtures, 'simple.pdf')]);
  assert.equal(r.status, 2);
});

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

