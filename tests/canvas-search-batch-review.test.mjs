import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const source = readFileSync(new URL('../src/main/resources/web/js/chatSearch.js', import.meta.url), 'utf8');
function fixture() {
  const start = source.indexOf('function selIdOf(');
  const end = source.indexOf('// v3.2: the date tab', start);
  const calls = [];
  const ctx = { lastResults: [{id:'img',type:'file',ref:'/tmp/a.png',name:'a.png'}, {id:'pdf',type:'file',ref:'/tmp/a.pdf',name:'a.pdf'}, {id:'dup',type:'file',ref:'/tmp/a.png',name:'a.png'}], multiSelected:new Set(['img','pdf','dup']), multiAnchor:-1, fileSelectionMode:true, currentTab:'files', popArtifactFromInput:()=>null, routeSelectedFile: item => calls.push([item.kind,item.filePath]), activateMediaEntry: item => calls.push(['entry',item.ref]), activateResult:()=>{}, closeSearchModal:()=>{}, document:{getElementById:()=>null, querySelectorAll:()=>[]}, CSS:{escape:x=>x}, t:()=>'', calls };
  vm.runInNewContext(source.slice(start,end) + '\nthis.button=openSelectedFilesInCanvas; this.keyboard=openMultiSelected;',ctx);
  return ctx;
}
test('FCI-SCN-004 button preserves deduplicated mixed selection',()=>{
  const c=fixture(); c.button(); assert.deepEqual(c.calls,[['image','/tmp/a.png'],['file','/tmp/a.pdf']]);
});
test('FCI-SCN-004 keyboard uses same file routing',()=>{
  const c=fixture(); c.keyboard(); assert.deepEqual(c.calls,[['image','/tmp/a.png'],['file','/tmp/a.pdf']]);
});
test('FCI-SCN-004 selection semantics independent of active descendant',()=>{
  const c=fixture(); const attrs = new Map();
  const option = {dataset:{key:'img'}, classList:{toggle(){}}, setAttribute:(k,v)=>attrs.set(k,v)};
  const list = {hasAttribute:()=>false, setAttribute:(k,v)=>attrs.set(k,v), querySelectorAll:()=>[option]};
  c.document = {getElementById:()=>list, querySelectorAll:()=>[option]};
  vm.runInNewContext(source.slice(source.indexOf('function paintMultiClass('), source.indexOf('/** Return selected')), c);
  c.paintMultiClass('img', true);
  assert.equal(attrs.get('aria-multiselectable'),'true'); assert.equal(attrs.get('aria-selected'),'true');
  c.paintMultiClass('img', false); assert.equal(attrs.get('aria-selected'),'false');
  assert.match(source,/multiSelected\.has\(selIdOf\(item\)\)/);
});
test('FCI-SCN-004 selected media follows desktop and mobile router', async()=>{
  const route = source.slice(source.indexOf('function routeSelectedFile('), source.indexOf('/** Grid activation:', source.indexOf('function routeSelectedFile(')));
  const mediaSource = readFileSync(new URL('../src/main/resources/web/js/mediaOpenRoute.js', import.meta.url), 'utf8');
  const routeMedia = mediaSource.slice(mediaSource.indexOf('export function routeMediaClick(')).replace('export function','function');
  for (const mobile of [false,true]) {
    const calls=[];
    const c={isMobileLayer:()=>mobile,openMediaDirect:item=>{calls.push(['direct',item.path]);return Promise.resolve(true);},openPopArtifact:path=>calls.push(['canvas',path]),openMediaLightbox:()=>calls.push(['fallback']),attachmentImageUrl:()=>'',calls};
    vm.runInNewContext(routeMedia+'\n'+route+'\nthis.run=routeSelectedFile;',c);
    c.run({kind:'image',filePath:'/tmp/a.png',title:'a.png'});
    c.run({kind:'file',filePath:'/tmp/a.pdf',title:'a.pdf'});
    await Promise.resolve();
    assert.deepEqual(calls,[[mobile?'direct':'canvas','/tmp/a.png'],['canvas','/tmp/a.pdf']]);
  }
});
