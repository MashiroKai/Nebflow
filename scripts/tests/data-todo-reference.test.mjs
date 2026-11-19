import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
test('duplicate names do not resolve by order and id references choose exactly one', () => {
 const src=readFileSync(new URL('../../src/main/resources/web/js/utils.js',import.meta.url),'utf8');
 const code=src.slice(src.indexOf('const TODO_DIGIT_HEAD_RE'),src.indexOf('/**\n * 把 prose'));
 const context={}; vm.runInNewContext(code+'\nthis.match=matchTodoAt;',context);
 const rows=[{name:'milk',id:'aaaa'},{name:'milk',id:'bbbb'}];
 assert.equal(context.match('#milk',0,rows),null);
 assert.equal(context.match('#milk·bbbb',0,rows).todo.id,'bbbb');
 assert.equal(context.match('#milk·missing',0,rows),null);
 assert.equal(context.match('#milk',0,[rows[0]]).todo.id,'aaaa');
});
