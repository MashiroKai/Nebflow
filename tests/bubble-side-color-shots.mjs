// bubble-side-color-shots.mjs — 气泡归属方底色批（2026-11-06）的**真渲染实拍**
//
// 与 tests/bubble-side-color.spec.mjs 同一套真渲染夹具（真 web 树 + 真模块图 + 真
// CSS + WS 帧桩），只是把断言换成落盘 PNG：主会话 / 联系人 / 群聊三面 × 亮暗两档。
//
// Run: node tests/bubble-side-color-shots.mjs
//   BUBBLE_SHOTS=<dir>  落盘目录（缺省 evidence/20261106_bubble-side-color）

import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = process.env.BUBBLE_SHOTS || join(HERE, '..', 'evidence', '20261106_bubble-side-color');
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();

for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({
    viewport: { width: 1180, height: 860 }, colorScheme: theme, deviceScaleFactor: 2,
  });
  const page = await ctx.newPage();
  await bootChat(page, { history: historyFixture(4, { lastRole: 'ai' }) });

  await page.evaluate(async () => {
    const chat = await import('/js/chat.js');
    chat.renderUserBubble('这是我们这方发的：作者本人输入');
    chat.appendAiText('这也是我们这方发的：我方 Agent 的回复');
    chat.finishAi(1234, 'fixture-model');
    const inj = await import('/js/chat/injectedRows.js');
    const row = inj.buildInjectedRow('节点 / delegate 完工回执（本机账号发出）', 'delegate', Date.now(), 'injected');
    document.getElementById('chat').appendChild(row);

    const msg = await import('/js/messages.js');
    const host = document.createElement('div');
    host.id = 'fixture-fm-host';
    host.style.cssText = 'position:fixed;right:24px;top:24px;width:440px;padding:16px;'
      + 'background:var(--color-bg);border:1px solid var(--color-border);'
      + 'border-radius:16px;display:flex;flex-direction:column;gap:6px;z-index:9999';
    host.innerHTML = '<div style="font:600 12px/1.4 system-ui;color:var(--color-text-muted);'
      + 'margin-bottom:4px">联系人 / 群聊面（下方两组各两条）</div>';
    document.body.appendChild(host);
    const direct = { id: 'c-direct', kind: 'direct', friend: { userId: 'friend-1', name: '好友甲' } };
    const group = { id: 'g-1', kind: 'group', title: '群聊甲', members: [{ userId: 'friend-1', name: '群友乙' }] };
    for (const [conv, out, text] of [
      [direct, true, '联系人窗 · 我们这方发的'],
      [direct, false, '联系人窗 · 对方发来的'],
      [group, true, '群聊 · 我们这方发的'],
      [group, false, '群聊 · 他人发来的'],
    ]) {
      const wrap = msg.bubbleEl(
        { id: `f-${Math.random().toString(36).slice(2)}`, body: text, senderId: out ? 'me' : 'friend-1', ours: out, createdAt: Date.now() },
        conv,
      );
      wrap.style.margin = '2px 0';
      host.appendChild(wrap);
    }
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  });

  await page.waitForTimeout(400);

  // ① 主会话（聊天列）
  await page.locator('#chat').screenshot({ path: join(OUT, `main-${theme}.png`) });
  // ② 联系人 / 群聊（夹具面板）
  await page.locator('#fixture-fm-host').screenshot({ path: join(OUT, `fm-${theme}.png`) });
  // ③ 三种面同框全景
  await page.screenshot({ path: join(OUT, `all-${theme}.png`) });
  console.log(`  ✓ ${theme}: main-${theme}.png / fm-${theme}.png / all-${theme}.png`);
  await ctx.close();
}

await browser.close();
console.log(`bubble-side shots → ${OUT}`);
