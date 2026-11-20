// server.mjs — gitviz MVP 本地开发服务（**dev-only**，不属于产品运行面）。
//
// 存在理由（本批纪律：生产 PID 56786 / :6138 不重启、不改配置、不碰生产数据）：
// 生产引擎从**主检出**的 classpath 提供静态树，本批代码在工作树里 ⇒ 生产不会提供它。
// 于是用一个本地只读 dev 服务把「同一批代码 + 真实 git 读数」跑起来给作者看：
//   · 静态面 = 工作树的 `src/main/resources/web/**`（**真** CSS/token/组件，零复制）；
//   · 数据面 = 与产品路由**同路径同契约**的 `GET /api/projects/<name>/git-graph`
//     （真实实现 = ./git-graph.mjs，即设计稿 P0 ① 的参考实现；零写面）；
//   · 项目清单 = 真实 `<dataRoot>/projects/*/project.json`（只读）。
// 落地后这三条分别由：引擎静态树、ProjectsRoutes 新增只读路由、引擎既有
// GET /api/projects 承担 —— 本文件届时整件退役。
//
// 🔴 只监听 127.0.0.1；不含任何写仓库的端点（唯一 POST = 本地转发日志，落在工作树
//    `.stand/tmp/gitviz-mvp-20261119/forward-log.jsonl`）。

import { createServer } from 'node:http';
import { readFile, readdir, mkdir, appendFile, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildGitGraph } from './git-graph.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WORKTREE = path.resolve(HERE, '../..');
const WEB_ROOT = path.join(WORKTREE, 'src/main/resources/web');
// 证据落点 = **主检出**的 `.stand/tmp/`（brief 指定路径；`.stand/tmp/` 在 .gitignore
// 内 ⇒ 不是对主检出的写入）。可用 GITVIZ_TMP_DIR 覆盖。
const EVIDENCE_DIR = process.env.GITVIZ_TMP_DIR || '/Users/kaiyu/Claude code/Stand/.stand/tmp/gitviz-mvp-20261119';
const DATA_ROOT = process.env.STAND_DATA_ROOT || '/Users/kaiyu/.stand';
const PORT = Number(process.env.GITVIZ_PORT || 8791);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.map': 'application/json; charset=utf-8',
};

async function projectRows() {
  const dir = path.join(DATA_ROOT, 'projects');
  const names = (await readdir(dir, { withFileTypes: true })).filter((d) => d.isDirectory()).map((d) => d.name);
  const rows = [];
  for (const name of names) {
    const p = path.join(dir, name, 'project.json');
    if (!existsSync(p)) continue;
    try {
      const j = JSON.parse(await readFile(p, 'utf8'));
      rows.push({
        name: j.name || name,
        workspace: j.workspace || '',
        description: j.description || '',
        device: j.device || 'self',
        mounted: existsSync(j.workspace || ''),
      });
    } catch (_) { /* 坏 json 跳过（只读探针） */ }
  }
  rows.sort((a, b) => a.name.localeCompare(b.name));
  return rows;
}

async function resolveWorkspace(name) {
  const rows = await projectRows();
  const hit = rows.find((r) => r.name === name);
  return hit?.workspace || '';
}

function json(res, code, body) {
  const s = JSON.stringify(body);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(s) });
  res.end(s);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const p = url.pathname;

  if (p === '/api/projects') {
    if (req.method !== 'GET') return json(res, 405, { error: 'read-only' });
    return json(res, 200, { projects: await projectRows() });
  }

  const m = /^\/api\/projects\/([^/]+)\/git-graph$/.exec(p);
  if (m) {
    if (req.method !== 'GET') return json(res, 405, { error: 'read-only' });
    const name = decodeURIComponent(m[1]);
    const workspace = await resolveWorkspace(name);
    if (!workspace) return json(res, 404, { error: `未挂载项目：${name}` });
    const t0 = Date.now();
    const g = buildGitGraph({
      root: workspace,
      project: name,
      limit: Number(url.searchParams.get("limit") || 1200),
      lanes: Number(url.searchParams.get('lanes') || 8),
      heatDays: Number(url.searchParams.get('heatDays') || 84),
    });
    g.serverMs = Date.now() - t0;
    return json(res, 200, g);
  }

  // 唯一的 POST：本地转发日志（只收集与展示；不落仓库、不写数据根）
  if (p === '/api/gitviz/forward' && req.method === 'POST') {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks).toString('utf8');
    await mkdir(EVIDENCE_DIR, { recursive: true });
    const line = JSON.stringify({ receivedAt: new Date().toISOString(), payload: JSON.parse(body || '{}') });
    await appendFile(path.join(EVIDENCE_DIR, 'forward-log.jsonl'), `${line}\n`, 'utf8');
    return json(res, 200, { logged: 1 });
  }

  if (p === '/' || p === '/index.html') {
    const file = path.join(HERE, 'harness.html');
    const s = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-store' });
    return res.end(s);
  }

  // 静态：工作树 web 树（真 CSS/组件）
  const rel = decodeURIComponent(p.replace(/^\/+/, ''));
  const abs = path.join(WEB_ROOT, rel);
  if (!abs.startsWith(WEB_ROOT)) { res.writeHead(403); return res.end('forbidden'); }
  try {
    const st = await stat(abs);
    if (!st.isFile()) throw new Error('not a file');
    const buf = await readFile(abs);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(abs)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
      'Content-Length': buf.length,
    });
    return res.end(buf);
  } catch (_) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end(`not found: ${rel}`);
  }
});

server.listen(PORT, '127.0.0.1', () => {
  // eslint-disable-next-line no-console
  console.log(`[gitviz-dev] http://127.0.0.1:${PORT}/  (web root: ${WEB_ROOT})`);
});
