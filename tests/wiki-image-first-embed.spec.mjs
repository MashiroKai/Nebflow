// Regression: an Obsidian image embed at offset zero must be expanded.
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = readFileSync(fileURLToPath(new URL('../src/main/resources/web/js/viewers/markdown.js', import.meta.url)), 'utf8');
const start = source.indexOf('function expandImageEmbeds(md) {');
const end = source.indexOf('\n}\n', start);
assert(start >= 0 && end > start, 'real renderer function must be present');
const expand = new Function('MD_IMG_EXT_RE', 'escapeAttr', 'FENCE_RE', 'humanCardBodyLines',
  source.slice(start, end + 2) + '\nreturn expandImageEmbeds;')(
  /\.(png|jpe?g|jfif|gif|webp|avif|bmp|svg|ico|tiff?)$/i,
  (s) => String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;'),
  /^\s*(`{3,}|~{3,})/, () => new Set());
assert.match(expand('![[x.png]]'), /^<img src="x.png"/);
assert.match(expand('text\n![[x.png]]'), /<img src="x.png"/);
assert.equal((expand('![[x.png]] ![[y.png]]').match(/<img /g) || []).length, 2);
assert.equal(expand('![[x.png'), '![[x.png');
console.log('PASS wiki image embed start, prefix, multiple and unclosed');
