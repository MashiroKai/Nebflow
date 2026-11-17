// FRM-SCN-001..011 consumer regressions for forms, task drafts and background controls.
// They boot the real web tree under scripts/lib/visual-harness.mjs (static files from disk, a MockWS that feeds the real
// ws.js dispatch). This is consumer-only evidence: no gateway, no persistence, no manual GUI acceptance.
import { test, expect } from '@playwright/test';
import { createSession, settle } from '../scripts/lib/visual-harness.mjs';

const SID = 'forms-root';
const BOOT = { sessionId: SID, config: { llm: { providers: {} }, features: {} } };
const PANEL_STUBS = {
  '/api/config/provider-types': { body: { domains: [{ id: 'llm' }, { id: 'imagegen' }], adapters: { imagegen: [] } } },
  '/api/config/imagegen': { body: { enabled: false, compiledIn: true, providers: {} } },
  '/api/config/provider-keys': { body: { providers: {} } },
};

async function session(browser, extra = {}) {
  const s = await createSession(browser, { width: 1280, height: 800, colorScheme: 'light', ...extra });
  // No service worker: with no server behind the virtual origin, WebKit lets it fail every same-origin fetch.
  await s.page.addInitScript(() => { try { localStorage.setItem('stand_sw', '0'); } catch { /* storage unavailable */ } });
  return s;
}

// Same sequence as the harness boot(), on an origin WebKit accepts (the harness origin uses port 1, which WebKit blocks).
// Every request is answered by the harness route from disk, so the origin value itself carries no meaning.
async function boot(s, base = BOOT) {
  const { page, frame } = s;
  await page.goto('http://localhost:8380/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; if (window.__wsMock.onopen) window.__wsMock.onopen(); });
  await page.waitForTimeout(250);
  await frame({ type: 'sessionList', sessionId: base.sessionId, activeId: base.sessionId, folders: [], sessions: [{ id: base.sessionId, name: 'Nebula', agentName: 'Nebula' }] });
  await frame({ type: 'configData', config: JSON.stringify(base.config), configured: true, onboarding: 'done' });
  await frame({ type: 'historyPage', sessionId: base.sessionId, messages: [], hasMore: false, offset: 0 });
  await settle(page);
}

async function mountProviderPanel(page) {
  await page.evaluate(async () => {
    const { PROVIDER_PANEL_HOST_ID, bindProviderPanel } = await import('/js/sidebar/providerPanel.js');
    const host = document.createElement('div');
    host.id = PROVIDER_PANEL_HOST_ID;
    document.body.appendChild(host);
    await bindProviderPanel();
  });
  await expect(page.locator('#btn-add-provider')).toBeAttached();
}

const updateConfigFrames = (page) => page.evaluate(() => window.__wsSent.filter((f) => f.type === 'updateConfig'));

// Record every toast the page raises (kind + text) without replacing the real toast.
async function captureToasts(page) {
  await page.evaluate(() => {
    window.__toasts = [];
    const original = window.__showToast;
    window.__showToast = (message, kind) => { window.__toasts.push({ message, kind }); return original ? original(message, kind) : undefined; };
  });
}
const errorToasts = (page) => page.evaluate(() => window.__toasts.filter((toast) => toast.kind === 'error'));

async function fillLlmForm(page) {
  await page.locator('#btn-add-provider').dispatchEvent('click');
  const modal = page.locator('#cfg-modal');
  await expect(modal).toBeVisible();
  await modal.locator('[data-field="name"]').fill('zz-p1');
  await modal.locator('[data-field="baseUrl"]').fill('https://api.example.invalid/v1');
  await modal.locator('[data-field="apiKey"]').fill('sk-test-not-real');
  await modal.locator('.cfg-model-id').first().fill('m1');
  return modal;
}

test('FRM-SCN-008 LLM provider form closes only on its own acknowledgement and keeps the draft otherwise', async ({ browser }) => {
  const s = await session(browser, { apiStubs: PANEL_STUBS });
  const { page } = s;
  try {
    await boot(s);
    await mountProviderPanel(page);
    const modal = await fillLlmForm(page);
    const save = page.locator('#cfg-modal-save');

    // Closed socket: nothing is sent, the draft stays, the form can be retried.
    await page.evaluate(() => { window.__wsMock.readyState = 3; });
    await save.dispatchEvent('click');
    await expect(save).toBeEnabled();
    expect((await updateConfigFrames(page)).length).toBe(0);
    await expect(modal).toBeVisible();
    await page.evaluate(() => { window.__wsMock.readyState = 1; });

    // First attempt: one correlated frame, single flight while it is pending.
    await save.dispatchEvent('click');
    await expect.poll(async () => (await updateConfigFrames(page)).length).toBe(1);
    const first = (await updateConfigFrames(page))[0];
    expect(first.requestId).toMatch(/^provider-/);
    expect(JSON.parse(first.config).llm.providers['zz-p1'].baseUrl).toBe('https://api.example.invalid/v1/');
    await expect(save).toBeDisabled();
    await save.dispatchEvent('click');
    expect((await updateConfigFrames(page)).length).toBe(1);

    // An acknowledgement for another request never confirms this save.
    await s.frame({ type: 'configUpdated', success: true, requestId: 'someone-else' });
    await expect(modal).toBeVisible();

    // A matching error keeps the form open with every input intact.
    await s.frame({ type: 'error', msgType: 'updateConfig', message: 'rejected', requestId: first.requestId });
    await expect(save).toBeEnabled();
    await expect(modal).toBeVisible();
    await expect(modal.locator('[data-field="name"]')).toHaveValue('zz-p1');
    await expect(modal.locator('[data-field="baseUrl"]')).toHaveValue('https://api.example.invalid/v1');
    await expect(modal.locator('[data-field="apiKey"]')).toHaveValue('sk-test-not-real');
    await expect(modal.locator('.cfg-model-id').first()).toHaveValue('m1');
    expect(await page.evaluate(async () => (await import('/js/state.js')).default.parsedConfig?.llm?.providers?.['zz-p1'])).toBeUndefined();

    // Retry: a new request id; only its own success closes the form, updates the snapshot and repaints the list.
    await save.dispatchEvent('click');
    await expect.poll(async () => (await updateConfigFrames(page)).length).toBe(2);
    const second = (await updateConfigFrames(page))[1];
    expect(second.requestId).not.toBe(first.requestId);
    await s.frame({ type: 'configUpdated', success: true, requestId: second.requestId });
    await expect(modal).toHaveCount(0);
    expect(await page.evaluate(async () => (await import('/js/state.js')).default.parsedConfig.llm.providers['zz-p1'].baseUrl)).toBe('https://api.example.invalid/v1/');
    await expect(page.locator('#provider-list')).toContainText('zz-p1');
  } finally {
    await s.close();
  }
});

test('FRM-SCN-008 LLM provider save without any answer keeps the draft after the timeout', async ({ browser }) => {
  const s = await session(browser, { apiStubs: PANEL_STUBS });
  const { page } = s;
  try {
    await boot(s);
    await mountProviderPanel(page);
    const modal = await fillLlmForm(page);
    await page.clock.install();
    await page.locator('#cfg-modal-save').dispatchEvent('click');
    await expect.poll(async () => (await updateConfigFrames(page)).length).toBe(1);
    await expect(page.locator('#cfg-modal-save')).toBeDisabled();
    await page.clock.fastForward(16000);
    await expect(page.locator('#cfg-modal-save')).toBeEnabled();
    await expect(modal).toBeVisible();
    await expect(modal.locator('[data-field="name"]')).toHaveValue('zz-p1');
    await expect(modal.locator('.cfg-model-id').first()).toHaveValue('m1');
  } finally {
    await s.close();
  }
});

test('FRM-SCN-008 a refused provider save is reported on the form and leaves the running turn and the conversation alone', async ({ browser }) => {
  const s = await session(browser, { apiStubs: PANEL_STUBS });
  const { page } = s;
  try {
    await boot(s);
    await mountProviderPanel(page);
    const modal = await fillLlmForm(page);
    await captureToasts(page);
    // A turn is running in the primary conversation while the user edits settings.
    await page.evaluate(async (sid) => { (await import('/js/state.js')).default.busySessionIds.add(sid); }, SID);
    await page.locator('#cfg-modal-save').dispatchEvent('click');
    await expect.poll(async () => (await updateConfigFrames(page)).length).toBe(1);
    const { requestId } = (await updateConfigFrames(page))[0];

    // The exact frame the gateway answers a refused save with (WsConfigHandlers.handleUpdateConfig).
    await s.frame({ type: 'error', msgType: 'updateConfig', message: 'Provider zz-p1: Base URL is required', requestId });
    await expect(page.locator('#cfg-modal-save')).toBeEnabled();
    await expect(modal).toBeVisible();
    await expect(modal.locator('[data-field="name"]')).toHaveValue('zz-p1');
    await expect.poll(async () => (await errorToasts(page)).length).toBeGreaterThan(0);
    const settled = () => page.evaluate(async (sid) => ({
      busy: (await import('/js/state.js')).default.busySessionIds.has(sid),
      errorRows: document.querySelectorAll('.row.error').length,
    }), SID);
    expect(await settled()).toEqual({ busy: true, errorRows: 0 });

    // A refusal that names no request id is not the form's answer: it keeps the generic error handling.
    await s.frame({ type: 'error', msgType: 'updateConfig', message: 'config rejected' });
    await expect.poll(async () => (await settled()).errorRows).toBe(1);
    expect((await settled()).busy).toBe(false);
  } finally {
    await s.close();
  }
});

test('FRM-SCN-001 validation failure keeps the form open without sending anything', async ({ browser }) => {
  const s = await session(browser, { apiStubs: PANEL_STUBS });
  const { page } = s;
  try {
    await boot(s);
    await mountProviderPanel(page);
    const modal = await fillLlmForm(page);
    await modal.locator('[data-field="baseUrl"]').fill('');
    await page.locator('#cfg-modal-save').dispatchEvent('click');
    await expect(modal).toBeVisible();
    await expect(page.locator('#cfg-modal-save')).toBeEnabled();
    await expect(modal.locator('[data-field="name"]')).toHaveValue('zz-p1');
    expect((await updateConfigFrames(page)).length).toBe(0);
  } finally {
    await s.close();
  }
});

// （三处 JeV 表单用例——FRM-SCN-004 两腿（adapter 造型）+ FRM-SCN-001 网络失败
// 腿——已随 JeV 全量退役删除，jev-retire 批 2026-11-16：jev 域不在统一服务商
// 面板，/api/config/jev 与 #btn-test-jev 不复存在。表单失败保留草稿语义的 LLM
// 腿仍由本文件 FRM-SCN-001 与 forms-provider-consumer.spec 守。）

// Mount the mobile Apple todo face on a minimal navigator. The previous flow leaves its list page mounted, so
// callers always take the newest page (`.last()`).
async function mountMobileTodo(page) {
  await page.evaluate(async () => {
    const { mountTodoAppleInto } = await import('/js/mobile/todoAppleFace.js');
    const host = document.createElement('div');
    host.id = 'forms-todo-host';
    document.body.appendChild(host);
    const stack = [];
    const nav = {
      push(pageId) {
        const pageEl = document.createElement('div');
        pageEl.className = 'mobile-page';
        const body = document.createElement('div');
        pageEl.appendChild(body);
        host.appendChild(pageEl);
        stack.push(pageEl);
        mountTodoAppleInto(pageId, body, nav);
      },
      pop() { const top = stack.pop(); if (top) top.remove(); },
    };
    window.__todoNav = nav;
    nav.push('tasks');
  });
}
const clickLast = (page, selector) => page.locator(selector).last().evaluate((el) => el.click());
async function openMobileTodoItem(page, title) {
  await clickLast(page, '.ta-row-tag');
  await page.locator('.ta-item-body', { hasText: title }).last().evaluate((el) => el.click());
}

test('FRM-SCN-002 mobile todo edit submits a null tag when the tag is cleared and keeps it otherwise', async ({ browser }) => {
  const s = await session(browser, { width: 390, height: 844 });
  const { page } = s;
  try {
    await boot(s);
    await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      todoStore.__seed({
        tagList: [{ id: 't1', name: 'Work', color: '#4488ff' }],
        itemList: [{ id: 'i1', tagId: 't1', name: 'Clear me', priority: 0 }, { id: 'i2', tagId: 't1', name: 'Keep me', priority: 0 }],
      });
    });
    await mountMobileTodo(page);
    const tagOf = (id) => page.evaluate(async (itemId) => (await import('/js/todoStore.js')).todoStore.allItems().find((x) => x.id === itemId).tagId, id);

    // Untouched tag: saving keeps it.
    await openMobileTodoItem(page, 'Keep me');
    await clickLast(page, '.ta-save');
    expect(await tagOf('i2')).toBe('t1');

    // Cycle the single tag to "none" and save: the clearing is submitted.
    await page.evaluate(() => window.__todoNav.push('tasks'));
    await openMobileTodoItem(page, 'Clear me');
    await page.locator('.ta-tool-btn').nth(2).evaluate((el) => el.click());
    await clickLast(page, '.ta-save');
    expect(await tagOf('i1')).toBeNull();
  } finally {
    await s.close();
  }
});

test('FRM-SCN-002 mobile todo edit puts an empty project on the wire when the tag is cleared and sends nothing when it is untouched', async ({ browser }) => {
  const s = await session(browser, { width: 390, height: 844 });
  const { page } = s;
  try {
    await boot(s);
    // The store is fed by the backend list frame, exactly as in production (no fixture seed).
    await s.frame({ type: 'scheduledTaskList', sessionId: SID, scope: 'all', tasks: [
      { id: 'todo-1', sessionId: SID, kind: 'todo', name: 'Clear me', detail: '', content: 'Clear me', project: 'Work', priority: 0, createdAt: 1 },
      { id: 'todo-2', sessionId: SID, kind: 'todo', name: 'Keep me', detail: '', content: 'Keep me', project: 'Work', priority: 0, createdAt: 2 },
    ] });
    await mountMobileTodo(page);
    const updates = () => page.evaluate(() => window.__wsSent.filter((f) => f.type === 'updateScheduledTask'));

    // Untouched tag: saving sends no tag change.
    await openMobileTodoItem(page, 'Keep me');
    await page.evaluate(() => { window.__wsSent.length = 0; });
    await clickLast(page, '.ta-save');
    await page.waitForTimeout(300);
    expect(await updates()).toEqual([]);

    // Cycle the single tag to "none" and save: the gateway is told to clear the assignment (empty project).
    await page.evaluate(() => window.__todoNav.push('tasks'));
    await openMobileTodoItem(page, 'Clear me');
    await page.evaluate(() => { window.__wsSent.length = 0; });
    await page.locator('.ta-tool-btn').nth(2).evaluate((el) => el.click());
    await clickLast(page, '.ta-save');
    await expect.poll(async () => (await updates()).length).toBe(1);
    expect((await updates())[0]).toEqual({ type: 'updateScheduledTask', id: 'todo-1', project: '', sessionId: SID });
  } finally {
    await s.close();
  }
});

test('FRM-SCN-003 desktop todo edit and create drafts survive unrelated store updates', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const result = await page.evaluate(async (sid) => {
      const { todoStore } = await import('/js/todoStore.js');
      const { __desktopFace, renderTodoTaskList } = await import('/js/todoFace.js');
      const seed = (extra = []) => todoStore.__seed({
        tagList: [{ id: 't1', name: 'Work', color: '#4488ff' }],
        itemList: [{ id: 'i1', tagId: 't1', name: 'First' }, { id: 'i2', tagId: 't1', name: 'Second' }, ...extra],
      });
      const type = (input, value, from, to) => {
        input.focus();
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.setSelectionRange(from, to);
      };
      seed();
      renderTodoTaskList(sid);
      const face = __desktopFace();

      // Edit: starting to edit must really build the edit form (a plain row must not be kept).
      face.startEditing('i1');
      const editRow = face.listInner.querySelector('.tfl-row.tfl-row-editing[data-id="i1"]');
      const editInput = editRow && editRow.querySelector('.tfl-edit-name');
      type(editInput, 'First (edited)', 3, 7);
      seed([{ id: 'i3', tagId: 't1', name: 'Third' }]);
      const editAfter = face.listInner.querySelector('.tfl-row.tfl-row-editing[data-id="i1"]');
      const edit = {
        built: !!editInput,
        sameRow: editAfter === editRow,
        sameInput: !!editAfter && editAfter.querySelector('.tfl-edit-name') === editInput,
        value: editInput.value,
        focused: document.activeElement === editInput,
        selection: [editInput.selectionStart, editInput.selectionEnd],
        rows: face.listInner.querySelectorAll('.tfl-row').length,
      };

      // Create: the open draft row survives as well.
      face.saveEditing();
      face.startCreate();
      const createRow = face.listInner.querySelector('.tfl-create-row');
      const createInput = createRow && createRow.querySelector('.tfl-row-title-input');
      type(createInput, 'Brand new draft', 2, 5);
      seed([{ id: 'i4', tagId: 't1', name: 'Fourth' }]);
      const createAfter = face.listInner.querySelector('.tfl-create-row');
      const create = {
        built: !!createInput,
        sameRow: createAfter === createRow,
        value: createInput.value,
        focused: document.activeElement === createInput,
        selection: [createInput.selectionStart, createInput.selectionEnd],
      };
      return { edit, create };
    }, SID);
    expect(result.edit).toEqual({ built: true, sameRow: true, sameInput: true, value: 'First (edited)', focused: true, selection: [3, 7], rows: 3 });
    expect(result.create).toEqual({ built: true, sameRow: true, value: 'Brand new draft', focused: true, selection: [2, 5] });
  } finally {
    await s.close();
  }
});

test('FRM-SCN-003 mobile todo bottom-sheet draft survives unrelated store updates', async ({ browser }) => {
  const s = await session(browser, { width: 390, height: 844 });
  const { page } = s;
  try {
    await boot(s);
    const result = await page.evaluate(async () => {
      const { todoStore } = await import('/js/todoStore.js');
      const { mountTodoFaceInto } = await import('/js/todoFace.js');
      const seed = (extra = []) => todoStore.__seed({
        tagList: [{ id: 't1', name: 'Work', color: '#4488ff' }],
        itemList: [{ id: 'i1', tagId: 't1', name: 'First' }, ...extra],
      });
      seed();
      const host = document.createElement('div');
      document.body.appendChild(host);
      const face = mountTodoFaceInto(host);
      face.startCreate();
      const row = face.sheetBody.querySelector('.tfl-create-row');
      const input = row && row.querySelector('.tfl-row-title-input');
      input.focus();
      input.value = 'Sheet draft';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.setSelectionRange(1, 4);
      seed([{ id: 'i9', tagId: 't1', name: 'Ninth' }]);
      const after = face.sheetBody.querySelector('.tfl-create-row');
      return {
        inSheet: !!row,
        notInList: face.listInner.querySelectorAll('.tfl-create-row').length === 0,
        sameRow: after === row,
        value: input.value,
        focused: document.activeElement === input,
        selection: [input.selectionStart, input.selectionEnd],
      };
    });
    expect(result).toEqual({ inSheet: true, notInList: true, sameRow: true, value: 'Sheet draft', focused: true, selection: [1, 4] });
  } finally {
    await s.close();
  }
});

test('FRM-SCN-003 desktop scheduled-task draft survives a refresh and is rebuilt for another target', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const tasks = () => ({
      type: 'scheduledTaskList',
      sessionId: SID,
      tasks: [
        { id: 'a', sessionId: SID, kind: 'task', content: 'Task A', detail: 'Detail A', triggerAt: Date.now() + 3600e3, createdAt: 1, enabled: true },
        { id: 'b', sessionId: SID, kind: 'task', content: 'Task B', detail: 'Detail B', triggerAt: Date.now() + 7200e3, createdAt: 2, enabled: true },
      ],
    });
    await s.frame(tasks());
    await page.locator('#reminder-btn').click();
    const body = page.locator('#reminder-panel .reminder-panel-body');
    const input = body.locator('.reminder-inline-input');
    await body.locator('button.reminder-edit[data-id="a"]').click();
    await expect(input).toHaveValue('Task A');
    await input.fill('Task A edited');
    await body.locator('.reminder-inline-desc').fill('Detail A edited');
    await page.evaluate(() => {
      window.__draftNode = document.querySelector('.reminder-inline-create');
      const desc = document.querySelector('.reminder-inline-desc');
      desc.focus();
      desc.setSelectionRange(2, 6);
    });

    // A refresh reuses the open draft: same node, same values, and the focus stays where the user was typing.
    // Two frames first: any deferred focus (requestAnimationFrame) must already have run when focus is read.
    await s.frame(tasks());
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    expect(await page.evaluate(() => {
      const desc = document.querySelector('.reminder-inline-desc');
      return {
        same: document.querySelector('.reminder-inline-create') === window.__draftNode,
        title: document.querySelector('.reminder-inline-input').value,
        detail: desc.value,
        focused: document.activeElement === desc,
        selection: [desc.selectionStart, desc.selectionEnd],
      };
    })).toEqual({ same: true, title: 'Task A edited', detail: 'Detail A edited', focused: true, selection: [2, 6] });

    // Editing another task must show that task, never the previous draft.
    await body.locator('button.reminder-edit[data-id="b"]').click();
    await expect(input).toHaveValue('Task B');
    expect(await page.evaluate(() => document.querySelector('.reminder-inline-create') === window.__draftNode)).toBe(false);
    expect(await page.evaluate(() => document.querySelector('.reminder-inline-create').dataset.target)).toBe('b');

    // A new-task draft is not carried into an edit either.
    await body.locator('[data-role="cancel"]').click();
    await page.locator('#reminder-create-btn').click();
    await input.fill('typed new task');
    expect(await page.evaluate(() => document.querySelector('.reminder-inline-create').dataset.target)).toBe('');
    await body.locator('button.reminder-edit[data-id="a"]').click();
    await expect(input).toHaveValue('Task A');
  } finally {
    await s.close();
  }
});

test('FRM-SCN-009 mobile scheduled drawer keeps its draft across a refresh and rebuilds it for a reopened or different target', async ({ browser }) => {
  const s = await session(browser, { width: 390, height: 844 });
  const { page } = s;
  try {
    // The closing animation window is the interesting one: keep the normal (non-reduced) timing.
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await boot(s);
    const frame = { type: 'scheduledTaskList', sessionId: SID, tasks: [
      { id: 'a', sessionId: SID, kind: 'task', content: 'Task A', detail: 'Detail A', triggerAt: Date.now() + 3600e3, createdAt: 1, enabled: true },
      { id: 'b', sessionId: SID, kind: 'task', content: 'Task B', detail: 'Detail B', triggerAt: Date.now() + 7200e3, createdAt: 2, enabled: true },
    ] };
    await s.frame(frame);
    await page.evaluate(() => document.body.classList.add('mobile-layer'));
    await page.evaluate(() => document.querySelector('#reminder-btn')?.click());
    await page.waitForSelector('#reminder-panel button.reminder-edit[data-id="a"]', { state: 'attached' });

    const result = await page.evaluate(async (tasksFrame) => {
      const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const edit = (id) => document.querySelector(`#reminder-panel button.reminder-edit[data-id="${id}"]`).click();
      const create = () => document.querySelector('.reminder-fab-add').click();
      const cancel = () => document.querySelector('.mf-drawer-cancel-btn').click();
      const form = () => document.querySelector('.mf-drawer-body .reminder-inline-create');
      const title = () => document.querySelector('.mf-drawer-body .reminder-inline-input');
      const type = (value) => { const el = title(); el.focus(); el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })); el.setSelectionRange(2, 6); };
      const out = {};

      // A refresh arriving while a draft is open keeps the same node with its value, focus and caret.
      edit('a'); await wait(150);
      const first = form();
      out.openedWith = title().value;
      type('half-typed garbage');
      window.__wsMock.onmessage({ data: JSON.stringify(tasksFrame) }); await wait(80);
      out.refresh = { sameNode: form() === first, value: title().value, focused: document.activeElement === title(), selection: [title().selectionStart, title().selectionEnd] };

      // Cancel, then reopen the same task inside the closing animation: the discarded draft must not come back.
      cancel(); await wait(120);
      edit('a'); await wait(150);
      out.reopenSameTarget = { value: title().value, sameNode: form() === first };

      // Another target shows its own values.
      cancel(); await wait(120);
      edit('b'); await wait(150);
      out.otherTarget = { value: title().value };

      // A discarded new-task draft does not come back either.
      cancel(); await wait(120);
      create(); await wait(150);
      type('previous draft');
      cancel(); await wait(120);
      create(); await wait(150);
      out.reopenNew = { value: title().value };
      return out;
    }, frame);

    expect(result.openedWith).toBe('Task A');
    expect(result.refresh).toEqual({ sameNode: true, value: 'half-typed garbage', focused: true, selection: [2, 6] });
    expect(result.reopenSameTarget).toEqual({ value: 'Task A', sameNode: false });
    expect(result.otherTarget).toEqual({ value: 'Task B' });
    expect(result.reopenNew).toEqual({ value: '' });
  } finally {
    await s.close();
  }
});

test('FRM-SCN-005 a frozen delegate offers wake only when the authoritative block says it is wakeable', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const html = await page.evaluate(async () => {
      const { bgTaskRowHtml } = await import('/js/main/bgAgents.js');
      const frozen = (wakeable) => ({
        sessionId: 'delegate-frozen01', name: 'frozen delegate', status: 'Frozen', frozen: true, startedAt: Date.now() - 60000,
        freeze: { frozen: true, kind: 'needs-human', needsHuman: true, autoRecoverable: false, wakeable, reasonWire: 'delegate-frozen:tool-failure', since: 1, status: 'Frozen', source: 'test', asOf: 10 },
      });
      return { notWakeable: bgTaskRowHtml('delegate-frozen01', frozen(false)), wakeable: bgTaskRowHtml('delegate-frozen01', frozen(true)) };
    });
    expect(html.notWakeable).toContain('bg-task-frozen');
    expect(html.notWakeable).not.toContain('bg-task-wake');
    expect(html.wakeable).toContain('bg-task-wake');
    expect(html.wakeable).toContain('data-bg-wake="delegate-frozen01"');
  } finally {
    await s.close();
  }
});

test('FRM-SCN-005 a delegate-frozen row shows or hides wake the same way for a live frame and a snapshot block', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const rows = await page.evaluate(async () => {
      const { bgTaskRowHtml } = await import('/js/main/bgAgents.js');
      const sid = 'delegate-frozen02';
      const base = { sessionId: sid, name: 'frozen delegate', status: 'Frozen', frozen: true, startedAt: Date.now() - 60000 };
      // Shape main.js gives a row from a live `delegateFrozen` frame: flat fields, no authoritative block.
      const live = (code) => ({ ...base, frozenReason: code, frozenReasonLabel: '', frozenDetail: '', wakeAddress: 'delegate:' + sid });
      // Shape of a snapshot row: the gateway block for the same reason (FreezeTruth: every delegate-frozen reason is wakeable).
      const block = (code, wakeable) => ({
        ...base,
        freeze: { frozen: true, kind: 'needs-human', needsHuman: true, autoRecoverable: code === 'llm-failure' || code === 'cancelled-by-quota', wakeable, reasonWire: 'delegate-frozen:' + code, since: 1, status: 'Frozen', source: 'test', asOf: 10 },
      });
      const out = {};
      for (const code of ['llm-failure', 'cancelled-by-quota', 'tool-failure', 'engine-fault']) {
        out[code] = {
          live: bgTaskRowHtml(sid, live(code)),
          snapshot: bgTaskRowHtml(sid, block(code, true)),
          refused: bgTaskRowHtml(sid, block(code, false)),
        };
      }
      return out;
    });
    for (const [code, variants] of Object.entries(rows)) {
      for (const source of ['live', 'snapshot']) {
        expect(variants[source], `${code} from a ${source} row`).toContain('data-bg-wake="delegate-frozen02"');
      }
      expect(variants.refused, `${code} refused by the block`).toContain('bg-task-frozen');
      expect(variants.refused, `${code} refused by the block`).not.toContain('bg-task-wake');
    }
  } finally {
    await s.close();
  }
});

test('FRM-SCN-007 the background badge counts active rows while retained rows keep the entry available', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const badge = page.locator('#bgagent-indicator');
    const seed = (rows) => page.evaluate(async ({ sid, rows: seeded }) => {
      const state = (await import('/js/state.js')).default;
      const { updateBgAgentIndicator } = await import('/js/main/bgAgents.js');
      state.sessionBgAgents ||= {};
      state.sessionBgAgents[sid] = Object.fromEntries(seeded.map((row) => [row.sessionId, { startedAt: Date.now() - 9000, ...row }]));
      updateBgAgentIndicator(sid);
    }, { sid: SID, rows });
    const running = { sessionId: 'delegate-run00001', name: 'running child', status: 'Running' };
    const finished = { sessionId: 'delegate-done0001', name: 'finished child', status: 'Done', done: true };

    await seed([running, finished]);
    await expect(badge).not.toHaveClass(/hidden/);
    await expect(badge.locator('.bgagent-count')).toHaveText('1');

    // Only retained history: the entry stays reachable and honestly reports zero active.
    await seed([finished]);
    await expect(badge).not.toHaveClass(/hidden/);
    await expect(badge.locator('.bgagent-count')).toHaveText('0');

    // Nothing at all: the entry is hidden.
    await seed([]);
    await expect(badge).toHaveClass(/hidden/);
  } finally {
    await s.close();
  }
});

async function openBackgroundPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.evaluate(() => document.querySelector('#bgagent-indicator')?.click());
  await page.waitForSelector('#bg-dropdown .bg-section-agents .bg-dropdown-list .bg-task-row', { timeout: 8000 });
}

const AGENTS_LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';
const registryRow = (over) => ({
  executor: 'nebflow', external: false, project: '', archivedAt: null, parentSessionId: '', mergeQueue: null, mergeQueuePos: null, ...over,
});

test('FRM-SCN-007 running and restarting archived children count as active while completed ones stay listed without counting', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const delegate = 'delegate-nebflow-aaaabbbb';
    await page.evaluate(async ({ id }) => {
      const now = Date.now();
      (await import('/js/delegateChainStub.js')).__setFixture({
        rows: [{ taskNo: '60', delegateId: id, title: 'running batch', name: 'batch', status: 'running', mode: 'kernel', createdAt: now - 600000,
          executor: 'nebflow', external: false, project: '', archivedAt: null, parentSessionId: '', mergeQueue: null, mergeQueuePos: null }],
        children: { [id]: [
          { taskId: 'subagent-77778888', parentSessionId: id, agentName: 'running child', description: 'still running in the store', status: 'running', spawnedAt: now - 300000, retryCount: 0, source: 'subagent' },
          { taskId: 'subagent-77779999', parentSessionId: id, agentName: 'restarting child', description: 'restarting in the store', status: 'restarting', spawnedAt: now - 240000, retryCount: 1, source: 'subagent' },
          { taskId: 'subagent-77770000', parentSessionId: id, agentName: 'done child', description: 'finished in the store', status: 'completed', spawnedAt: now - 900000, completedAt: now - 600000, retryCount: 0, source: 'subagent' },
        ] },
      });
    }, { id: delegate });
    // The delegate has a live row; its children exist only in the durable store (for example right after a reload).
    await s.frame({ type: 'agentStart', sessionId: SID, rootSessionId: SID, agentId: delegate, nodeSessionId: delegate, name: 'delegate head', taskDescription: 'live head', status: 'Processing' });
    await openBackgroundPanel(page);

    // The list keeps every row: the head and three archived children.
    await expect(page.locator(`${AGENTS_LIST} .bg-task-row`)).toHaveCount(4);
    await expect(page.locator(AGENTS_LIST)).toHaveAttribute('data-bg-row-total', '4');
    // The badge counts what the list shows as in progress: the head, the running child and the restarting child.
    await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('3');
  } finally {
    await s.close();
  }
});

test('FRM-SCN-007 a running flow step counts as active while a pending step stays listed without counting', async ({ browser }) => {
  const s = await session(browser);
  const { page } = s;
  try {
    await boot(s);
    const delegate = 'delegate-nebflow-w2';
    await page.evaluate(async ({ id }) => {
      (await import('/js/delegateChainStub.js')).__setFixture({
        rows: [{ taskNo: '72', delegateId: id, title: 'flow batch', status: 'running', orchestrationMode: 'agentflow', mode: 'dispatcher', createdAt: Date.now() - 30000,
          executor: 'nebflow', external: false, project: 'Nebflow', archivedAt: null, mergeQueue: null, mergeQueuePos: null }],
        chains: [{ taskId: '72', delegateId: id, nodes: [
          { id, kind: 'delegate', label: '#72', description: '', status: 'running', deps: [], executor: '' },
          { id: 's1', kind: 'flow-step', label: 'step one', description: '', status: 'running', deps: [], executor: '' },
          { id: 's2', kind: 'flow-step', label: 'step two', description: '', status: 'pending', deps: ['s1'], executor: '' },
        ], worktree: null, verdict: null, mergeQueue: [], frames: [] }],
      });
    }, { id: delegate });
    await s.frame({ type: 'agentStart', sessionId: SID, rootSessionId: SID, agentId: delegate, nodeSessionId: delegate, name: 'Planner', taskDescription: 'flow batch', status: 'Processing' });
    await openBackgroundPanel(page);

    // The list keeps the head and both steps.
    await expect(page.locator(`${AGENTS_LIST} .bg-task-row`)).toHaveCount(3);
    // The badge counts the head and the running step; the pending step is listed but not in progress.
    await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('2');
  } finally {
    await s.close();
  }
});

test('FRM-SCN-011 mobile background conversation keeps the status capsule, one details control and one stop control', async ({ browser }) => {
  const sid = 'delegate-forms-fixture';
  const detail = {
    record: { id: sid, executor: 'fixture-executor', parentSessionId: 'fixture-root', createdAt: 1793940000000 },
    live: { status: 'running' }, verdict: null, task: null, childrenCount: 0,
  };
  const s = await session(browser, { width: 390, height: 844, apiStubs: { '/api/delegates': { body: detail } } });
  const { page } = s;
  try {
    await boot(s, { sessionId: 'fixture-root', config: BOOT.config });
    await page.evaluate(async (id) => {
      const popup = await import('/js/bgAgentPopup.js');
      popup.openStepPopup(id, 'Fixture agent', 'Fixture task detail');
    }, sid);
    await expect(page.locator('.bgagent-status-pill')).toBeVisible();
    await expect(page.locator('.bgagent-more')).toBeVisible();
    // One way out only (the back control): the duplicate close X is gone, and the stop control is a single floating button.
    expect(await page.locator('#bgagent-close').count()).toBe(0);
    expect(await page.locator('.bgagent-stop-fab').count()).toBe(1);

    await page.locator('.bgagent-more').click();
    const sheet = page.locator('.bgagent-more-sheet');
    await expect(sheet).toBeVisible();
    await expect.poll(async () => (await sheet.locator('.bgagent-meta-row').allTextContents()).length).toBeGreaterThanOrEqual(2);
    const rows = await sheet.locator('.bgagent-meta-row').allTextContents();
    expect(rows.some((row) => row.includes('fixture-executor'))).toBe(true);
    // While the sheet is open its scrim covers the details control, so a tap on that spot closes the sheet through the scrim.
    const box = await page.locator('.bgagent-more').boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await expect(sheet).toBeHidden();
  } finally {
    await s.close();
  }
});
