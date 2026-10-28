// mcp-roundtrip.mjs — MCP stdio 首跑证据采集器（模拟 PluginMcpManager.runtimeConfig 的拉起方式）
// 用法: node mcp-roundtrip.mjs <pkgDir> <dbmRoot> <transcriptPath> [scenario=basic|full]
// env 复刻引擎行为（PluginMcpManager.scala:107-120）：PLUGIN_ROOT / PLUGIN_DATA 注入，
// env 值中 ${PLUGIN_DATA} 单次展开（mcp.json 声明 DBM_ROOT=${PLUGIN_DATA}）。
import { spawn } from 'node:child_process';
import { appendFileSync, writeFileSync } from 'node:fs';

const [pkgDir, dbmRoot, transcript, scenario = 'basic'] = process.argv.slice(2);
if (!pkgDir || !dbmRoot || !transcript) {
  console.error('usage: node mcp-roundtrip.mjs <pkgDir> <dbmRoot> <transcript> [basic|full]');
  process.exit(2);
}
writeFileSync(transcript, '');

const log = (dir, obj) => {
  appendFileSync(transcript, `${dir} ${JSON.stringify(obj)}\n`);
};

const child = spawn('node', ['./vendor/dbm/src/mcp.js'], {
  cwd: pkgDir, // 引擎：cwd 缺省 → 插件根（${PLUGIN_ROOT} 展开结果）
  env: {
    ...process.env,
    PLUGIN_ROOT: pkgDir,                       // 引擎强制注入（§9.1）
    PLUGIN_DATA: dbmRoot,                      // 引擎强制注入 = <dataRoot>/plugin-data/<plugin>/
    DBM_ROOT: dbmRoot,                         // mcp.json env {"DBM_ROOT":"${PLUGIN_DATA}"} 的展开结果
  },
});
child.stderr.on('data', (d) => log('STDERR', { line: d.toString().trim() }));
child.on('error', (e) => { console.error('SPAWN-ERR', e.message); process.exit(1); });

let buf = '';
const pending = new Map();
child.stdout.on('data', (d) => {
  buf += d;
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i);
    buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { log('<<RAW', { line }); continue; }
    log('<<', msg);
    const p = pending.get(msg.id);
    if (p) { pending.delete(msg.id); p(msg); }
  }
});

let nextId = 1;
function request(method, params) {
  const id = nextId++;
  const msg = { jsonrpc: '2.0', id, method, params };
  log('>>', msg);
  return new Promise((resolve) => {
    pending.set(id, resolve);
    child.stdin.write(JSON.stringify(msg) + '\n');
  });
}
function notify(method, params) {
  const msg = { jsonrpc: '2.0', method, params };
  log('>>', msg);
  child.stdin.write(JSON.stringify(msg) + '\n');
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 往返序列 ──────────────────────────────────────────────────────────────
const out = {};
await sleep(200);
const init = await request('initialize', {
  protocolVersion: '2024-11-05',
  capabilities: {},
  clientInfo: { name: 'nebflow-plugin-pilot', version: '0.1.0' },
});
out.serverInfo = init.result?.serverInfo;
out.protocolVersion = init.result?.protocolVersion;
notify('notifications/initialized');
await sleep(100);

const tools = await request('tools/list', {});
out.toolCount = tools.result?.tools?.length;
out.toolNames = tools.result?.tools?.map((t) => t.name);

const idents = await request('tools/call', { name: 'dbm_identity_list', arguments: {} });
out.identityList = idents.result?.structuredContent;

if (scenario === 'full') {
  // 首跑真实顺序：身份要显式建（工具不隐式建，2026-10-28 试点实测坑①）
  const mk = await request('tools/call', { name: 'dbm_identity_create', arguments: { identity: 'agent:pilot', displayName: 'mcp 首例试点' } });
  out.identityCreate = mk.result?.structuredContent ?? mk.result?.content?.[0]?.text;

  // 治理包路径用相对形态（服务进程 cwd = 插件根，path.resolve 语义）
  const pkgPath = 'vendor/governance/mini-package';
  const inst = await request('tools/call', { name: 'dbm_package_install', arguments: { path: pkgPath, identity: 'agent:pilot' } });
  out.packageInstall = inst.result?.structuredContent ?? inst.result?.content?.[0]?.text;

  const doc = await request('tools/call', {
    name: 'dbm_doc_add',
    arguments: {
      identity: 'agent:pilot',
      name: 'mcp-pilot-first-doc',
      description: 'dbm-memory 插件 MCP 首例往返的落库文档（2026-10-28 试点）',
      markdown: '## 结论\n\n首例 MCP 往返于 2026-10-28 由 dbm-memory 插件试点完成，stdio 拉起 + initialize/tools/list/tools/call 全链路走通。\n\n## 现状\n\nplugin-data 目录按规范创建，治理包 mini-package 安装成功，检索面可用。',
    },
  });
  out.docAdd = doc.result?.structuredContent ?? doc.result?.content?.[0]?.text;

  await sleep(300); // embedding(hash) 异步索引落盘
  const search = await request('tools/call', { name: 'dbm_search', arguments: { identity: 'agent:pilot', query: 'MCP 往返', mode: 'inverted', limit: 3 } });
  out.search = search.result?.structuredContent ?? search.result?.content?.[0]?.text;

  const sql = await request('tools/call', { name: 'dbm_sql', arguments: { identity: 'agent:pilot', query: 'SELECT name, kind, class FROM v_docs', limit: 10 } });
  out.sql = sql.result?.structuredContent ?? sql.result?.content?.[0]?.text;

  const stats = await request('tools/call', { name: 'dbm_stats', arguments: { identity: 'agent:pilot' } });
  out.stats = stats.result?.structuredContent;
}

child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 999, method: 'exit' }) + '\n');
child.kill();
setTimeout(() => {
  console.log(JSON.stringify(out, null, 2));
  process.exit(0);
}, 400);
