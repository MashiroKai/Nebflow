import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const source = readFileSync(new URL('../../src/main/resources/web/js/chatSearch.js', import.meta.url), 'utf8');
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
  const mediaSource = readFileSync(new URL('../../src/main/resources/web/js/mediaOpenRoute.js', import.meta.url), 'utf8');
  // 取法重锚（2026-11-19 普查收编批）：旧窗从 `export function routeMediaClick(` 起切，
  // 但 routeMediaClick 现在调用模块级 `mediaKind()`（mediaOpenRoute.js:125）⇒ 窗口内无定义，
  // vm 抛 `ReferenceError: mediaKind is not defined`。窗口改为从 `export function mediaKind(`
  // 切到文件末（该窗自足：mediaKind/routeMediaClick 只用入参与内置对象），
  // 并把动态 `import('./lightbox.js')` 换成可注入的画廊桩，好让「图片走画廊」可断言。
  const routeMedia = mediaSource
    .slice(mediaSource.indexOf('export function mediaKind('))
    .replace(/export function/g,'function')
    .replace("import('./lightbox.js')","Promise.resolve({ openImageGallery: (list, i) => { globalThis.__gallery(list, i); return true; } })");
  // 平台分支已退役的**具名证据**（同一批断言）：`isMobileLayer` / `openMediaDirect`
  // 在路由里已无出现 —— f406ee462（2026-11-11「desktop = Canvas open, mobile = system
  // direct-open」）钉的就是旧分支；bf2184785 + a7fa1c531（2026-11-17 手机层图片改应用内
  // 画廊 / 共享桌面画廊）把它退役，平台维度搬进 lightbox.openImageGallery。故下面两档
  // 断言同一序列：这是「路由不再分平台」的**活断言**，不是把断言放松成恒真。
  assert.doesNotMatch(routeMedia, /isMobileLayer|openMediaDirect/);
  for (const mobile of [false,true]) {
    const calls=[];
    const c={isMobileLayer:()=>mobile,__gallery:(list,i)=>calls.push(['gallery',list.map(it=>it.kind+':'+it.path).join(','),i]),openPopArtifact:path=>calls.push(['canvas',path]),openMediaLightbox:()=>calls.push(['fallback']),attachmentImageUrl:()=>'',calls};
    vm.runInNewContext(routeMedia+'\n'+route+'\nthis.run=routeSelectedFile;',c);
    c.run({kind:'image',filePath:'/tmp/a.png',title:'a.png'});
    c.run({kind:'file',filePath:'/tmp/a.pdf',title:'a.pdf'});
    // 非媒体腿（kind==='file'）同步落 openCanvas；媒体腿经画廊 Promise 异步 —— 两条都钉。
    assert.deepEqual(calls,[['canvas','/tmp/a.pdf']],`同步腿 mobile=${mobile}`);
    await Promise.resolve();
    // 调用序列 = 事实集（画廊腿天然异步，先后由微任务决定），但每一笔的形状/取值必须逐字相等，
    // 且两档（桌面/手机）序列必须一致 —— 平台分支退役后的活断言。
    const sorted=calls.slice().sort((a,b)=>String(a[0]).localeCompare(String(b[0])));
    assert.deepEqual(sorted,[['canvas','/tmp/a.pdf'],['gallery','image:/tmp/a.png',0]],`mobile=${mobile}`);
  }
});
