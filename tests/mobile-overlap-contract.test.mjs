// Targeted mutations against the production sweep probe and negative-control policy.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadPlaywright, launchBrowser } from '../scripts/lib/visual-harness.mjs';

const source = readFileSync(new URL('./mobile-overlap-sweep.spec.mjs', import.meta.url), 'utf8');
const probe = source.match(/const MEASURE = ([\s\S]*?\n});\n\nasync function runPanel/)[1];
const policy = source.match(/const acceptsNegative = ([^\n]+);/)?.[1];
const pw = loadPlaywright();
const { browser } = await launchBrowser(pw.mod.playwright || pw.mod);
test.after(async () => browser.close());
const cfg = { scrollSel: '#scroll', headerSel: '#header', firstSel: '.first', rowSel: '.row', bubbleSel: '.bubble', fabSels: [] };
async function measure(mutation = '') {
  const page = await browser.newPage();
  try {
    await page.setContent('<div id="header" style="height:20px"></div><div id="scroll"><div class="row first"><div class="bubble"><span id="text" style="display:block;width:40px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">long long long long long</span></div></div></div>');
    if (mutation) await page.evaluate(mutation);
    return await page.evaluate(({ probe, cfg }) => (0, eval)(`(${probe})`)(cfg), { probe, cfg });
  } finally { await page.close(); }
}
test('O4 detects clipping without ellipsis', async () => {
  const r = await measure("document.querySelector('#text').style.textOverflow='clip'");
  assert.ok(r.violations.some(v => v.rule === 'O4'), JSON.stringify(r));
});
test('O0 detects missing declared bubble', async () => {
  const r = await measure("document.querySelector('.bubble').className='missing'");
  assert.ok(r.violations.some(v => v.rule === 'O0'), JSON.stringify(r));
});
test('negative control rejects unrelated O0', () => {
  assert.ok(policy, 'negative-control policy missing');
  const accepts = (0, eval)(`(${policy})`);
  assert.equal(accepts([{ rule: 'O0' }], 'O1'), false);
  assert.equal(accepts([{ rule: 'O1' }], 'O1'), true);
});
test('O0 detects missing declared header', async () => {
  const r = await measure("document.querySelector('#header').id='missing'");
  assert.ok(r.violations.some(v => v.rule === 'O0'), JSON.stringify(r));
});
test('O0 detects missing declared first item', async () => {
  const r = await measure("document.querySelector('.first').classList.remove('first')");
  assert.ok(r.violations.some(v => v.rule === 'O0'), JSON.stringify(r));
});
