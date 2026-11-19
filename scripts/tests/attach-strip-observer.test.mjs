import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../src/main/resources/web/js/attachStripFold.js', import.meta.url), 'utf8');

function harness(wrap = 'nowrap') {
  const records = [];
  let callback;
  let watching = false;
  let disconnects = 0;
  const mutate = () => { if (watching) records.push({}); };
  function element(classes = '') {
    const names = new Set(classes.split(/\s+/).filter(Boolean));
    return {
      children: [], parentElement: null, isConnected: false, hidden: false,
      offsetWidth: 60, clientWidth: 140, textContent: '',
      classList: {
        contains: name => names.has(name),
        add: name => { names.add(name); mutate(); },
        remove: name => { names.delete(name); mutate(); },
        toggle: name => { names.has(name) ? names.delete(name) : names.add(name); mutate(); },
      },
      addEventListener() {},
      appendChild(child) {
        if (child.parentElement) child.remove();
        this.children.push(child); child.parentElement = this; child.isConnected = true; mutate();
      },
      remove() {
        if (this.parentElement) this.parentElement.children.splice(this.parentElement.children.indexOf(this), 1);
        this.parentElement = null; this.isConnected = false; mutate();
      },
      get lastElementChild() { return this.children.at(-1); },
      querySelectorAll() { return this.children.filter(c => c.classList.contains('mh-att-collapsed')); },
    };
  }
  const row = element();
  row.isConnected = true;
  const context = vm.createContext({
    document: { getElementById: () => row, createElement: () => element('mh-att-more') },
    window: {},
    getComputedStyle: () => ({ flexWrap: wrap, columnGap: '6px' }),
    MutationObserver: class {
      constructor(fn) { callback = fn; }
      observe() { watching = true; }
      disconnect() { watching = false; disconnects++; }
    },
  });
  new vm.Script(source.replaceAll('export function ', 'function ')).runInContext(context);
  return {
    row, element,
    watch: () => context.watchAttachmentStrip(),
    sync: () => context.resyncAttachmentStrip(),
    disconnects: () => disconnects,
    drain() {
      let deliveries = 0;
      while (records.length && deliveries < 12) {
        records.splice(0); callback(); deliveries++;
      }
      assert.equal(records.length, 0, 'empty attachment strip must not starve timers with observer feedback');
      return deliveries;
    },
  };
}

test('empty single-line attachment strip reaches an observer fixed point', () => {
  const h = harness();
  const stop = h.watch();
  h.drain();
  h.sync();
  assert.equal(h.drain(), 0);
  stop();
  assert.equal(h.disconnects(), 1);
});

test('removing the last expanded attachment clears both expansion and the more button', () => {
  const h = harness();
  const chip = h.element('att-file');
  h.row.appendChild(chip);
  h.row.classList.add('mh-att-expanded');
  const stop = h.watch();
  h.drain();
  assert.equal(h.row.children.length, 2, 'expanded strip includes its collapse button');
  chip.remove();
  h.drain();
  assert.equal(h.row.classList.contains('mh-att-expanded'), false);
  assert.equal(h.row.children.length, 0);
  assert.equal(h.drain(), 0);
  stop();
});

test('wrapping desktop attachment strip does not fold or mutate an empty row', () => {
  const h = harness('wrap');
  const stop = h.watch();
  assert.equal(h.drain(), 0);
  assert.equal(h.row.children.length, 0);
  stop();
});
