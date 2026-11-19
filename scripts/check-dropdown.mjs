// High-confidence guard for native select creation and overrides of the shared menu.
// Existing native controls are temporary, exact file:line exceptions: remove each on migration.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const web = join(root, 'src/main/resources/web');
const exceptions = JSON.parse(readFileSync(join(root, 'scripts/dropdown-lint-exceptions.json'), 'utf8'));
const allowed = new Map();
let failures = 0;
for (const entry of exceptions) {
  const key = `${entry.file}:${entry.line}`;
  if (!entry.file || !Number.isInteger(entry.line) || entry.line < 1 || !entry.reason?.trim() || allowed.has(key)) {
    console.error(`FAIL invalid or duplicate exception: ${key}`); failures++; continue;
  }
  allowed.set(key, false);
}
function* files(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'vendor' && dir === web) continue; // Distributed third-party code is not our UI source.
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* files(path);
    else if (entry.isFile()) yield path;
  }
}
const native = /<select(?=[\s/>])|\b(?:document\s*\.\s*)?createElement\s*\(\s*['"`]select['"`]\s*\)/gi;
for (const path of files(web)) {
  const file = relative(web, path).split(sep).join('/');
  const ext = extname(path).toLowerCase();
  if (!['.js', '.mjs', '.html', '.htm', '.css'].includes(ext)) continue;
  const text = readFileSync(path, 'utf8');
  if (['.js', '.mjs', '.html', '.htm'].includes(ext)) {
    // Strip whole-line comments only; do not strip inline JS as templates may contain markup.
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (/^\s*(?:\/\/|\*|<!--)/.test(line)) continue;
      native.lastIndex = 0;
      let match;
      while ((match = native.exec(line))) {
        const key = `${file}:${i + 1}`;
        if (allowed.has(key) && !allowed.get(key)) allowed.set(key, true);
        else { console.error(`FAIL ${key}: native select construction (${match[0]})`); failures++; }
      }
    }
  }
  if (ext === '.css' && file !== 'css/projectPanel.css') {
    // High-confidence fork: styling the baseline menu or redeclaring its private tokens elsewhere.
    const css = text.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '));
    const fork = /\.project-open-menu\b|--dropdown-(?:min-width|item-height|icon-column|check-column|hover|focus)\s*:/g;
    let match;
    while ((match = fork.exec(css))) {
      const line = css.slice(0, match.index).split('\n').length;
      console.error(`FAIL ${file}:${line}: shared dropdown style fork (${match[0]})`); failures++;
    }
  }
}
for (const [key, used] of allowed) {
  if (!used) { console.error(`FAIL stale exception ${key}: remove it or update its exact location`); failures++; }
}
if (failures) { console.error(`dropdown lint: FAIL (${failures} violations)`); process.exitCode = 1; }
else console.log(`dropdown lint: PASS (${allowed.size} explicit legacy exceptions; vendor excluded)`);
