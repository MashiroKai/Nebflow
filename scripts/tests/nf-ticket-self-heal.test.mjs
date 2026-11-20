#!/usr/bin/env node
// nf-ticket-self-heal.test.mjs — the element-leg ticket self-heal (author
// ruling 2026-11-19, 「图片加载不出来」裁定 B1).
//
// The unit under test is deliberately dependency-free: `nfTicketUrlPath` is
// pure, `healTicketElement` takes an injectable re-mint, and the installer
// takes an injectable window — so the browser path is exercised here without a
// DOM (the re-mint itself is the very `nfTicket.reMint` the ledger leg uses).
//
// Negative control built in: WITHOUT the heal an element holding a stale ticket
// keeps its dead `src`; WITH it the src is swapped exactly once, and a second
// failure does NOT loop (the bounded-retry contract).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  nfTicketUrlPath,
  healTicketElement,
  installTicketSelfHeal,
  REMINTED_FLAG,
} from '../../src/main/resources/web/js/nfTicketSelfHeal.js';

const BASE = 'https://host.invalid/';
const STALE = BASE + 'api/nf-file?path=%2FUsers%2Fk%2Fa.png&ticket=STALE';

/** A minimal media-element stub: src attribute + dataset + connectivity. */
function fakeMedia(tag, src) {
  const el = {
    tagName: tag,
    dataset: {},
    isConnected: true,
    parentNode: null,
    getAttribute: (n) => (n === 'src' ? el._src : null),
    _src: src,
  };
  Object.defineProperty(el, 'src', {
    get: () => el._src,
    set: (v) => {
      el._src = v;
    },
  });
  return el;
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

test('nfTicketUrlPath: accepts an /api/nf-file ticket URL, rejects everything else', () => {
  assert.equal(nfTicketUrlPath(STALE, BASE), '/Users/k/a.png');
  assert.equal(nfTicketUrlPath('/api/nf-file?path=%2Fx.png&ticket=T', BASE), '/x.png');
  // no ticket ⇒ the credential-free 401 face, not a stale-ticket case
  assert.equal(nfTicketUrlPath('/api/nf-file?path=%2Fx.png', BASE), null);
  // another endpoint is not ours to heal
  assert.equal(nfTicketUrlPath('/api/uploads?path=%2Fx.png&ticket=T', BASE), null);
  assert.equal(nfTicketUrlPath('blob:https://host.invalid/abc', BASE), null);
  assert.equal(nfTicketUrlPath('', BASE), null);
});

test('healTicketElement: a failing <img> with a stale ticket is re-minted exactly once', async () => {
  const calls = [];
  const remint = async (path) => {
    calls.push(path);
    return BASE + 'api/nf-file?path=' + encodeURIComponent(path) + '&ticket=FRESH';
  };
  const img = fakeMedia('IMG', STALE);
  assert.equal(healTicketElement(img, remint), true);
  await flush();
  assert.deepEqual(calls, ['/Users/k/a.png'], 'exactly one re-mint for the failing element');
  assert.match(img.src, /ticket=FRESH/, 'the element is re-pointed at the fresh ticket URL');
  assert.equal(img.dataset[REMINTED_FLAG], '1', 'the single re-mint is recorded');
  // Bounded: the second failure must NOT arm another re-mint (no loop).
  assert.equal(healTicketElement(img, remint), false);
  await flush();
  assert.deepEqual(calls, ['/Users/k/a.png'], 'still exactly one re-mint after a second failure');
});

test('healTicketElement: negative control — without a heal the stale src is untouched', () => {
  const img = fakeMedia('IMG', STALE);
  assert.equal(img.src, STALE, 'the dead ticket URL is exactly what the element keeps today');
  assert.equal(img.dataset[REMINTED_FLAG], undefined);
});

test('healTicketElement: ignores non-media elements and a failed re-mint does not blank the src', async () => {
  const div = fakeMedia('DIV', STALE);
  assert.equal(healTicketElement(div, async () => 'x'), false);
  const img = fakeMedia('IMG', STALE);
  // A failed mint yields the credential-free URL — the element must not be
  // overwritten with it (its existing 401 face is the honest answer).
  healTicketElement(img, async () => '/api/nf-file?path=%2FUsers%2Fk%2Fa.png');
  await flush();
  assert.equal(img.src, STALE);
});

test('installTicketSelfHeal: captures a <source> failure onto its media parent, and installs once', async () => {
  const listeners = [];
  const target = { addEventListener: (type, fn, capture) => listeners.push([type, fn, capture]) };
  assert.equal(installTicketSelfHeal(target, async () => STALE + '&x=1'), true);
  assert.equal(installTicketSelfHeal(target, async () => STALE + '&x=1'), false, 'idempotent');
  assert.equal(listeners.length, 1);
  assert.equal(listeners[0][0], 'error');
  assert.equal(listeners[0][2], true, 'capture phase — resource errors do not bubble');

  const calls = [];
  const video = fakeMedia('VIDEO', STALE);
  const source = { tagName: 'SOURCE', parentNode: video };
  listeners[0][1]({ target: source });
  await flush();
  assert.equal(video.dataset[REMINTED_FLAG], '1', 'the parent media element was healed, not the <source>');
  assert.ok(video.src.includes('x=1'));
  assert.deepEqual(calls, []);
});
