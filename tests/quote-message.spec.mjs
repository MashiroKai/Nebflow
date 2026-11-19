// quote-message.spec.mjs — 主聊天区「消息右键引用（微信式）」一期（2026-10-09）
// 的回归网（设计规格 20261009_quote-message-design-spec §7.1-5 + 裁定项）
// + 2026-10-09 用户反馈改版（引用 chip 与附件 chip 同条带）。
//
// 驱动 tests/fixtures/quote-message/harness.html（真实模块，无 stub）。
// 探针：右键菜单（contextMenu.showPopupMenu 同壳）→ 引用 chip（宿主 =
// #attachment-preview 附件条，输入框上方 —— 2026-10-09 用户反馈改版，原整宽
// 引用条 #msg-quote-strip 退役）→ 带引用发送（renderUserBubble 气泡上方引用块）
// → 同屏跳转 + 1.8s 高亮 → restoreFromBackendHistory 重渲 m.quote。
//
//   B1  右键 AI/工具/注入行 → 菜单项正确（文本行=复制+引用；工具行=仅引用）
//   B2  引用 → 引用 chip 落附件条 #attachment-preview（输入框上方）+ pending 恰 1 条
//   B3  替换语义：换引另一条 ⇒ chip 换内容、仍 1 条
//   B4  ✕ 清除 ⇒ chip 消失 + 引用态清空
//   B5  带引用发送：行 data-msg-id=clientMessageId、气泡上方引用块、无气泡内 ref 卡
//   B6  摘要规则：图片 [图片] 文件名 / 工具 [工具] label · summary / 长文截断
//   B7  同屏跳转：点击引用块 → 目标行滚入 + quote-target-flash 高亮
//   B8  历史重渲：m.quote 命中页内目标 ⇒ data-quote-jump=ready 可点
//   B9  删除降级：目标不在 DOM ⇒ 无指针 + 「原消息已删除」注记（无死点击）
//   B10 历史 h-<idx>-<mount> id 页内唯一
//   B11 footer 引用按钮：复制钮旁「引用」钮在场；点按钮 = 与右键引用逐字同效
//       （按钮 ⊆ footer —— 无 footer 的行保持右键单入口）
//   B12 引用 chip 落面形态（改版）：与附件 chip 同条带、同大小档（±4px）、
//       位于输入框上方；窄容器溢出走换行（无横向溢出、不越条、不压输入框）
//   B13 暗亮两态：chip 计算背景随 colorScheme 切换 + 双主题 composer 截图存证
//       （.nebflow/evidence/20261010_quotechip-composer/）
//   B14 空引用不发（零回归）：仅引用无正文 ⇒ 纯引用帧闸生效（isRefOnlyFrame /
//       syncRefOnlyGate ⇒ 发送键禁用）；引用+附件并存时闸不误伤
//   B15 引用块宽度约束（2026-10-20 作者令「引用文字不要超过用户发送的文字」）：
//       几何断言「引用块宽度 ≤ 气泡宽度」—— 引用块+文本气泡包进
//       .msg-quote-composite 气泡驱宽复合盒（chat.css 同名段），窄气泡跟着窄，
//       超长被引文本走既有摘要+两行 clamp 不撑宽；zh/en × 亮暗各验一例 + 截图
//       存证（.nebflow/evidence/20261020_quoteblock-width/）
//
// Run: node node_modules/@playwright/test/cli.js test tests/quote-message.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/quote-message/harness.html';
const EVIDENCE_DIR = path.join(REPO_ROOT, '.nebflow/evidence/20261010_quotechip-composer');

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => {
        if ([6138, 8080, 8097].includes(p)) reject(new Error(`protected port selected: ${p}`));
        else resolve(p);
      });
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  const script = `import functools, http.server, sys, threading\nhandler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=sys.argv[2])\nserver = http.server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), handler)\nthread = threading.Thread(target=server.serve_forever)\nthread.start()\nprint('ready', flush=True)\nsys.stdin.readline()\nserver.shutdown()\nthread.join()\nserver.server_close()`;
  return spawn('python3', ['-u', '-c', script, String(p), dir], { stdio: ['pipe', 'pipe', 'ignore'] });
}

function serverReady(server) {
  return new Promise((resolve, reject) => {
    server.stdout.once('data', resolve);
    server.once('error', reject);
    server.once('exit', code => reject(new Error(`server exited before ready: ${code}`)));
  });
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  console.log(`quote harness port=${port}; data root unused`);
  const server = startStaticServer(port, REPO_ROOT);
  servers.push(server);
  await serverReady(server);
});

test.afterAll(async () => {
  for (const server of servers) {
    const exited = new Promise(resolve => server.once('exit', resolve));
    server.stdin.end('quit\n');
    await exited;
    expect(() => process.kill(server.pid, 0)).toThrow();
    const probe = net.createServer();
    await new Promise((resolve, reject) => {
      probe.once('error', reject);
      probe.listen(port, '127.0.0.1', () => probe.close(resolve));
    });
  }
});

async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/* ═══════════ B1 — 右键菜单（文本行 = 复制+引用；工具行 = 仅引用）═══════════ */

test.describe('B1 — 右键菜单项', () => {
  test('用户文本行菜单 = 复制 + 引用；工具卡行 = 仅引用', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const userId = await page.evaluate(() => window.__renderUserRow('用户的一条消息'));
    expect(userId, '用户行 data-msg-id 在场').toBeTruthy();
    expect(await page.evaluate(id => window.__menuLabelsOn(id), userId)).toEqual(['复制', '引用']);

    const toolId = await page.evaluate(() => window.__renderToolRow('Read', '读取 src/main.rs'));
    expect(toolId, '工具行 data-msg-id 在场').toBeTruthy();
    expect(await page.evaluate(id => window.__menuLabelsOn(id), toolId)).toEqual(['引用']);

    const injId = await page.evaluate(() => window.__renderInjectedRow('Mail · 项目周报已送达'));
    expect(injId, '注入行 data-msg-id 在场').toBeTruthy();
    expect(await page.evaluate(id => window.__menuLabelsOn(id), injId)).toEqual(['复制', '引用']);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══ B16 — 选区面收敛（作者令 2026-11-18：选区「复制/引用去掉，只留 Ask」）═══
 * 逐字：「现在选中ask 不可用 然后选中的复制和引用去掉 就只保留ask 然后是选中就在
 * 气泡的旁边 你发的是右边 我发的是左边 出现一个ask按钮 点击就可以出现canvas Ask
 * 那样的对话框提问」。断言面 = ①有选区 ⇒ **零菜单**（`.fm-context-menu` 数与
 * 「复制/引用」两项都不存在，且旧 `.selection-ask-panel` 卡片整族退役）
 * ②按钮**与气泡同侧**（左右各一次，逐条 rect 读数）③点击开**共用件**对话框
 * （`.ask-dock.selection-dock`，与 canvas 档同一实现）+ 选区正文进提问 + 帧回包落地。 */

test.describe('B16 — 选区面收敛（只留 Ask）', () => {
  test('有选区 ⇒ 零菜单；按钮与气泡同侧（左右各一次）；点击开共用件对话框', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const userId = await page.evaluate(() => window.__renderUserRow('before selected after'));
    await page.evaluate(() => window.__renderHistory([{ type: 'ai', text: 'before selected after' }]));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    const aiId = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#chat .row')).find(r => r.querySelector('.bubble.ai'));
      return row ? row.dataset.msgId : null;
    });
    expect(userId, '我方 user 行在场').toBeTruthy();
    expect(aiId, '对方 ai 行在场').toBeTruthy();

    const sides = [];
    for (const [role, rowId, expectSide] of [['user', userId, 'right'], ['ai', aiId, 'left']]) {
      // 真 Selection（Range）⇒ 选区面事件
      const sel = await page.evaluate(id => window.__selectTextInRow(id, 0, 6), rowId);
      expect(sel.ok, `${role} 选区成功`).toBe(true);
      expect(sel.text).toBe('before');

      // ① 有选区 ⇒ 零菜单：旧「复制/引用」永不进选区面
      const withSel = await page.evaluate(id => window.__menuLabelsOn(id), rowId);
      expect(withSel, `${role}：有选区 ⇒ 菜单零出场（复制/引用退役）`).toEqual([]);
      expect(withSel).not.toContain('复制');
      expect(withSel).not.toContain('引用');
      expect(withSel).not.toContain('Ask');
      await expect(page.locator('.selection-ask-panel')).toHaveCount(0); // 旧卡片整族退役

      // ③ 按钮出场且与气泡同侧（几何读数）
      await expect(page.locator('.selection-ask-btn')).toBeVisible();
      const p = await page.evaluate(() => window.__selAskProbe());
      // ③ 现场读数（rect 证据面）：按钮 / 气泡 / 会话列中点 + 菜单计数
      console.log(`[B16/${role}] ${JSON.stringify(p)}`);
      expect(p.btnSide, `${role}：按钮侧别`).toBe(expectSide);
      expect(p.bubbleSide, `${role}：按钮记录的侧别 = 气泡侧别`).toBe(expectSide);
      expect(p.menuCount, `${role}：选区面零菜单`).toBe(0);
      if (expectSide === 'right') expect(p.gapRight, `${role}：按钮在气泡右侧外面`).toBeGreaterThanOrEqual(0);
      else expect(p.gapLeft, `${role}：按钮在气泡左侧外面`).toBeGreaterThanOrEqual(0);
      const bubbleMid = p.bubbleRect.mid, chatMid = p.chatMid;
      if (expectSide === 'right') expect(bubbleMid, `${role}：气泡中点与会话列中点同侧（右）`).toBeGreaterThan(chatMid);
      else expect(bubbleMid, `${role}：气泡中点与会话列中点同侧（左）`).toBeLessThan(chatMid);
      sides.push({ role, ...p });

      // ④ 点击 ⇒ 共用件对话框 + 选区正文进提问 + 帧回包落地
      const opened = await page.evaluate(() => window.__selAskClick());
      expect(opened.ok, `${role}：对话框打开`).toBe(true);
      expect(opened.className, `${role}：面板 = 共用件 .ask-dock 的第二份实例`).toContain('ask-dock');
      expect(opened.className).toContain('selection-dock');
      expect(opened.open, `${role}：对话框处于打开态`).toBe(true);
      expect(opened.frame, `${role}：真心跳帧（主会话 /ask 腿）`).toBeTruthy();
      expect(opened.frame.type).toBe('ask');
      expect(opened.frame.question, `${role}：选区正文带进提问`).toContain('<selection>\nbefore\n</selection>');
      expect(opened.frame.question).toContain('<before>\n');
      await page.evaluate(() => {
        window.__selAskFrame('askTextDelta', { sessionId: 'quote-harness', delta: '合成解释文本' });
        window.__selAskFrame('askDone', { sessionId: 'quote-harness' });
      });
      await expect(page.locator('.ask-dock.selection-dock .ask-bub').last()).toContainText('合成解释文本');
      await page.evaluate(() => window.__selectionAskClose());
      // 关窗后按当前选区重锚：选区还在 ⇒ 按钮回来（可再问一次）；选区散 ⇒ 收起。
      await expect(page.locator('.selection-ask-btn')).toBeVisible();
      await page.evaluate(() => window.getSelection().removeAllRanges());
      await page.evaluate(() => document.dispatchEvent(new Event('selectionchange')));
      await expect(page.locator('.selection-ask-btn')).toBeHidden();
    }
    // 左右两测的真实读数（证据面）：两条 rect 差
    expect(sides.length).toBe(2);
    expect(sides[0].gapRight).toBeGreaterThanOrEqual(0);
    expect(sides[1].gapLeft).toBeGreaterThanOrEqual(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('触屏档（390×844）：有选区 ⇒ 长按零菜单 + Ask 按钮同侧；无选区 ⇒ 既有行为不变', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
    const page = await context.newPage();
    await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
    await page.waitForFunction(() => window.__ready === true);
    const id = await page.evaluate(() => { document.body.classList.add('mobile-layer'); return window.__renderUserRow('before selected after'); });
    const cdp = await context.newCDPSession(page);
    const center = async () => {
      const box = await page.locator(`.row[data-msg-id="${id}"] .bubble`).boundingBox();
      return { x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2) };
    };
    // A. 无选区：长按 800ms ⇒ 不出菜单（attachQuoteMenu 既有纪律「只对已有选区动作」
    //    逐字不变；触屏长按退役批 2026-10-31 之后的现状照旧，本批零改动这条）。
    let c = await center();
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: c.x, y: c.y }] });
    await page.waitForTimeout(800);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    expect(await page.locator('.fm-context-menu').count(), '无选区 ⇒ 不出菜单（既有行为不变）').toBe(0);
    // B. 有选区：长按 ⇒ 零菜单（② 收敛：复制/引用永不进选区面）+ Ask 按钮出场（③ 同侧）
    await page.evaluate(id => window.__selectTextInRow(id, 0, 6), id);
    c = await center();
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: c.x, y: c.y }] });
    await page.waitForTimeout(800);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    expect(await page.locator('.fm-context-menu').count(), '有选区 ⇒ 长按也不出菜单').toBe(0);
    await expect(page.locator('.selection-ask-btn')).toBeVisible();
    const p = await page.evaluate(() => window.__selAskProbe());
    expect(p.menuCount, '选区面零菜单').toBe(0);
    expect(p.menuLabels, '复制/引用不在选区面').toEqual([]);
    expect(p.btnSide, '移动端按钮侧别 = 气泡侧别（本方气泡 ⇒ 右）').toBe('right');
    expect(p.gapRight, '移动端按钮在气泡右侧外面').toBeGreaterThanOrEqual(0);
    // C. 触屏点按 ⇒ 同一个共用件对话框
    const opened = await page.evaluate(() => window.__selAskClick());
    expect(opened.ok, '移动端点击也开共用件对话框').toBe(true);
    expect(opened.className).toContain('selection-dock');
    expect(opened.frame?.type, '移动端同一条 /ask 腿').toBe('ask');
    await page.evaluate(() => window.__selectionAskClose());
    await context.close();
  });
});

/* ═════ B2/B3/B4 — 引用 chip 落附件条（输入框上方）+ 替换语义 + ✕ 清除 ═════ */

test.describe('B2-B4 — 引用态', () => {
  test('右键引用 → 引用 chip 落输入框上方附件条；换引替换；✕ 清除', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const aiId = await page.evaluate(() => window.__renderHistory([{ type: 'ai', text: '第一条助手结论，比较长一点的摘要内容' }]).then(() => window.__msgIds()[0]));
    const userId = await page.evaluate(() => window.__renderUserRow('用户原始消息'));

    // B2 引用助手行 —— chip 与附件同宿主（#attachment-preview）、位于输入框上方
    const q1 = await page.evaluate(async id => window.__startQuoteOn(id), aiId);
    expect(q1.ok, '菜单引用项存在').toBe(true);
    let strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.hidden, '引用 chip 可见').toBe(false);
    expect(strip.hostId, 'chip 宿主 = 附件条 #attachment-preview（与附件同条带）').toBe('attachment-preview');
    expect(strip.aboveInput, 'chip 整体位于输入框上方').toBe(true);
    expect(strip.refType, 'chip refType=chat-message').toBe('chat-message');
    expect(strip.chipText).toContain('主 Agent：');
    expect(strip.chipText).toContain('第一条助手结论');
    expect(strip.hasRemove, '✕（实心圆白）在场').toBe(true);
    expect(await page.evaluate(() => window.__pendingQuoteCount()), 'pendingAttachments 恰 1 条引用').toBe(1);

    // B3 换引 = 替换语义
    const q2 = await page.evaluate(async id => window.__startQuoteOn(id), userId);
    expect(q2.ok).toBe(true);
    strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.chipText).toContain('我：');
    expect(strip.chipText).toContain('用户原始消息');
    expect(await page.evaluate(() => window.__pendingQuoteCount()), '换引后仍 1 条').toBe(1);

    // B4 ✕ 清除
    const rm = await page.evaluate(() => window.__clearQuoteViaX());
    expect(rm.clicked).toBe(true);
    strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.hidden, '✕ 后引用 chip 消失').toBe(true);
    expect(await page.evaluate(() => window.__pendingQuoteCount()), '✕ 后引用态清空').toBe(0);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B5 — 带引用发送：引用块渲染在气泡上方 ═══════════ */

test.describe('B5 — 发送后引用块', () => {
  test('data-msg-id=clientMessageId；引用块在气泡上方；引用不进气泡内 ref 卡', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 引用一条助手消息（历史腿渲染，确保同屏目标在场）
    await page.evaluate(() => window.__renderHistory([
      { type: 'ai', text: '这是被引用的助手结论' },
      { type: 'user', text: '早期用户消息' },
    ]));
    const aiId = await page.evaluate(() => window.__msgIds()[0]);
    await page.evaluate(async id => window.__startQuoteOn(id), aiId);

    const rowId = await page.evaluate(() => window.__renderUserRowWithQuote('并进周报', 'cm-x-1').dataset.msgId);
    expect(rowId, '行 id = 传入的 clientMessageId').toBe('cm-x-1');

    const shape = await page.evaluate(id => window.__quoteShape(id), rowId);
    expect(shape, '引用块在场').toBeTruthy();
    expect(shape.quoteBlockFirst, '引用块在气泡正上方').toBe(true);
    expect(shape.sender, '被引用者名加粗 span').toBe('主 Agent');
    expect(shape.text).toContain('这是被引用的助手结论'.slice(0, 10));
    expect(shape.inBubbleRefCards, 'chat-message ref 不进气泡 ref 卡循环').toBe(0);
    expect(shape.hasBubble, '正文气泡在场').toBe(true);
    expect(shape.bubbleText).toBe('并进周报');
    expect(shape.jump, '同屏目标在场 ⇒ ready').toBe('ready');
    expect(shape.note, 'ready 态无降级注记').toBe('');

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B6 — 摘要规则（§2.5 表）═══════════ */

test.describe('B6 — 摘要规则', () => {
  test('图片 [图片] 文件名；工具 [工具] label · summary；长文截断 60+…', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'image', fileName: 'screenshot-2026.png', path: '/tmp/x/screenshot-2026.png',
      senderRole: 'user', senderName: '我', ts: Date.now(),
    }));
    let s = await page.evaluate(() => window.__quoteStripState());
    expect(s.chipText).toContain('[图片]');
    expect(s.chipText).toContain('screenshot-2026.png');

    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'tool', label: 'Read', summary: '读取 src/main.rs',
      senderRole: 'tool', senderName: 'Nebula', ts: Date.now(),
    }));
    s = await page.evaluate(() => window.__quoteStripState());
    expect(s.chipText).toContain('[工具]');
    expect(s.chipText).toContain('Read');
    expect(s.chipText).toContain('读取 src/main.rs');

    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'ask', question: '请选择部署目标环境？',
      senderRole: 'askUser', senderName: 'Nebula', ts: Date.now(),
    }));
    s = await page.evaluate(() => window.__quoteStripState());
    expect(s.chipText).toContain('[提问]');
    expect(s.chipText).toContain('请选择部署目标环境？');

    const long = '一'.repeat(120);
    await page.evaluate(txt => window.__startQuoteDirect({
      kind: 'text', text: txt, senderRole: 'user', senderName: '我', ts: Date.now(),
    }), long);
    const excerpt = await page.evaluate(() => window.__pendingQuoteExcerpt());
    expect(excerpt.length, '长文显示摘要截到 60+…').toBe(61);
    expect(excerpt.endsWith('…')).toBe(true);

    const original = '引用正文\n\t' + 'x'.repeat(2500) + '结尾';
    const wire = await page.evaluate(async text => {
      const q = await import('/src/main/resources/web/js/msgQuote.js');
      const model = await import('/src/main/resources/web/js/refModel.js');
      return model.makeReference(q.chatMessageRefInput({kind: 'text', text, senderName: '作者'}));
    }, original);
    expect(wire.content.fullText).toBe(original);
    expect(wire.content.fullTextComplete).toBe(true);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B7 — 同屏跳转 + 高亮 ═══════════ */

test.describe('B7 — 点击引用块跳转', () => {
  test('目标行滚入视口 + 1.8s 整块 fade 高亮', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__renderHistory([
      { type: 'ai', text: '被引用的助手结论，等待跳转' },
      { type: 'user', text: '中间消息一' },
      { type: 'user', text: '中间消息二' },
    ]));
    await page.evaluate(() => window.__makeRowsTall());
    await page.evaluate(() => window.__renderUserRow('最新消息（视口外）'));

    const aiId = await page.evaluate(() => window.__msgIds().find((id, i) => {
      const row = window.__rows()[i];
      return row && row.querySelector('.bubble.ai') && !row.querySelector('.msg-quote-block');
    }));
    await page.evaluate(async id => window.__startQuoteOn(id), aiId);
    const quoteRowId = await page.evaluate(() => window.__renderUserRowWithQuote('跳转测试').dataset.msgId);

    const jump = await page.evaluate(async id => window.__jumpFromQuote(id), quoteRowId);
    expect(jump.ok).toBe(true);
    expect(jump.targetFound, '目标行在同屏 DOM').toBe(true);
    expect(jump.inView, '点击后目标行滚入视口（完全可见；本就居中 ⇒ 居中写为亚像素 no-op）').toBe(true);
    expect(jump.flash, '目标行带 quote-target-flash 高亮类').toBe(true);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B11 — footer 引用按钮（点按钮 = 与右键引用同效）═══════════
 * 2026-10-09 用户原话：「footer 不是有复制按钮吗 在旁边加一个双引号的引用按钮呗…
 * 既可以右键也可以点按钮」。按钮 ⊆ footer：只长在已有 .duration-badge 徽章里
 * （复制钮右侧、同族 .msg-copy 材质）；无 footer 的行（工具卡等，beta.56 裁定
 * footer 仅消息类）保持右键单入口 —— 挂点复用 attachQuoteMenu 同一收口。 */

test.describe('B11 — footer 引用按钮', () => {
  test('用户行：复制钮旁引用钮在场；点按钮与右键引用逐字同效', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const userId = await page.evaluate(() => window.__renderUserRow('按钮引用这条消息'));

    const btn = await page.evaluate(id => window.__quoteButtonOn(id), userId);
    expect(btn.present, 'footer 引用按钮在场').toBe(true);
    expect(btn.family, '与复制钮同族（.msg-copy 材质族）').toBe(true);
    expect(btn.badge, '长在 footer 徽章内').toBe(true);
    expect(btn.nextToCopy, '紧挨复制钮右侧').toBe(true);
    expect(btn.title, 'tooltip 复用 messages.quote（引用）').toBe('引用');

    // 点按钮 → 引用 chip 落附件条（与 B2 右键路径同一断言面）
    const clickRes = await page.evaluate(id => window.__clickQuoteButtonOn(id), userId);
    expect(clickRes.ok, '按钮可点').toBe(true);
    const viaButton = await page.evaluate(() => window.__quoteStripState());
    expect(viaButton.hidden, '引用 chip 可见').toBe(false);
    expect(viaButton.hostId, 'chip 宿主 = 附件条（按钮腿同条带）').toBe('attachment-preview');
    expect(viaButton.refType, 'chip refType=chat-message').toBe('chat-message');
    expect(viaButton.chipText).toContain('我：');
    expect(viaButton.chipText).toContain('按钮引用这条消息');
    expect(await page.evaluate(() => window.__pendingQuoteCount()), 'pendingAttachments 恰 1 条').toBe(1);

    // 同效断言：✕ 清除后改走右键引用 → 引用芯片逐字一致（= 完全相同的 startQuote 动作）
    await page.evaluate(() => window.__clearQuoteViaX());
    const menuRes = await page.evaluate(id => window.__startQuoteOn(id), userId);
    expect(menuRes.ok, '右键引用项存在').toBe(true);
    const viaMenu = await page.evaluate(() => window.__quoteStripState());
    expect(viaMenu.chipText, '点按钮 = 与右键引用同效（芯片逐字一致）').toBe(viaButton.chipText);
    expect(await page.evaluate(() => window.__pendingQuoteCount()), '右键腿同样恰 1 条').toBe(1);

    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('AI 历史行（时长徽章）/历史用户行/注入行带钮；无 footer 的工具卡行保持右键单入口', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const ids = await page.evaluate(() => window.__renderHistory([
      { type: 'ai', text: '带时长徽章的历史助手行', durationMs: 4200, timestamp: 1760000000000 },
      { type: 'user', text: '历史用户行', timestamp: 1760000000001 },
    ]));
    const injId = await page.evaluate(() => window.__renderInjectedRow('Mail · 注入通知'));
    const toolId = await page.evaluate(() => window.__renderToolRow('Read', '读取 src/main.rs'));

    const aiBtn = await page.evaluate(id => window.__quoteButtonOn(id), ids[0]);
    expect(aiBtn.present, 'AI 历史行时长徽章带引用钮').toBe(true);
    const userBtn = await page.evaluate(id => window.__quoteButtonOn(id), ids[1]);
    expect(userBtn.present, '历史用户行带引用钮').toBe(true);
    const injBtn = await page.evaluate(id => window.__quoteButtonOn(id), injId);
    expect(injBtn.present, '注入行带引用钮').toBe(true);

    // 点 AI 行按钮 → 主 Agent 摘要（时长徽章腿的按钮同样可用）
    await page.evaluate(id => window.__clickQuoteButtonOn(id), ids[0]);
    const strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.hidden, '引用 chip 可见').toBe(false);
    expect(strip.chipText).toContain('主 Agent：');
    expect(strip.chipText).toContain('带时长徽章的历史助手行');

    // 边界（按钮 ⊆ footer）：工具卡行右键可用但无 footer ⇒ 无引用钮，右键仍是引用入口
    const toolBtn = await page.evaluate(id => window.__quoteButtonOn(id), toolId);
    expect(toolBtn.present, '工具卡行无 footer ⇒ 无引用钮').toBe(false);
    const toolMenu = await page.evaluate(id => window.__menuLabelsOn(id), toolId);
    expect(toolMenu, '工具卡行右键引用仍在').toEqual(['引用']);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ B8/B9/B10 — 历史重渲（m.quote）═══════════ */

test.describe('B8-B10 — restoreFromBackendHistory 重渲', () => {
  test('命中页内目标 ⇒ ready 可点；未命中 ⇒ 删除降级注记；h-id 页内唯一', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const ids = await page.evaluate(() => window.__renderHistory([
      { type: 'ai', text: '被引用的历史助手消息' },
      {
        type: 'user', text: '带引用的历史发送', timestamp: 1760000000000,
        // 引用页内第 0 行（目标在场 ⇒ ready）—— refMessageId 用占位，
        // 真实形状由前端历史腿挂 h-id 后由 syncQuoteJumpStates 收敛；
        // 这里直接用「未来才存在的 id」构造，随后用 DOM 内真实 id 替换校验 ready 面。
        quote: { refMessageId: '__pending__', excerpt: '被引用的历史助手消息', senderRole: 'ai', senderName: 'Nebula' },
      },
      {
        type: 'user', text: '引用已删消息的历史发送', timestamp: 1760000000001,
        quote: { refMessageId: 'h999-gone', excerpt: '已被删除的原文', senderRole: 'user', senderName: '我' },
      },
    ]));
    expect(ids.filter(Boolean).length, '三条历史行全部挂 id').toBe(3);
    expect(new Set(ids).size, '页内 id 唯一').toBe(3);

    // 把占位 refMessageId 替换为页内第 0 行的真实 h-id（模拟「恢复后 id 收敛命中」）
    await page.evaluate(firstId => {
      const box = document.querySelector('#chat .row[data-msg-id] .msg-quote-block');
      box.dataset.refMessageId = firstId;
      const firstRow = window.__rowById(firstId);
      const ownerBox = firstRow.parentElement.querySelector('.msg-quote-block') || box;
      void ownerBox;
      // 直接对该块跑一次单点收敛（与 restore 末尾同一函数）
      return window.__syncJump ? window.__syncJump() : null;
    }, ids[0]).catch(() => {});

    const shapes = await page.evaluate(() => {
      const rows = window.__rows();
      return rows.map(r => {
        const box = r.querySelector('.msg-quote-block');
        if (!box) return null;
        return {
          jump: box.dataset.quoteJump || '',
          role: box.getAttribute('role') || '',
          note: (box.querySelector('.msg-quote-note') || {}).textContent || '',
          text: (box.querySelector('.msg-quote-text') || {}).textContent || '',
        };
      }).filter(Boolean);
    });
    expect(shapes.length, '两个引用块重渲').toBe(2);
    // 已删块（h999-gone 不在 DOM）：无指针 + 注记
    const deleted = shapes.find(s => s.text.includes('已被删除的原文'));
    expect(deleted.jump, '未命中 ⇒ 无可点入口').toBe('none');
    expect(deleted.role, '无按钮语义（禁死点击）').toBe('');
    expect(deleted.note, '「原消息已删除」降级注记').toBe('原消息已删除');
    // 命中块：占位 id 修正后（B8 用跳转面单独验证）
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('命中页内目标的历史引用块可点（ready 面）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__renderHistory([
      { type: 'user', text: '页首原始消息' },
      {
        type: 'user', text: '引用它的历史发送', timestamp: 1760000000002,
        quote: { refMessageId: 'will-be-replaced', excerpt: '页首原始消息', senderRole: 'user', senderName: '我' },
      },
    ]));
    // 用页内真实 id 修正引用块目标，再跑同一收敛单点（msgQuote.syncQuoteJumpStates
    // 经 harness 重新 import 暴露）
    const fixed = await page.evaluate(async () => {
      const rows = window.__rows();
      const firstId = rows[0].dataset.msgId;
      const box = rows[1].querySelector('.msg-quote-block');
      box.dataset.refMessageId = firstId;
      const qm = await import('/src/main/resources/web/js/msgQuote.js');
      qm.syncQuoteJumpStates(document.getElementById('chat'));
      return window.__quoteShape(rows[1].dataset.msgId);
    });
    expect(fixed.jump).toBe('ready');
    expect(fixed.role, 'ready ⇒ 按钮语义').toBe('button');
    expect(fixed.note, '').toBe('');
    expect(fixed.pointer, 'ready ⇒ pointer').toBe('pointer');

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ══ B12 — 引用 chip 落面形态（2026-10-09 用户反馈改版）═══
 * 原话：「不需要铺满整个对话框，应该要像我添加这个附件类似的样式和大小。而且位置
 * 也是在上面而不是在下面，这样如果引用+附件的情况，形式能够统一」。断言面 =
 * 同条带（#attachment-preview）、同大小档（高度 ±4px）、输入框上方、并存同一行、
 * 溢出走换行（flex-wrap）不横向溢出。颜色/图标沿用现状（sapphire 材质 +
 * message-circle + 实心圆白 ✕）—— 不在本批断言翻转面内。 */

test.describe('B12 — 引用 chip 落面形态', () => {
  test('与附件 chip 同条带、同大小档、位于输入框上方；并存同一行排布', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 几何读数避开 chip 入场动画（taskref-in 0.18s translateY）—— 走既有
    // prefers-reduced-motion 分支（input.css .att-ref 段：animation none）。
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const userId = await page.evaluate(() => window.__renderUserRow('形态断言的目标消息'));
    await page.evaluate(async id => window.__startQuoteOn(id), userId);
    await page.evaluate(() => window.__attachPlainFile('周报-draft.pdf'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

    const g = await page.evaluate(() => window.__chipLayout());
    expect(g.quote, '引用 chip 在场').toBeTruthy();
    expect(g.file, '附件 chip 在场').toBeTruthy();
    expect(g.quoteAboveInput, '引用 chip 整体位于输入框上方').toBe(true);
    expect(g.fileAboveInput, '附件 chip 亦在输入框上方（同一条带）').toBe(true);
    expect(g.sameRow, '引用 chip 与附件 chip 同一行排布').toBe(true);
    expect(g.bothInHost, '两 chip 都落在附件条盒内').toBe(true);
    expect(g.noHorizontalOverflow, '附件条无横向溢出').toBe(true);
    expect(g.heightDelta, '引用 chip 与附件 chip 高度同档（±4px）').toBeLessThanOrEqual(4);
    expect(g.quote.width, 'chip 收缩为内容宽（非整宽横条）').toBeLessThan(g.host.width);

    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('窄条带（240px）：多 chip 溢出走换行 —— 无横向溢出、不越条带、不压输入框', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.emulateMedia({ reducedMotion: 'reduce' }); // 几何读数避开入场动画
    const userId = await page.evaluate(() => window.__renderUserRow('窄容器目标消息'));
    await page.evaluate(async id => window.__startQuoteOn(id), userId);
    await page.evaluate(() => {
      document.getElementById('input-bar').style.width = '300px'; // 收窄条带逼出换行
      window.__attachPlainFile('a-very-long-filename-xxxxxxxxxxxx.pdf');
      window.__attachPlainFile('second-file-with-a-long-name-too.pdf');
    });
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

    const g = await page.evaluate(() => window.__chipLayout());
    expect(g.quote, '引用 chip 在场').toBeTruthy();
    expect(g.noHorizontalOverflow, '换行处理 ⇒ 无横向溢出').toBe(true);
    expect(g.bothInHost, '引用 chip 换行后仍整体落在条带盒内').toBe(true);
    expect(g.quoteAboveInput, '换行后引用 chip 仍在输入框上方').toBe(true);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ══ B13 — 暗亮两态截图自验 ══
 * chip 材质走 sapphire alpha token（亮 0.06 / 暗 0.10，input.css .att-ref 段）：
 * emulateMedia 切 colorScheme ⇒ 计算背景必变；双主题 composer 截图落
 * .nebflow/evidence/20261010_quotechip-composer/ 供人工复核。 */

test.describe('B13 — 暗亮两态截图自验', () => {
  test('chip 计算背景随 colorScheme 切换 + 双主题截图存证非空', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
    const userId = await page.evaluate(() => window.__renderUserRow('主题截图目标消息'));
    await page.evaluate(async id => window.__startQuoteOn(id), userId);
    await page.evaluate(() => window.__attachPlainFile('theme-check.pdf'));
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

    const bgs = {};
    const shots = {};
    for (const scheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.waitForTimeout(260); // 入场动画 0.18s 落定后再取数/截图
      const s = await page.evaluate(() => window.__quoteStripState());
      expect(s.hidden, `${scheme} 态 chip 在场`).toBe(false);
      expect(s.aboveInput, `${scheme} 态 chip 在输入框上方`).toBe(true);
      bgs[scheme] = s.chipBg;
      const file = path.join(EVIDENCE_DIR, `composer-quote-${scheme}.png`);
      await page.locator('#input-bar').screenshot({ path: file, animations: 'disabled' });
      shots[scheme] = file;
    }
    expect(bgs.light, '两态计算背景不同（主题真切换，非同一硬编码色）').toBeTruthy();
    expect(bgs.light).not.toBe(bgs.dark);
    for (const [scheme, f] of Object.entries(shots)) {
      expect(fs.existsSync(f), `${scheme} 截图在场`).toBe(true);
      expect(fs.statSync(f).size, `${scheme} 截图非空`).toBeGreaterThan(1000);
    }

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ══ B14 — 空引用不发（零回归：fwdguard 纯引用帧闸 × 新 chip 落面）═══ */

test.describe('B14 — 纯引用帧闸（空引用不发）', () => {
  test('仅引用无正文 ⇒ 闸生效、发送键禁用；补附件后闸解除', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const userId = await page.evaluate(() => window.__renderUserRow('闸测试目标消息'));
    await page.evaluate(async id => window.__startQuoteOn(id), userId);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

    let gate = await page.evaluate(() => window.__gateProbe());
    expect(gate.refOnly, '空正文 + 仅引用 = 纯引用帧').toBe(true);
    expect(gate.blocked, '闸生效').toBe(true);
    expect(gate.disabled, '发送键禁用（空引用不发）').toBe(true);

    // 补一枚附件 ⇒ wire 载荷非空 ⇒ 闸解除（并存形态不误伤）
    await page.evaluate(() => window.__attachPlainFile('gate-check.pdf'));
    gate = await page.evaluate(() => window.__gateProbe());
    expect(gate.refOnly, '引用+附件并存 = 非纯引用帧').toBe(false);
    expect(gate.blocked, '闸解除').toBe(false);
    expect(gate.disabled, '发送键恢复').toBe(false);

    // ✕ 清引用（纯附件帧）⇒ 闸保持解除
    await page.evaluate(() => window.__clearQuoteViaX());
    gate = await page.evaluate(() => window.__gateProbe());
    expect(gate.refOnly).toBe(false);
    expect(gate.blocked, '清引用后闸不再介入').toBe(false);

    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ══ B15 — 引用块宽度约束（2026-10-20 作者令「引用文字不要超过用户发送的文字」）═══
 * 断言面 = 几何：引用块渲染宽 ≤ 气泡渲染宽（作者截图 2026-10-20 16.24.50：灰底
 * 引用块横铺整行、比下方绿色回复气泡还宽）。修法 = 引用块 + 文本气泡同时在场 ⇒
 * 包进 .msg-quote-composite 气泡驱宽复合盒（规则单点 = chat.css 同名段）：引用块
 * 本征贡献归零（width:0）+ 渲染宽 = 复合盒宽 = 气泡宽（min-width:100%），复合盒
 * max-width 继承气泡帽（80% 行宽）⇒ 窄气泡时引用块跟着窄；超长被引文本走既有
 * 60 字摘要 + 两行 clamp，不撑宽。live（renderUserBubble）与历史重放
 * （restoreFromBackendHistory）同一包裹规则。几何读数走 prefers-reduced-motion
 * （nf-pop 动画 none，rect 无 scale 抖动）。 */

test.describe('B15 — 引用块宽度约束（几何断言）', () => {
  const WIDTH_EVIDENCE_DIR = path.join(REPO_ROOT, '.nebflow/evidence/20261020_quoteblock-width');
  const QUOTED_AI_TEXT = '你推的数据面还不够广，要推广到所有，比如后台agent，消息，token面板，所有需要通过中继的数据都要盘点一遍，然后做统一的加速通道设计。';
  const REPLY_TEXT = '我这里还要补充一下，就是比如我是在Windows上，那么我就应该加速，这里的本机不是指主机，是指我们使用控制台的机器。';

  /** 渲染「引用长助手消息 + 回复」一行并读几何（row / quote / bubble 矩形宽）。 */
  async function quotedRowGeometry(page, replyText) {
    const ids = await page.evaluate(txt => window.__renderHistory([{ type: 'ai', text: txt }]), QUOTED_AI_TEXT);
    await page.evaluate(async id => window.__startQuoteOn(id), ids[0]);
    await page.evaluate(txt => window.__renderUserRowWithQuote(txt, 'cm-width-1'), replyText);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    return page.evaluate(() => {
      const row = document.querySelector('.row[data-msg-id="cm-width-1"]');
      const q = row.querySelector('.msg-quote-block');
      const b = row.querySelector('.bubble.user:not(.att-bubble)');
      const qr = q.getBoundingClientRect();
      const br = b.getBoundingClientRect();
      return {
        quoteW: qr.width,
        bubbleW: br.width,
        rowW: row.getBoundingClientRect().width,
        composite: !!row.querySelector('.msg-quote-composite'),
        quoteBlockFirst: q.nextElementSibling === b,
      };
    });
  }

  test('长引用+气泡：引用块 ≤ 气泡宽且不再铺满整行；复合盒在场、引用块仍在气泡正上方', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: 'light' });
    const g = await quotedRowGeometry(page, REPLY_TEXT);
    expect(g.composite, '引用+文本气泡 ⇒ 复合盒包裹').toBe(true);
    expect(g.quoteBlockFirst, '引用块仍在气泡正上方（B5 形态零回归）').toBe(true);
    expect(g.quoteW, '几何断言：引用块宽度 ≤ 气泡宽度').toBeLessThanOrEqual(g.bubbleW);
    expect(g.quoteW, '引用块不再铺满整行（修复前 = 整行宽）').toBeLessThan(g.rowW);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('窄气泡跟随：短回复 ⇒ 引用块跟着收窄到气泡宽（不被动引用撑宽）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: 'light' });
    const g = await quotedRowGeometry(page, '好的');
    expect(g.quoteW, '窄气泡 ⇒ 引用块 ≤ 气泡宽').toBeLessThanOrEqual(g.bubbleW);
    expect(g.bubbleW, '气泡维持内容宽（长引用未把气泡撑宽）').toBeLessThan(g.rowW * 0.3);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('zh/en × 亮暗各验一例：几何恒成立 + 双语双主题截图存证', async ({ browser }) => {
    fs.mkdirSync(WIDTH_EVIDENCE_DIR, { recursive: true });
    for (const [locale, scheme] of [['zh-CN', 'light'], ['zh-CN', 'dark'], ['en-US', 'light'], ['en-US', 'dark']]) {
      const { context, page, pageErrors } = await newPage(browser);
      await page.emulateMedia({ reducedMotion: 'reduce', colorScheme: scheme });
      if (locale !== 'zh-CN') {
        await page.evaluate(async loc => {
          (await import('/src/main/resources/web/js/i18n.js')).setLocale(loc);
        }, locale);
      }
      const g = await quotedRowGeometry(page, REPLY_TEXT);
      expect(g.quoteW, `${locale}/${scheme}：引用块宽度 ≤ 气泡宽度`).toBeLessThanOrEqual(g.bubbleW);
      const file = path.join(WIDTH_EVIDENCE_DIR, `after-${locale}-${scheme}.png`);
      await page.locator('.row[data-msg-id="cm-width-1"]').screenshot({ path: file, animations: 'disabled' });
      expect(fs.statSync(file).size, `${locale}/${scheme} 截图非空`).toBeGreaterThan(1000);
      expect(pageErrors).toEqual([]);
      await context.close();
    }
  });
});
