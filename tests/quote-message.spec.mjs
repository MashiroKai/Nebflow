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

/* ═════ B2/B3/B4 — 引用牌落附件条（输入框上方）+ 多选追加/单条移除 + ✕ 清除 ═════
 *
 * 🔴 多选引用批（2026-11-22 作者令逐字「还有支持 多选引用 现在引用两个还做不到」）：
 * B3 的旧断言「换引 = 替换 ⇒ 仍 1 条」是**被本批合法推翻**的规格（一条回复只带一条
 * 引用）。新规格：连续引用 = **追加**（顺序 = 选择顺序），同一条再点 = 取消该条，
 * 每条可单独 ✕ 移除；≥2 条时牌面自动缩成图标 chip（B17 专测）。 */

test.describe('B2-B4 — 引用态', () => {
  test('右键引用 → 引用牌落输入框上方附件条；第二次引用为追加（多选）；✕ 逐条/整体清除', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const aiId = await page.evaluate(() => window.__renderHistory([{ type: 'ai', text: '第一条助手结论，比较长一点的摘要内容' }]).then(() => window.__msgIds()[0]));
    const userId = await page.evaluate(() => window.__renderUserRow('用户原始消息'));

    // B2 引用助手行 —— 牌与附件同宿主（#attachment-preview）、位于输入框上方
    const q1 = await page.evaluate(async id => window.__startQuoteOn(id), aiId);
    expect(q1.ok, '菜单引用项存在').toBe(true);
    expect(q1.action, '未引用态 ⇒ 菜单项 =「引用」').toBe('引用');
    let strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.hidden, '引用牌可见').toBe(false);
    expect(strip.hostId, '牌宿主 = 附件条 #attachment-preview（与附件同条带）').toBe('attachment-preview');
    expect(strip.aboveInput, '牌整体位于输入框上方').toBe(true);
    expect(strip.refType, '牌 refType=chat-message').toBe('chat-message');
    expect(strip.chipText).toContain('主 Agent：');
    expect(strip.chipText).toContain('第一条助手结论');
    expect(strip.hasRemove, '✕（实心圆白）在场').toBe(true);
    expect(await page.evaluate(() => window.__pendingQuoteCount()), 'pendingAttachments 恰 1 条引用').toBe(1);
    let chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.compact, '① 选中 1 条 = 现状整卡预览（非紧凑档）').toBe(0);

    // B3 追加语义（本批新规格）：第二条引用 ⇒ 2 条，顺序 = 选择顺序
    const q2 = await page.evaluate(async id => window.__startQuoteOn(id), userId);
    expect(q2.ok).toBe(true);
    expect(await page.evaluate(() => window.__pendingQuoteCount()), '② 追加后 2 条（旧规格是替换成 1 条）').toBe(2);
    const order = await page.evaluate(() => window.__pendingQuoteOrder());
    expect(order.map(o => o.messageId), '选择顺序 = 卡片顺序（助手行在前、用户行在后）').toEqual([aiId, userId]);
    chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.count, '条带上 2 枚引用牌').toBe(2);
    expect(chips.compact, '③ ≥2 条 ⇒ 自动缩小为图标 chip').toBe(2);
    expect(chips.chips.map(c => c.order), '牌面顺序号 = 选择顺序').toEqual(['1', '2']);
    expect(chips.chips.every(c => c.hasOpen && c.hasRemove), '每枚牌面 = 回看钮 + ✕（每条可单独移除）').toBe(true);

    // B3' 同一条再点 = 取消该条（多选 toggle；菜单项文案同步切换为「取消引用」）
    const q3 = await page.evaluate(async id => window.__startQuoteOn(id), userId);
    expect(q3.action, '已在引用态 ⇒ 菜单项 =「取消引用」').toBe('取消引用');
    expect(await page.evaluate(() => window.__pendingQuoteCount()), 'toggle 移除后 1 条').toBe(1);
    chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.compact, '回到 1 条 ⇒ 牌面回到整卡档').toBe(0);
    strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.chipText).toContain('主 Agent：');

    // B4 ✕ 清除（单枚 ✕ 删尽后引用态清空）
    const rm = await page.evaluate(() => window.__clearQuoteViaX());
    expect(rm.clicked).toBe(true);
    strip = await page.evaluate(() => window.__quoteStripState());
    expect(strip.hidden, '✕ 后引用牌消失').toBe(true);
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

    // 多选引用批（2026-11-22）：引用态改追加语义 ⇒ 逐例复位（本用例验的是**摘要
    // 规则**，每例都必须是「单条整卡」的读数面）。
    await page.evaluate(() => window.__clearQuoteAll());
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'tool', label: 'Read', summary: '读取 src/main.rs',
      senderRole: 'tool', senderName: 'Nebula', ts: Date.now(),
    }));
    s = await page.evaluate(() => window.__quoteStripState());
    expect(s.chipText).toContain('[工具]');
    expect(s.chipText).toContain('Read');
    expect(s.chipText).toContain('读取 src/main.rs');

    await page.evaluate(() => window.__clearQuoteAll());
    await page.evaluate(() => window.__startQuoteDirect({
      kind: 'ask', question: '请选择部署目标环境？',
      senderRole: 'askUser', senderName: 'Nebula', ts: Date.now(),
    }));
    s = await page.evaluate(() => window.__quoteStripState());
    expect(s.chipText).toContain('[提问]');
    expect(s.chipText).toContain('请选择部署目标环境？');

    const long = '一'.repeat(120);
    await page.evaluate(() => window.__clearQuoteAll());
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

/* ═════ B17 — 多选引用（2026-11-22 作者令「还有支持 多选引用 现在引用两个还做不到」
 *              ＋「多选的就自动缩小为小图标」）═══════════════════════════════════
 *
 * 本块是**本批的新规格验收面**（B2-B4 已按追加语义同步改）：
 *   ① 多选可达且顺序 = 选择顺序（右键入口连续引用三条 ⇒ 牌面/数据面同序）；
 *   ② 选中 ≥2 条 ⇒ 牌面自动缩成图标 chip（定长短名、零 CSS 省略；单条回整卡档）；
 *      每条可单独移除，幸存者按新序重排（牌面 = 现实）；
 *   ③ 发送链路：全部引用按选择顺序进同一条消息（live 腿 N 块引用块、顺序一致、
 *      复合盒在场）；历史重放腿读引擎落盘的 quotes 有序数组逐条渲染（旧 quote
 *      单条行仍渲染 1 块 = 旧读法零回归）；
 *   ④ 牌面回看原消息：点牌 = 目标行高亮（与气泡引用块同一实现）；目标不在页内 ⇒
 *      就地展开快照摘要（零死点击）；
 *   ⑤ 真机尺寸（390×844）不撑爆 composer：条带无横向溢出、牌面仍在输入框上方、
 *      手机层「+N」折叠接管溢出。
 * 证据帧：.nebflow/evidence/20261122_quote-multiselect/（真实渲染像素）。
 */
const MULTI_EVIDENCE_DIR = path.join(REPO_ROOT, '.nebflow/evidence/20261122_quote-multiselect');

test.describe('B17 — 多选引用', () => {
  test('连续引用三条 ⇒ 追加、顺序 = 选择顺序、牌面缩成图标 chip（定长可读短名）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 几何断言在牌面入场动画之外采样（reduce = 既有 CSS 的 animation:none 档，
    // 与 B12/B13/B15 同口径）。
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const ids = await page.evaluate(async () => {
      const a = window.__renderUserRow('第一条原始消息');
      const b = window.__renderUserRow('第二条原始消息');
      window.__renderToolRow('Read', '读取 src/main.rs');
      const t = Array.from(document.querySelectorAll('#chat > .row.tool')).pop().dataset.msgId;
      return [a, b, t];
    });
    for (const id of ids) {
      const r = await page.evaluate(async rid => window.__startQuoteOn(rid), id);
      expect(r.ok, `引用 ${id} 成功`).toBe(true);
    }
    expect(await page.evaluate(() => window.__pendingQuoteCount()), '三条全部在引用态（旧实现在这里只有 1 条）').toBe(3);
    const order = await page.evaluate(() => window.__pendingQuoteOrder());
    expect(order.map(o => o.messageId), '数据面顺序 = 点击顺序').toEqual(ids);
    const chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.count).toBe(3);
    expect(chips.compact, '≥2 条 ⇒ 全部缩成紧凑图标 chip').toBe(3);
    expect(chips.chips.map(c => c.order), '牌面顺序号 1/2/3').toEqual(['1', '2', '3']);
    // 短名可读且**定长**（角色词：我 / 工具），标签不靠省略号（text-overflow: clip）
    expect(chips.chips[0].label, '第 1 枚 = 顺序号 + 发送方短名').toBe('1 · 我');
    expect(chips.chips[2].label, '第 3 枚 = 工具行短名').toBe('3 · 工具');
    expect(chips.chips.every(c => c.textOverflow === 'clip'), '牌面标签零 ellipsis 截断').toBe(true);
    expect(chips.chips.every(c => c.labelOverflowing === false), '定长短名不溢出（无需截断）').toBe(true);
    expect(chips.chips.every(c => c.aboveInput && c.inHost), '牌面全在条带内、位于输入框上方').toBe(true);
    expect(chips.hostOverflowX, '条带无横向溢出').toBe(false);
    expect(chips.chips.every(c => c.height === 28), '牌面保持 28px 档（与附件 chip 同档）').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('单条移除后幸存者按新序重排；点牌回看原消息（目标高亮）/目标不在页内降级展开', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const ids = await page.evaluate(async () => {
      const a = window.__renderUserRow('甲消息');
      const b = window.__renderUserRow('乙消息');
      const c = window.__renderUserRow('丙消息');
      return [a, b, c];
    });
    for (const id of ids) await page.evaluate(async rid => window.__startQuoteOn(rid), id);
    // ② 移除中间一条 ⇒ 幸存者顺序 = 原相对顺序、牌面顺序号重排
    const removed = await page.evaluate(() => window.__removeChip(1));
    expect(removed.ok).toBe(true);
    expect(removed.left, '3 - 1 = 2 条').toBe(2);
    const order = await page.evaluate(() => window.__pendingQuoteOrder());
    expect(order.map(o => o.messageId), '幸存者 = 第 1、3 条（原相对顺序不变）').toEqual([ids[0], ids[2]]);
    let chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.chips.map(c => c.order), '牌面顺序号重排为 1/2').toEqual(['1', '2']);
    // ④ 点牌回看原消息 ⇒ 目标行高亮（与气泡引用块点击同一实现）
    const jump = await page.evaluate(() => window.__revealChip(1));
    expect(jump.ok).toBe(true);
    expect(jump.flashed, '第 2 枚牌指向「丙消息」⇒ 该行高亮').toEqual([ids[2]]);
    expect(jump.expanded, '命中目标 ⇒ 不展开摘要').toBe(false);
    // 目标不在页内 ⇒ 就地展开快照摘要（零死点击）
    await page.evaluate(() => window.__clearChat());
    await page.evaluate(() => window.__renderUserRow('仍在页内的行'));
    const probe = await page.evaluate(() => {
      const chip = document.querySelector('#attachment-preview .att-ref[data-ref-type="chat-message"]');
      const open = chip.querySelector('.att-ref-open');
      open.click();
      return { expanded: chip.classList.contains('expanded'), meta: (chip.querySelector('.att-ref-meta') || {}).textContent || '' };
    });
    expect(probe.expanded, '目标行不在页内 ⇒ 牌面展开').toBe(true);
    expect(probe.meta).toContain('甲消息');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('发送：三条引用按选择顺序进同一条消息（live 腿 3 块引用块、复合盒在场、逐块可跳转）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    fs.mkdirSync(MULTI_EVIDENCE_DIR, { recursive: true });
    const ids = await page.evaluate(async () => {
      const a = window.__renderUserRow('第一条原始消息');
      const b = window.__renderUserRow('第二条原始消息');
      const c = window.__renderUserRow('第三条原始消息');
      return [a, b, c];
    });
    for (const id of ids) await page.evaluate(async rid => window.__startQuoteOn(rid), id);
    const rowId = await page.evaluate(() => window.__sendPendingQuoted('三条并进同一回复', 'cm-multi-1'));
    const blocks = await page.evaluate(id => window.__quoteBlocksOn(id), rowId);
    expect(blocks.length, '发送后消息面 = 3 块引用块（顺序 = 选择顺序）').toBe(3);
    expect(blocks.map(b => b.refMessageId), '块顺序 = 选择顺序').toEqual(ids);
    expect(blocks.map(b => b.text.includes('原始消息')), '逐块摘要落位').toEqual([true, true, true]);
    expect(blocks.every(b => b.jump === 'ready'), '三条目标都在页内 ⇒ 逐块可跳转').toBe(true);
    expect(blocks.every(b => b.inComposite && b.beforeBubble), '全部在复合盒内、气泡正上方').toBe(true);
    // 证据帧（真实渲染像素）：多选态 composer + 发送后消息面多块
    await page.locator('#input-bar').screenshot({ path: path.join(MULTI_EVIDENCE_DIR, 'desktop-after-multi-composer.png'), animations: 'disabled' });
    await page.locator(`.row[data-msg-id="${rowId}"]`).screenshot({ path: path.join(MULTI_EVIDENCE_DIR, 'desktop-after-multi-sent.png'), animations: 'disabled' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('历史重放：quotes 有序数组 ⇒ 逐块渲染；旧 quote 单条行仍 1 块（旧读法零回归）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const rows = await page.evaluate(async () => {
      // 目标行（跳转就绪面）先在场
      const t1 = window.__renderUserRow('历史目标一');
      const t2 = window.__renderUserRow('历史目标二');
      const multi = [t1, t2].map((id, i) => ({
        refMessageId: id,
        excerpt: `历史被引 ${i + 1}`,
        senderRole: 'user',
        senderName: '我',
        ts: 1700000000000 + i,
      }));
      await window.__renderHistory([
        // 引擎落盘形态：quote = 首条（旧读法）+ quotes = 全量有序（新读法）
        { type: 'user', text: '多条并引回复', quote: multi[0], quotes: multi },
        { type: 'user', text: '旧单条引用回复', quote: multi[0] },
      ]);
      return window.__msgIds();
    });
    const multiRow = rows[rows.length - 2];
    const legacyRow = rows[rows.length - 1];
    const multiBlocks = await page.evaluate(id => window.__quoteBlocksOn(id), multiRow);
    expect(multiBlocks.length, '引擎落盘的 quotes 有序数组 ⇒ 2 块（顺序一致）').toBe(2);
    expect(multiBlocks.map(b => b.jump), '命中页内目标 ⇒ 逐块 ready').toEqual(['ready', 'ready']);
    expect(multiBlocks[0].text).toContain('历史被引 1');
    expect(multiBlocks[1].text).toContain('历史被引 2');
    const legacyBlocks = await page.evaluate(id => window.__quoteBlocksOn(id), legacyRow);
    expect(legacyBlocks.length, '旧 quote 单条行 ⇒ 仍 1 块（旧读法逐字不变）').toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('真机尺寸（390×844）：多选 5 条不撑爆 composer —— 无横向溢出、不压输入框', async ({ browser }) => {
    // 视口口径 = 作者主用 iPhone Safari 的 CSS 像素档（390×844）。本壳无 viewport meta
    // （既有 B16 触屏腿依赖 980 布局视口），故这里用 viewport + hasTouch 精确量 390
    // CSS px，并显式挂 body.mobile-layer（产品真机姿态类）—— 与产品同一条手机层
    // 样式域；isMobile emulation 会绕过本壳的固定宽度夹具，故不启用。
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', e => pageErrors.push(String(e && e.message ? e.message : e)));
    await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
    await page.waitForFunction(() => window.__ready === true);
    await page.evaluate(() => { document.body.classList.add('mobile-layer'); });
    const ids = await page.evaluate(async () => {
      const out = [];
      for (let i = 1; i <= 5; i++) out.push(window.__renderUserRow(`手机档第 ${i} 条被引消息`));
      return out;
    });
    // 手机层的引用入口 = 行 footer 引用钮（长按/右键菜单在 mobile-layer 下不出，
    // 见 attachQuoteMenu 的手机端长按退役批）—— 多选可达性按真入口验。
    for (const id of ids) {
      const r = await page.evaluate(async rid => window.__clickQuoteButtonOn(rid), id);
      expect(r.ok, `footer 引用钮在手机档可用（${id}）`).toBe(true);
    }
    const chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.count, '5 枚引用牌都在数据面').toBe(5);
    expect(chips.compact, '全部紧凑档').toBe(5);
    expect(chips.hostOverflowX, '手机层条带无横向溢出（溢出走 +N 折叠）').toBe(false);
    const geom = await page.evaluate(() => {
      const host = document.getElementById('attachment-preview');
      const input = document.getElementById('input');
      const hr = host.getBoundingClientRect();
      const ir = input.getBoundingClientRect();
      return {
        hostBottom: Math.round(hr.bottom), inputTop: Math.round(ir.top),
        hostW: Math.round(hr.width), inputW: Math.round(ir.width),
        inputVisible: ir.height > 0 && ir.top >= 0 && ir.bottom <= window.innerHeight,
        foldCount: host.querySelectorAll('.mh-att-collapsed').length,
        moreBtn: !!host.querySelector('.mh-att-more'),
        viewport: { w: window.innerWidth, h: window.innerHeight },
      };
    });
    expect(geom.hostBottom, '条带底 ≤ 输入框顶（不压输入框）').toBeLessThanOrEqual(geom.inputTop + 1);
    expect(geom.inputVisible, '输入框仍在视口内').toBe(true);
    expect(geom.viewport.w, '量的是 390 真机档').toBe(390);
    // 手机层溢出：要么折叠腿接管（+N / collapsed），要么条带自身不溢出 —— 二者必居其一
    expect(geom.foldCount > 0 || geom.moreBtn || !chips.hostOverflowX).toBe(true);
    fs.mkdirSync(MULTI_EVIDENCE_DIR, { recursive: true });
    await page.locator('#input-bar').screenshot({ path: path.join(MULTI_EVIDENCE_DIR, 'mobile-390-after-multi-composer.png'), animations: 'disabled' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});


/* ═══════ B18 — ④ 引用**作者自己**的消息（2026-11-22 09:21 作者补充令）═══════
 * 作者令逐字：「我发现 我无法引用自己的话 这个要支持」。
 *
 * 现状排查（本腿要钉住的面）：引用链上**没有任何 senderRole 过滤** ——
 *   · 入口 ①  msgQuote.js::openQuoteMenuForRow（菜单项 复制+引用/取消引用，无角色判断）
 *   · 入口 ②  msgQuote.js::mountQuoteFooterButton（行 footer 引用钮，无角色判断）
 *   · 渲染 ③  chat.js::renderUserBubble 的行级 attachQuoteMenu（senderRole:'user'）+ 气泡
 *              引用块（quoteViewOf 把 'user' 映成「我」）
 *   · 落盘 ④  WebSocketRoutes.quotesFromRefs / RefResolver.scala 的 chat-message 分支
 *  ⇒ 自引与它引必须是**同一交互面、同一卡片形态**（短名/展开/投递规则一致），
 *    本条把「自己的消息」做成显式回归项（此前只有它引侧的用例）。
 * 另外：手机层的唯一入口是 footer 引用钮（长按/右键菜单在 mobile-layer 下不出），
 * 自引的手机可达性单列一腿（作者主视口 = iPhone）。 */

test.describe('B18 — ④ 引用作者自己的消息', () => {
  test('自己消息：右键菜单与 footer 钮两入口都可引；与 AI 消息混选；发送后卡片同形可跳', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 铺两条目标：先铺历史 AI 行（restoreFromBackendHistory 会重写 #chat，故先于
    // 作者自己那条），再铺作者自己的一条既往消息 —— 混选腿要两者同时在页。
    const aiId = await page.evaluate(async () => {
      await window.__renderHistory([{ type: 'ai', text: 'AI 的回答正文', timestamp: Date.now() }]);
      const ai = Array.from(document.querySelectorAll('#chat > .row.ai'));
      return ai.length ? ai[ai.length - 1].dataset.msgId : null;
    });
    expect(aiId, 'AI 目标行在场（它引侧对账用）').toBeTruthy();
    const ownId = await page.evaluate(() => window.__renderUserRow('我自己的原话：先查 A 再查 B'));
    expect(ownId, '作者自己那条行在场').toBeTruthy();

    // ① 入口 a：右键菜单对自己消息给出「引用」（无角色过滤 —— 现状反证点）
    const menu = await page.evaluate(id => window.__menuLabelsOn(id), ownId);
    expect(menu.some(l => l === '引用'), `自己消息的右键菜单含「引用」（实读：${JSON.stringify(menu)}）`).toBe(true);

    // ① 入口 b：footer 引用钮对自己消息在场、可用（复制钮右侧同族）
    const btn = await page.evaluate(id => window.__quoteButtonOn(id), ownId);
    expect(btn.present, '自己消息 footer 有引用钮').toBe(true);
    expect(btn.badge, '引用钮在徽章内（按钮 ⊆ footer）').toBe(true);

    // ② 点按钮 = 进引用态，引用目标 = 自己那条（身份不错位）
    expect((await page.evaluate(id => window.__clickQuoteButtonOn(id), ownId)).ok).toBe(true);
    const pend = await page.evaluate(() => window.__pendingQuoteOrder());
    expect(pend.length, '引用态 1 条').toBe(1);
    expect(pend[0].messageId, '引用目标 = 作者自己那条消息的行 id').toBe(ownId);
    expect(pend[0].excerpt, '自引摘要 = 自己原话').toContain('先查 A');

    // ② 单条 = 整卡预览（与它引同档，不因「自己」降级）
    let chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.count).toBe(1);
    expect(chips.compact, '单条 = 整卡（非紧凑档）').toBe(0);
    expect(chips.chips[0].hasRemove, '整卡带 ✕').toBe(true);

    // ③ 与 AI 消息混选（自引 + 它引同一交互面；顺序 = 选择顺序）
    expect((await page.evaluate(id => window.__startQuoteOn(id), aiId)).ok).toBe(true);
    const order = await page.evaluate(() => window.__pendingQuoteOrder());
    expect(order.length, '自引 + 它引 = 2 条').toBe(2);
    expect(order[0].messageId, '顺序 1 = 自己那条（先选）').toBe(ownId);
    expect(order[1].messageId, '顺序 2 = AI 那条').toBe(aiId);
    chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.compact, '≥2 条全部紧凑档（②的规则对自引一视同仁）').toBe(2);
    expect(chips.chips[0].label, '紧凑牌短名 = 「我」+ 顺序号').toContain('我');
    expect(chips.chips[0].textOverflow, '短名零省略（clip）').toBe('clip');

    // ④ 回看：点自己那条的引用牌 ⇒ 目标行高亮（不是死点击）
    const reveal = await page.evaluate(() => window.__revealChip(0));
    expect(reveal.ok).toBe(true);
    expect(reveal.flashed, '回看落在作者自己那条消息上').toContain(ownId);

    // ⑤ 发送面：两块引用块（顺序同选择顺序），自引块形制 = 它引块形制，
    //    发送方标注走「我」（quoteViewOf 的 user 分支），可跳 ready
    const sentId = await page.evaluate(() => window.__sendPendingQuoted('收到，我按这个顺序来', 'cm-own-quote-1'));
    const blocks = await page.evaluate(id => window.__quoteBlocksOn(id), sentId);
    expect(blocks.length, '发送后气泡上方 2 块（自引未被吞）').toBe(2);
    expect(blocks[0].refMessageId, '块 1 = 自己那条').toBe(ownId);
    expect(blocks[0].text, '块 1 摘要 = 自己原话').toContain('先查 A');
    expect(blocks[0].sender, '块 1 发送方标注 = 我').toBe('我');
    expect(blocks[0].jump, '块 1 可跳（目标在页内）').toBe('ready');
    expect(blocks[1].refMessageId, '块 2 = AI 那条').toBe(aiId);
    expect(blocks[0].inComposite && blocks[1].inComposite, '两块与它引同壳（composite）').toBe(true);
    expect(blocks[0].beforeBubble, '引用块在气泡上方').toBe(true);

    // ⑥ 复位面零残留（本壳的发送腿只渲渲染半边，不消费引用态 —— 真 send 的
    //    clearQuote 在 input.js；此处按探针清空并核计数归零，供后续腿复用干净状态）
    expect(await page.evaluate(() => window.__clearQuoteAll())).toBe(0);

    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('手机层（390×844）：自己消息的 footer 引用钮是唯一入口且可用；自引多选可达', async ({ browser }) => {
    // 手机层口径同 B17：viewport 精确 390 + hasTouch + 产品姿态类 body.mobile-layer
    //（长按/右键菜单在 mobile-layer 下不出 ⇒ 触屏唯一入口 = footer 引用钮）。
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', e => pageErrors.push(String(e && e.message ? e.message : e)));
    await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
    await page.waitForFunction(() => window.__ready === true);
    await page.evaluate(() => { document.body.classList.add('mobile-layer'); });
    const own = await page.evaluate(() => [
      window.__renderUserRow('手机档：我自己的第一条'),
      window.__renderUserRow('手机档：我自己的第二条'),
    ]);
    // 手机层右键菜单不出（现状，写死为判据）—— 入口只剩 footer 钮
    const menu = await page.evaluate(id => window.__menuLabelsOn(id), own[0]);
    expect(menu, 'mobile-layer 下右键菜单不出（长按退役批）').toEqual([]);
    for (const id of own) {
      const b = await page.evaluate(rid => window.__quoteButtonOn(rid), id);
      expect(b.present, `手机层自己消息 footer 引用钮在场（${id}）`).toBe(true);
      expect((await page.evaluate(rid => window.__clickQuoteButtonOn(rid), id)).ok).toBe(true);
    }
    const chips = await page.evaluate(() => window.__quoteChips());
    expect(chips.count, '自己两条 = 2 枚引用牌').toBe(2);
    expect(chips.compact, '≥2 紧凑档').toBe(2);
    expect(chips.chips.map(c => c.label).join('|'), '两枚都标「我」（短名可读、可区分顺序号）').toContain('我');
    expect(chips.chips[0].label, '带顺序号 1').toContain('1');
    expect(chips.chips[1].label, '带顺序号 2').toContain('2');
    const geom = await page.evaluate(() => {
      const hr = document.getElementById('attachment-preview').getBoundingClientRect();
      const ir = document.getElementById('input').getBoundingClientRect();
      return { hostBottom: Math.round(hr.bottom), inputTop: Math.round(ir.top), vw: window.innerWidth };
    });
    expect(geom.vw, '量的是 390 真机档').toBe(390);
    expect(geom.hostBottom, '条带不压输入框').toBeLessThanOrEqual(geom.inputTop + 1);
    // 发送：两块引用块都在（手机层自引 + 自引）
    const sentId = await page.evaluate(() => window.__sendPendingQuoted('手机档发送', 'cm-own-m1'));
    const blocks = await page.evaluate(id => window.__quoteBlocksOn(id), sentId);
    expect(blocks.length, '手机层发送后 2 块').toBe(2);
    expect(blocks.map(b => b.sender), '两块发送方都是「我」').toEqual(['我', '我']);
    expect(blocks.map(b => b.jump), '两块都可跳').toEqual(['ready', 'ready']);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
