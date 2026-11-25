const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('/Users/kaiyu/Claude code/Stand/node_modules/playwright');
const root = process.env.WEB_ROOT || require('node:path').join(__dirname, '../src/main/resources/web');
(async () => {
  const md = fs.readFileSync(root + '/js/viewers/markdown.js', 'utf8');
  const lex = fs.readFileSync(root + '/js/termLexicon.js', 'utf8');
  const match = md.match(/export function renderDocumentMentions\([\s\S]*?\n}\n\n\/\*\*/);
  assert.ok(match, 'document mention pass must exist');
  const matcher = lex.slice(lex.indexOf('function isAsciiWordChar'), lex.indexOf('/**\n * 从 graph')) + lex.slice(lex.indexOf('export function buildTermMatcher')).replace('export function', 'function');
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.setContent('<h1>HeaderDoc</h1><p>UniqueDoc DuplicateDoc <code>CodeDoc</code> <a href="#">LinkDoc</a></p><pre><code>BlockDoc</code></pre>');
    const result = await page.evaluate(({ matcher, implementation }) => {
      const api = new Function(matcher + '\nconst normVaultPath = s => String(s || "").toLowerCase();\nconst resolvedForNode = (n,p) => ({absPath:p.vault+"/"+n.rel});\nconst attachTermFace = (a) => a.dataset.face="yes";\n' + implementation.replace('export function','function').replace(/\n\/\*\*$/, '') + '\nreturn renderDocumentMentions;')();
      const names = ['UniqueDoc','DuplicateDoc','DuplicateDoc','HeaderDoc','CodeDoc','LinkDoc','BlockDoc'];
      const graph = {projects:[{name:'p',vault:'/p'}],nodes:names.map((title,i)=>({title,rel:'dir'+i+'/'+title+'.md',project:'p',device:'self'}))};
      api(document.body,graph,'p','/p/current.md');
      return {links:[...document.querySelectorAll('a.nf-term')].map(a=>({text:a.textContent,face:a.dataset.face,mention:a.dataset.wikiMention})), text:document.body.textContent};
    }, {matcher, implementation: match[0]});
    assert.deepEqual(result.links,[{text:'UniqueDoc',face:'yes',mention:'doc'}]);
    assert.match(result.text,/DuplicateDoc/);
    console.log('PASS unique, duplicate, code, existing link, heading, mention marker');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1});
