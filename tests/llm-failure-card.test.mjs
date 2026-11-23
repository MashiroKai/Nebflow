// LLM 失败卡片（卡片批 2026-11-21；第 3 轮聚合键改造）：纯视图模型回归（无 DOM）。
// 运行：node --test tests/llm-failure-card.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildLlmFailureModel, llmFailureKey, SUMMARY_MAX, AGENTS_SHOW_MAX,
} from '../src/main/resources/web/js/llmFailureCard.js';

test('视图模型：标题为「模型 调用失败」（大字主信息），分类映射为中文标签', () => {
  const m = buildLlmFailureModel({ layer: 'root', modelRef: 'p/deepseek', agents: ['main'], summary: 'boom', failureClass: 'llm-overloaded' });
  assert.equal(m.title, 'p/deepseek 调用失败');
  assert.equal(m.failureLabel, '过载');
  assert.equal(m.waitLabel, '等待');
  assert.equal(m.switchLabel, '换模型');
});

test('视图模型：层级映射为中文标签与图标；未知层级回落到主代理', () => {
  assert.equal(buildLlmFailureModel({ layer: 'team', modelRef: 'm' }).layerLabel, '团队成员');
  assert.equal(buildLlmFailureModel({ layer: 'subtask', modelRef: 'm' }).layerIcon, 'network');
  assert.equal(buildLlmFailureModel({ layer: 'bogus', modelRef: 'm' }).layer, 'root');
});

test('视图模型：聚合键 = 层级 + 模型；同层级同模型同键，异层级异键', () => {
  assert.equal(llmFailureKey('team', 'p/a'), 'team|p/a');
  assert.equal(buildLlmFailureModel({ layer: 'team', modelRef: 'p/a', agents: ['x'] }).key,
    buildLlmFailureModel({ layer: 'team', modelRef: 'p/a', agents: ['y', 'z'] }).key);
  assert.notEqual(llmFailureKey('root', 'p/a'), llmFailureKey('team', 'p/a'));
});

test('视图模型：代理列表超过上限折叠为「等 N 个」', () => {
  const few = buildLlmFailureModel({ layer: 'team', modelRef: 'm', agents: ['a', 'b'] });
  assert.equal(few.agentsText, 'a、b');
  const many = buildLlmFailureModel({ layer: 'team', modelRef: 'm', agents: ['a', 'b', 'c', 'd', 'e'] });
  assert.equal(AGENTS_SHOW_MAX, 3);
  assert.equal(many.agentsText, 'a、b、c 等 5 个');
  assert.equal(buildLlmFailureModel({ layer: 'root', modelRef: 'm' }).agentsText, '');
});

test('视图模型：未知分类原样显示，缺失分类为「未分类」', () => {
  assert.equal(buildLlmFailureModel({ modelRef: 'm', failureClass: 'weird-x' }).failureLabel, 'weird-x');
  assert.equal(buildLlmFailureModel({ modelRef: 'm' }).failureLabel, '未分类');
});

test('视图模型：摘要折叠空白并截断到上限（含省略号）', () => {
  const long = 'x'.repeat(SUMMARY_MAX + 50);
  const m = buildLlmFailureModel({ modelRef: 'm', summary: `  a\n\n b  ${long}` });
  assert.equal(m.summary.length, SUMMARY_MAX);
  assert.ok(m.summary.endsWith('…'));
  assert.ok(!m.summary.includes('\n'));
});

test('视图模型：空摘要给占位文案，缺模型名给「未知模型」，不出现 undefined', () => {
  const m = buildLlmFailureModel({});
  assert.equal(m.summary, '（无错误摘要）');
  assert.equal(m.title, '未知模型 调用失败');
  assert.ok(!JSON.stringify(m).includes('undefined'));
});

// ── 消失语义（2026-11-22 作者 23:24 报「点了不消失」）──
import {
  isLlmFailureControlTarget, createLlmDismissStore, SWITCH_FADE_MS, FADE_OUT_MS,
  SCHEDULE_WINDOW_CLASS, buildWindowTitle,
} from '../src/main/resources/web/js/llmFailureCard.js';

const fakeEl = (hits) => ({ closest: (sel) => (hits.some((h) => sel.includes(h)) ? {} : null) });

test('消失：控件区（按钮/下拉/关闭钮/动作行）点击不触发正文消失', () => {
  assert.equal(isLlmFailureControlTarget(fakeEl(['button'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl(['select'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl(['llm-failure-close'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl(['llm-failure-actions'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl([])), false);
  assert.equal(isLlmFailureControlTarget(null), false);
});

test('消失：dismiss store 记录同组，recovered 清除后才允许再出', () => {
  const s = createLlmDismissStore();
  const k = llmFailureKey('root', 'p/a');
  assert.equal(s.has(k), false);
  s.add(k);
  assert.equal(s.has(k), true);
  assert.equal(s.has(llmFailureKey('team', 'p/a')), false, '不同层级互不影响');
  s.clear(k);
  assert.equal(s.has(k), false);
  assert.equal(s.size(), 0);
});

test('消失：切换成功自动淡出的时长常量为正且淡出早于移除', () => {
  assert.ok(SWITCH_FADE_MS > 0);
  assert.ok(FADE_OUT_MS > 0 && FADE_OUT_MS < SWITCH_FADE_MS);
});

// ── 窗口受限·等待恢复型（provider-notice-card-unify 批 2026-11-23）──

test('窗口受限：视图模型标记 isWindow、带 windowDesc，分类标签为「窗口受限」', () => {
  assert.equal(SCHEDULE_WINDOW_CLASS, 'llm-schedule-window');
  const m = buildLlmFailureModel({
    layer: 'root', modelRef: 'UTSC/glm-5.3-flash',
    summary: '当前时段全部候选模型受限', failureClass: 'llm-schedule-window', windowDesc: '06:00-01:00',
  });
  assert.equal(m.isWindow, true);
  assert.equal(m.windowDesc, '06:00-01:00');
  assert.equal(m.failureLabel, '窗口受限');
});

test('窗口受限：普通失败不受影响（isWindow=false、windowDesc 空串）', () => {
  const m = buildLlmFailureModel({ layer: 'root', modelRef: 'p/m', summary: 'boom', failureClass: 'llm-auth' });
  assert.equal(m.isWindow, false);
  assert.equal(m.windowDesc, '');
});

test('窗口受限：标题带 provider 与窗口时段；缺窗口时不追加括号', () => {
  assert.equal(buildWindowTitle('UTSC/glm-5.3-flash', '06:00-01:00'), 'UTSC/glm-5.3-flash 当前时段不可用（06:00-01:00）');
  assert.equal(buildWindowTitle('UTSC/glm-5.3-flash', ''), 'UTSC/glm-5.3-flash 当前时段不可用');
  assert.equal(buildWindowTitle('', ''), '未知模型 当前时段不可用');
});

test('窗口受限：聚合键仍为层级+模型（窗口描述不进键，同组更新不重复出卡）', () => {
  const a = buildLlmFailureModel({ layer: 'root', modelRef: 'UTSC/glm-5.3-flash', failureClass: 'llm-schedule-window', windowDesc: '06:00-01:00' });
  const b = buildLlmFailureModel({ layer: 'root', modelRef: 'UTSC/glm-5.3-flash', failureClass: 'llm-schedule-window', windowDesc: '06:00-02:00' });
  assert.equal(a.key, b.key);
});

// ── 卡内直接设置（provider-notice-card-unify 增量批；作者 09:48「卡片上应该要可以直接设置」）──
import {
  providerOfModelRef, formatWindows, WINDOW_PRESETS,
} from '../src/main/resources/web/js/llmFailureCard.js';

test('卡内设置：模型引用取 provider 段（写入口按 provider 名落盘）', () => {
  assert.equal(providerOfModelRef('UTSC/glm-5.3-flash'), 'UTSC');
  assert.equal(providerOfModelRef('Irical/DeepSeek-V4.1-Flash-1017'), 'Irical');
  assert.equal(providerOfModelRef('solo'), 'solo');
  assert.equal(providerOfModelRef(''), '');
  assert.equal(providerOfModelRef(undefined), '');
});

test('卡内设置：当前窗口展示串（段/星期/空态），不出现 undefined', () => {
  assert.equal(formatWindows([]), '未配置窗口');
  assert.equal(formatWindows(null), '未配置窗口');
  assert.equal(formatWindows([{ from: '06:00', to: '01:00' }]), '06:00-01:00（每天）');
  assert.equal(formatWindows([{ from: '01:00', to: '06:00', days: [1, 3] }]), '01:00-06:00（周一、周三）');
  const multi = formatWindows([{ from: '01:00', to: '06:00' }, { from: '12:00', to: '13:00', days: [7] }]);
  assert.equal(multi, '01:00-06:00（每天）；12:00-13:00（周日）');
  assert.ok(!multi.includes('undefined'));
});

test('卡内设置：快捷预设 = 一键清除 + 仅深夜 01:00-06:00（两动作都在，段结构合法）', () => {
  assert.equal(WINDOW_PRESETS.length, 2);
  const clear = WINDOW_PRESETS.find((p) => p.id === 'clear');
  const night = WINDOW_PRESETS.find((p) => p.id === 'night');
  assert.ok(clear && Array.isArray(clear.windows) && clear.windows.length === 0, '清除 = 空段列表');
  assert.equal(night.label, '仅深夜 01:00-06:00');
  assert.deepEqual(night.windows, [{ from: '01:00', to: '06:00' }]);
  // 段结构 = 引擎 unavailableWindows 契约（from/to HH:mm）
  for (const p of WINDOW_PRESETS) {
    for (const w of p.windows) {
      assert.match(w.from, /^\d{2}:\d{2}$/);
      assert.match(w.to, /^\d{2}:\d{2}$/);
    }
  }
});
