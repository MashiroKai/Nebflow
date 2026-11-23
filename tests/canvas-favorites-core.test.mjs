// canvas-favorites-core.test.mjs — 文档收藏纯逻辑闸（2026-11-23 前端功能批）。
// 覆盖：收藏/取消切换、同文档去重=置顶（不产生重复条目、保留原收藏时间）、
// 归一化（丢非法/非绝对路径/重复、标题缺省回落末段）、上限截断。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  addFavorite, removeFavorite, toggleFavorite, normalizeList, isFavorited,
  basenameOf, FAVORITES_MAX,
} from '../src/main/resources/web/js/canvasFavoritesCore.js';

test('toggle: 未收藏 → 收藏；再点 → 取消', () => {
  const a = toggleFavorite([], { path: '/w/a.md', title: 'A' }, 100);
  assert.equal(a.favorited, true);
  assert.deepEqual(a.list, [{ path: '/w/a.md', title: 'A', savedAt: 100 }]);
  const b = toggleFavorite(a.list, { path: '/w/a.md' }, 200);
  assert.equal(b.favorited, false);
  assert.deepEqual(b.list, []);
});

test('去重 = 置顶：同 path 不产生第二条，移到表首并保留原 savedAt，标题快照刷新', () => {
  let list = addFavorite([], { path: '/w/a.md', title: 'A' }, 100);
  list = addFavorite(list, { path: '/w/b.md', title: 'B' }, 200);
  list = addFavorite(list, { path: '/w/a.md', title: 'A2' }, 300);
  assert.equal(list.length, 2, '不产生重复条目');
  assert.equal(list[0].path, '/w/a.md', '重复收藏 = 置顶');
  assert.equal(list[0].savedAt, 100, '收藏时间不被重复点击改写');
  assert.equal(list[0].title, 'A2', '标题快照刷新');
});

test('addFavorite 不改入参；非绝对路径被忽略', () => {
  const src = [{ path: '/w/a.md', title: 'A', savedAt: 1 }];
  const out = addFavorite(src, { path: 'relative/x.md' }, 5);
  assert.equal(out.length, 1);
  assert.equal(src.length, 1);
  assert.notEqual(addFavorite(src, { path: '/w/c.md' }, 5), src);
});

test('removeFavorite / isFavorited', () => {
  const list = addFavorite([], { path: '/w/a.md' }, 1);
  assert.equal(isFavorited(list, '/w/a.md'), true);
  assert.equal(isFavorited(removeFavorite(list, '/w/a.md'), '/w/a.md'), false);
});

test('normalizeList: 丢非数组/非对象/相对路径/重复；标题缺省回落末段；savedAt 非数字归零', () => {
  assert.deepEqual(normalizeList('nope'), []);
  const out = normalizeList([
    null,
    { path: 'rel.md' },
    { path: '/w/a.md', title: '  ', savedAt: 'x' },
    { path: '/w/a.md', title: 'dup' },
    { path: '/w/z.md', title: 'Z', savedAt: 9 },
  ]);
  assert.deepEqual(out, [
    { path: '/w/a.md', title: 'a.md', savedAt: 0 },
    { path: '/w/z.md', title: 'Z', savedAt: 9 },
  ]);
});

test('上限截断：超过 FAVORITES_MAX 时丢弃最旧（表尾）', () => {
  let list = [];
  for (let i = 0; i < FAVORITES_MAX + 5; i++) list = addFavorite(list, { path: `/w/${i}.md` }, i);
  assert.equal(list.length, FAVORITES_MAX);
  assert.equal(list[0].path, `/w/${FAVORITES_MAX + 4}.md`);
  assert.equal(normalizeList(list.concat(list)).length, FAVORITES_MAX);
});

test('basenameOf', () => {
  assert.equal(basenameOf('/a/b/c.txt'), 'c.txt');
  assert.equal(basenameOf('/'), '/');
});
