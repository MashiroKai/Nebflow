import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { BADGE_TIPS, TIP_EVERY, createBadgeTipMixer } from '../../src/main/resources/web/js/chat/badgeTips.js';

const durationSrc = readFileSync(
  new URL('../../src/main/resources/web/js/chat/durationBadges.js', import.meta.url), 'utf8');

test('池内容 = 作者拍板的 4 条（逐字）', () => {
  assert.deepEqual([...BADGE_TIPS], [
    '给好友也能直接发文件',
    '文件也能发到自己另一台机器',
    '问答窗可以把任务转给主会话',
    '密钥不用贴聊天，弹框直接存',
  ]);
});

test('连续 12 次出现：恰好 2 条 tip，位于第 6、12 次，不相邻，其余全为浪漫池(null)', () => {
  const m = createBadgeTipMixer();
  const seq = Array.from({ length: 12 }, () => m.next());
  const hits = seq.map((v, i) => (v === null ? -1 : i)).filter(i => i >= 0);
  assert.deepEqual(hits, [TIP_EVERY - 1, 2 * TIP_EVERY - 1]);
  assert.equal(hits.length, 2);
  assert.ok(hits[1] - hits[0] > 1, 'two tips must never be adjacent');
  for (const [i, v] of seq.entries()) {
    if (!hits.includes(i)) assert.equal(v, null, `slot ${i} must be romantic pool`);
  }
});

test('tip 按池内顺序轮转，且每条 tip 都来自池', () => {
  const m = createBadgeTipMixer();
  const tips = [];
  for (let i = 0; i < TIP_EVERY * BADGE_TIPS.length; i++) {
    const v = m.next();
    if (v !== null) tips.push(v);
  }
  assert.deepEqual(tips, [...BADGE_TIPS]);
});

test('长序列不连出两条 tip（周期性抽样 1000 次）', () => {
  const m = createBadgeTipMixer();
  let prevTip = false;
  for (let i = 0; i < 1000; i++) {
    const isTip = m.next() !== null;
    assert.ok(!(prevTip && isTip), `adjacent tips at ${i}`);
    prevTip = isTip;
  }
});

test('every 非法值被拒绝', () => {
  assert.throws(() => createBadgeTipMixer({ every: 1 }));
  assert.throws(() => createBadgeTipMixer({ every: 2.5 }));
});

test('tip 模块无外部依赖（可独立注入测试，池与浪漫池物理分离）', () => {
  const src = readFileSync(
    new URL('../../src/main/resources/web/js/chat/badgeTips.js', import.meta.url), 'utf8');
  assert.equal(/^\s*import\s/m.test(src), false);
});

test('pickThinkingPhrase 接线：徽章出现走混入器（源码级断言，模块依赖 i18n/DOM 无法在 node 直接加载）', () => {
  assert.match(durationSrc, /import \{ defaultBadgeTipMixer \} from '\.\/badgeTips\.js'/);
  assert.match(durationSrc, /tipMixer\.next\(\)/);
});
