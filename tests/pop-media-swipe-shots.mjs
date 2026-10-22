// pop-media-swipe-shots.mjs — 图片叠卡触控板横滑切换批验收证据（2026-10-22 作者令）。
//
// 真输入实拍：Playwright mouseWheel 在叠卡盒心派发双指横扫（= mac 触控板
// wheel deltaX 流），抓翻页前后两张实拍；亮暗 × 双侧（LLM Pop 叠卡 + 用户附件
// 叠卡）。断言随拍同帧：横扫翻页成立、纵滚不翻页、惯性尾巴不重复翻页。
//
// 用法：node tests/pop-media-swipe-shots.mjs
// 夹具形制同 tests/pop-media-trackpad-swipe.spec.mjs（pop-artifacts harness +
// 自起静态服务，永不碰宿主 :8080）。
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.join(HERE, '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';
const OUT = path.join(REPO_ROOT, 'evidence', '20261022_pop-media-trackpad-swipe__delegate-nebflow-f48de9fb', 'shots');
mkdirSync(OUT, { recursive: true });

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const port = await new Promise((resolve, reject) => {
  const srv = net.createServer();
  srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  srv.on('error', reject);
});
const server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', REPO_ROOT], { stdio: 'ignore' });
for (let i = 0; i < 60; i++) {
  try { const r = await fetch(`http://127.0.0.1:${port}${HARNESS_PATH}`); if (r.ok) break; } catch { /* not up yet */ }
  await new Promise(r => setTimeout(r, 100));
}

const browser = await chromium.launch();

async function boot(scheme) {
  // deviceScaleFactor 恒 1：Playwright 在 DSF≠1 时把 mouseWheel 的 delta 按设备
  // 比缩放（dx=10 实达 5），横滑累积量会被悄悄减半——本批证据要的是精确 delta。
  const page = await (await browser.newContext({ viewport: { width: 1200, height: 900 }, colorScheme: scheme })).newPage();
  page.on('pageerror', e => { console.log('PAGEERROR', String(e)); failures++; });
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return page;
}

const frontName = (page, sel) => page.evaluate((s) =>
  document.querySelector(s + ' .media-layer.layer-front .pop-img')?.dataset.nfName || '', sel);

async function park(page, view) {
  await view.scrollIntoViewIfNeeded();
  await page.waitForTimeout(120);
  const box = await view.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(60);
}
async function wheelSteps(page, { dx = 0, dy = 0, steps = 7, gapMs = 12 }) {
  for (let i = 0; i < steps; i++) { await page.mouse.wheel(dx, dy); if (i < steps - 1) await page.waitForTimeout(gapMs); }
}

for (const scheme of ['dark', 'light']) {
  /* ── 双侧 1：LLM Pop 叠卡（shot-1/2/3）── */
  const page = await boot(scheme);
  await page.evaluate(() => window.__popStack());
  await page.waitForFunction(() => document.querySelectorAll('#chat .pop-stack-view .media-layer').length === 3, null, { timeout: 10000 });
  await page.waitForTimeout(300);
  const view = page.locator('#chat .pop-stack-view');
  const row = page.locator('#chat > .row.pop-artifact');

  await park(page, view);
  ok(`[${scheme}] Pop 叠卡初始前景 = shot-1.png`, (await frontName(page, '#chat .pop-stack-view')) === 'shot-1.png');
  await row.screenshot({ path: path.join(OUT, `1-pop-${scheme}-before.png`) });

  // 横扫翻页 + 惯性尾巴连发（中间不插任何读数/截图——那会超出 160ms 动量窗），
  // 一次读数钉两条腿：70px 横扫翻了恰好一页（shot-2），紧接着的 64px 尾巴
  // 没有重复翻页（否则会是 shot-3）。
  await wheelSteps(page, { dx: 10, steps: 7 });          // 双指向左横扫（70px ≥ 56）
  await wheelSteps(page, { dx: 8, steps: 8 });           // 惯性尾巴再贡献 64px
  await page.waitForTimeout(120);
  const after = await frontName(page, '#chat .pop-stack-view');
  ok(`[${scheme}] 横扫翻一页且惯性尾巴不重复（shot-1 → shot-2）`, after === 'shot-2.png', `front=${after}`);
  await row.screenshot({ path: path.join(OUT, `2-pop-${scheme}-after-swipe.png`) });

  // 纵滚：先填出滚动余量，再验聊天列滚动、叠卡不动。
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    for (let i = 0; i < 30; i++) {
      const r = document.createElement('div');
      r.className = 'row ai';
      const b = document.createElement('div');
      b.className = 'bubble ai';
      b.textContent = '填充行 ' + i + ' —— 让聊天列出现纵向滚动条。';
      r.appendChild(b);
      chat.insertBefore(r, chat.firstChild);
    }
    chat.scrollTop = 0;
  });
  await wheelSteps(page, { dx: 0, dy: 80, steps: 5, gapMs: 10 });
  await page.waitForTimeout(120);
  const v = await page.evaluate(() => ({
    scrollTop: document.getElementById('chat').scrollTop,
    front: document.querySelector('#chat .pop-stack-view .media-layer.layer-front .pop-img')?.dataset.nfName || '',
  }));
  ok(`[${scheme}] 纵滚不翻页（前景仍 shot-2）`, v.front === 'shot-2.png', `front=${v.front}`);
  ok(`[${scheme}] 纵滚放行聊天列（scrollTop 0 → ${v.scrollTop}）`, v.scrollTop > 0);
  await page.close();

  /* ── 双侧 2：用户附件叠卡（u1/u2）── */
  const upage = await boot(scheme);
  // 真实 app 里 popup 容器在屏外（getHiddenRoot）永不可交互；harness 的装饰
  // 容器与栏右缘重叠会吞输入，按真实形态关掉命中（只影响本页）。
  await upage.evaluate(() => { document.getElementById('popup-chat').style.pointerEvents = 'none'; });
  await upage.evaluate(() => window.__userMediaLive());
  await upage.waitForFunction(() => document.querySelectorAll('#chat .row.user-media .pop-stack-view .media-layer').length === 2, null, { timeout: 10000 });
  await upage.waitForTimeout(300);
  const uview = upage.locator('#chat > .row.user-media .pop-stack-view');
  const urow = upage.locator('#chat > .row.user-media');

  await park(upage, uview);
  ok(`[${scheme}] 用户叠卡初始前景 = u1.png`, (await frontName(upage, '#chat > .row.user-media .pop-stack-view')) === 'u1.png');
  await urow.screenshot({ path: path.join(OUT, `3-user-${scheme}-before.png`) });

  await wheelSteps(upage, { dx: 10, steps: 7 });
  await upage.waitForTimeout(120);
  const uafter = await frontName(upage, '#chat > .row.user-media .pop-stack-view');
  ok(`[${scheme}] 用户侧同款横扫翻页：u1 → u2`, uafter === 'u2.png', `front=${uafter}`);
  await urow.screenshot({ path: path.join(OUT, `4-user-${scheme}-after-swipe.png`) });
  await upage.close();
}

await browser.close();
server.kill('SIGTERM');
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
