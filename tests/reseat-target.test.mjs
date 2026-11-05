#!/usr/bin/env node
/**
 * 整页重座导航目标 · 卡窗守恒（弹窗回跳批 2026-11-05）。
 *
 * ── 事故 ────────────────────────────────────────────────────────────────────
 * 作者报障 2026-11-05 09:23（逐字）「现在怎么老是有弹窗然后重新进控制台？」。
 * 证据截图（09:19:36）：一个独立浏览器窗口停在入口/门户面「Entering your
 * console…」，地址栏已是控制台 URL、加载条未走完 —— 一次**自动**整页导航在途。
 *
 * 控制台侧「整页重进」有三条腿，全部把 mint 回来的 URL 原样丢给 location.href：
 *   · ws.js remintHostSessionAndReload（WS 握手被拒的自愈重座）
 *   · ws.js 环回近路跳（relay 腿 → 127.0.0.1:<port>）
 *   · ws.js LAN 近路跳（relay 腿 → LAN 地址）
 * 三条 URL 都只带 `?ticket=`，**不带卡窗契约** `card-window=1&path=…&title=…`。
 * ⇒ 独立卡窗（popoutCardWindow 的 `popup=yes` 弹窗）一旦遇到握手被拒，就被
 * 重座导航从「一张内容卡」升级成「一整个控制台」：弹窗还在，里面已经换了东西
 * = 作者逐字描述的「弹窗然后重新进控制台」。副产物 = 同一浏览器多出一个控制台
 * 实例（抢 WS ⇒ 接管模态 ⇒ 再 reload），这就是「老是」的节奏。
 *
 * ── 本测钉的契约 ────────────────────────────────────────────────────────────
 *   1. 红（旧规则）：卡窗页重座 ⇒ 目标只有 ticket，`card-window` 丢失 ⇒ 重座后
 *      变成整控制台（第 1 组用例把旧规则逐字复刻并断言其"丢失"）。
 *   2. 绿（新规则）：卡窗页重座 ⇒ 目标保留 ticket **且**带 card-window=1 与
 *      path/title ⇒ 重座后仍是同一张卡窗。
 *   3. 不误伤：主窗（非卡窗）重座 ⇒ 返回的目标与传入逐字相同（relay 腿与本地
 *      直连腿零变化）。
 *   4. 契约不完整（卡窗但无 path）⇒ 仍带 card-window=1（宁可停在卡窗自己的
 *      引导/失败面，绝不悄悄退化成整控制台）。
 *   5. 不可解析的目标 ⇒ 原样返回（诊断逻辑绝不拦重座）。
 *   6. 相对目标按基址解析（共用一个出口，相对形态也必须成立）。
 *
 * 纯函数直测：reseatTarget 零依赖零副作用（ws.js 本身带 DOM 副作用，无法在
 * node 里 import）。零网络、零浏览器、零实例。
 *
 * Run: node --test tests/reseat-target.test.mjs
 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  readReseatContext,
  resolveReseatTarget,
} from "../src/main/resources/web/js/reseatTarget.js";

const CARD_SEARCH = "?card-window=1&path=%2Ftmp%2Fnotes%2Fa.md&title=Notes%20A";
const MAIN_SEARCH = "";
const BASE = "https://link.mystand.space/?card-window=1&path=%2Fa.md&title=A";
const CONSOLE_URL = "https://link.mystand.space/?ticket=T1ck%2Bbet";
const LOCAL_URL = "http://127.0.0.1:6138/?ticket=T1ck%2Bbet";

/** 旧规则逐字复刻：把 mint 回来的 URL 原样交给 location.href。 */
const legacyTarget = (target) => target;

test("红腿 · 旧规则把卡窗导航成整控制台（card-window 丢失）", () => {
  const out = legacyTarget(CONSOLE_URL);
  assert.equal(out, CONSOLE_URL);
  assert.equal(new URL(out).searchParams.get("card-window"), null, "旧规则必然丢卡窗标记");
  assert.equal(new URL(out).searchParams.get("path"), null, "旧规则必然丢内容路径");
});

test("绿腿 · 卡窗重座后仍是卡窗（ticket + 卡窗契约都在）", () => {
  const ctx = readReseatContext(CARD_SEARCH);
  assert.deepEqual(ctx, { cardWindow: true, path: "/tmp/notes/a.md", title: "Notes A" });
  const out = resolveReseatTarget(CONSOLE_URL, ctx, BASE);
  const u = new URL(out);
  assert.equal(u.origin, "https://link.mystand.space");
  assert.equal(u.pathname, "/", "入口路径不变（卡窗白屏修复批：入口必须是 /）");
  assert.equal(u.searchParams.get("ticket"), "T1ck+bet", "票原样保留（本次重座的凭据）");
  assert.equal(u.searchParams.get("card-window"), "1");
  assert.equal(u.searchParams.get("path"), "/tmp/notes/a.md");
  assert.equal(u.searchParams.get("title"), "Notes A");
});

test("不误伤 · 主窗重座目标逐字不变", () => {
  for (const target of [CONSOLE_URL, LOCAL_URL, "https://link.mystand.space/?ticket=x&cwpo=y"]) {
    assert.equal(resolveReseatTarget(target, readReseatContext(MAIN_SEARCH), BASE), target);
  }
  assert.deepEqual(readReseatContext(MAIN_SEARCH), { cardWindow: false, path: "", title: "" });
});

test("近路腿 · 环回/LAN 目标同样守恒（绝对串与票都保持）", () => {
  const ctx = readReseatContext(CARD_SEARCH);
  const out = resolveReseatTarget(LOCAL_URL, ctx, BASE);
  const u = new URL(out);
  assert.equal(u.origin, "http://127.0.0.1:6138", "环回 origin 不被改写");
  assert.equal(u.searchParams.get("ticket"), "T1ck+bet");
  assert.equal(u.searchParams.get("card-window"), "1");
  assert.equal(u.searchParams.get("path"), "/tmp/notes/a.md");
});

test("契约不完整 · 卡窗无 path 时仍不得退化成整控制台", () => {
  const ctx = readReseatContext("?card-window=1");
  assert.equal(ctx.cardWindow, true);
  assert.equal(ctx.path, "");
  const u = new URL(resolveReseatTarget(CONSOLE_URL, ctx, BASE));
  assert.equal(u.searchParams.get("card-window"), "1");
  assert.equal(u.searchParams.get("path"), null, "无 path 就不写 path（不留空值）");
  assert.equal(u.searchParams.get("ticket"), "T1ck+bet");
});

test("不可解析目标 ⇒ 原样返回（诊断逻辑绝不拦重座）", () => {
  const ctx = readReseatContext(CARD_SEARCH);
  assert.equal(resolveReseatTarget("", ctx, BASE), "");
  assert.equal(resolveReseatTarget(null, ctx, BASE), null);
  assert.equal(resolveReseatTarget(undefined, ctx, BASE), undefined);
  assert.equal(resolveReseatTarget("http://", ctx, BASE), "http://");
});

test("相对目标按基址解析", () => {
  const ctx = readReseatContext(CARD_SEARCH);
  const u = new URL(resolveReseatTarget("/?ticket=rel", ctx, BASE));
  assert.equal(u.origin, "https://link.mystand.space");
  assert.equal(u.searchParams.get("ticket"), "rel");
  assert.equal(u.searchParams.get("card-window"), "1");
});

test("输入面健壮 · search 畸形不抛，判为非卡窗（现状腿）", () => {
  assert.deepEqual(readReseatContext(undefined), { cardWindow: false, path: "", title: "" });
  assert.deepEqual(readReseatContext(null), { cardWindow: false, path: "", title: "" });
  assert.equal(readReseatContext("card-window=1&path=%2Fb.md").cardWindow, true);
});
