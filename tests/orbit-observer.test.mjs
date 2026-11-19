// Bounded consumer regression for the real orbit observer. No browser or server is started.
// The DOM double deliberately reports same-value classList writes, just as MutationObserver does.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../src/main/resources/web/js/flowAnim.js', import.meta.url), 'utf8');

function harness() {
  const records = [];
  const listeners = new Map();
  const nodes = [];
  let observer;
  class Element {
    constructor(classes = '', id = '') {
      this.classes = new Set(classes.split(/\s+/).filter(Boolean));
      this.id = id;
      this.nodeType = 1;
      this.dataset = {};
      this.style = { setProperty(name, value) { this[name] = value; } };
      this.spins = [];
      this.classList = {
        contains: name => this.classes.has(name),
        add: (...names) => { names.forEach(name => this.classes.add(name)); this.mutate(); },
        remove: (...names) => { names.forEach(name => this.classes.delete(name)); this.mutate(); },
      };
    }
    mutate() { if (observer) records.push({ type: 'attributes', target: this }); }
    getAttribute(name) { return name === 'data-node-id' ? this.id : null; }
    closest(selector) { return selector === '.solar-node' ? this.owner : { dataset: { tabId: 'test-pane' } }; }
    querySelectorAll() { return this.spins; }
    querySelector() { return null; }
    getAnimations() { return [{ animationName: 'solar-spin', currentTime: 100 }]; }
  }
  const document = {
    body: new Element(), documentElement: new Element(),
    querySelectorAll: () => nodes,
    addEventListener: (type, fn) => listeners.set(type, fn),
  };
  const window = {};
  const context = vm.createContext({
    document, window, Element, performance: { now: () => 1000 },
    matchMedia: () => ({ matches: false }),
    getComputedStyle: () => ({ getPropertyValue: () => '' }),
    setTimeout: fn => { fn(); return 0; },
    MutationObserver: class {
      constructor(fn) { observer = fn; }
      observe() {}
    },
  });
  // Only strip the module export keyword; execute the production implementation unchanged.
  new vm.Script(source.replace('export function orbitSync', 'function orbitSync')).runInContext(context);
  return {
    node(id, ringCount = 3) {
      const node = new Element('solar-node running', id);
      node.spins = Array.from({ length: ringCount }, () => {
        const spin = new Element();
        spin.owner = node;
        return spin;
      });
      nodes.push(node);
      return node;
    },
    sync: () => window.__orbitTest.sync(),
    states: () => window.__orbitTest.states(),
    event(type, target) { listeners.get(type)({ target, animationName: 'solar-spin-coast' }); },
    drain() {
      let deliveries = 0;
      while (records.length && deliveries < 12) {
        const batch = records.splice(0);
        observer(batch);
        deliveries++;
      }
      assert.equal(records.length, 0, 'observer must settle within 12 deliveries, not starve the event loop');
      return deliveries;
    },
  };
}

for (const status of ['completed', 'failed', 'cancelled']) {
  test(`orbit-less folded chain running -> ${status} does not self-notify forever`, () => {
    const h = harness();
    const card = h.node('folded-chain', 0);
    h.sync();
    h.drain();
    card.classList.remove('running');
    card.classList.add(status);
    h.drain();
    assert.equal(card.classList.contains('fm-orbit-coast'), false);
    assert.equal(h.states().length, 0);
  });

  test(`real three-ring node running -> ${status} coasts and fades only after all rings finish`, () => {
    const h = harness();
    const node = h.node('real-node');
    h.sync();
    h.drain();
    assert.equal(h.states()[0].phase, 'running');
    assert.ok(node.spins.every(spin => spin.dataset.orbitPhase === '1'));
    node.classList.remove('running');
    node.classList.add(status);
    h.drain();
    assert.equal(h.states()[0].phase, 'coasting');
    assert.ok(node.spins.every(spin => spin.style.animationName === 'solar-spin-coast'));
    h.event('animationend', node.spins[0]);
    h.event('animationend', node.spins[1]);
    assert.equal(node.classList.contains('fm-orbit-faded'), false);
    h.event('animationend', node.spins[2]);
    h.drain();
    assert.equal(node.classList.contains('fm-orbit-faded'), true);
  });
}

test('losing an orbit during a coast drops stale phase state without observer feedback', () => {
  const h = harness();
  const node = h.node('changing-shape');
  h.sync();
  h.drain();
  node.classList.remove('running');
  node.classList.add('completed');
  h.drain();
  node.spins.pop();
  h.sync();
  h.drain();
  assert.equal(h.states().length, 0);
  assert.equal(node.classList.contains('fm-orbit-coast'), false);
  assert.ok(node.spins.every(spin => spin.style.animationName === ''));
});

test('cancelled coast can re-enter running and later complete normally', () => {
  const h = harness();
  const node = h.node('resume');
  h.sync();
  h.drain();
  node.classList.remove('running');
  node.classList.add('cancelled');
  h.drain();
  node.classList.remove('cancelled');
  node.classList.add('running');
  node.spins.forEach(spin => h.event('animationcancel', spin));
  h.drain();
  assert.equal(h.states()[0].phase, 'running');
  assert.equal(node.classList.contains('fm-orbit-coast'), false);
  assert.ok(node.spins.every(spin => spin.style.animationName === ''));
  node.classList.remove('running');
  node.classList.add('completed');
  h.drain();
  node.spins.forEach(spin => h.event('animationend', spin));
  h.drain();
  assert.equal(node.classList.contains('fm-orbit-faded'), true);
});
