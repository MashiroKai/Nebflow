// 模型配置五类角色枚举与本地化键的静态回归。

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');

test('模型配置枚举五类会话角色并从主角色派生 follower', async () => {
  const src = readFileSync(join(WEB, 'js', 'modelPanel.js'), 'utf8');
  expect(src).toContain("const ALL_ROLES = ['Nebula', 'kernel', 'project-dispatcher', 'node', 'subagent'];");
  expect(src).toContain('const ROLES = ALL_ROLES.filter((r) => !PANEL_HIDDEN_ROLES.includes(r));');
  expect(src).toContain("const FOLLOWER_ROLES = ROLES.filter((r) => r !== 'Nebula');");
});

test('M4 零 locale 触碰：model.roleDispatcher 键 zh/en 成对保留', async () => {
  for (const locale of ['zh-CN', 'en']) {
    const src = readFileSync(join(WEB, 'js', 'locales', `${locale}.js`), 'utf8');
    expect(src, `${locale} keeps model.roleDispatcher`).toContain('roleDispatcher');
  }
});
