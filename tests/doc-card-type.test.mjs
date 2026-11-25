import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const canvas = readFileSync(new URL('../src/main/resources/web/js/canvas.js', import.meta.url), 'utf8');
const viewers = readFileSync(new URL('../src/main/resources/web/js/fileViewers.js', import.meta.url), 'utf8');
const markdown = readFileSync(new URL('../src/main/resources/web/js/viewers/markdown.js', import.meta.url), 'utf8');
const helper = canvas.match(/async function openOptimisticFile\([\s\S]*?\n}\n/)[0]
  .replace("await import('./attachmentPreview.js')", '({ previewWithProgress: mockPreview })');
const typeFn = viewers.match(/export function itemTypeForFileName\([\s\S]*?\n}\n/)[0].replace('export ', '');
const extensions = markdown.match(/extensions:\s*\[([^\]]+)\]/)[1].match(/\.[a-z0-9]+/g);
const classify = runInNewContext(`(${typeFn})`, { registry: new Map([['markdown', { name: 'markdown', extensions }]]) });
async function inspect(title, absPath) {
  let args;
  const open = runInNewContext(`(${helper})`, { mockPreview: p => { args = p; }, OPTIMISTIC_FILE_TIMEOUT_MS: 30000 });
  await open({ id: 'x', title, absPath, pinned: false });
  return { title: args.title, type: classify(args.fileName) || 'code' };
}

test('真实 markdown 路径优先于无扩展名标题', async () => {
  assert.deepEqual(await inspect('中子谱剂量贡献能段', '/tmp/report.md'), { title: '中子谱剂量贡献能段', type: 'markdown' });
});
test('未提供路径时沿用标题类型', async () => {
  assert.deepEqual(await inspect('report.md', undefined), { title: 'report.md', type: 'markdown' });
});
test('空路径时无扩展名标题仍回落 code', async () => {
  assert.deepEqual(await inspect('标题', ''), { title: '标题', type: 'code' });
});
