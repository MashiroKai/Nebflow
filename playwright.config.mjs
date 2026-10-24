// playwright.config.mjs — Playwright Test 总配置（2026-10-24 CI 迁移批）
//
// 背景：tests/*.spec.mjs 有两类文件共用一个目录、此前**没有** playwright 配置：
//   ① @playwright/test 套件（import { test } from '@playwright/test'）——
//     由 `npx playwright test` 收集执行；
//   ② 独立 node 脚本（#!/usr/bin/env node，自起浏览器、自打 PASS/FAIL）——
//     必须 `node tests/xxx.spec.mjs` 直跑；被 playwright 收集器 import 时
//     会触发顶层副作用（自起浏览器），直接炸掉整轮收集。
// 本配置把 ② 从收集面排除（运行时按 import 形态判定，新增 spec 无需维护名单），
// 并钉 workers=1：大量自足 spec 绑定固定默认端口（8117–8300，存在重复占用），
// 并行必撞端口。`-Dselfcontained` / PW_SELFCONTAINED_ONLY=1 进一步排除
// instance-backed spec（需真实实例 + token，由 ci.yml frontend-smoke 的
// 实例任务统一拉起服务器并桥接环境变量后点名执行）。

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const runnerImport = /from\s+['"]@playwright\/test['"]/;

// ② 独立脚本：不匹配 @playwright/test import ⇒ 永不进收集面。
// （含 ① 文件里注释提及该包名的假阳性风险由 `from ... '...'` 形态规避。）
const standaloneScripts = readdirSync('tests')
  .filter((f) => f.endsWith('.spec.mjs'))
  .filter((f) => !runnerImport.test(readFileSync(join('tests', f), 'utf8')));

// instance-backed spec：驱动真实服务器（BASE_URL/NEBFLOW_URL + token）。
// 由 frontend-smoke 在拉起 CI 实例、桥接 ~/.stand/auth.json 后点名跑；
// 自足套件任务用 PW_SELFCONTAINED_ONLY=1 整体排除它们。
const instanceBacked = [
  'smoke.spec.mjs',
  'askuser.spec.mjs',
  'askuser-attach.spec.mjs',
  'workspace-picker.spec.mjs',
  'plugins-panel-redesign.spec.mjs',
  'plugins-panel-two-actions.spec.mjs',
  // asset-url-versioning：静态资源缓存击穿批（2026-10-25）——驱动真实引擎的
  // 静态路由面（版本化引用/immutable 头），自足 mock 无法表达引擎行为。
  'asset-url-versioning.spec.mjs',
  // askuser-refresh-survive：自足任务面排除（需隔离 home + mock-LLM 夹具 runner），
  // CI 也不跑——与 askinput-off-pending-text / askuser-lockline-history 同族重件，
  // 编排成本见 ci.yml frontend-smoke 的说明。
  'askuser-refresh-survive.spec.mjs',
  // session-rail / clientconn-wake / askuser-lockline-history / askinput-off-pending-text
  // 是独立脚本（②），本就不在收集面；其 CI 编排见 ci.yml frontend-smoke。
];

const testIgnore = [...standaloneScripts];
if (process.env.PW_SELFCONTAINED_ONLY === '1') testIgnore.push(...instanceBacked);

export default {
  testDir: 'tests',
  testIgnore,
  // 固定默认端口的自足 spec 不可并行（端口重复占用清单见文件头）。
  workers: 1,
  timeout: 180_000,
  retries: 0,
  use: { headless: true },
  reporter: [['list']],
};
