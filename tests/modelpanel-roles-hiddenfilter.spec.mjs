// modelpanel-roles-hiddenfilter.spec.mjs — /model 面板角色枚举过 PanelHiddenNames
// 过滤的静态钉（追加第三修复项 · 作者 2026-10-07 23:13 令；S14 UI 收尾批同族）。
//
// 覆盖面：
//   M1 隐藏名镜像在场：modelPanel 的 PANEL_HIDDEN_ROLES 与 BuiltinAgents.PanelHiddenNames
//      同值（['project-dispatcher']；plugins.js 第三过滤点同款前端镜像）。
//   M2 枚举走过滤：ROLES = 全集滤隐藏（非裸字面列表）；FOLLOWER_ROLES 从 ROLES 派生。
//   M3 现役两角色保留：Nebula + nebflow 在全集（面板只留现役可配角色）。
//   M4 零 locale 触碰：model.roleDispatcher 键保留（zh/en 成对）——藏的是枚举不是键。
// 判据单源：BuiltinAgents.PanelHiddenNames（scala 侧不可直读，静态钉镜像值 +
// plugins.js 既有镜像对账）。

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');

test('M1+M2+M3 /model 角色枚举 = 全集滤 PanelHiddenNames；现役两角色保留', async () => {
  const src = readFileSync(join(WEB, 'js', 'modelPanel.js'), 'utf8');
  // M1 隐藏名镜像（与 BuiltinAgents.PanelHiddenNames / plugins.js 同值）
  expect(src).toContain("const PANEL_HIDDEN_ROLES = ['project-dispatcher']");
  // M2 枚举走过滤（裸字面三元素列表退役）
  expect(src).toMatch(/const ALL_ROLES = \['Nebula', 'project-dispatcher', 'nebflow'\];/);
  expect(src).toContain('const ROLES = ALL_ROLES.filter((r) => !PANEL_HIDDEN_ROLES.includes(r));');
  expect(src).toContain("const FOLLOWER_ROLES = ROLES.filter((r) => r !== 'Nebula');");
  // 旧裸字面 ROLES/FOLLOWER_ROLES 行不得回归
  expect(src).not.toMatch(/const ROLES = \['Nebula', 'project-dispatcher', 'nebflow'\];/);
  expect(src).not.toMatch(/const FOLLOWER_ROLES = \['project-dispatcher', 'nebflow'\];/);
  // M3 现役两角色保留
  expect(src).toContain("'nebflow'");
  expect(src).toContain("'Nebula'");
});

test('M2b 插件面板既有镜像对账（同一隐藏名单值双镜像）', async () => {
  const pluginsSrc = readFileSync(join(WEB, 'js', 'plugins.js'), 'utf8');
  expect(pluginsSrc).toContain("const PANEL_HIDDEN_AGENTS = ['project-dispatcher']");
});

test('M4 零 locale 触碰：model.roleDispatcher 键 zh/en 成对保留', async () => {
  for (const locale of ['zh-CN', 'en']) {
    const src = readFileSync(join(WEB, 'js', 'locales', `${locale}.js`), 'utf8');
    expect(src, `${locale} keeps model.roleDispatcher`).toContain('roleDispatcher');
  }
});
