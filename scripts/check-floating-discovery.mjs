#!/usr/bin/env node
// Conservative change detector, not an inference of whether a positioned box
// is interactive chrome. Existing source is the review baseline; every new
// positioning selector or mobile DOM-construction line requires human review.
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const web = 'src/main/resources/web';
const baseline = '99a888915fef0374092afd8b0e37637a70841b74';
function files(dir, suffix) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const p = join(dir, e.name);
    return e.isDirectory() ? files(p, suffix) : e.name.endsWith(suffix) ? [p] : [];
  });
}
export function cssCandidates(source) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, ' ');
  return [...clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)].flatMap(m => {
    const pos = [...m[2].matchAll(/(?:^|;)\s*position\s*:\s*(fixed|absolute|sticky)\s*(?:!important)?\s*(?=;|$)/gi)];
    return pos.map(p => `${m[1].replace(/\s+/g, ' ').trim()} :: ${p[1].toLowerCase()}`);
  });
}
export function dynamicCandidates(source) {
  return source.split('\n').map(line => line.trim()).filter(line =>
    /(?:createElement(?:NS)?\s*\(|\.innerHTML\s*(?:\+?=)|\.insertAdjacentHTML\s*\(|\.outerHTML\s*=|\.className\s*=|\.classList\.(?:add|toggle)\s*\(|\.style\.(?:position|cssText)\s*=|\.style\.setProperty\s*\(\s*['"]position['"])/.test(line)
    && !/^(?:\/\/|\*|\/\*)/.test(line));
}
function added(current, old) {
  const counts = new Map();
  for (const x of old) counts.set(x, (counts.get(x) || 0) + 1);
  return current.filter(x => { const n = counts.get(x) || 0; if (n) { counts.set(x, n - 1); return false; } return true; });
}
export function audit({ content, previous, name, kind }) {
  const extract = kind === 'css' ? cssCandidates : dynamicCandidates;
  return added(extract(content), extract(previous)).map(x => `${name}: unreviewed ${kind === 'css' ? 'positioned selector' : 'dynamic DOM construction'}: ${x}`);
}
function run() {
  const css = files(join(root, web, 'css'), '.css');
  const js = files(join(root, web, 'js/mobile'), '.js');
  const errors = [];
  for (const path of [...css, ...js]) {
    const name = relative(root, path).split('\\').join('/');
    const result = spawnSync('git', ['show', `${baseline}:${name}`], { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    // New files have an empty reviewed baseline; missing commits are errors.
    if (result.status !== 0 && !/does not exist in|exists on disk, but not in/.test(result.stderr)) {
      errors.push(`${name}: cannot read discovery baseline ${baseline}: ${result.stderr.trim()}`); continue;
    }
    errors.push(...audit({ name, kind: path.endsWith('.css') ? 'css' : 'js', content: readFileSync(path, 'utf8'), previous: result.status === 0 ? result.stdout : '' }));
  }
  return errors;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const errors = process.argv[2] === '--pair'
    ? audit({ kind: process.argv[3], name: process.argv[5], previous: readFileSync(process.argv[4], 'utf8'), content: readFileSync(process.argv[5], 'utf8') })
    : run();
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('floating discovery ratchet: PASS');
}
