// content-i18n.spec.mjs — bundled-content localization (contenti18n batch, 2026-09-15)
//
// 真渲染验收探针：**同一支探针跑两棵树**（判红纪律：「改前不可满足」）。
//
//   · CONTENTI18N_WEB_ROOT 未设 ⇒ 跑本仓 `src/main/resources/web`（**改后树**）
//     ⇒ 全部绿（含「改前红锚」逐条翻绿）。
//   · CONTENTI18N_WEB_ROOT=<改前树 web/>（`git archive HEAD` 出来的基线）⇒
//     红锚集（RED_ANCHORS）**逐条必须红** ⇒ 那就是「改前切语言三处列表文案不变」
//     在真渲染里的原文证据（每条红锚的 id + 读数逐行打印）。
//
// 判据口径（关键 ①）：**期望值取自本仓（改后树）的 en 字典这个固定参照**，与被
// 服务的那棵树无关——否则改前树会「用自己的 zh 值当期望」而假绿。红锚 = 「en 态
// 渲染文本 === en 译值」；改前树无译值 ⇒ 渲染仍是 zh ⇒ 逐条红。
//
// 判据口径（关键 ②）：面板一律经**应用自己的入口**打开（`openPlugins()` /
// `openProjectsTab()` / `openAgentDetail()`），**禁裸点活动栏按钮**——canvas 面板
// 按钮是 4 态开关（`canvas.js:372-377`），本应用**启动即已展开 plugins 面板**，
// 再点 #agents-btn 命中 state 2（toggle off）反而把面板关掉（实测：cardCount 1→0）。
//
// 覆盖：
//   · 三处列表渲染点（默认集条目）：插件卡片（plugins.js）／项目卡片（projectTab.js）／
//     agent 行（plugins.js 的 agent 摘要行 + agentManager 的详情面）
//   · 切 en ⇒ 文案随动；切回 zh-CN ⇒ 恢复（逐条目对照读数，禁只给一处）
//   · 回退链单测（仅改后树）：构造缺译夹具 ⇒ locale → en → 源字段，禁空白/键名/占位符
//   · 亮暗双主题各跑一轮（全站主题机制 = prefers-color-scheme 媒体查询，无 class 开关）
//
// 自包含：静态服务器随机隔离端口 + route 拦截；finally 必关（进程清理纪律）。
// Run:
//   node tests/content-i18n.spec.mjs
//   CONTENTI18N_WEB_ROOT=/tmp/ci18n-baseline/src/main/resources/web node tests/content-i18n.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const REPO_WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const WEB = process.env.CONTENTI18N_WEB_ROOT || REPO_WEB;
const MODE = process.env.CONTENTI18N_WEB_ROOT ? 'BEFORE' : 'AFTER';
const SHOTS = process.env.CONTENTI18N_SHOTS || join('/tmp', 'nb-contenti18n', MODE);
const SEED = join(WEB, '..', 'seed');
const SEED_SERVICE = join(WEB, '..', '..', '..', 'main', 'scala', 'nebflow', 'core', 'seed', 'SeedService.scala');

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 固定参照：本仓（改后树）的 en 字典 —— 期望值与被服务的树解耦 ─────────────
const EN_REF = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'en.js')).href)).default;

// ── 默认集 8 条目：现取 seed 真值（禁凭记忆写文案）─────────────────────────
function loadDefaultSet() {
  const manifest = JSON.parse(readFileSync(join(SEED, 'manifest.json'), 'utf8'));
  const ss = readFileSync(SEED_SERVICE, 'utf8');
  const projectName = ss.match(/GeneralProjectName\s*(?::\s*String\s*)?=\s*"([^"]+)"/)[1];
  const projectDesc = ss.match(/ProjectStore\.create\(\s*name,\s*workspace,\s*Some\("([^"]*)"\)/)[1];
  const out = { plugins: [], agents: [], projects: [] };
  for (const item of manifest.items) {
    const [kind, name] = item.split(':');
    if (kind === 'plugins') {
      const j = JSON.parse(readFileSync(join(SEED, 'plugins', name, 'plugin.json'), 'utf8'));
      out.plugins.push({ id: name, name: j.name, desc: j.description, version: j.version || '1.0.0' });
    } else if (kind === 'agents') {
      const j = JSON.parse(readFileSync(join(SEED, 'agents', name, 'agent.json'), 'utf8'));
      out.agents.push({ id: name, name: j.name, desc: j.description });
    } else if (kind === 'project') {
      out.projects.push({ id: name, name: projectName, desc: projectDesc });
    }
  }
  return out;
}
const DS = loadDefaultSet();
const DS_TOTAL = DS.plugins.length + DS.agents.length + DS.projects.length;

const expDesc = (kind, id, src) => EN_REF[`content.${kind}.${id}.desc`] ?? src;
const expName = (kind, id, src) => EN_REF[`content.${kind}.${id}.name`] ?? src;

/** 红锚 = 「en 态文案必须随动」的断言；BEFORE 树逐条必须红。
 *  name 槽不入红锚集：默认集条目名是语言中立的 ASCII id（两方言同值），
 *  它作**护栏**（不得被污染），红/绿判别只在可翻译的描述槽上成立。 */
const RED_ANCHORS = new Set();
for (const p of DS.plugins) RED_ANCHORS.add(`E-plugin-desc-${p.id}`);
for (const a of DS.agents) RED_ANCHORS.add(`E-agent-desc-${a.id}`);
for (const p of DS.projects) RED_ANCHORS.add(`E-project-desc-${p.id}`);
RED_ANCHORS.add(`E-detail-desc-${DS.agents[0].id}`);

let currentTheme = 'light';
const results = [];
function ok(id, name, cond, extra = '') {
  results.push({ id, theme: currentTheme, pass: !!cond, name, extra });
  const tag = RED_ANCHORS.has(id) ? 'RED-ANCHOR' : 'guardrail';
  console.log(`  ${cond ? 'PASS' : 'FAIL'}  [${id}/${currentTheme}] (${tag}) ${name}${extra ? '  — ' + extra : ''}`);
}
function skip(id, name, why) {
  results.push({ id, theme: currentTheme, pass: true, skipped: true, name, extra: why });
  console.log(`  SKIP  [${id}/${currentTheme}] ${name}  — ${why}`);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 桩（服务端真值形状；一个 handler 内按 path 分派）──────────────────────
function installRoutes(page) {
  return page.route('**/api/**', async r => {
    const p = decodeURIComponent(new URL(r.request().url()).pathname);
    if (p === '/api/plugins') {
      return r.fulfill({ json: {
        plugins: DS.plugins.map(x => ({
          name: x.name, version: x.version, description: x.desc, author: { name: 'Nebflow' },
          trusted: true, blocked: false, contentChanged: false,
        })),
        rejected: [],
      } });
    }
    if (p === '/api/agents') {
      return r.fulfill({ json: { agents: DS.agents.map(x => ({ name: x.name, description: x.desc, layer: 'global', category: 'standalone' })) } });
    }
    if (/^\/api\/agents\/[^/]+\/model$/.test(p)) return r.fulfill({ json: {} });
    if (/^\/api\/agents\/[^/]+$/.test(p)) {
      const id = decodeURIComponent(p.split('/')[3]);
      const a = DS.agents.find(x => x.id === id);
      return r.fulfill({ json: a ? { name: a.name, description: a.desc, systemPrompt: '' } : {} });
    }
    if (p === '/api/projects') {
      return r.fulfill({ json: { projects: DS.projects.map(x => ({ name: x.name, workspace: '/tmp/ws', description: x.desc })) } });
    }
    if (/\/flow-map$/.test(p)) return r.fulfill({ json: { nodes: [], worktrees: [], meta: {} } });
    return r.fulfill({ json: {} });
  });
}

function installWs(page) {
  return page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
}

const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

async function shot(page, theme, name) {
  try {
    await mkdir(join(SHOTS, theme), { recursive: true });
    await page.screenshot({ path: join(SHOTS, theme, name + '.png') });
  } catch { /* 证据截图不是判据 */ }
}

// ── 面板驱动：一律走应用自己的入口（禁裸点 4 态开关的活动栏按钮）─────────────
const openPluginsPanel = (page) => page.evaluate(async () => { (await import('/js/plugins.js')).openPlugins(); });
const openProjectsPanel = (page) => page.evaluate(async () => { (await import('/js/projectTab.js')).openProjectsTab(); });
const openAgentDetailPane = (page, id) => page.evaluate(async (i) => { await (await import('/js/agentManager.js')).openAgentDetail(i); }, id);
const setLocale = (page, code) => page.evaluate(async (c) => { const m = await import('/js/i18n.js'); m.setLocale(c); return m.getLocale(); }, code);

const waitPluginsRendered = (page) => page.waitForFunction(() => {
  const c = document.getElementById('plugins-content');
  return !!c && c.isConnected && !!c.querySelector('.plugins-card') && !c.querySelector('.plugins-loading');
}, null, { timeout: 15000 });
const waitProjectsRendered = (page) => page.waitForFunction(() => {
  const s = document.querySelector('.team-scroll');
  return !!s && s.isConnected && !!s.querySelector('.project-card') && s.dataset.projectsState === 'ready';
}, null, { timeout: 15000 });

/** 读三处列表渲染点的真文本 + 当前 locale。 */
const readDom = (page) => page.evaluate(() => {
  const txt = el => (el ? el.textContent : null);
  const firstText = el => {
    if (!el) return null;
    for (const n of el.childNodes) if (n.nodeType === 3 && n.textContent.trim()) return n.textContent.trim();
    return el.textContent.trim();
  };
  return {
    locale: localStorage.getItem('nebflow_locale'),
    pluginCards: [...document.querySelectorAll('#plugins-content .plugins-card')].map(c => ({
      name: txt(c.querySelector('.plugins-card-name')),
      desc: txt(c.querySelector('.plugins-card-desc')),
    })),
    agentRows: [...document.querySelectorAll('#plugins-content .plugins-agent-row')].map(r => ({
      name: txt(r.querySelector('.plugins-agent-name')),
      desc: txt(r.querySelector('.plugins-agent-desc')),
    })),
    projectCards: [...document.querySelectorAll('.team-scroll .project-card')].map(c => ({
      name: firstText(c.querySelector('.team-card-title')),
      desc: txt(c.querySelector('.project-field-value.desc')),
    })),
    detail: {
      name: txt(document.querySelector('.agent-detail-name')),
      desc: txt(document.querySelector('.agent-detail-desc')),
    },
  };
});

const browser = await chromium.launch();
let exitCode = 0;
try {
  for (const theme of ['light', 'dark']) {
    currentTheme = theme;
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 960 }, colorScheme: theme });
    try {
      await ctx.addInitScript(() => {
        localStorage.setItem('nebflow_token', 't');
        localStorage.setItem('nebflow_locale', 'zh-CN');
      });
      const page = await ctx.newPage();
      const pageErrors = [];
      page.on('pageerror', e => pageErrors.push(e.message));
      await installRoutes(page);
      await installWs(page);

      await page.goto(BASE + '/index.html');
      await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 20000 });
      await sleep(1500);

      const themeProbe = await page.evaluate(() => ({
        scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
        bg: getComputedStyle(document.body).backgroundColor,
      }));
      console.log(`  [theme=${theme}] prefers-color-scheme=${themeProbe.scheme} body.bg=${themeProbe.bg}`);
      ok(`P-theme-${theme}`, `本轮主题读数 = ${theme}（证明亮暗双轮确实是两个主题）`,
        themeProbe.scheme === theme, `scheme=${themeProbe.scheme} bg=${themeProbe.bg}`);

      // ══ ① 插件页（三处之一：插件卡片 + agent 摘要行）═══════════════════
      await openPluginsPanel(page);
      await waitPluginsRendered(page);
      await sleep(500);
      const zhView = await readDom(page);
      await shot(page, theme, '01-plugins-zh');

      ok(`P-plugins-card-count`, `插件页渲染出 ${zhView.pluginCards.length} 个插件卡片（期望 ≥ ${DS.plugins.length}）`,
        zhView.pluginCards.length >= DS.plugins.length);
      ok(`P-agent-row-count`, `插件页渲染出 ${zhView.agentRows.length} 个 agent 行（期望 ≥ ${DS.agents.length}）`,
        zhView.agentRows.length >= DS.agents.length);

      let zhCardOk = 0;
      for (const p of DS.plugins) {
        const got = zhView.pluginCards.find(c => c.name === p.name);
        if (got && got.desc === p.desc) zhCardOk++;
        else ok(`P-zh-card-${p.id}`, `zh 态插件卡片文案 = 服务端原值（${p.id}）`, false, `got=${JSON.stringify(got?.desc?.slice(0, 40))}`);
      }
      ok(`P-zh-cards-verbatim`, `zh 态 ${zhCardOk}/${DS.plugins.length} 插件卡片文案逐字等于服务端原值`,
        zhCardOk === DS.plugins.length);

      // ── 切 en：三处列表必须随动（红锚）────────────────────────────────
      await setLocale(page, 'en');
      await waitPluginsRendered(page);
      await sleep(600);
      const enView = await readDom(page);
      await shot(page, theme, '02-plugins-en');
      ok('S-locale-en', '切 en 后方言 = en', enView.locale === 'en', `locale=${enView.locale}`);

      for (const p of DS.plugins) {
        const got = enView.pluginCards.find(c => c.name === expName('plugin', p.id, p.name)) || enView.pluginCards.find(c => c.name === p.name);
        ok(`E-plugin-name-${p.id}`, `en 态插件名 = 条目 id（语言中立，两方言同值）`,
          !!got && got.name === expName('plugin', p.id, p.name), `got=${JSON.stringify(got?.name)}`);
        ok(`E-plugin-desc-${p.id}`, `en 态插件描述随动（${p.id}）`,
          !!got && got.desc === expDesc('plugin', p.id, p.desc),
          `got=${JSON.stringify(got?.desc?.slice(0, 60))} want=${JSON.stringify(expDesc('plugin', p.id, p.desc).slice(0, 60))}`);
      }

      for (const a of DS.agents) {
        const got = enView.agentRows.find(r => r.name === expName('agent', a.id, a.name)) || enView.agentRows.find(r => r.name === a.name);
        ok(`E-agent-name-${a.id}`, `en 态 agent 行名 = 条目 id（语言中立，两方言同值）`,
          !!got && got.name === expName('agent', a.id, a.name), `got=${JSON.stringify(got?.name)}`);
        ok(`E-agent-desc-${a.id}`, `en 态 agent 行描述随动（${a.id}）`,
          !!got && got.desc === expDesc('agent', a.id, a.desc),
          `got=${JSON.stringify(got?.desc?.slice(0, 60))} want=${JSON.stringify(expDesc('agent', a.id, a.desc).slice(0, 60))}`);
      }

      // ── 切回 zh-CN：恢复 ───────────────────────────────────────────────
      await setLocale(page, 'zh-CN');
      await waitPluginsRendered(page);
      await sleep(600);
      const backView = await readDom(page);
      let restoreOk = 0;
      for (const p of DS.plugins) {
        const got = backView.pluginCards.find(c => c.name === p.name);
        if (got && got.desc === p.desc) restoreOk++;
      }
      ok(`R-plugins-restore`, `切回 zh-CN 后 ${restoreOk}/${DS.plugins.length} 插件卡片文案恢复服务端原值`,
        restoreOk === DS.plugins.length);

      // ══ ② 项目页（三处之二）════════════════════════════════════════════
      await openProjectsPanel(page);
      await waitProjectsRendered(page);
      await setLocale(page, 'en');
      await sleep(1500);
      const projEn = await readDom(page);
      await shot(page, theme, '03-projects-en');
      for (const p of DS.projects) {
        const got = projEn.projectCards.find(c => c.name === expName('project', p.id, p.name)) || projEn.projectCards.find(c => c.name === p.name);
        ok(`E-project-name-${p.id}`, `en 态项目名 = 条目 id（语言中立，两方言同值）`,
          !!got && got.name === expName('project', p.id, p.name), `got=${JSON.stringify(got?.name)}`);
        ok(`E-project-desc-${p.id}`, `en 态项目描述随动（${p.id}）`,
          !!got && got.desc === expDesc('project', p.id, p.desc),
          `got=${JSON.stringify(got?.desc)} want=${JSON.stringify(expDesc('project', p.id, p.desc))}`);
      }
      await setLocale(page, 'zh-CN');
      await sleep(1500);
      const projZh = await readDom(page);
      const projRestore = DS.projects.every(p => {
        const got = projZh.projectCards.find(c => c.name === p.name);
        return got && got.desc === p.desc;
      });
      ok('R-projects-restore', '切回 zh-CN 后项目卡片文案恢复服务端原值', projRestore,
        JSON.stringify(projZh.projectCards.map(c => c.desc)));

      // ══ ③ agent 详情面（三处之三：agentManager renderAgentDetail）═══════
      await openPluginsPanel(page);
      await waitPluginsRendered(page);
      const firstAgent = DS.agents[0];
      await openAgentDetailPane(page, firstAgent.id);
      await page.waitForSelector('.agent-detail-desc', { timeout: 15000 });
      await sleep(600);
      const detailZh = await readDom(page);
      await shot(page, theme, '04-agent-detail-zh');
      ok('P-detail-zh', `zh 态 agent 详情面文案 = 服务端原值（${firstAgent.id}）`,
        detailZh.detail.desc === firstAgent.desc && detailZh.detail.name === firstAgent.name,
        `name=${JSON.stringify(detailZh.detail.name)} desc=${JSON.stringify(detailZh.detail.desc?.slice(0, 40))}`);

      await setLocale(page, 'en');
      await sleep(1800);
      const detailEn = await readDom(page);
      await shot(page, theme, '05-agent-detail-en');
      ok(`E-detail-name-${firstAgent.id}`, `en 态 agent 详情面名 = 条目 id（两方言同值）`,
        detailEn.detail.name === expName('agent', firstAgent.id, firstAgent.name),
        `got=${JSON.stringify(detailEn.detail.name)}`);
      ok(`E-detail-desc-${firstAgent.id}`, `en 态 agent 详情面描述随动（${firstAgent.id}）`,
        detailEn.detail.desc === expDesc('agent', firstAgent.id, firstAgent.desc),
        `got=${JSON.stringify(detailEn.detail.desc?.slice(0, 60))} want=${JSON.stringify(expDesc('agent', firstAgent.id, firstAgent.desc).slice(0, 60))}`);
      await setLocale(page, 'zh-CN');
      await sleep(1200);

      // ══ ④ 回退链单测：locale → en → 源字段（in-memory 夹具，禁改生产数据）══
      const fb = await page.evaluate(async () => {
        let mod;
        try { mod = await import('/js/contentI18n.js'); } catch { return { available: false }; }
        const zhMod = (await import('/js/locales/zh-CN.js')).default;
        const enMod = (await import('/js/locales/en.js')).default;
        const i18n = await import('/js/i18n.js');
        const out = { available: true };

        i18n.setLocale('en');
        out.missingReturnsSource = mod.contentText('plugin', '__no_such_entry__', 'desc', '源字段原值');
        out.missingEmpty = mod.contentText('plugin', '__no_such_entry__', 'desc', '');

        i18n.setLocale('zh-CN');
        const KEY = 'content.agent.general.desc';
        const savedZh = zhMod[KEY];
        const savedEn = enMod[KEY];
        delete zhMod[KEY];
        out.enFallback = mod.contentText('agent', 'general', 'desc', '源字段原值');
        out.enFallbackWant = savedEn;
        zhMod[KEY] = savedZh;

        delete zhMod[KEY]; delete enMod[KEY];
        out.bothMissing = mod.contentText('agent', 'general', 'desc', '源字段原值');
        zhMod[KEY] = savedZh; enMod[KEY] = savedEn;
        out.restoredOk = zhMod[KEY] === savedZh && enMod[KEY] === savedEn;

        out.zhHit = mod.contentText('agent', 'general', 'desc', '源字段原值');
        out.zhHitWant = savedZh;
        return out;
      });
      if (!fb.available) {
        for (const id of ['F-source-fallback', 'F-source-empty', 'F-en-fallback', 'F-both-missing', 'F-fixture-restored', 'F-zh-hit'])
          skip(id, '回退链单测', '改前树无 js/contentI18n.js（该模块本批新增）');
      } else {
        ok('F-source-fallback', '回退链末步 = 源字段（无译值 ⇒ 返回服务端原值，禁空白/禁键名）',
          fb.missingReturnsSource === '源字段原值', JSON.stringify(fb.missingReturnsSource));
        ok('F-source-empty', '回退链末步对空源值返回空串（不抛、不露出键名）',
          fb.missingEmpty === '', JSON.stringify(fb.missingEmpty));
        ok('F-en-fallback', '回退链中步 = en（本方言缺译 ⇒ 取 en 值）',
          fb.enFallback === fb.enFallbackWant, `got=${JSON.stringify(fb.enFallback?.slice(0, 50))} want=${JSON.stringify(fb.enFallbackWant?.slice(0, 50))}`);
        ok('F-both-missing', '本方言与 en 双缺 ⇒ 源字段', fb.bothMissing === '源字段原值', JSON.stringify(fb.bothMissing));
        ok('F-fixture-restored', '夹具还原（in-memory，生产数据零触碰）', fb.restoredOk === true);
        ok('F-zh-hit', '本方言命中 ⇒ 取本方言（不回退）', fb.zhHit === fb.zhHitWant, JSON.stringify(fb.zhHit?.slice(0, 40)));
      }

      // ══ ⑤ 护栏：渲染文本无空白/键名露出/占位符；零 pageerror ═══════════
      const allTexts = [...enView.pluginCards.flatMap(c => [c.name, c.desc]),
        ...enView.agentRows.flatMap(r => [r.name, r.desc]),
        ...enView.projectCards.flatMap(c => [c.name, c.desc])].filter(Boolean);
      const badTexts = allTexts.filter(t =>
        t.trim() === '' || t.trim() === 'undefined' || /^content\.[a-z]+\.[\w-]+\.(name|desc)$/.test(t.trim()) || /\{\w+\}/.test(t));
      ok('G-no-bad-text', 'en 态渲染文本零空白 / 零键名露出 / 零占位符残留',
        badTexts.length === 0, JSON.stringify(badTexts.slice(0, 5)));
      ok('G-no-pageerror', '真渲染全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 3)));
      await shot(page, theme, '06-final');
    } finally {
      await ctx.close();
    }
  }
} finally {
  await browser.close();
  await new Promise(r => server.close(r));
}

// ── 判决 ────────────────────────────────────────────────────────────────────
const redResults = results.filter(r => RED_ANCHORS.has(r.id));
const guardResults = results.filter(r => !RED_ANCHORS.has(r.id));
const redFailed = redResults.filter(r => !r.pass).length;
const guardFailed = guardResults.filter(r => !r.pass).length;

console.log(`\n══ MODE=${MODE} (themes: light + dark) ══`);
console.log(`  served tree : ${WEB}`);
console.log(`  red-anchors : ${redResults.length - redFailed}/${redResults.length} pass (${redFailed} red)`);
console.log(`  guardrails  : ${guardResults.length - guardFailed}/${guardResults.length} pass`);
console.log(`  screenshots : ${SHOTS}`);

if (MODE === 'AFTER') {
  const okAll = redFailed === 0 && guardFailed === 0;
  console.log(okAll ? 'RESULT: GREEN — 改后树全体断言通过（红锚逐条翻绿）' : 'RESULT: NOT GREEN');
  exitCode = okAll ? 0 : 1;
} else {
  const okRed = redFailed === redResults.length;
  console.log(okRed
    ? 'RESULT: RED-AS-EXPECTED — 改前树红锚逐条红（切语言三处列表文案不变，已复现）'
    : `RESULT: UNEXPECTED — 改前树有 ${redResults.length - redFailed} 条红锚意外为绿`);
  exitCode = (okRed && guardFailed === 0) ? 0 : 1;
}
try {
  await mkdir(SHOTS, { recursive: true });
  await writeFile(join(SHOTS, 'probe-results.json'),
    JSON.stringify({ mode: MODE, servedTree: WEB, results }, null, 2) + '\n');
} catch { /* 读数落盘失败不影响判决 */ }
process.exit(exitCode);
