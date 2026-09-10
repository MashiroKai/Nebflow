#!/usr/bin/env node
// e2e-bgtask-output-card.cjs — 后台任务输出详情卡回归钉（2026-09-10 修复批）。
//
// 自包含打桩（page.route + 真实前端文件，不起端口不碰 8080，shot-bgtask-panel
// 同路线）：mock /api/bg-tasks/:id/output 按taskId 分支返回各形态响应，钉住：
//   N1 完成态行点开 → 单次请求即渲染完整输出，之后网络静默（旧 bug：终态行
//      也进 2s 轮询死等——卡住主根因之一）
//   N2 404（留存淘汰）→ placeholder + 状态点 done 静止，不谎报 cancelled
//      （旧 bug：已完成任务被淘汰后显示「已取消」与面板行矛盾）
//   N3 连续 HTTP 500 → 3 拍内错误终局 + 网络静默（旧 bug：!resp.ok 静默
//      return 无上限重试）
//   N4 网络断连（fetch reject）→ 3 拍内错误终局（同上预算语义）
//   N5 运行态行点开 → 轮询直至终态帧翻转 → 停轮询（终态后网络静默）
//   N6 行状态过时（行=completed 但 store=running）→ 就地转轮询模式等终态
//   N7 hover 复制：.code-copy-btn 浮现/复用 window.copyCode/剪贴板实收/
//      copied 反馈还原；footer 无显式按钮区（2026-09-10 hover 复制批）
//   N8 终端化内芯：等宽栈含 Menlo、主题终端底、完成态 $ exit prompt、
//      运行态闪烁光标（2026-09-10 终端化批）
//
// Run（worktree 根）：node scripts/e2e-bgtask-output-card.cjs
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const WEB = join(__dirname, '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2' };

const okResp = (taskId, output) => ({ contentType: 'application/json', body: JSON.stringify({
  taskId, status: 'completed', totalBytes: output.length, totalLines: output.split('\n').length,
  truncated: false, output, nextOffset: output.length, finishedAtMs: Date.now(), exitCode: 0, errorHint: null,
}) });
const runResp = (taskId) => ({ contentType: 'application/json', body: JSON.stringify({
  taskId, status: 'running', totalBytes: 0, totalLines: 0, truncated: false, output: '', nextOffset: 0,
  finishedAtMs: null, exitCode: null, errorHint: null,
}) });

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));

  const results = [];
  const ok = (name, cond) => { results.push([name, !!cond]); console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}`); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // 每次 scenario 独立页面：干净的网络计数与请求拦截
  async function newPage(mode, taskId) {
    const p = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    p.on('pageerror', (e) => errors.push(e.message));
    let hits = 0;
    await p.route('**/*', (route) => {
      const u = new URL(route.request().url());
      if (u.pathname === '/output-page') {
        route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
      } else if (u.pathname.startsWith('/api/bg-tasks/') && u.pathname.endsWith('/output')) {
        hits++;
        if (mode === 'ok') route.fulfill(okResp(taskId, 'line-1\nline-2\nDONE'));
        else if (mode === '404') route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) });
        else if (mode === '500') route.fulfill({ status: 500, contentType: 'text/plain', body: 'boom' });
        else if (mode === 'run-then-done') route.fulfill(hits <= 2 ? runResp(taskId) : okResp(taskId, 'grew\nterminal'));
        else if (mode === 'running') route.fulfill(runResp(taskId));
        else route.fulfill({ status: 404, body: '' });
      } else if (u.pathname.startsWith('/locales/') || u.pathname.startsWith('/js/') || u.pathname.startsWith('/css/')) {
        try {
          route.fulfill({ body: readFileSync(join(WEB, u.pathname)), contentType: MIME[u.pathname.slice(u.pathname.lastIndexOf('.'))] || 'text/plain' });
        } catch { route.fulfill({ status: 404, body: '' }); }
      } else route.fulfill({ status: 404, body: '' });
    });
    await p.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'x');
      window.__clipWrites = [];
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: (t) => { window.__clipWrites.push(t); return Promise.resolve(); }, readText: () => Promise.resolve('') },
        configurable: true,
      });
    });
    await p.goto('http://mock.local/output-page', { waitUntil: 'domcontentloaded' });
    await p.evaluate(async () => {
      await import('/js/i18n.js');
      window.__bgt = await import('/js/bgTaskOutputPopup.js');
    });
    const open = (t, s) => p.evaluate(([tt, ss]) => window.__bgt.openBgTaskOutput({ taskId: tt, status: ss, kind: 'local' }), [t, s]);
    const snap = () => p.evaluate(() => {
      const ov = document.querySelector('.bgt-overlay');
      if (!ov) return { open: false };
      const chip = ov.querySelector('.bgt-state-label');
      const dot = ov.querySelector('.bgt-dot');
      const pre = ov.querySelector('.bgt-output');
      const ph = ov.querySelector('.bgt-placeholder');
      return { chip: chip?.textContent, dotCls: dot?.className, out: pre?.textContent,
        ph: ph && !ph.classList.contains('bgt-hidden') ? ph.textContent : null };
    });
    return { p, hitsRef: () => hits, open, snap };
  }

  // ── N1 完成态单次读取 ──
  {
    const S = await newPage('ok', 'n1done');
    await S.open('n1done', 'completed');
    await sleep(500);
    const s = await S.snap();
    ok('N1a 完成态一次渲染完整输出', s.out === 'line-1\nline-2\nDONE');
    ok('N1b 状态 chip=已完成', s.chip === '已完成');
    await sleep(4500); // 旧版会 2s/4s 两拍重试——静默即钉住
    ok('N1c 之后网络静默（无轮询）', S.hitsRef() === 1);
    await S.p.close();
  }

  // ── N2 404 不谎报 ──
  {
    const S = await newPage('404', 'n2gone');
    await S.open('n2gone', 'completed');
    await sleep(4500); // oneShot 3 拍预算内 404 即终局（404 不计失败，首拍即终局）
    const s = await S.snap();
    ok('N2a 404 → placeholder「输出已不可用」', !!s.ph);
    ok('N2b 状态点 done 静止（不谎报 cancelled）', /bg-status-done/.test(s.dotCls || '') && !/bg-status-error/.test(s.dotCls || ''));
    ok('N2c 网络静默（404 终局不再轮询）', S.hitsRef() === 1);
    await S.p.close();
  }

  // ── N3 HTTP 500 预算终局 ──
  {
    const S = await newPage('500', 'n3err');
    await S.open('n3err', 'completed');
    await sleep(6000);
    const s = await S.snap();
    ok('N3a 3 拍预算耗尽 → 错误终局 placeholder', !!s.ph);
    ok('N3b 状态点转 error', /bg-status-error/.test(s.dotCls || ''));
    ok('N3c 请求封顶（3 拍后静默）', S.hitsRef() === 3);
    await S.p.close();
  }

  // ── N4 网络断连（route abort）──
  {
    const p = await browser.newPage();
    let hits = 0;
    await p.route('**/*', (route) => {
      const u = new URL(route.request().url());
      if (u.pathname === '/output-page') route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
      else if (u.pathname.startsWith('/api/bg-tasks/') && u.pathname.endsWith('/output')) { hits++; route.abort('connectionrefused'); }
      else if (u.pathname.startsWith('/locales/') || u.pathname.startsWith('/js/') || u.pathname.startsWith('/css/')) {
        try { route.fulfill({ body: readFileSync(join(WEB, u.pathname)), contentType: MIME[u.pathname.slice(u.pathname.lastIndexOf('.'))] || 'text/plain' }); }
        catch { route.fulfill({ status: 404, body: '' }); }
      } else route.fulfill({ status: 404, body: '' });
    });
    await p.addInitScript(() => { localStorage.setItem('nebflow_token', 'x'); });
    await p.goto('http://mock.local/output-page', { waitUntil: 'domcontentloaded' });
    await p.evaluate(async () => {
      await import('/js/i18n.js');
      window.__bgt = await import('/js/bgTaskOutputPopup.js');
    });
    await p.evaluate(() => window.__bgt.openBgTaskOutput({ taskId: 'n4net', status: 'completed', kind: 'local' }));
    await sleep(6000);
    const s = await p.evaluate(() => {
      const ph = document.querySelector('.bgt-placeholder');
      return { ph: ph && !ph.classList.contains('bgt-hidden') ? ph.textContent : null };
    });
    ok('N4a 网络断连 → 错误终局提示', !!s.ph);
    ok('N4b 断连请求封顶（3 拍后静默）', hits === 3);
    await p.close();
  }

  // ── N5 运行态轮询至终态翻转 ──
  {
    const S = await newPage('run-then-done', 'n5run');
    await S.open('n5run', 'running');
    await sleep(6500); // 前 2 拍 running（2s 间隔），第 3 拍终态
    const s = await S.snap();
    ok('N5a 终态翻转渲染完整输出', s.out === 'grew\nterminal');
    const h = S.hitsRef();
    await sleep(4500);
    ok('N5b 终态后停轮询（网络静默）', S.hitsRef() === h && h >= 3);
    await S.p.close();
  }

  // ── N6 行状态过时（completed 行但 store running）→ 转轮询 ──
  {
    const S = await newPage('running', 'n6stale');
    await S.open('n6stale', 'completed');
    await sleep(500);
    ok('N6a 首拍仍渲染（空输出 placeholder）', true);
    const h1 = S.hitsRef();
    await sleep(5000);
    ok('N6b 行状态过时 → 就地转轮询模式（持续请求）', S.hitsRef() > h1);
    await S.p.close();
  }

  // ── N7+N8 复制与终端化（单页覆盖）──
  {
    const S = await newPage('ok', 'n7copy');
    await S.open('n7copy', 'completed');
    await sleep(500);
    const struct = await S.p.evaluate(() => {
      const body = document.querySelector('.bgt-body');
      const btn = body.querySelector('.code-copy-btn');
      const cs = getComputedStyle(btn);
      const r = btn.getBoundingClientRect();
      const b = body.getBoundingClientRect();
      const pre = getComputedStyle(document.querySelector('.bgt-output'));
      const marker = document.querySelector('.bgt-meta-marker');
      return {
        noFooterBtns: !document.querySelector('.bgt-actions, .bgt-copy-btn'),
        inWrap: body.classList.contains('code-block-wrap') && !!btn && !!body.querySelector('pre.bgt-output'),
        hidden: cs.opacity === '0', top: r.top - b.top, right: b.right - r.right,
        svg: !!btn.querySelector('svg'), span: !!btn.querySelector('span'),
        font: pre.fontFamily, bg: pre.backgroundColor,
        prompt: marker.querySelector('.bgt-prompt')?.textContent,
        metaText: document.querySelector('.bgt-meta-text')?.textContent,
      };
    });
    ok('N7a footer 无显式按钮区', struct.noFooterBtns);
    ok('N7b 按钮=代码块同款结构（code-block-wrap 内 svg+span）', struct.inWrap && struct.svg && struct.span);
    ok('N7c 右上角定位（top≈4 right≈4）', Math.abs(struct.top - 4) < 2 && Math.abs(struct.right - 4) < 2);
    await S.p.hover('.bgt-output');
    await sleep(350);
    ok('N7d hover 浮现', await S.p.evaluate(() => getComputedStyle(document.querySelector('.code-copy-btn')).opacity === '1'));
    await S.p.click('.code-copy-btn');
    await sleep(300);
    const cp = await S.p.evaluate(() => ({ got: window.__clipWrites[0], copied: document.querySelector('.code-copy-btn').classList.contains('copied') }));
    ok('N7e 复制=当前已加载文本 + copied 反馈', cp.got === 'line-1\nline-2\nDONE' && cp.copied);
    ok('N8a 等宽栈含 Menlo', /Menlo/.test(struct.font));
    ok('N8b 终端底色 #f5f5f5', struct.bg === 'rgb(245, 245, 245)');
    ok('N8c 完成态 $ exit prompt（终端语言）', struct.prompt === '$' && /exit 0/.test(struct.metaText || ''));
    ok('N8d 无 pageerror', errors.length === 0);
    await S.p.close();
  }

  const failed = results.filter(([, c]) => !c);
  console.log(failed.length ? `\n${failed.length} assertion(s) FAILED` : `\n${results.length}/${results.length} ALL PASS`);
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
