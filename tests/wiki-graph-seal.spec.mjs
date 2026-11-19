#!/usr/bin/env node
// wiki-graph-seal.spec.mjs — wiki-graph 边栏入口封存批（作者手写想法①
// 2026-10-29 逐字：「词典边栏的图谱入口暂时隐藏，因为目前感觉并没有很好用，
// 未来可能还要优化。但是保留词条有关的各种关联、引用的功能，需要保留」）。
//
// 覆盖（featureFlags.js wikiGraphEnabled() 语义，源码树直跑 = 无
// __NEBFLOW_RELEASE__；harness 同 friends-dev-default-on.spec.mjs）：
//   W1  空配置（configData 不含 features.wikiGraph）→ 封存缺省：#wiki-btn
//       **不在 DOM**（防复活钉）；「其余项在场」——files/projects/agents/
//       usage/settings 五入口全部在位，且 projects → agents 相邻（图谱静态槽
//       空出、边栏不塌）
//   W2  显式 features.wikiGraph:true → 入口回挂静态槽（前邻 projects、后邻
//       agents），i18n 标题就位 —— 恢复 = 配置加一行，零改码
// 范围边界（零触碰钉）：文件查看器图谱浮钮/移动端图谱页签是别的入口，本批
// 不动（不在本 spec 断言面）；词条关联引用链路不经本 flag。
// 自包含：route 拦截静态文件 + mock WS，无后端、无真实端口。
// Run: node tests/wiki-graph-seal.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

async function bootPage({ configJson = '{}' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 't'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: configJson, configured: true, onboarding: 'done', models: [], defaults: {} }));
    sendConfig();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, undefined, { timeout: 15000 });
  await sleep(600); // configData latch → 回挂窗口
  return { ctx, page, pageErrors };
}

// ── W1: 空配置 → 封存缺省（作者想法① 2026-10-29）：入口缺席 + 其余项在场 ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{}' });
  ok('W1 #wiki-btn absent (sealed default: 缺席 = OFF，作者想法① 2026-10-29)', await page.locator('#wiki-btn').count() === 0);
  // 「其余项在场」：边栏五入口全部连接在 DOM（布局不塌）。
  for (const id of ['files-btn', 'projects-btn', 'agents-btn', 'usage-btn', 'settings-btn']) {
    ok(`W1 #${id} present (其余项在场)`, await page.locator(`#${id}`).count() === 1 && await page.locator(`#${id}`).evaluate(el => el.isConnected));
  }
  // 槽位契约：图谱静态槽（projects 与 agents 之间）空出 —— projects 的下一
  // 元素兄弟即 agents（中间无残留/无错位）。
  const order = await page.locator('#activity-bar > *').evaluateAll(els => els.map(e => e.id || e.className));
  const pi = order.indexOf('projects-btn'), ai = order.indexOf('agents-btn');
  ok('W1 projects-btn → agents-btn adjacent (图谱槽空出、顺序不乱)', pi !== -1 && ai === pi + 1, JSON.stringify(order));
  ok('W1 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ── W2: 显式 true → 回挂静态槽（恢复 = 配置一行，零改码） ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{"features":{"wikiGraph":true}}' });
  ok('W2 #wiki-btn attached (explicit on)', await page.locator('#wiki-btn').count() === 1 && await page.locator('#wiki-btn').evaluate(el => el.isConnected));
  // 静态槽位契约：前邻 projects、后邻 agents（index.html 静态序的精确还原）。
  const order = await page.locator('#activity-bar > *').evaluateAll(els => els.map(e => e.id || e.className));
  const pi = order.indexOf('projects-btn'), wi = order.indexOf('wiki-btn'), ai = order.indexOf('agents-btn');
  ok('W2 slot = projects → wiki → agents (静态槽精确还原)', pi !== -1 && wi === pi + 1 && ai === wi + 1, JSON.stringify(order));
  const title = await page.locator('#wiki-btn').getAttribute('title');
  ok('W2 title applied (i18n wiki.tabTitle)', title === 'Wiki Graph' || title === '文档图谱', String(title));
  ok('W2 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
process.exit(failures ? 1 : 0);
