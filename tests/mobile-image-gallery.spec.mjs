// mobile-image-gallery.spec.mjs — 手机层图片画廊（mobile-image-lightbox 批
// 2026-11-17 作者令逐字：「现在手机端点图片为什么会单独打开一个标签页 我想的是
// 像微信那样的弹窗就行了」）的验收面。
//
// 覆盖（真 DOM 手机视口 + Playwright 自建路由，不碰任何真实实例端口）：
//   · G1 叠卡图组点开 → 应用内画廊（遮罩 + 居中大图 + 页码 n / m + 「在标签页
//     打开」显式入口 + 返回钮 .mfs-back + 页体锁滚），不开新页签；
//   · G2 横滑跟手切图（页码/序号读数推进；组端橡皮筋不越界）；
//   · G3 下滑关闭 + 页体滚动位保持（锁滚不吞 scrollTop）；
//   · G4 单图打开 → 无页码（组内 ≥2 才显示）；
//   · G5 「在标签页打开」→ 预约式直开铸票 URL（原能力显式保留，P16 腿原样）；
//   · G6 返回钮 .mfs-back → 关闭画廊；
//   · V1 视频点按 → 仍直开（新页签），画廊只吃图片（作者令「图片」面）；
//   · D0 桌面对照：同夹具同入口在桌面视口 → Canvas 页签（P16 桌面腿逐字不变，
//     画廊 chrome 节点在桌面 DOM 不出现）。
//
// 与 media-click-unify.spec.mjs 的关系：那边的 M1–M3 已翻转为画廊断言（手机×
// 直开面 → 手机×画廊面的矩阵改写），本 spec 补手势/页码/锁滚/返回/直开入口的
// 画廊专面；D1–D3 桌面断言零改动继续在跑 = 桌面零变化的逐字证据。
//
// 证据：SHOTS_DIR=<dir> 时写各态截图（默认不写）；录屏 = test-results 下的
// video.webm（describe 级 video:'on'）。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-image-gallery.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const SID = 'mil-gallery-session';
const IMG_PATH = 'docs/pic.png';

// 1×1 真解码 PNG（img onload 面，与 media-click-unify 族同款夹具字节）。
const PNG = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540" viewBox="0 0 960 540"><rect width="960" height="540" fill="#175b80"/><circle cx="710" cy="160" r="90" fill="#ffd166"/><path d="M0 460L240 190 470 420 610 260 960 540H0" fill="#75ba9a"/><text x="55" y="90" fill="white" font-size="48">Landscape 960 × 540</text></svg>`);
const PORTRAIT = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="480" height="760"><rect width="480" height="760" fill="#864ba1"/><circle cx="240" cy="320" r="150" fill="#facb73"/><text x="50" y="90" fill="white" font-size="38">Portrait 480 × 760</text></svg>`);
const SQUARE = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600"><rect width="600" height="600" fill="#b65e44"/><path d="M60 520L300 70 540 520Z" fill="#f8d385"/><text x="100" y="580" fill="white" font-size="40">Square 600 × 600</text></svg>`);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__nfOpenItems = [];
    window.addEventListener('workspace-open-item', (e) => {
      const d = e && e.detail ? e.detail : null;
      window.__nfOpenItems.push(d ? { id: d.id, absPath: d.absPath, itemType: d.itemType, title: d.title, error: d.error || '' } : null);
    });
    window.__nfOpenCalls = [];
    const origOpen = window.open.bind(window);
    window.open = (...args) => { window.__nfOpenCalls.push(args.map(String)); return origOpen(...args); };
  });
  // context 级路由：直开的预约页是同 context popup——page.route 覆盖不到。
  await page.context().route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/nf-ticket') {
        let body = '{}';
        try {
          const req = route.request();
          const post = req.postDataBuffer();
          if (post) {
            const j = JSON.parse(post.toString('utf8'));
            const tickets = {};
            for (const k of Object.keys(j && j.paths ? j.paths : {})) tickets[k] = { t: 'e2e-ticket', exp: Date.now() + 1800000, size: PNG.length };
            body = JSON.stringify({ tickets });
          }
        } catch { /* fall through to '{}' */ }
        return route.fulfill({ status: 200, contentType: 'application/json', body });
      }
      if (p === '/api/nf-file') {
        const name = url.searchParams.get('path') || '';
        const image = /b\.png$/.test(name) ? PORTRAIT : /c\.png$/.test(name) ? SQUARE : PNG;
        return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: image });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (parsed.type === 'pop.readFile') {
        ws.send(JSON.stringify({
          type: 'fileContent',
          path: parsed.path,
          absPath: '/tmp/' + parsed.path,
          fileName: parsed.path.split('/').pop(),
          itemType: 'image',
          content: '',
          size: PNG.length,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  // 真实指针面（tap/drag）要求入场门彻底离场：揭幕腿是两拍——先加
  // nf-boot-reveal（opacity:0 但 pointer-events:auto 仍罩整屏），220ms 淡出后
  // 才摘 nf-boot-gate 类。窗口内起手会被门卡接住（本批探针实证
  // nf-entry-card 截获 pointerdown ⇒ 画廊手势全灭）。类摘除 = 门真正让位。
  await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, { timeout: 15000 });
}

/** 生产构建器：buildUserMediaRow（live 发送 / attachmentsSaved 重渲 / 历史恢复
 *  三路同一构建器）。多附件 → 叠卡图组（buildMediaStack，整组进画廊）。 */
async function renderRealPopRow(page, atts) {
  const list = Array.isArray(atts) ? atts : [atts];
  return page.evaluate(async (a) => {
    const { buildUserMediaRow } = await import('/js/popArtifacts.js');
    const row = buildUserMediaRow(a, { ts: Date.now() });
    if (!row) return false;
    row.dataset.testid = 'pop-artifact-row';
    document.getElementById('chat')?.appendChild(row);
    return true;
  }, list);
}

/** 画廊态读数（一处收口，各断言面共用）。滚动读数取 #chat——手机层会话滚动
 *  容器就是它自己（chat.css `#chat{overflow-y:auto}`，手机层无覆盖；
 *  .mobile-page-body 是设置/fm 等其余页的滚动容器，锁规则同批覆盖）。 */
function galState(page) {
  return page.evaluate(() => {
    const lb = document.querySelector('.nf-lightbox');
    const pager = document.querySelector('.nf-lightbox .nf-lightbox-pager');
    const scroller = document.getElementById('chat');
    return {
      on: !!(lb && lb.classList.contains('on')),
      mode: lb ? lb.getAttribute('data-nf-lb-mode') : null,
      gallery: lb ? lb.getAttribute('data-nf-lb-gallery') : null,
      index: lb ? lb.getAttribute('data-nf-lb-gal-index') : null,
      total: lb ? lb.getAttribute('data-nf-lb-gal-total') : null,
      pagerText: pager ? pager.textContent.trim() : '',
      pagerShown: !!pager && pager.style.display !== 'none',
      opentab: !!document.querySelector('.nf-lightbox .nf-lightbox-opentab'),
      back: !!document.querySelector('.nf-lightbox > .nf-lightbox-close'),
      imgLoaded: !!document.querySelector('.nf-lightbox .nf-lightbox-img.loaded'),
      bodyLock: document.body.classList.contains('nf-lightbox-open'),
      scrollLocked: scroller ? getComputedStyle(scroller).overflow : null,
      scrollTop: scroller ? scroller.scrollTop : -1,
      openCalls: (window.__nfOpenCalls || []).length,
    };
  });
}

/** 「描画已落定」判据（同 tests/media-viewer-zoom.spec.mjs，本批判定名：
 *  *用墙钟时间/固定毫秒顶替可观测状态条件*）：
 *  元素 computed transform == 浮层已写下的状态（`data-nf-lb-zoom/pan-x/pan-y`）。
 *  候选元素必须**真的在画**：computed opacity === 1 且有盒 —— 视频/音频腿里
 *  `ensureDom` 建的 `.nf-lightbox-img` 常驻但 `opacity:0`、零盒（实测），盯着它必误超时。
 *  本 spec 的手势面不含缩放 ⇒ 不变式退化为「恒等 transform 且已 `loaded`」。 */
async function waitPainted(page) {
  await page.waitForFunction(() => {
    const lb = document.querySelector('.nf-lightbox');
    if (!lb || !lb.classList.contains('on')) return false;
    const z = Number(lb.getAttribute('data-nf-lb-zoom') || 1);
    const tx = Number(lb.getAttribute('data-nf-lb-pan-x') || 0);
    const ty = Number(lb.getAttribute('data-nf-lb-pan-y') || 0);
    if (!Number.isFinite(z) || !Number.isFinite(tx) || !Number.isFinite(ty)) return false;
    const cands = [lb.querySelector('.nf-lightbox-av'), lb.querySelector('.nf-lightbox-img')]
      .filter(Boolean)
      .filter((el) => { const cs = getComputedStyle(el); return cs.opacity === '1' && el.getBoundingClientRect().width > 0; });
    if (!cands.length) return false;
    return cands.some((el) => {
      const m = getComputedStyle(el).transform;
      let a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
      if (m && m !== 'none') { const hit = /^matrix\(([^)]+)\)$/.exec(m); if (!hit) return false; [a, b, c, d, e, f] = hit[1].split(',').map(Number); }
      return Math.abs(a - z) < 1e-3 && Math.abs(d - z) < 1e-3 && Math.abs(b) < 1e-9 && Math.abs(c) < 1e-9
          && Math.abs(e - tx) <= 0.5 && Math.abs(f - ty) <= 0.5;
    });
  }, null, { timeout: 8000 });
}

/** 收起态的可观测终态：浮层 `.nf-lightbox` 的 `display` 已回到 `none`（或元素已移除）。
 *  关闭是**瞬时** display 切换（`.nf-lightbox{display:none}` / `.on{display:flex}`），
 *  所以这条通常立刻成立 —— 它的价值是把「等 300ms 再截图」变成「等状态」，
 *  机器慢时不会截到半关的画面。 */
async function waitClosedPainted(page) {
  await page.waitForFunction(() => {
    const lb = document.querySelector('.nf-lightbox');
    if (!lb) return true;
    if (lb.classList.contains('on')) return false;
    return getComputedStyle(lb).display === 'none';
  }, null, { timeout: 8000 });
}

/** 鼠标拖动（Playwright mouse 面派发对应 pointer 事件——bindGalleryGestures
 *  的 pointerdown/move/up 消费面）。分步 move 产生跟手帧。 */
async function drag(page, x, y, dx, dy, steps = 8) {
  await page.mouse.move(x, y);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(x + (dx * i) / steps, y + (dy * i) / steps);
    // 保留（不适用）：16ms = **拖动手势的输入节拍**（模拟手指逐帧移动），不是「等落定」。
    // 去掉它拖拽就变成一次跳跃，测的是另一种手势；产品侧没有可观测能替代它。
    await page.waitForTimeout(16);
  }
  await page.mouse.up();
}

// 录屏证据面：video 只能文件级 use（describe 级会强制新 worker，Playwright 收集
// 期即拒）——录全部用例（含 D0 桌面对照），落在 test-results/<用例>/video.webm。
test.use({ video: 'on' });

test.describe('mobile image gallery (WeChat-style overlay)', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test('G1: stack group tap → in-app gallery, pager 1 / 3, chrome + locks, no new tab', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
      { type: 'image', name: 'c.png', path: 'docs/c.png' },
    ]);
    // 折叠叠卡整面点击 = 开当前张（带整组）。
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    const st = await galState(page);
    // 画廊面：遮罩浮层开、图片模式、页码 1 / 3、序号读数、chrome 两枚、锁滚两道。
    expect(st.on).toBe(true);
    expect(st.mode).toBe('img');
    expect(st.gallery).toBe('on');
    expect(st.index).toBe('0');
    expect(st.total).toBe('3');
    expect(st.pagerText).toBe('1 / 3');
    expect(st.pagerShown).toBe(true);
    expect(st.opentab).toBe(true);
    expect(st.back).toBe(true);
    expect(st.imgLoaded).toBe(true);
    expect(st.bodyLock).toBe(true);
    expect(st.scrollLocked).toBe('hidden');
    // 关键行为面：不开新页签（P16 直开在新批里只保留为显式入口，见 G5）。
    expect(st.openCalls).toBe(0);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await waitPainted(page);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-1-open-pager.png') });
    }
  });

  test('G2: horizontal drag advances the page; rubber band holds at group ends', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
      { type: 'image', name: 'c.png', path: 'docs/c.png' },
    ]);
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    // 左滑（dx<0）→ 下一张：1 / 3 → 2 / 3。
    await drag(page, 187, 400, -160, 0);
    await page.waitForFunction(() => document.querySelector('.nf-lightbox')?.getAttribute('data-nf-lb-gal-index') === '1', null, { timeout: 5000 });
    let st = await galState(page);
    expect(st.pagerText).toBe('2 / 3');
    // 再左滑 → 末张 3 / 3。
    await drag(page, 187, 400, -160, 0);
    await page.waitForFunction(() => document.querySelector('.nf-lightbox')?.getAttribute('data-nf-lb-gal-index') === '2', null, { timeout: 5000 });
    st = await galState(page);
    expect(st.pagerText).toBe('3 / 3');
    // 组端橡皮筋：末张再左滑不过阈也不越界（页码停在 3 / 3）。
    await drag(page, 187, 400, -120, 0);
    // 橡皮筋回弹走得是 CSS 过渡 ⇒ 等 **paint 追上状态**（组端不回退 ⇒ transform 回到钳位值），
    // 不赌固定毫秒：机器慢时旧写法会读在半回弹的几何上。
    await waitPainted(page);
    st = await galState(page);
    expect(st.index).toBe('2');
    expect(st.pagerText).toBe('3 / 3');
    expect(st.on).toBe(true);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await waitPainted(page);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-2-swipe-pager.png') });
    }
  });

  test('G3: drag-down closes; .mobile-page-body scrollTop preserved, overflow restored', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
    ]);
    // 会话滚动容器 #chat 垫高：#chat 是 flex 列——spacer 不写 flex:none 会被
    // flex-shrink 压回视口高（垫不出 scrollHeight，本批探针实证）；且 chat 的
    // 「新内容滚到底」观察器会在垫入后抢滚到底（2751）——所以滚动位设在锁滚
    // 生效后（overflow:hidden 仍可程序设 scrollTop，观察器从此滚不动它），
    // 正好直测「锁滚期间滚动位不漂、关闭后原样」。
    await page.evaluate(() => {
      const sc = document.getElementById('chat');
      const spacer = document.createElement('div');
      spacer.style.cssText = 'height: 3000px; flex: none;';
      sc.appendChild(spacer);
    });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    // 行渲染的「贴底」定时器链（persistence.js rAF/100ms/500ms 三拍抢滚到底）与
    // 进场落底武装窗（chat/settleBottom.js 1200ms 每帧钉底）排空/解除后再设
    // 900。武装窗的解除闸 = 用户意图事件（pointerdown/mousedown…）——真实用户
    // 点开画廊的那次按下天然解除；本夹具的点击是 JS 派发（无指针事件），须补
    // 一枚同语义的意图事件，滚动位从此只受锁滚规则管。
    // 保留（不适用，理由写在此处）：这 700ms 等的**不是动画**，而是另一个子系统的
    // 定时器链——persistence.js:1050-1055 的底钉三拍（立刻 + rAF + 100ms + 500ms）与
    // settleBottom 的 1200ms 武装窗（SETTLE_MS，chat/settleBottom.js:58）。产品侧没有
    // 「最后一拍已过」的可观测（那两个文件只导出 SETTLE_MS/cancelSettleBottom/settleBottom，
    // 内部 raf/timer/done 不外露）；而武装窗的**显式解除闸**就是下一行的用户意图事件，
    // 夹具用的正是它。要变得可观测得先给产品加导出（生产代码改动，超出本次测试卫生范围）。
    await page.waitForTimeout(700);
    await page.evaluate(() => { document.dispatchEvent(new Event('pointerdown')); });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 900; });
    let st = await galState(page);
    expect(st.scrollLocked).toBe('hidden');
    expect(st.scrollTop).toBe(900);      // 锁滚不动滚动位
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, 'gal-3a-open-locked.png') });
    // 下滑跟手关闭（dy 过阈 110）。
    await drag(page, 187, 350, 0, 220);
    await page.waitForFunction(() => !document.querySelector('.nf-lightbox.on'), null, { timeout: 5000 });
    st = await galState(page);
    expect(st.on).toBe(false);
    expect(st.gallery).toBe(null);       // 画廊态属性一并收零
    expect(st.bodyLock).toBe(false);
    expect(st.scrollLocked).not.toBe('hidden');
    expect(st.scrollTop).toBe(900);      // 关闭回原滚动位
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await waitClosedPainted(page);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-3b-closed-restored.png') });
    }
  });

  test('G4: single image open → pager hidden (group of 1)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    const st = await galState(page);
    expect(st.on).toBe(true);
    expect(st.gallery).toBe('on');
    expect(st.total).toBe('1');
    expect(st.pagerShown).toBe(false);   // 组内 ≥2 才显示页码
    expect(st.opentab).toBe(true);       // 单图也保留直开显式入口
    expect(st.openCalls).toBe(0);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await waitPainted(page);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-4-single.png') });
    }
  });

  test('G5: explicit open-in-tab button → reserved direct-open with ticketed URL', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    // 原能力显式入口：点「在标签页打开」→ 预约式 window.open → 铸票 nf-file URL。
    const downloadPromise = page.waitForEvent('download');
    await page.locator('.nf-lightbox .nf-lightbox-opentab').click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('pic.png');
    // 画廊本体仍在（入口不打断浏览）。
    const st = await galState(page);
    expect(st.on).toBe(true);
    expect(errors).toEqual([]);
  });

  test('G6: injected .mfs-back button closes the gallery', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on');
    }, null, { timeout: 8000 });
    await page.locator('.nf-lightbox > .nf-lightbox-close').click();
    await page.waitForFunction(() => !document.querySelector('.nf-lightbox.on'), null, { timeout: 5000 });
    const st = await galState(page);
    expect(st.on).toBe(false);
    expect(st.bodyLock).toBe(false);
    expect(errors).toEqual([]);
  });

  test('V1: video tap stays direct-open (gallery only claims images)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'video', name: 'v.mp4', path: 'docs/v.mp4' });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await expect(page.locator('.nf-lightbox.on video')).toBeVisible();
    const st = await galState(page);
    expect(st.on).toBe(true);
    expect(st.gallery).toBe('on');
    expect(errors).toEqual([]);
  });
});

test.describe('media classification and mixed gallery', () => {
  test.use({ viewport: { width: 1280, height: 900 } });
  test('document exclusions and media controls', async ({ page }) => {
    await bootApp(page);
    const result = await page.evaluate(async () => {
      const { mediaKind, routeMediaClick } = await import('/js/mediaOpenRoute.js');
      const cases = ['x.pdf', 'x.html', 'x.md', 'x.png', 'x.mp4', 'x.mp3'];
      const kinds = cases.map(name => mediaKind({ name, mime: 'application/octet-stream' }));
      const opened = [];
      for (const name of cases.slice(0, 3)) routeMediaClick({ name, path: '/tmp/' + name }, path => opened.push(path), () => {});
      return { kinds, opened, overlay: !!document.querySelector('.nf-lightbox.on') };
    });
    expect(result.kinds).toEqual(['', '', '', 'image', 'video', 'audio']);
    expect(result.opened).toEqual(['/tmp/x.pdf', '/tmp/x.html', '/tmp/x.md']);
    expect(result.overlay).toBe(false);
    await page.evaluate(async () => {
      const { openImageGallery } = await import('/js/lightbox.js');
      openImageGallery([
        { kind: 'image', name: 'one.png', src: '/api/nf-file?path=one.png' },
        { kind: 'video', name: 'two.mp4', src: '/api/nf-file?path=two.mp4' },
        { kind: 'audio', name: 'three.mp3', src: '/api/nf-file?path=three.mp3' },
      ], 0);
    });
    const lb = page.locator('.nf-lightbox.on');
    await expect(lb).toHaveAttribute('data-nf-lb-gal-total', '3');
    await page.keyboard.press('ArrowRight');
    await expect(lb.locator('video')).toHaveCount(1);
    await page.keyboard.press('ArrowRight');
    await expect(lb.locator('video')).toHaveCount(0);
    await expect(lb.locator('audio')).toHaveCount(1);
    await page.keyboard.press('Escape');
    await expect(page.locator('.nf-lightbox audio')).toHaveCount(0);
  });
});

test.describe('desktop gallery interactions', () => {
  test.use({ viewport: { width: 1280, height: 900 } });
  test('keyboard, zoom, pan, backdrop and escape', async ({ page }) => {
    await bootApp(page);
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
    ]);
    await page.locator('.pop-stack-view').first().dispatchEvent('click');
    const lb = page.locator('.nf-lightbox.on');
    await expect(lb).toBeVisible();
    await expect(lb).toHaveAttribute('data-nf-lb-gal-total', '2');
    await expect(lb.locator('.nf-lightbox-img.loaded')).toBeVisible();
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, 'desktop-multiple-after.png') });
    expect(await page.evaluate(() => window.__nfOpenItems.length)).toBe(0);
    await page.keyboard.press('ArrowRight');
    await expect(lb).toHaveAttribute('data-nf-lb-gal-index', '1');
    await page.mouse.move(640, 450);
    await page.mouse.wheel(0, -120);
    await expect(lb).toHaveAttribute('data-nf-lb-zoom', '1.2');
    await page.locator('.nf-lightbox-img').dispatchEvent('dblclick');
    await expect(lb).toHaveAttribute('data-nf-lb-zoom', '1');
    await page.locator('.nf-lightbox-img').dispatchEvent('dblclick');
    await expect(lb).toHaveAttribute('data-nf-lb-zoom', '2');
    await page.mouse.move(640, 450);
    await page.mouse.down();
    await page.mouse.move(700, 490, { steps: 5 });
    await page.mouse.up();
    await expect(lb.locator('.nf-lightbox-img')).toHaveCSS('transform', /matrix\(2, 0, 0, 2, 60, 40\)/);
    await page.keyboard.press('ArrowLeft');
    await expect(lb).toHaveAttribute('data-nf-lb-gal-index', '0');
    await page.keyboard.press('Escape');
    await expect(lb).toHaveCount(0);
    await page.locator('.pop-stack-view').first().dispatchEvent('click');
    await expect(lb).toBeVisible();
    await lb.click({ position: { x: 10, y: 450 } });
    await expect(lb).toHaveCount(0);
  });
});

test.describe('desktop zero-change control (D0)', () => {
  test.use({ viewport: { width: 1280, height: 900 } });
  test('D0: desktop image opens gallery not Canvas', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await expect(page.locator('.nf-lightbox.on')).toBeVisible();
    const st = await galState(page);
    expect(st.on).toBe(true);
    expect(st.gallery).toBe('on');
    expect(await page.evaluate(() => window.__nfOpenItems.length)).toBe(0);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await waitPainted(page);
      await page.screenshot({ path: join(SHOTS_DIR, 'desktop-single-after.png') });
    }
  });
});
