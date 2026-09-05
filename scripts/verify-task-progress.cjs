// verify-task-progress.cjs — 第七件 (2026-08-30) 任务工具重做 → 进展展示,
// frontend phase 2 (Backend feat/task-redesign 契约对接).
//
//   G1  four-state visibility: ONLY pending + in_progress render — failed /
//       completed / needs_confirmation / cancelled never reach the DOM
//   G2  grouping: teamId -> member -> tasks hierarchy, first-appearance
//       order after updatedAt-desc sort, two members in one team, two teams
//   G3  mixed: ungrouped flat first, grouped teams after
//   G4  animation ruling: in_progress = standalone spinner glyph (ring IS the
//       icon, no box; computed animation running); pending = static hollow
//       square (no animation); no inner .task-check-spinner element
//   G5  reduced-motion: spinner animation disabled
//   G6  todo zone: human pending renders with clickable circle; click sends
//       completeTask {sessionId, taskId} directly (no intermediate state);
//       agent rows have no circle
//   G7  裁定② merge: teamTaskListUpdate data (state.teamTasks) renders into
//       the Nebula session panel grouped under its team header, with
//       namespaced row ids; a NON-Nebula session shows zero team tasks
//   G8  retired paths: no cancel / return / archive controls anywhere
//   T1  Team tab: flowCardHtml output contains zero task section markup
//   T2  zero page errors

const { chromium } = require('playwright');
const PORT = process.argv[2] || '8383';
const BASE = `http://127.0.0.1:${PORT}`;

const results = [];
const ok = (name, cond, extra) => { results.push({ name, pass: !!cond, extra }); console.log((cond ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' — ' + JSON.stringify(extra) : '')); };

const HTML = `<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/taskList.css">
<link rel="stylesheet" href="/css/sapphire.css">
</head><body style="display:block !important">
<div id="task-list"></div>
</body></html>`;

const now = Date.now();
// Recent timestamps only (minutes-back offsets): any "today"-scoped logic
// breaks when an hour-plus offset crosses midnight in the fixture.
const tk = (id, status, extra) => Object.assign({
  id: String(id), subject: 'task-' + id, status, taskKind: 'agent',
  createdAt: new Date(now - 120000 - id * 1000).toISOString(),
  updatedAt: new Date(now - id * 1000).toISOString(),
}, extra || {});

async function renderIn(page, tasks, sessionId) {
  return page.evaluate(async ({ list, sid }) => {
    const m = await import('/js/taskList.js');
    const container = document.getElementById('task-list');
    m.renderTaskList(list, container, sid || 'sess1');
    await new Promise(r => setTimeout(r, 50));
    const ps = container.querySelector('.task-section-progress');
    const directRows = ps ? [...ps.children].filter(el => el.classList.contains('task-item')).length : -1;
    const subHeaders = [...container.querySelectorAll('.task-subgroup-header')].map(h => ({ text: h.textContent, level: h.classList.contains('task-subgroup-team') ? 'team' : 'member' }));
    const groups = [...container.querySelectorAll('.task-subgroup')].map(g => ({
      team: g.querySelector('.task-subgroup-team') ? g.querySelector('.task-subgroup-team').textContent : null,
      members: [...g.querySelectorAll('.task-member-group')].map(mg => ({
        member: mg.querySelector('.task-subgroup-member') ? mg.querySelector('.task-subgroup-member').textContent : null,
        rows: mg.querySelectorAll('.task-item').length,
        rowIds: [...mg.querySelectorAll('.task-item')].map(r => r.dataset.taskId),
      })),
    }));
    const allRows = [...container.querySelectorAll('.task-item')].map(r => r.dataset.taskId);
    return { directRows, subHeaders, groups, allRows, html: ps ? ps.innerHTML.length : 0 };
  }, { list: tasks, sid: sessionId });
}

(async () => {
  const browser = await chromium.launch();

  // ── DOM-level groups (normal motion) ──
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: HTML }));
    await page.goto(`${BASE}/h.html`);

    // G1 four-state visibility: only pending + in_progress render
    let r = await renderIn(page, [
      tk(1, 'pending'), tk(2, 'in_progress'), tk(3, 'failed'),
      tk(4, 'completed'), tk(5, 'needs_confirmation'), tk(6, 'cancelled'),
    ]);
    ok('G1 only pending+in_progress render', r.allRows.length === 2 && r.allRows.includes('1') && r.allRows.includes('2'), r.allRows);
    ok('G1 flat zero-regression (no subgroup headers)', r.subHeaders.length === 0 && r.groups.length === 0, r.subHeaders);
    ok('G1 rows direct children of progress section', r.directRows === 2, r.directRows);

    // G2 grouping: teamA(two members) + teamB(one member) — team attribution
    // rides the delivered contract field `teamId`; member accessor dormant
    // (reads `member` until Backend ships the field). The progress zone sorts
    // updatedAt DESC before grouping, groups in sorted first-appearance order.
    r = await renderIn(page, [
      tk(1, 'in_progress', { teamId: 'teamA', member: 'backend', updatedAt: new Date(now - 60000).toISOString() }),
      tk(2, 'pending', { teamId: 'teamA', member: 'frontend', updatedAt: new Date(now - 30000).toISOString() }),
      tk(3, 'in_progress', { teamId: 'teamA', member: 'backend', updatedAt: new Date(now - 50000).toISOString() }),
      tk(4, 'pending', { teamId: 'teamB', member: 'qa', updatedAt: new Date(now - 120000).toISOString() }),
    ]);
    ok('G2 two team groups in first-appearance order', r.groups.length === 2 && r.groups[0].team === 'teamA' && r.groups[1].team === 'teamB', r.groups.map(g => g.team));
    const gA = r.groups[0] || { members: [] };
    ok('G2 teamA has two member subgroups', gA.members.length === 2, gA.members.map(m => m.member));
    const mBackend = gA.members.find(m => m.member === 'backend');
    ok('G2 backend member holds its two tasks', !!mBackend && mBackend.rows === 2, mBackend && mBackend.rowIds);
    const gB = r.groups[1] || { members: [] };
    ok('G2 teamB one member one task', gB.members.length === 1 && gB.members[0].member === 'qa' && gB.members[0].rows === 1, gB.members);
    ok('G2 no ungrouped rows when all attributed', r.directRows === 0, r.directRows);

    // G3 mixed: ungrouped flat first
    r = await renderIn(page, [tk(1, 'in_progress'), tk(2, 'pending', { teamId: 'teamA', member: 'backend' })]);
    ok('G3 ungrouped renders flat before groups', r.directRows === 1 && r.groups.length === 1, { direct: r.directRows, groups: r.groups.length });

    // G4 animation ruling
    const anim = await page.evaluate(async () => {
      const m = await import('/js/taskList.js');
      const container = document.getElementById('task-list');
      m.renderTaskList([
        { id: 'a', subject: 'running one', status: 'in_progress', taskKind: 'agent', updatedAt: new Date().toISOString() },
        { id: 'b', subject: 'queued one', status: 'pending', taskKind: 'agent', updatedAt: new Date().toISOString() },
      ], container, 'sess1');
      await new Promise(r => setTimeout(r, 50));
      const spinRow = container.querySelector('.task-item[data-task-id="a"]');
      const pendRow = container.querySelector('.task-item[data-task-id="b"]');
      const spinCheck = spinRow.querySelector('.task-check');
      const pendCheck = pendRow.querySelector('.task-check');
      const csSpin = getComputedStyle(spinCheck);
      const csPend = getComputedStyle(pendCheck);
      return {
        spinClass: spinCheck.className,
        spinAnim: csSpin.animationName,
        spinRadius: csSpin.borderRadius,
        spinInner: spinCheck.querySelectorAll('.task-check-spinner').length,
        spinDuration: csSpin.animationDuration,
        pendClass: pendCheck.className,
        pendAnim: csPend.animationName,
        pendRadius: csPend.borderRadius,
      };
    });
    ok('G4 in_progress: standalone spinner class on the glyph itself', /task-check-spin/.test(anim.spinClass), anim.spinClass);
    ok('G4 in_progress: ring spins (animation running)', anim.spinAnim === 'spin' && anim.spinDuration === '0.9s', { a: anim.spinAnim, d: anim.spinDuration });
    ok('G4 in_progress: circular ring, no box', anim.spinRadius === '50%', anim.spinRadius);
    ok('G4 in_progress: no inner spinner-in-box element', anim.spinInner === 0, anim.spinInner);
    ok('G4 pending: static hollow square, zero animation', /task-check-box/.test(anim.pendClass) && (anim.pendAnim === 'none' || anim.pendAnim === '') && anim.pendRadius === '4px', anim);

    // G6 todo zone: human pending circle + click sends completeTask directly
    const todo = await page.evaluate(async () => {
      const state = (await import('/js/state.js')).default;
      state.connected = true;
      const sent = [];
      state.ws = { readyState: 1, send: (m) => sent.push(JSON.parse(m)) };
      const m = await import('/js/taskList.js');
      const container = document.getElementById('task-list');
      m.renderTaskList([
        { id: 'h1', subject: 'buy milk', status: 'pending', taskKind: 'human', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() },
        { id: 'a1', subject: 'agent work', status: 'pending', taskKind: 'agent', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() },
      ], container, 'sess1');
      await new Promise(r => setTimeout(r, 50));
      const humanRow = container.querySelector('.task-section-todo .task-item[data-task-id="h1"]');
      const humanCheck = humanRow && humanRow.querySelector('.task-check');
      const agentRow = container.querySelector('.task-section-progress .task-item[data-task-id="a1"]');
      const agentCheck = agentRow && agentRow.querySelector('.task-check');
      const before = { humanCircle: humanCheck ? getComputedStyle(humanCheck).borderRadius : null,
                       humanRole: humanCheck ? humanCheck.getAttribute('role') : null,
                       agentRole: agentCheck ? agentCheck.getAttribute('role') : null };
      if (humanCheck) humanCheck.click();
      await new Promise(r => setTimeout(r, 50));
      return { before, sent };
    });
    ok('G6 human pending: circle (50%) with checkbox role', todo.before.humanCircle === '50%' && todo.before.humanRole === 'checkbox', todo.before);
    ok('G6 agent row: read-only glyph (no checkbox role)', todo.before.agentRole === null, todo.before.agentRole);
    const ct = todo.sent.find(f => f.type === 'completeTask');
    ok('G6 circle click sends completeTask directly', !!ct && ct.taskId === 'h1', todo.sent);

    // G7 裁定②: team tasks merge into the Nebula session panel only
    const merge = await page.evaluate(async () => {
      const state = (await import('/js/state.js')).default;
      state.sessionAgentMap = { sessNebula: 'Nebula', sessMember: 'Backend' };
      state.teamTasks = { 'demo-team': [tkTeam('9', 'in_progress')] };
      function tkTeam(id, status) {
        return { id, subject: 'teamtask-' + id, status, taskKind: 'agent', scope: 'team', updatedAt: new Date().toISOString() };
      }
      const m = await import('/js/taskList.js');
      const container = document.getElementById('task-list');
      // Nebula session: team group appears
      m.renderTaskList([{ id: 's1', subject: 'local', status: 'pending', taskKind: 'agent', updatedAt: new Date().toISOString() }], container, 'sessNebula');
      await new Promise(r => setTimeout(r, 50));
      const teamHeader = [...container.querySelectorAll('.task-subgroup-team')].map(h => h.textContent);
      const teamRows = [...container.querySelectorAll('.task-subgroup .task-item')].map(r => r.dataset.taskId);
      const flatRows = [...container.querySelectorAll('.task-section-progress > .task-item')].map(r => r.dataset.taskId);
      // Non-Nebula session: zero team tasks
      m.renderTaskList([], container, 'sessMember');
      await new Promise(r => setTimeout(r, 50));
      const memberTeamRows = container.querySelectorAll('.task-subgroup').length;
      const memberEmpty = !container.classList.contains('has-tasks');
      return { teamHeader, teamRows, flatRows, memberTeamRows, memberEmpty };
    });
    ok('G7 Nebula session: team group header rendered', merge.teamHeader.length === 1 && merge.teamHeader[0] === 'demo-team', merge.teamHeader);
    ok('G7 team task row namespaced id under the group', merge.teamRows.length === 1 && merge.teamRows[0] === 'team:demo-team:9', merge.teamRows);
    ok('G7 session task stays flat (ungrouped first)', merge.flatRows.length === 1 && merge.flatRows[0] === 's1', merge.flatRows);
    ok('G7 non-Nebula session: zero team tasks', merge.memberTeamRows === 0 && merge.memberEmpty === true, merge);

    // G8 retired paths: no cancel / return / archive controls
    const ctrls = await page.evaluate(async () => {
      const m = await import('/js/taskList.js');
      const container = document.getElementById('task-list');
      m.renderTaskList([
        { id: 'x1', subject: 'a', status: 'pending', taskKind: 'agent', updatedAt: new Date().toISOString() },
        { id: 'x2', subject: 'b', status: 'in_progress', taskKind: 'agent', updatedAt: new Date().toISOString() },
        { id: 'x3', subject: 'c', status: 'pending', taskKind: 'human', updatedAt: new Date().toISOString() },
      ], container, 'sess1');
      await new Promise(r => setTimeout(r, 50));
      return {
        cancel: container.querySelectorAll('.task-cancel-btn').length,
        ret: container.querySelectorAll('.task-return-btn').length,
        archive: container.querySelectorAll('.task-archive-btn').length,
        nc: container.querySelectorAll('.task-needs-confirmation').length,
      };
    });
    ok('G8 zero cancel/return/archive/needs-confirmation controls', ctrls.cancel === 0 && ctrls.ret === 0 && ctrls.archive === 0 && ctrls.nc === 0, ctrls);

    ok('T2 zero page errors', errs.length === 0, errs);
    await page.screenshot({ path: process.env.SHOT || '/tmp/task-progress.png' });
    await ctx.close();
  }

  // ── G5 reduced motion ──
  {
    const ctx = await browser.newContext({ reducedMotion: 'reduce' });
    const page = await ctx.newPage();
    await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: HTML }));
    await page.goto(`${BASE}/h.html`);
    const rm = await page.evaluate(async () => {
      const m = await import('/js/taskList.js');
      const container = document.getElementById('task-list');
      m.renderTaskList([{ id: 'a', subject: 'x', status: 'in_progress', taskKind: 'agent', updatedAt: new Date().toISOString() }], container, 'sess1');
      await new Promise(r => setTimeout(r, 50));
      const check = container.querySelector('.task-item .task-check');
      return { anim: getComputedStyle(check).animationName, cls: check.className };
    });
    ok('G5 reduced-motion: spinner static', rm.anim === 'none' || rm.anim === '', rm);
    await ctx.close();
  }

  // ── T1 team card zero task markup ──
  {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: HTML }));
    await page.goto(`${BASE}/h.html`);
    const t1 = await page.evaluate(async () => {
      const m = await import('/js/flowTeams.js');
      const html = m.flowCardHtml({ name: 'demo-team', agents: [{ name: 'Manager', manager: true }], tasks: [{ id: 1, subject: 'x', status: 'pending' }], flows: [] }, {}, new Set(), []);
      return { hasTasks: /team-tasks|team-task-row|task-status-badge/.test(html), len: html.length };
    });
    ok('T1 team card renders zero task section markup', t1.hasTasks === false, t1);
    ok('T1 flowCardHtml import clean', errs.length === 0, errs);
    await ctx.close();
  }

  await browser.close();
  const fails = results.filter(r => !r.pass);
  console.log(`\n${results.length - fails.length}/${results.length} PASS`);
  process.exit(fails.length ? 1 : 0);
})();
