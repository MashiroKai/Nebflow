// make-fixtures.mjs — build the two REAL diff fixtures used by the survey prototypes.
//
// Fixture A (delegate diff): extracted verbatim from an engine-written file-changes
//   report on disk (<repo>/.nebflow/file-changes/*.json) — the exact payload shape
//   the Delegate canvas reader consumes.
// Fixture B (canvas ask diff): derived from a REAL git commit — baseText = the file
//   at commit^, currentText = the file at commit, hunks computed like the server
//   (maximal changed runs merged across a ≤2-line unchanged gap).
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = '/Users/kaiyu/Claude code/Stand';
mkdirSync(HERE, { recursive: true });

// ── Fixture A: delegate ─────────────────────────────────────────────────────
const dir = join(REPO, '.nebflow', 'file-changes');
const cands = [];
for (const f of readdirSync(dir)) {
  if (!f.endsWith('.json')) continue;
  let j; try { j = JSON.parse(readFileSync(join(dir, f), 'utf8')); } catch { continue; }
  if (!j || !Array.isArray(j.files)) continue;
  for (const x of j.files) {
    if (typeof x.diffText !== 'string' || !x.diffText) continue;
    // prefer source/text files (syntax highlighting is part of the comparison)
    if (!/\.(js|mjs|ts|css|scala|md|json|html)$/.test(x.path)) continue;
    const lines = x.diffText.split('\n');
    const maxLine = lines.reduce((m, l) => Math.max(m, l.length), 0);
    const longLines = lines.filter((l) => l.length > 110).length;
    const adds = lines.filter((l) => l.startsWith('+') && !l.startsWith('+++')).length;
    const dels = lines.filter((l) => l.startsWith('-') && !l.startsWith('---')).length;
    if (lines.length >= 34 && lines.length <= 80 && maxLine >= 110 && maxLine <= 260 && longLines >= 2 && adds >= 8 && dels >= 2) {
      cands.push({ report: f, nodeId: j.nodeId, path: x.path, changeType: x.changeType,
        added: x.added, removed: x.removed, source: x.source, lines: lines.length, maxLine, longLines, diffText: x.diffText });
    }
  }
}
console.log('DELEGATE CANDIDATES:', cands.length);
for (const c of cands) console.log('  ', c.report, c.path, 'lines', c.lines, 'maxLine', c.maxLine, 'long', c.longLines, '+', c.added, '-', c.removed);
// prefer a JS/CSS source file (highlighting), then most long lines
const rank = (c) => (/\.(js|mjs|css)$/.test(c.path) ? 1000 : 0) + c.longLines * 10 + Math.min(c.lines, 80);
cands.sort((a, b) => rank(b) - rank(a));
const A = cands[0];
if (!A) throw new Error('no delegate fixture candidate found');
const delegate = {
  nodeId: 'survey-delegate-fixture',
  generatedAt: 1794000000000,
  root: REPO,
  files: [{
    path: A.path, changeType: A.changeType || 'modified',
    added: A.added || 0, removed: A.removed || 0, source: A.source || 'git',
    diffText: A.diffText,
  }],
  _provenance: { kind: 'real-engine-file-changes-report', report: join(dir, A.report), nodeId: A.nodeId, path: A.path, lines: A.lines, maxLine: A.maxLine, longLines: A.longLines },
};

// ── Fixture B: ask (real commit → base/current/hunks) ───────────────────────
const gitShow = (rev, path) => execFileSync('git', ['-C', REPO, 'show', `${rev}:${path}`], { maxBuffer: 64 * 1024 * 1024 }).toString('utf8');

function opcodes(a, b) {
  const n = a.length, m = b.length;
  let p = 0; while (p < n && p < m && a[p] === b[p]) p++;
  let s = 0; while (s < n - p && s < m - p && a[n - 1 - s] === b[m - 1 - s]) s++;
  const mb = a.slice(p, n - s), mc = b.slice(p, m - s);
  const L = mb.length, M = mc.length;
  if (L * M > 9_000_000) return null;
  const dp = Array.from({ length: L + 1 }, () => new Int32Array(M + 1));
  for (let i = L - 1; i >= 0; i--) for (let j = M - 1; j >= 0; j--)
    dp[i][j] = mb[i] === mc[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const ops = []; let i = 0, j = 0;
  while (i < L && j < M) {
    if (mb[i] === mc[j]) { ops.push(['e', i, j]); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { ops.push(['d', i, j]); i++; }
    else { ops.push(['a', i, j]); j++; }
  }
  while (i < L) { ops.push(['d', i, j]); i++; }
  while (j < M) { ops.push(['a', i, j]); j++; }
  return { p, ops };
}
/** Hunks = changed runs merged across a ≤gap unchanged window; ranges contiguous
 *  (same slice semantics askEditReview relies on). */
function computeHunks(base, cur, gap = 2) {
  const r = opcodes(base.split('\n'), cur.split('\n'));
  if (!r) throw new Error('middle window too large');
  const { p, ops } = r;
  const H = []; let c = null, equalRun = 0, prevB = null, prevC = null;
  const close = () => { if (c) { H.push({ oldStart: c.oldStart, oldCount: c.oldEnd - c.oldStart + 1, newStart: c.newStart, newCount: c.newEnd - c.newStart + 1 }); c = null; } };
  for (const [op, oi, oj] of ops) {
    const absB = p + oi, absC = p + oj;
    if (op === 'e') { equalRun++; if (c && equalRun > gap) close(); prevB = absB; prevC = absC; continue; }
    equalRun = 0;
    if (!c) c = { oldStart: absB, newStart: absC, oldEnd: absB, newEnd: absC };
    else { c.oldEnd = Math.max(c.oldEnd, absB); c.newEnd = Math.max(c.newEnd, absC); }
    prevB = absB; prevC = absC;
  }
  close();
  // trim interior unchanged lines off hunk ends (keep the range tight)
  const bl = base.split('\n'), cl = cur.split('\n');
  for (const h of H) {
    while (h.oldCount > 1 && h.newCount > 1 && bl[h.oldStart] === cl[h.newStart]) { h.oldStart++; h.newStart++; h.oldCount--; h.newCount--; }
    while (h.oldCount > 1 && h.newCount > 1 && bl[h.oldStart + h.oldCount - 1] === cl[h.newStart + h.newCount - 1]) { h.oldCount--; h.newCount--; }
  }
  return H.filter((h) => h.oldCount > 0 && h.newCount > 0).map((h, k) => ({ id: 'h' + k, ...h, added: h.newCount, removed: h.oldCount }));
}

// Prefer a markdown commit (ask_edit targets documents); fall back to a JS commit.
const targets = [
  { path: 'README.md', glob: 'README.md' },
  { path: 'src/main/resources/web/js/diffCanvas.js', glob: 'src/main/resources/web/js/diffCanvas.js' },
];
let B = null;
for (const tgt of targets) {
  const log = execFileSync('git', ['-C', REPO, 'log', '--format=%H%x09%s', '-n', '600', '--', tgt.path], { maxBuffer: 16 * 1024 * 1024 }).toString('utf8').trim().split('\n');
  for (const entry of log) {
    const [sha, subject] = entry.split('\t');
    let base, cur;
    try { base = gitShow(`${sha}^`, tgt.path); cur = gitShow(sha, tgt.path); } catch { continue; }
    if (!base || !cur || base === cur) continue;
    const bl = base.split('\n');
    if (bl.length < 40 || bl.length > 400) continue;
    if (Math.max(...bl.map((l) => l.length)) < 100) continue;
    let hunks; try { hunks = computeHunks(base, cur); } catch { continue; }
    if (hunks.length < 2 || hunks.length > 6) continue;
    const totalChanged = hunks.reduce((a, h) => a + h.oldCount + h.newCount, 0);
    if (totalChanged < 40) continue;
    const maxHunk = Math.max(...hunks.map((h) => h.oldCount + h.newCount));
    if (maxHunk < 12) continue;
    B = { sha, subject, path: tgt.path, base, cur, hunks };
    break;
  }
  if (B) break;
}
if (!B) throw new Error('no ask fixture commit found');

const ask = {
  sessionId: 'ask-survey01', path: B.path, fileName: B.path.split('/').pop(),
  baseText: B.base, currentText: B.cur, hunks: B.hunks, count: B.hunks.length,
  _provenance: { kind: 'real-git-commit', repo: REPO, commit: B.sha, subject: B.subject, path: B.path, baseLines: B.base.split('\n').length, hunks: B.hunks.length, hunkMergeGap: 2 },
};

// ── Fixture C: ask-large (large real document → sync-render cost probe) ─────
let C = null;
{
  const log = execFileSync('git', ['-C', REPO, 'log', '--format=%H%x09%s', '-n', '800', '--', '*.md'], { maxBuffer: 32 * 1024 * 1024 }).toString('utf8').trim().split('\n');
  for (const entry of log) {
    const [sha, subject] = entry.split('\t');
    let files;
    try { files = execFileSync('git', ['-C', REPO, 'show', '--name-only', '--format=', sha], { maxBuffer: 32 * 1024 * 1024 }).toString('utf8').trim().split('\n').filter((x) => x.endsWith('.md')); } catch { continue; }
    for (const f of files) {
      let base, cur;
      try { base = gitShow(`${sha}^`, f); cur = gitShow(sha, f); } catch { continue; }
      if (!base || !cur || base === cur) continue;
      const bl = base.split('\n');
      if (bl.length < 900 || bl.length > 4000) continue;
      let hunks; try { hunks = computeHunks(base, cur); } catch { continue; }
      if (hunks.length < 1 || hunks.length > 8) continue;
      C = { sha, subject, path: f, base, cur, hunks };
      break;
    }
    if (C) break;
  }
}
if (!C) throw new Error('no ask-large fixture commit found');
const askLarge = {
  sessionId: 'ask-survey02', path: C.path, fileName: C.path.split('/').pop(),
  baseText: C.base, currentText: C.cur, hunks: C.hunks, count: C.hunks.length,
  _provenance: { kind: 'real-git-commit', repo: REPO, commit: C.sha, subject: C.subject, path: C.path, baseLines: C.base.split('\n').length, hunks: C.hunks.length },
};

writeFileSync(join(HERE, 'delegate.json'), JSON.stringify(delegate));
writeFileSync(join(HERE, 'ask.json'), JSON.stringify(ask));
writeFileSync(join(HERE, 'ask-large.json'), JSON.stringify(askLarge));
console.log('askLarge:', C.path, '@', C.sha.slice(0, 9), '| baseLines', C.base.split('\n').length, '| hunks', C.hunks.length);
console.log('delegate:', A.path, '| lines', A.lines, '| maxLine', A.maxLine, '| longLines', A.longLines, '|', A.added, '+', A.removed, '-', '| from', A.report);
console.log('ask     :', B.path, '@', B.sha.slice(0, 9), '|', B.subject, '| baseLines', B.base.split('\n').length, '| hunks', B.hunks.length, JSON.stringify(B.hunks));
