import assert from 'node:assert/strict';
import { loadPlaywright, launchBrowser, createSession, setWebRoot, settle } from './lib/visual-harness.mjs';

setWebRoot('build/web-dist');
const { mod } = loadPlaywright();
const { browser, label } = await launchBrowser(mod);
try {
  const session = await createSession(browser);
  const requests = [];
  const api = [];
  session.page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/')) api.push(path);
    if (/^\/api\/chains\/delegate-/.test(path) || /^\/api\/projects\/(stand-kai|%E7%94%B5%E5%AD%90%E4%B9%A6)\/flow-map/i.test(path)) requests.push(path);
  });
  await session.page.route('**/api/**', route => {
    const p = new URL(route.request().url()).pathname;
    let body = {};
    if (p === '/api/delegates') body = { rows: [{ id: 'delegate-nebflow-fixture', title: 'kernel', mode: 'kernel', executor: 'nebflow', live: { status: 'running' } }, { id: 'delegate-nebflow-chain', taskId: 'chain-fixture', title: 'chain', executor: 'nebflow', live: { status: 'running' } }] };
    else if (p === '/api/chain-digests') body = { fingerprint: 'mixed', digests: { 'chain-fixture': 'digest' } };
    else if (p === '/api/chains/chain-fixture') body = { record: { taskId: 'chain-fixture', entries: [] } };
    else if (p === '/api/projects') body = { projects: [{ name: 'stand' }], unmounted: [{ name: '电子书' }] };
    else if (p === '/api/canvas-tabs') body = { v: 2, tabs: [{ id: 'flow-map-stand-kai', title: 'stand-kai', type: 'flow-map', pinned: true }, { id: 'flow-map-电子书', title: '电子书', type: 'flow-map', pinned: true }], activeTabId: 'flow-map-stand-kai' };
    else if (p === '/api/child-batch') body = { children: {} };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await session.page.addInitScript(() => localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [{ id: 'flow-map-stand-kai', title: 'stand-kai', type: 'flow-map', pinned: true }, { id: 'flow-map-电子书', title: '电子书', type: 'flow-map', pinned: true }], activeTabId: 'flow-map-stand-kai' })));
  await session.boot();
  await session.page.evaluate(async () => {
    await window.__nfAdaptApi.loadFlowMapTab();
    const chains = await window.__nfAdaptApi.loadDelegateChainView();
    chains.openDelegateChainTask('delegate-nebflow-fixture');
    for (const name of ['stand-kai', '电子书']) window.dispatchEvent(new CustomEvent('canvas-tab-restore', { detail: { id: `flow-map-${name}`, type: 'flow-map' } }));
  });
  await settle(session.page, 1000);
  await session.page.evaluate(() => { window.__wsMock.onopen(); });
  await settle(session.page, 1000);
  assert(api.includes('/api/chains/chain-fixture'), 'real chain detail was not requested');
  assert(api.filter(p => p === '/api/delegates').length >= 2, 'reconnect did not refresh delegates');
  console.log(JSON.stringify({ browser: label, api, requests, errors: session.errors }));
  assert.deepEqual(requests, [], 'unexpected chain or stale project flow-map requests');
  await session.close();
} finally { await browser.close(); }
