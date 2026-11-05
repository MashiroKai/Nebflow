// chat-last-bubble-round.spec.mjs — R71 主会话底部消息圆角化（末条气泡死角归零）门禁。
//
// 条目来源：UI 批次审计 2026-11-04（D 档 9 条之 R71；台账 #1475⑥，作者 recall
// 2026-11-03 18:30 + 2026-11-04 11:09）。改前读数（审计实读 + 对抗复核R2 独立复验）：
//   末条 user 气泡 computed `border-radius: 16px 16px 4px 4px`（底右 4px；
//   `chat.css` `.bubble.user{border-bottom-right-radius:4px}`）、
//   末条 ai 气泡 `16px 16px 16px 4px`（底左 4px；`.bubble.ai{border-bottom-left-radius:4px}`）
//   ⇒ 底缘 4px 方角恒在；`:last-child` 圆角规则 0 条。
//
// 判据（机械，逐值读 computed style）：
//   ① 末条气泡**四角全 16px**（= `--ds-radius-card-lg`，运行时解析）—— user / ai 两夹具各一遍；
//   ② 非末条气泡**保留**原 4px 尾角（本批只改末条，不许把整族尾巴抹平 = 反回退判据）；
//   ③ 三态不变：停靠态 / 滚动中态（scrollTop 中段）/ 键盘态（视口高 900→560 收缩）——
//      三态下末条四角读数逐值相同；
//   ④ 展开态不变：末条 ai 气泡内的产品折叠件（`.thinking-label.collapsible`）收起/展开
//      两态下末条四角读数不变（该类是 chat.js 思考折叠件的真类名；本桩无流式帧，
//      折叠块以产品自身类名注入，断言面 = 「态类不能把末条规则顶掉」）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/chat-last-bubble-round.spec.mjs
//   env：EVID_DIR=<dir> 落 readings.json + 双档截图；CHATFACE_WEB_ROOT=<web 树> 跑改前腿。

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootChat, probeChatFace, historyFixture } from './_chat-face-harness.mjs';

const EVID = process.env.EVID_DIR || null;
const TAG = process.env.EVID_TAG || 'after';

const tokenPx = (page, name) => page.evaluate((n) => {
  const probe = document.createElement('span');
  probe.style.cssText = `position:absolute;left:-9999px;top:0;width:var(${n});height:0`;
  document.body.appendChild(probe);
  const v = parseFloat(getComputedStyle(probe).width) || 0;
  probe.remove();
  return v;
}, name);

function saveReadings(name, payload) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

/** 末条气泡四角（px 数值）。 */
const lastCorners = (page) => page.evaluate(() => {
  const rows = [...document.querySelectorAll('#chat .row')];
  const b = rows.length ? rows[rows.length - 1].querySelector('.bubble') : null;
  if (!b) return null;
  const s = getComputedStyle(b);
  const px = (v) => parseFloat(v) || 0;
  return {
    tl: px(s.borderTopLeftRadius), tr: px(s.borderTopRightRadius),
    br: px(s.borderBottomRightRadius), bl: px(s.borderBottomLeftRadius),
    cls: b.className,
  };
});

test.describe('R71 末条气泡圆角化', () => {
  test('末条 user 气泡：四角 == --ds-radius-card-lg；三态 + 展开态不变；非末条保留 4px 尾角', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await bootChat(page, { history: historyFixture(8, { lastRole: 'user' }) });
    const want = await tokenPx(page, '--ds-radius-card-lg');
    const docked = await lastCorners(page);
    const geo = await probeChatFace(page);
    saveReadings('r71-user-docked', { want, docked, geo });
    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r71-user-1440.${TAG}.png`) });
    }

    // ① 末条四角全 16px
    expect(docked.cls).toContain('user');
    expect(docked.tl).toBeCloseTo(want, 1);
    expect(docked.tr).toBeCloseTo(want, 1);
    expect(docked.br).toBeCloseTo(want, 1);
    expect(docked.bl).toBeCloseTo(want, 1);

    // ② 非末条保留尾角 4px（本批只改末条）
    const nonLast = await page.evaluate(() => {
      const bubbles = [...document.querySelectorAll('#chat .bubble.user')];
      const b = bubbles[bubbles.length - 2];
      if (!b) return null;
      return parseFloat(getComputedStyle(b).borderBottomRightRadius) || 0;
    });
    expect(nonLast).toBeCloseTo(4, 1);

    // ③ 滚动中态
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 120; });
    await page.waitForTimeout(120);
    const scrolling = await lastCorners(page);
    expect(scrolling).toEqual(docked);
    // 回到停靠态
    await page.evaluate(() => {
      const c = document.getElementById('chat');
      c.scrollTop = c.scrollHeight;
    });
    await page.waitForTimeout(120);
    const redocked = await lastCorners(page);
    expect(redocked).toEqual(docked);
    saveReadings('r71-user-three-states', { want, docked, scrolling, redocked });

    // ④ 键盘态（视口高收缩 = 手机键盘抬起后的可视区）
    await page.setViewportSize({ width: 1440, height: 560 });
    await page.waitForTimeout(400);
    const keyboard = await lastCorners(page);
    expect(keyboard).toEqual(docked);
    saveReadings('r71-user-keyboard', { want, keyboard });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(300);
  });

  test('末条 ai 气泡：四角 == --ds-radius-card-lg；折叠件收起/展开两态不变', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await bootChat(page, { history: historyFixture(8, { lastRole: 'ai' }) });
    const want = await tokenPx(page, '--ds-radius-card-lg');
    const docked = await lastCorners(page);
    saveReadings('r71-ai-docked', { want, docked });
    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r71-ai-1440.${TAG}.png`) });
    }

    expect(docked.cls).toContain('ai');
    expect(docked.tl).toBeCloseTo(want, 1);
    expect(docked.tr).toBeCloseTo(want, 1);
    expect(docked.br).toBeCloseTo(want, 1);
    expect(docked.bl).toBeCloseTo(want, 1);

    // ④ 展开态：产品自身折叠件（.thinking-label.collapsible）注入末条气泡后端内，
    //    收起/展开各量一次（态类不得把末条规则顶掉）。
    const fold = await page.evaluate(() => {
      const rows = [...document.querySelectorAll('#chat .row')];
      const bubble = rows[rows.length - 1].querySelector('.bubble');
      const label = document.createElement('div');
      label.className = 'thinking-label collapsible expanded';
      label.setAttribute('role', 'button');
      label.textContent = '思考过程（态类探针）';
      bubble.prepend(label);
      const read = () => {
        const s = getComputedStyle(bubble);
        return {
          tl: parseFloat(s.borderTopLeftRadius) || 0, tr: parseFloat(s.borderTopRightRadius) || 0,
          br: parseFloat(s.borderBottomRightRadius) || 0, bl: parseFloat(s.borderBottomLeftRadius) || 0,
        };
      };
      const expanded = read();
      label.classList.remove('expanded');
      const collapsed = read();
      return { expanded, collapsed };
    });
    expect(fold.expanded).toEqual({ tl: want, tr: want, br: want, bl: want });
    expect(fold.collapsed).toEqual({ tl: want, tr: want, br: want, bl: want });
    saveReadings('r71-ai-fold', { want, docked, fold });
  });

  test('手机层 375×812：末条气泡四角同样归零（圆角化不留平台差）', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.emulateMedia({ colorScheme: 'dark' });
    await bootChat(page, { history: historyFixture(6, { lastRole: 'ai' }) });
    await page.waitForSelector('body.mobile-layer', { timeout: 10000 });
    const want = await tokenPx(page, '--ds-radius-card-lg');
    const last = await lastCorners(page);
    saveReadings('r71-mobile', { want, last });
    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r71-mobile-375.${TAG}.png`) });
    }
    expect(last.tl).toBeCloseTo(want, 1);
    expect(last.br).toBeCloseTo(want, 1);
    expect(last.bl).toBeCloseTo(want, 1);
  });
});
