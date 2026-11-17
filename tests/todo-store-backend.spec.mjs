// todo-store-backend.spec.mjs — 待办体系统一批（2026-11-04）前端**数据面换血**验收：
// todoStore.js 由「纯内存 mock 仓」换成「后端真源适配器」（渲染层零改动）后的
// 帧级契约：
//   ① 首次挂载惰性拉真源：发 `listScheduledTasks {scope:'all'}` 且渲染面取到回执数据
//      （行顺序 = 后端 todoSortKey 复算：priority 降序 → due 升序 → createdAt；
//        chips 行序**不参与**排序 —— 反转 chips 后行序不变）；
//   ② completeItem ⇒ `completeTodo {id, sessionId}`（跨会话行带**行自身** sessionId），
//      乐观退场 + 回执收敛 + 取消完成回退（`uncompleteTodo`）；
//   ③ updateItem(priority / title) ⇒ `updateScheduledTask` 字段级 patch；
//      removeItem ⇒ `deleteScheduledTask`；
//   ④ 服务端广播/清单回执 ⇒ 可见面收敛（无需用户操作）；
//   ⑤ `__seed` 测试缝仍可用（既有 shots/几何 spec 的数据注入腿不破）；
//   ⑥ 服务端显式拒绝 ⇒ 回滚 + console.error（不静默吞）；notes/due 明确不落库。
//
// 数据面 = **有状态的 WS 夹具后端**（routeWebSocket 拦截，零后端、零 sbt、零 6138）：
//   夹具真的按 WsTasksWorkspaceHandlers 的口径处理写帧并回帧（scheduledTaskCreated /
//   Updated / Completed / Deleted），因此断言的是「帧 + 回执收敛」的完整闭环，
//   不是单向发包。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/todo-store-backend.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'todo-store-root';
const OTHER_SID = 'todo-store-other';
const T0 = Date.now();
const H = 3600_000;

test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });

/** 收件箱夹具（后端 listTaskJson 形状 + 本批新增三键）。
 *  · t1/t4 同为 priority 3（t4 triggerAt 更早 ⇒ 后端序把 t4 排前，证明排序按
 *    due 而不按 chips 行序）；t1 属「工作」，t4 属「生活」；
 *  · t2 属**其它会话**（跨会话行级寻址面）且已过期为逾期计数来源；
 *  · t3 无 project（未分组）；
 *  · m1 = ask 镜像行（name=ask-…）⇒ 适配器必须把它挡在人待办收件箱面之外。 */
function inboxFixture() {
  return [
    {
      id: 't4', content: '待办四：设计稿第二批意见', triggerAt: T0 + 1 * H, createdAt: T0 - 8 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: SID, project: '生活',
      priority: 3, completed: false, completedAt: null,
    },
    {
      id: 't1', content: '待办一：修登录态过期提示', triggerAt: T0 + 2 * H, createdAt: T0 - 10 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: SID, project: '工作',
      priority: 3, completed: false, completedAt: null,
    },
    {
      id: 't2', content: '待办二：缴房租', triggerAt: T0 - 3 * H, createdAt: T0 - 20 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: OTHER_SID, project: '生活',
      priority: 2, completed: false, completedAt: null,
    },
    {
      id: 't3', content: '待办三：读设计中的设计', triggerAt: T0 + 30 * H, createdAt: T0 - 5 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: null, missCount: 0, sessionId: SID, project: null,
      priority: 0, completed: false, completedAt: null,
    },
    {
      id: 'm1', content: '[待答] 选哪个方案？ — worker-a', triggerAt: T0 + 24 * H, createdAt: T0 - 2 * H,
      triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
      kind: 'todo', name: 'ask-asknb-aaa111', missCount: 0, sessionId: SID, project: null,
      priority: 0, completed: false, completedAt: null,
    },
  ];
}

/** 服务端排序权威复算（ScheduledTask.todoSortKey：已完成殿后 → priority 降序 →
 *  triggerAt 升序 → createdAt 升序）。 */
function backendSort(rows) {
  return [...rows].sort((a, b) => {
    const ac = a.completed ? 1 : 0;
    const bc = b.completed ? 1 : 0;
    if (ac !== bc) return ac - bc;
    if (a.priority !== b.priority) return b.priority - a.priority;
    if (a.triggerAt !== b.triggerAt) return a.triggerAt - b.triggerAt;
    return a.createdAt - b.createdAt;
  });
}

const rowJson = (r) => ({
  id: r.id, content: r.content, triggerAt: r.triggerAt, createdAt: r.createdAt,
  triggered: r.triggered, triggeredAt: r.triggeredAt, referencePath: r.referencePath,
  repeat: r.repeat, enabled: r.enabled, kind: r.kind, name: r.name, missCount: r.missCount,
  sessionId: r.sessionId, project: r.project,
  priority: r.priority, completed: !!r.completed, completedAt: r.completedAt ?? null,
});

/** 有状态 WS 夹具后端：按 WsTasksWorkspaceHandlers 口径处理写帧并回帧。 */
async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  const captured = [];
  const errors = [];
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const text = m.text();
    if (/Failed to load resource|fetching the script/i.test(text)) return;
    errors.push(text);
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  // ── 夹具后端状态 ──────────────────────────────────────────────────────
  const backend = { rows: inboxFixture(), createSeq: 0 };
  let live = null;
  const send = (obj) => live && live.send(JSON.stringify(obj));
  const findRow = (id) => backend.rows.find((r) => r.id === id);

  const sendList = (sessionId) => {
    // scope:'all' 默认 includeCompleted:false ⇒ 已完成行不下发（收件箱语义）。
    const rows = backendSort(backend.rows.filter((r) => !r.completed)).map(rowJson);
    send({ type: 'scheduledTaskList', tasks: rows, sessionId, scope: 'all', includeCompleted: false });
  };

  await page.routeWebSocket(/\/ws/, (ws) => {
    live = ws;
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      captured.push(m);
      switch (m.type) {
        case 'getConfig': sendConfig(); break;
        case 'getSessions': case 'listSessions': sendSessions(); break;
        case 'getHistory':
          ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
          break;
        case 'listScheduledTasks':
          if (m.scope === 'all') {
            if (m.includeCompleted === true) send({ type: 'scheduledTaskList', tasks: backendSort(backend.rows).map(rowJson), sessionId: m.sessionId, scope: 'all', includeCompleted: true });
            else sendList(m.sessionId);
          }
          break;
        case 'createScheduledTask': {
          // 拒绝腿：夹具后端按后端口径回 error 帧（msgType 指回创建帧），
          // 不落库（WsTasksWorkspaceHandlers 「Rejections must be loud」同形）。
          if (backend.rejectCreates) {
            send({ type: 'error', msgType: 'createScheduledTask', message: 'missing sessionId' });
            break;
          }
          const row = {
            id: `srv-${++backend.createSeq}`, content: m.content, triggerAt: T0 + 24 * H, createdAt: Date.now(),
            triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
            kind: 'todo', name: null, missCount: 0, sessionId: m.sessionId,
            project: m.project ?? null, priority: m.priority ?? 0, completed: false, completedAt: null,
          };
          backend.rows.push(row);
          send({ type: 'scheduledTaskCreated', sessionId: m.sessionId, task: rowJson(row) });
          break;
        }
        case 'updateScheduledTask': {
          const row = findRow(m.id);
          if (!row) { send({ type: 'scheduledTaskDeleted', id: m.id, sessionId: m.sessionId }); break; }
          if (typeof m.content === 'string' && m.content) row.content = m.content;
          if (typeof m.priority === 'number') row.priority = Math.min(3, Math.max(0, m.priority));
          if (typeof m.project === 'string') row.project = m.project || null;
          send({ type: 'scheduledTaskUpdated', sessionId: row.sessionId, task: rowJson(row) });
          break;
        }
        case 'completeTodo': {
          const row = findRow(m.id);
          if (!row) { send({ type: 'scheduledTaskDeleted', id: m.id, sessionId: m.sessionId }); break; }
          row.completed = true;
          row.completedAt = row.completedAt ?? Date.now();
          // 后端兼容策略：新帧 + 老帧同发（CONTRACT.md §3）。
          send({ type: 'scheduledTaskCompleted', id: row.id, sessionId: row.sessionId, completedAt: row.completedAt, task: rowJson(row) });
          send({ type: 'scheduledTaskDeleted', id: row.id, sessionId: row.sessionId });
          break;
        }
        case 'uncompleteTodo': {
          const row = findRow(m.id);
          if (!row) { send({ type: 'scheduledTaskDeleted', id: m.id, sessionId: m.sessionId }); break; }
          row.completed = false;
          row.completedAt = null;
          send({ type: 'scheduledTaskUpdated', sessionId: row.sessionId, task: rowJson(row) });
          break;
        }
        case 'deleteScheduledTask': {
          backend.rows = backend.rows.filter((r) => r.id !== m.id);
          send({ type: 'scheduledTaskDeleted', id: m.id, sessionId: m.sessionId });
          break;
        }
        default: break;
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });

  const store = () => page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    return {
      dataSource: todoStore.dataSource,
      openCount: todoStore.openCount(),
      overdueCount: todoStore.overdueCount(),
      tags: todoStore.tags.map((t) => t.name),
      rows: todoStore.visibleItems().map((x) => ({
        id: x.id, title: x.title, priority: x.priority, due: x.due,
        tag: (todoStore.tagById(x.tagId) || {}).name || null,
      })),
      persistence: todoStore.persistence,
    };
  });

  return {
    captured,
    errors,
    store,
    backend,
    sendFrame: (obj) => send(obj),
    /** 等真源回执落到内存（不禁用 sleep：Playwright 的轮询断言）。 */
    waitRows: (n) => page.waitForFunction(async (want) => {
      const { todoStore } = await import('/js/todoStore.js');
      return todoStore.visibleItems().length === want;
    }, n, { timeout: 8000 }),
    page,
  };
}

const frames = (captured, type) => captured.filter((f) => f.type === type);

test('null 来源不回填当前会话且不可错误寻址', async ({ page }) => {
  const h = await bootApp(page);
  await h.waitRows(4);
  h.sendFrame({ type: 'scheduledTaskList', sessionId: SID, scope: 'all', tasks: [{ id: 'unknown', kind: 'todo', content: '来源未知', sessionId: null, project: null }] });
  await h.waitRows(1);
  const row = await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.allItems()[0]);
  expect(row.sessionId).toBeNull();
  const count = frames(h.captured, 'completeTodo').length;
  await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.completeItem('unknown'));
  expect(frames(h.captured, 'completeTodo')).toHaveLength(count);
  expect((await page.evaluate(async () => (await import('/js/todoStore.js')).todoStore.allItems()[0])).completed).toBe(false);
});

test('① 首次挂载惰性拉真源：listScheduledTasks{scope:"all"} 一发 + 渲染面取到回执数据（后端排序权威，chips 行序不参与）', async ({ page }) => {
  const h = await bootApp(page);
  // 零用户操作：等待回执落库（惰性首拉发生在渲染层挂载 subscribe 时）。
  await h.waitRows(4);

  const pulls = frames(h.captured, 'listScheduledTasks').filter((f) => f.scope === 'all');
  expect(pulls.length).toBeGreaterThanOrEqual(1);
  expect(pulls[0]).toEqual({ type: 'listScheduledTasks', sessionId: SID, scope: 'all' });

  const s = await h.store();
  expect(s.dataSource).toBe('backend');
  // ask 镜像行 m1 被挡在人待办收件箱面外；4 条人建待办按后端字段序。
  expect(s.rows.map((r) => r.id)).toEqual(['t4', 't1', 't2', 't3']);
  expect(s.rows.map((r) => r.title)).toEqual(['待办四：设计稿第二批意见', '待办一：修登录态过期提示', '待办二：缴房租', '待办三：读设计中的设计']);
  // tagId = 后端 project 的本地投影（后端无 tag 实体，见交付报告「标签面决定」）。
  expect(s.rows.map((r) => r.tag)).toEqual(['生活', '工作', '生活', null]);
  expect(s.tags.sort()).toEqual(['工作', '生活']);
  expect(s.openCount).toBe(4);
  expect(s.overdueCount).toBe(1);       // t2 triggerAt 已过
  expect(s.persistence.notes).toBe('local-only');

  // 反转 chips 行序后行序**不变** ⇒ 真源面没有任何"只有前端知道"的顺序。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const ids = todoStore.tags.map((t) => t.id);
    if (ids.length >= 2) todoStore.reorderTag(ids[0], ids[1]);
  });
  const after = await h.store();
  expect(after.rows.map((r) => r.id)).toEqual(['t4', 't1', 't2', 't3']);

  // 归档面 opt-in（panel 节点的「已完成/撤销」视图）：帧显式带 includeCompleted:true。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.refresh({ includeCompleted: true });
  });
  await expect.poll(() => frames(h.captured, 'listScheduledTasks').filter((f) => f.includeCompleted === true).length).toBe(1);
  expect(h.errors).toEqual([]);
});

test('② completeItem ⇒ completeTodo 帧（跨会话行带行自身 sessionId）+ 乐观退场 + 回执收敛 + uncomplete 回退', async ({ page }) => {
  const h = await bootApp(page);
  await h.waitRows(4);
  const before = frames(h.captured, 'completeTodo').length;

  // t2 属其它会话（OTHER_SID）：帧必须带**行自身** sessionId（按 id 全库寻址）。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.completeItem('t2');
  });
  // 乐观更新：行立刻从可见面退场（不等回执）。
  let s = await h.store();
  expect(s.rows.some((r) => r.id === 't2')).toBe(false);
  expect(s.openCount).toBe(3);

  await expect.poll(() => frames(h.captured, 'completeTodo').length).toBe(before + 1);
  expect(frames(h.captured, 'completeTodo').at(-1)).toEqual({ type: 'completeTodo', sessionId: OTHER_SID, id: 't2' });

  // 回执收敛（scheduledTaskCompleted + 老帧 scheduledTaskDeleted 双发 + 去抖刷新）后仍是 3 条。
  await page.waitForTimeout(600);
  s = await h.store();
  expect(s.rows.map((r) => r.id)).toEqual(['t4', 't1', 't3']);
  expect(s.overdueCount).toBe(0);

  // 取消完成（本批新增帧）：行回到可见面。
  const beforeUndo = frames(h.captured, 'uncompleteTodo').length;
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.uncompleteItem('t2');
  });
  await expect.poll(() => frames(h.captured, 'uncompleteTodo').length).toBe(beforeUndo + 1);
  expect(frames(h.captured, 'uncompleteTodo').at(-1)).toEqual({ type: 'uncompleteTodo', sessionId: OTHER_SID, id: 't2' });
  await h.waitRows(4);
  s = await h.store();
  expect(s.rows.some((r) => r.id === 't2')).toBe(true);
  expect(s.openCount).toBe(4);
  expect(h.errors).toEqual([]);
});

test('③ updateItem(priority/title) ⇒ updateScheduledTask 字段级 patch；removeItem ⇒ deleteScheduledTask；新建 ⇒ createScheduledTask{kind:"todo"}', async ({ page }) => {
  const h = await bootApp(page);
  await h.waitRows(4);

  // 重要度 patch（只带变化字段，不夹带 content/project）。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.updateItem('t3', { priority: 3 });
  });
  await expect.poll(() => frames(h.captured, 'updateScheduledTask').length).toBeGreaterThanOrEqual(1);
  const prioFrame = frames(h.captured, 'updateScheduledTask').at(-1);
  expect(prioFrame).toEqual({ type: 'updateScheduledTask', id: 't3', sessionId: SID, priority: 3 });
  await h.waitRows(4);
  let s = await h.store();
  expect(s.rows.find((r) => r.id === 't3').priority).toBe(3);
  // 升到最高重要度后按后端字段序落在 t4(+1h) / t1(+2h) 之后、t2(中) 之前。
  expect(s.rows.map((r) => r.id)).toEqual(['t4', 't1', 't3', 't2']);

  // 标题 patch。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.updateItem('t1', { title: '待办一（改名）' });
  });
  await expect.poll(() => frames(h.captured, 'updateScheduledTask').length).toBeGreaterThanOrEqual(2);
  expect(frames(h.captured, 'updateScheduledTask').at(-1)).toEqual({ type: 'updateScheduledTask', id: 't1', sessionId: SID, content: '待办一（改名）' });

  // notes / due 不落库：本地生效、**零帧**（诚实申报，不假装落库）。
  const beforeNotes = frames(h.captured, 'updateScheduledTask').length;
  s = await h.store();
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.updateItem('t1', { notes: '本地备注' });
  });
  await page.waitForTimeout(200);
  expect(frames(h.captured, 'updateScheduledTask').length).toBe(beforeNotes);

  // 新建：kind=todo + content + priority + project（tagId → 后端 project 落库）。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    const tagId = todoStore.tags[0] ? todoStore.tags[0].id : null;
    todoStore.createItem({ tagId, title: '新待办：从适配器创建', priority: 2 });
  });
  await expect.poll(() => frames(h.captured, 'createScheduledTask').length).toBe(1);
  const createFrame = frames(h.captured, 'createScheduledTask')[0];
  expect(createFrame.type).toBe('createScheduledTask');
  expect(createFrame.kind).toBe('todo');
  expect(createFrame.content).toBe('新待办：从适配器创建');
  expect(createFrame.priority).toBe(2);
  expect(createFrame.sessionId).toBe(SID);
  expect(typeof createFrame.project).toBe('string');   // tagId → project
  expect('triggerAt' in createFrame).toBe(false);      // 浮出时刻由引擎算，客户端不掺和

  // 回执收敛：乐观 tmp 行被服务端行替换（不重不漏）。
  await expect.poll(async () => (await h.store()).rows.filter((r) => r.title === '新待办：从适配器创建').length).toBe(1);
  await expect.poll(async () => (await h.store()).rows.some((r) => r.id.startsWith('srv-'))).toBe(true);

  // 物理删除。
  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.removeItem('t3');
  });
  await expect.poll(() => frames(h.captured, 'deleteScheduledTask').length).toBe(1);
  expect(frames(h.captured, 'deleteScheduledTask')[0]).toEqual({ type: 'deleteScheduledTask', sessionId: SID, id: 't3' });
  await page.waitForTimeout(600);
  s = await h.store();
  expect(s.rows.some((r) => r.id === 't3')).toBe(false);
  expect(h.backend.rows.some((r) => r.id === 't3')).toBe(false);
  expect(h.errors).toEqual([]);
});

test('④ 服务端广播/清单回执 ⇒ 可见面收敛（零用户操作）', async ({ page }) => {
  const h = await bootApp(page);
  await h.waitRows(4);

  // (a) scheduledTaskUpdated：把 t3 提到最高重要度 ⇒ 排序面重排。
  //     夹具后端的状态先跟着变（真后端广播即状态，否则后续清单回执会把行还原，
  //     测出来的就不是收敛而是夹具自相矛盾）。
  const t3 = h.backend.rows.find((r) => r.id === 't3');
  t3.priority = 3;
  h.sendFrame({ type: 'scheduledTaskUpdated', sessionId: SID, task: { ...t3 } });
  await expect.poll(async () => {
    const s = await h.store();
    return s.rows.find((r) => r.id === 't3').priority;
  }).toBe(3);

  // (b) scheduledTaskCompleted：行从可见面退场（真源广播驱动，不靠本地写口）。
  const t1 = h.backend.rows.find((r) => r.id === 't1');
  t1.completed = true;
  t1.completedAt = Date.now();
  h.sendFrame({ type: 'scheduledTaskCompleted', id: 't1', sessionId: SID, completedAt: t1.completedAt, task: { ...t1 } });
  await expect.poll(async () => (await h.store()).rows.some((r) => r.id === 't1')).toBe(false);
  await page.waitForTimeout(600);   // 越过写后去抖刷新，确认收敛不是瞬态
  expect((await h.store()).rows.some((r) => r.id === 't1')).toBe(false);

  // (c) 清单回执整表替换（他端改动/浮出后的一次 refresh）。
  h.sendFrame({ type: 'scheduledTaskList', sessionId: SID, scope: 'all', includeCompleted: false, tasks: [
    { ...t1, completed: false, completedAt: null, content: '回执替换后的唯一行', priority: 1, triggerAt: T0 + 5 * H },
  ] });
  await expect.poll(async () => (await h.store()).rows.length).toBe(1);
  const s = await h.store();
  expect(s.rows[0].title).toBe('回执替换后的唯一行');
  expect(h.errors).toEqual([]);
});

test('⑤ __seed 测试缝不破：夹具数据注入仍可用（旧排序语义 + dataSource=fixture + 零写帧），__live 回真源', async ({ page }) => {
  const h = await bootApp(page);
  await h.waitRows(4);
  const writesBefore = h.captured.filter((f) => ['createScheduledTask', 'updateScheduledTask', 'completeTodo', 'uncompleteTodo', 'deleteScheduledTask'].includes(f.type)).length;

  const seeded = await page.evaluate(async (now) => {
    const m = await import('/js/todoStore.js');
    const H = 3600_000;
    m.todoStore.__seed({
      tagList: [
        { id: 'tag-work', name: '工作', color: m.TAG_COLOR_PRESETS[5] },
        { id: 'tag-life', name: '生活', color: m.TAG_COLOR_PRESETS[3] },
      ],
      itemList: [
        { id: 'i-1', tagId: 'tag-work', title: '夹具甲', notes: '', due: now + 2 * H, priority: 3, createdAt: now - 26 * H },
        { id: 'i-2', tagId: 'tag-life', title: '夹具乙', notes: '', due: now - 3 * H, priority: 3, createdAt: now - 50 * H },
        { id: 'i-3', tagId: 'tag-work', title: '夹具丙', notes: '', due: now + 26 * H, priority: 2, createdAt: now - 20 * H },
      ],
    });
    return {
      dataSource: m.todoStore.dataSource,
      rows: m.todoStore.visibleItems().map((x) => x.id),
      open: m.todoStore.openCount(),
      overdue: m.todoStore.overdueCount(),
    };
  }, T0);

  expect(seeded.dataSource).toBe('fixture');
  expect(seeded.open).toBe(3);
  expect(seeded.overdue).toBe(1);
  // 夹具面保留旧排序语义（All 视图 = 重要度降序 → chips 行序 → 截止）：
  // i-1（工作，chips 首位）在 i-2（生活）之前。
  expect(seeded.rows).toEqual(['i-1', 'i-2', 'i-3']);

  // 夹具模式下写口零帧（既有 shots 数据注入腿不受真源写帧污染）。
  await page.evaluate(async () => {
    const m = await import('/js/todoStore.js');
    m.todoStore.updateItem('i-3', { priority: 1 });
    m.todoStore.completeItem('i-2');
  });
  await page.waitForTimeout(300);
  const writesAfter = h.captured.filter((f) => ['createScheduledTask', 'updateScheduledTask', 'completeTodo', 'uncompleteTodo', 'deleteScheduledTask'].includes(f.type)).length;
  expect(writesAfter).toBe(writesBefore);

  // 显式回真源：重新拉回后端清单，夹具被真源覆盖。
  await page.evaluate(async () => {
    const m = await import('/js/todoStore.js');
    m.todoStore.__live();
  });
  await h.waitRows(4);
  const s = await h.store();
  expect(s.dataSource).toBe('backend');
  expect(s.rows.map((r) => r.id)).toEqual(['t4', 't1', 't2', 't3']);
  expect(h.errors).toEqual([]);
});

test('⑥ 服务端显式拒绝 ⇒ 回滚 + console.error（不静默吞）', async ({ page }) => {
  const h = await bootApp(page);
  await h.waitRows(4);
  h.backend.rejectCreates = true;

  await page.evaluate(async () => {
    const { todoStore } = await import('/js/todoStore.js');
    todoStore.createItem({ tagId: null, title: '会被服务端拒的待办', priority: 1 });
  });
  await expect.poll(() => frames(h.captured, 'createScheduledTask').length).toBe(1);
  // 服务端回 error{msgType:'createScheduledTask'}：乐观行必须撤回，错误必须出声。
  await expect.poll(async () => (await h.store()).rows.some((r) => r.title === '会被服务端拒的待办')).toBe(false);
  await expect.poll(() => h.errors.length).toBeGreaterThanOrEqual(1);
  expect(h.errors.join('\n')).toContain('server rejected createScheduledTask');
  expect(h.backend.rows.some((r) => r.content === '会被服务端拒的待办')).toBe(false);
});
