// settings-thinking-global-only.spec.mjs — 思考配置收归全局钉
// （settings-panel-fourfix 批 ①件，作者令 2026-10-29 逐字：「不要每个模型都能
// 配置思考模式」，「思考档位只在设置里统一的一个地方配置（全局思考设置处）」）。
//
// 形态：源级断言（check-provider-modellist.mjs 同族——被钉的是「模型行不再
// 出现思考配置控件」这一静态事实）+ 全局配置点存在性断言。thinkng-config
// Phase 2 的 per-model 下拉（modelThinkingSelect.js）随本批整件退役。
//
// 语义钉：
//   G1 per-model 模块文件不存在（js/sidebar/modelThinkingSelect.js 已删）
//   G2 sidebar.js 不再 import / 调用任何 modelThinkingSelect* 面；
//      renderProviderCard 的模型行模板无 <select 控件
//   G3 两份 locale 均无 per-model 思考键（thinkingPerModel/Inherit/Effective/
//      ForcedBadge/MediumMap）
//   G4 全局唯一配置点在位：settings.thinkingMode 下拉（#cfg-thinking-effort）
//      + 其 setThinking 全局写路（payload 不带 provider/model 键）+ 档位键齐全
//
// Run: npx playwright test tests/settings-thinking-global-only.spec.mjs

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const WEB = path.join(REPO_ROOT, 'src', 'main', 'resources', 'web');

const read = (p) => fs.readFileSync(path.join(WEB, p), 'utf8');

test('G1. per-model thinking 模块文件已删除', () => {
  expect(fs.existsSync(path.join(WEB, 'js', 'sidebar', 'modelThinkingSelect.js'))).toBe(false);
});

test('G2. sidebar.js 零 per-model 思考接线，模型行模板无 select', () => {
  const src = read(path.join('js', 'sidebar.js'));
  expect(src).not.toContain('modelThinkingSelect');
  expect(src).not.toContain('cfg-model-thinking');
  expect(src).not.toContain('cfg-thinking-forced-badge');
  // renderProviderCard 的模型行模板：名称 + vision 位 + 测试钮，无任何 select。
  const rowStart = src.indexOf('const modelsHtml = (p.models || [])');
  expect(rowStart).toBeGreaterThan(-1);
  const rowBlock = src.slice(rowStart, rowStart + 900);
  expect(rowBlock).toContain('cfg-model-row');
  expect(rowBlock).not.toContain('<select');
});

test('G3. locale 无 per-model 思考键', () => {
  for (const f of ['js/locales/zh-CN.js', 'js/locales/en.js']) {
    const src = read(f);
    for (const key of ['thinkingPerModel', 'thinkingInherit', 'thinkingEffective', 'thinkingForcedBadge', 'thinkingMediumMap']) {
      expect(src).not.toContain(`'settings.${key}'`);
    }
  }
});

test('G4. 全局唯一配置点在位且写路是全局键', () => {
  const src = read(path.join('js', 'sidebar.js'));
  // 全局下拉渲染 + 全局 setThinking（payload 无 provider/model 键 = 引擎全局腿）。
  expect(src).toContain("id=\"cfg-thinking-effort\"");
  const handlerAt = src.indexOf("getElementById('cfg-thinking-effort')");
  expect(handlerAt).toBeGreaterThan(-1);
  const handler = src.slice(handlerAt, handlerAt + 600);
  expect(handler).toContain("sendWs({ type: 'setThinking', thinking: state.thinkingMode })");
  expect(handler).not.toContain('provider');
  expect(handler).not.toContain('model');
  // 档位键（全局下拉的选项面）在两份 locale 齐全。
  for (const f of ['js/locales/zh-CN.js', 'js/locales/en.js']) {
    const loc = read(f);
    expect(loc).toContain("'settings.thinkingMode'");
    for (const lvl of ['off', 'low', 'medium', 'high']) {
      expect(loc).toContain(`'settings.thinkingEffort.${lvl}'`);
    }
  }
});
