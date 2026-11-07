// memory-panel-scroll.spec.mjs — 记忆面板滚动源验收（memory-panel-scroll 批 2026-11-07）
//
// 作者报障：「记忆这个面板，为什么外部多了一个滚动条 有些多余」。
// 归因（隔离浏览器逐条对照实测）：外层可滚量恒为 28px = css/scrollUnderGlass.css 的
// `.scroll-under-glass::after` 裙边（面板盒外、top:100%、高 --sug-fade）——
// 绝对定位后代计入块流末端可滚溢出区 ⇒ 滚动容器面板凭空多 28px 可滚量，
// overflow-y:auto 立刻画槽；而这些面板本来就把盒外裙边整条裁掉（零视觉损失）。
// 修法 = 该文件「滚动容器上的裙边幻影」块（关裙边）+ modal.css 记忆面板纵列化
//      （内容面可收缩、动作行固定、编辑器自持唯一真滚动）。
//
// 本 spec 的验收口径（全部为**实测几何/命中判定**，不读图）：
//   T1 桌面亮暗四档视口：外层可滚量 = 0、编辑器可滚量 > 0（唯一真滚动在编辑器内）
//   T2 手机两档：同上；且编辑态下文本域与动作行都在页面体内可达
//   T3 放大字体（×1.5 / ×2）：编辑器按三行地板收缩、Save/Cancel 仍可点（改前被顶出）
//   T4 窗口实时缩放跨档（1440→900→1440）：任何一档都不长出外滚动条
//   T5 长文本：编辑器内可滚到末行、末行内容完整（不隐藏真正需要的滚动）
//   T6 键盘：Tab 从编辑器可达 Cancel → Save，Enter 触发 saveMemory 帧（不靠鼠标）
//   T7 保存/取消行为零变化（WS 帧 + 层栈出栈回焦）
//   T8 智能体分区零回归（同面板另一分区几何不变）
//   T9 根因守卫：记忆面板裙边伪元素已关（content:none）且外层可滚量 0
// 载体 = 静态文件直供 + WS 夹具（零后端、零 sbt、不连宿主实例、不读改真实记忆）。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/memory-panel-scroll.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

// 载体根可被 MPS_WEB 覆盖：验红实证用（把「改前」的 css/ 快照放到别处直供，
// 同一套判据在不打补丁的资产上必须变红），默认仍是本仓 web/。
const WEB = process.env.MPS_WEB || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.jpg': 'image/jpeg', '.webp': 'image/webp',
};

const SID = 'mps-root-session';
/** 长文本夹具：40 节 ⇒ 编辑器远超一屏，用于「真滚动留在编辑器内」。 */
const LONG_MD = Array.from({ length: 40 }, (_, i) => `## 第 ${i + 1} 节\n内容行 ${i + 1}：用于拉长文本，验证长文本滚动归属。`).join('\n\n');
const AGENT_MD = ['# 记忆', '', '项目背景速览。', '', LONG_MD].join('\n');
const USER_MD = '## 沟通偏好\n回复保持简短。';

/** 引导：静态文件直供 + WS 夹具（照 mobile-p5-memory.spec.mjs 同款载体）。 */
async function boot(page) {
  /** @type {string[]} */
  const sentTypes = [];
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      sentTypes.push(String(parsed.type || ''));
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getMemory') {
        ws.send(JSON.stringify({ type: 'memoryData', scope: parsed.scope, content: parsed.scope === 'user' ? USER_MD : AGENT_MD }));
      } else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(300);
  return { sentTypes };
}

/** 打开合并面板的记忆分区（桌面走入口函数，手机走头像 → 记忆页签 → 编辑态）。 */
async function openMemoryEditor(page, mobile) {
  if (mobile) {
    await page.locator('#agent-avatar-btn').click();
    await expect(page.locator('#memory-modal')).toBeVisible();
    await page.locator('.merged-tab[data-pane="memory"]').click();
    await page.locator('.mh-memory-edit').click();
  } else {
    await page.evaluate(async () => { (await import('/js/memory.js')).openMergedPanel('memory'); });
    await expect(page.locator('#memory-modal')).toBeVisible();
  }
  await expect(page.locator('#memory-content-input')).toBeVisible();
  await page.waitForFunction(() => (document.getElementById('memory-content-input'))?.value.length > 500, null, { timeout: 8000 });
  await page.waitForTimeout(250);
}

/** 放大字体仿真：只放大字阶令牌（真滚动条宿主的字号源），保留布局栅格。 */
async function scaleFonts(page, k) {
  await page.addStyleTag({
    content: `:root{--ds-fs-title:${19.5 * k}px !important;--ds-fs-body:${13.5 * k}px !important;` +
      `--ds-fs-hint:${12 * k}px !important;--ds-fs-btn:${13 * k}px !important;--ds-fs-small:${11.5 * k}px !important;}` +
      `html{font-size:${13 * k}px !important;}`,
  });
  await page.waitForTimeout(300);
}

/** 核心读数：外层可滚量/滚动槽占位/编辑器可滚量/动作行与保存钮的可达性。 */
const READ = () => {
  const modal = document.getElementById('memory-modal');
  const ta = document.getElementById('memory-content-input');
  const save = document.getElementById('memory-modal-save');
  const cancel = document.getElementById('memory-modal-cancel');
  const cs = (el) => getComputedStyle(el);
  /** 命中判定：中心点真的落在该元素（= 未被遮挡、未被裁到框外） */
  const hit = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const top = document.elementFromPoint(Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2));
    return !!top && (top === el || el.contains(top) || top.contains(el));
  };
  return {
    vw: window.innerWidth,
    vh: window.innerHeight,
    mobile: document.body.classList.contains('mobile-layer'),
    modal: modal ? {
      clientH: modal.clientHeight,
      scrollH: modal.scrollHeight,
      scrollableBy: modal.scrollHeight - modal.clientHeight,
      sbW: modal.offsetWidth - modal.clientWidth - (
        (parseFloat(cs(modal).borderLeftWidth) || 0) + (parseFloat(cs(modal).borderRightWidth) || 0)
      ),
      overflowY: cs(modal).overflowY,
      afterContent: getComputedStyle(modal, '::after').content,
      inViewport: (() => { const r = modal.getBoundingClientRect(); return r.top >= -1 && r.bottom <= window.innerHeight + 1; })(),
    } : null,
    ta: ta ? {
      clientH: ta.clientHeight,
      scrollH: ta.scrollHeight,
      scrollableBy: ta.scrollHeight - ta.clientHeight,
      overflowY: cs(ta).overflowY,
      lineH: parseFloat(cs(ta).lineHeight) || 0,
      fs: parseFloat(cs(ta).fontSize) || 0,
    } : null,
    saveHit: hit(save),
    cancelHit: hit(cancel),
    saveInModal: !!(save && modal && modal.contains(save)),
    layerStack: (document.getElementById('memory-overlay')?.className || ''),
  };
};

/** 唯一真滚动必须留在编辑器内：滚到末行、末行文本完整可读，再复位。 */
const SCROLL_TO_END = () => {
  const ta = document.getElementById('memory-content-input');
  if (!ta) return null;
  ta.scrollTop = ta.scrollHeight;
  const atEnd = Math.abs(ta.scrollTop + ta.clientHeight - ta.scrollHeight) <= 2;
  const moved = ta.scrollTop > 0;
  ta.scrollTop = 0;
  return { atEnd, moved };
};

test.describe('记忆面板滚动源：外层不得有可滚量', () => {
  for (const theme of ['light', 'dark']) {
    for (const vp of [{ w: 1440, h: 900 }, { w: 1280, h: 720 }, { w: 1024, h: 640 }, { w: 900, h: 560 }]) {
      test(`T1 桌面 ${vp.w}x${vp.h} ${theme}：外层可滚量 0，真滚动只在编辑器`, async ({ page }) => {
        await page.setViewportSize({ width: vp.w, height: vp.h });
        await page.emulateMedia({ colorScheme: /** @type {any} */ (theme) });
        await boot(page);
        await openMemoryEditor(page, false);
        const r = await page.evaluate(READ);
        expect(r.mobile).toBe(false);
        // 外层零可滚量（改前恒 28px）且不占滚动条宽度
        expect(r.modal.scrollableBy).toBe(0);
        expect(r.modal.sbW).toBe(0);
        expect(r.modal.inViewport).toBe(true);
        // 真滚动留在编辑器内：长文本可滚量 > 一屏
        expect(r.ta.scrollableBy).toBeGreaterThan(500);
        expect(r.ta.overflowY).toBe('auto');
        // 动作行可达（不被顶出面板/视口）
        expect(r.saveInModal).toBe(true);
        expect(r.saveHit).toBe(true);
        expect(r.cancelHit).toBe(true);
        // 唯一真滚动可用：编辑器能滚到末行
        expect(await page.evaluate(SCROLL_TO_END)).toEqual({ atEnd: true, moved: true });
      });
    }
  }

  for (const theme of ['light', 'dark']) {
    for (const vp of [{ w: 375, h: 812 }, { w: 320, h: 568 }]) {
      test(`T2 手机 ${vp.w}x${vp.h} ${theme}：外层可滚量 0，编辑态内容可达`, async ({ page }) => {
        await page.setViewportSize({ width: vp.w, height: vp.h });
        await page.emulateMedia({ colorScheme: /** @type {any} */ (theme) });
        await boot(page);
        await openMemoryEditor(page, true);
        const r = await page.evaluate(READ);
        // 手机层激活判据 = max-width:768px（js/mobile/bootstrap.js），本档宽度必中
        expect(r.mobile).toBe(true);
        expect(r.modal.scrollableBy).toBe(0);
        // 小屏（320×568 改前 112px 内容被裁、Save 落到面板体外不可点）⇒ 现在动作行仍在面板内可点
        expect(r.saveInModal).toBe(true);
        expect(r.saveHit).toBe(true);
        expect(r.cancelHit).toBe(true);
        expect(r.ta.clientH).toBeGreaterThanOrEqual(Math.floor(3 * 1.5 * r.ta.fs) - 2);
        expect(await page.evaluate(SCROLL_TO_END)).toEqual({ atEnd: true, moved: true });
      });
    }
  }

  for (const k of [1.5, 2]) {
    test(`T3 放大字体 ×${k}（900x560 暗）：编辑器收缩到地板，Save/Cancel 仍可点`, async ({ page }) => {
      await page.setViewportSize({ width: 900, height: 560 });
      await page.emulateMedia({ colorScheme: 'dark' });
      await boot(page);
      await openMemoryEditor(page, false);
      await scaleFonts(page, k);
      const r = await page.evaluate(READ);
      expect(r.ta.fs).toBeGreaterThan(13 * k - 2);
      expect(r.modal.scrollableBy).toBe(0);
      expect(r.modal.inViewport).toBe(true);
      // 改前：编辑器 504px 高把动作行顶到视口之外（saveTop 782 > vh 560）
      expect(r.saveHit).toBe(true);
      expect(r.cancelHit).toBe(true);
      expect(r.ta.clientH).toBeGreaterThanOrEqual(Math.floor(3 * 1.5 * r.ta.fs) - 2);
      expect(await page.evaluate(SCROLL_TO_END)).toEqual({ atEnd: true, moved: true });
    });
  }

  test('T4 窗口实时缩放跨档：任何一档都不长出外滚动条', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await boot(page);
    await openMemoryEditor(page, false);
    for (const [w, h] of [[1440, 900], [1100, 700], [900, 560], [820, 520], [1280, 800], [1440, 900]]) {
      await page.setViewportSize({ width: w, height: h });
      await page.waitForTimeout(220);
      const r = await page.evaluate(READ);
      expect({ at: `${w}x${h}`, scrollableBy: r.modal.scrollableBy, sbW: r.modal.sbW, saveHit: r.saveHit })
        .toEqual({ at: `${w}x${h}`, scrollableBy: 0, sbW: 0, saveHit: true });
    }
  });

  test('T5 长文本：编辑器内滚到末行，末行内容完整', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await boot(page);
    await openMemoryEditor(page, false);
    const tail = await page.evaluate(() => {
      const ta = document.getElementById('memory-content-input');
      ta.scrollTop = ta.scrollHeight;
      const lines = ta.value.split('\n');
      return { lastLine: lines[lines.length - 1], scrollTop: ta.scrollTop, scrollH: ta.scrollHeight, clientH: ta.clientHeight };
    });
    expect(tail.lastLine).toBe('内容行 40：用于拉长文本，验证长文本滚动归属。');
    expect(tail.scrollTop).toBeGreaterThan(0);
    expect(tail.scrollH - tail.clientH).toBeGreaterThan(500);
    // 外层仍然零可滚量（长文本不把面板顶出滚动条）
    expect((await page.evaluate(READ)).modal.scrollableBy).toBe(0);
  });

  test('T6 键盘：Tab 从编辑器可达 Cancel → Save，Enter 触发保存帧', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const h = await boot(page);
    await openMemoryEditor(page, false);
    await page.locator('#memory-content-input').focus();
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.activeElement?.id)).toBe('memory-modal-cancel');
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.activeElement?.id)).toBe('memory-modal-save');
    // 焦点框可见（键盘用户看得见落点）
    const outline = await page.evaluate(() => {
      const s = getComputedStyle(document.getElementById('memory-modal-save'));
      return { outlineWidth: s.outlineWidth, boxShadow: s.boxShadow };
    });
    expect(outline.outlineWidth !== '0px' || outline.boxShadow !== 'none').toBe(true);
    await page.keyboard.press('Enter');
    await expect.poll(() => h.sentTypes.includes('saveMemory'), { timeout: 5000 }).toBe(true);
    // 焦点不出层（focusTrap 在位）
    const trapped = await page.evaluate(() => {
      const ov = document.getElementById('memory-overlay');
      return !!ov && ov.contains(document.activeElement);
    });
    expect(trapped).toBe(true);
  });

  test('T7 保存/取消行为零变化（WS 帧 + 层栈出栈）', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const h = await boot(page);
    await openMemoryEditor(page, false);
    const content = await page.evaluate(() => {
      const ta = document.getElementById('memory-content-input');
      ta.value = '# 改写后的记忆';
      return ta.value;
    });
    await page.locator('#memory-modal-save').click();
    await expect.poll(() => h.sentTypes.includes('saveMemory'), { timeout: 5000 }).toBe(true);
    expect(content).toBe('# 改写后的记忆');
    // 面板不因保存而关（桌面语义不变）
    await expect(page.locator('#memory-modal')).toBeVisible();
    await page.locator('#memory-modal-cancel').click();
    await expect(page.locator('#memory-modal')).toBeHidden();
    await expect(page.locator('#memory-overlay')).not.toHaveClass(/on/);
  });

  test('T8 智能体分区零回归（同一面板另一分区几何不变）', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await boot(page);
    await page.evaluate(async () => { (await import('/js/memory.js')).openMergedPanel('agent'); });
    await expect(page.locator('#memory-modal')).toBeVisible();
    await page.waitForTimeout(300);
    const r = await page.evaluate(() => {
      const m = document.getElementById('memory-modal');
      const ap = m.querySelector('.ap-pane');
      const b = ap?.getBoundingClientRect();
      return {
        scrollableBy: m.scrollHeight - m.clientHeight,
        apH: b ? Math.round(b.height) : 0,
        apVisible: !!ap && getComputedStyle(ap).display !== 'none',
        hasAvatar: !!m.querySelector('.ap-avatar-box'),
        hasName: !!m.querySelector('.ap-namerow'),
      };
    });
    expect(r.scrollableBy).toBe(0);
    expect(r.apVisible).toBe(true);
    expect(r.apH).toBeGreaterThan(100);
    expect(r.hasAvatar).toBe(true);
    expect(r.hasName).toBe(true);
  });

  test('T9 根因守卫：记忆面板裙边已关，且无幻影可滚量', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await boot(page);
    await page.evaluate(async () => { (await import('/js/memory.js')).openMergedPanel('memory'); });
    await page.waitForTimeout(200);
    const r = await page.evaluate(READ);
    expect(r.modal.afterContent).toBe('none');
    expect(r.modal.scrollableBy).toBe(0);
  });
});
