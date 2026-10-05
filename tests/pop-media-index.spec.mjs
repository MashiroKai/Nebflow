// pop-media-index.spec.mjs — #491 主题二: the WeChat-style categorized
// retrieval over the UNIFIED media index (author 2026-10-05 09:54 原话②).
//
//   分类正确性 — a history with a user image + a user file + an agent Pop
//     (image/video/file/URL-leg) + a payload-less legacy Pop row + an injected
//     attachment lands in the right tabs (media/files/links), with sources.
//   结构 — CONTENT_TABS has no 'pop' (the standalone tab is retired); locales
//     carry no search.tabPop/tabImages references; the rebuilt strip is
//     全部/图片与视频/文件/链接/日期.
//   时间分组 — tool rows anchor to the nearest earlier row with a ts; the grid
//     groups 本周/按月.
//   来源筛选 + 分类内搜索 — chips filter by entry source; keyword filters
//     entry fields (name/ext/path).
//   索引一致性 (PLAN §3.1) — for the same history, the artifact set the
//     message stream renders inline == the index's agent-source entry set.
//   打开行为 — grid media card → the shared lightbox; grid file card → the
//     workspace-open-item single point (AC-S1/S2 parity holds panel-wide).
//
// Drives the REAL modules through tests/fixtures/pop-media-index/panel.html
// (self-spawned static server, mocked /api fetches — never the host).
//
// Run: node node_modules/@playwright/test/cli.js test tests/pop-media-index.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-media-index/panel.html';

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 60) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  // Bounded boot: a module the assertions need may not exist yet (the red leg
  // against the pre-batch tree) — let the cases fail on their reads instead of
  // hanging the runner.
  try {
    await page.waitForFunction(() => window.__ready === true, null, { timeout: 5000 });
  } catch { /* boot failed — red leg */ }
  return { context, page, pageErrors };
}

/* ═══════════ the unified index (unit, through the real module) ═══════════ */

test.describe('indexMedia — entry construction over the seed history', () => {
  test('user attachments + Pop payload items + URL leg + legacy degrade, with ts anchoring', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => {
      const msgs = window.__seedHistory();
      const entries = window.__idx.indexMedia(msgs, { sessionId: 'pmi-session', sessionName: '索引夹具会话' });
      return entries.map(e => ({
        type: e.type, source: e.source, name: e.name, ref: e.ref,
        ts: e.ts, msgOrd: e.msgOrd, thumb: !!e.thumbRef,
      }));
    });

    // user image + user file (source user)
    const u = read.filter(e => e.source === 'user');
    expect(u.some(e => e.type === 'image' && e.name === 'user-shot.png' && e.thumb), 'user image entry with inline thumb').toBe(true);
    expect(u.some(e => e.type === 'file' && e.name === 'user-notes.pdf'), 'user file entry').toBe(true);

    // agent Pop: image / video / file / link
    const a = read.filter(e => e.source === 'agent');
    expect(a.some(e => e.type === 'image' && e.name === 'agent.png' && e.thumb), 'Pop image entry').toBe(true);
    expect(a.some(e => e.type === 'video' && e.name === 'agent.mp4'), 'Pop video entry').toBe(true);
    expect(a.some(e => e.type === 'file' && e.name === 'agent-doc.pdf'), 'Pop file entry').toBe(true);
    expect(a.some(e => e.type === 'link' && e.ref === 'https://example.com/spec'), 'URL-leg link entry').toBe(true);
    // legacy payload-less Pop row degrades to a file entry
    expect(a.some(e => e.type === 'file' && e.name === 'legacy.bin'), 'legacy Pop row degraded to file entry').toBe(true);
    // injected row's attachment is agent-sourced
    expect(a.some(e => e.type === 'image' && e.name === 'injected.png'), 'injected attachment → agent source').toBe(true);

    // ts anchoring: the Pop tool row (ord 1) inherits the user row's ts
    const popImage = read.find(e => e.name === 'agent.png');
    expect(popImage.ts, 'tool row entry anchors to the nearest earlier ts').toBeGreaterThan(0);
    expect(popImage.msgOrd, 'anchored entry belongs to the tool row').toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('PLAN §3.1 consistency: inline artifact set == index agent set (double render)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => {
      const msgs = window.__seedHistory();
      const entries = window.__idx.indexMedia(msgs, { sessionId: 'pmi-session', sessionName: 's' });
      const agentEntries = entries.filter(e => e.type !== 'link').map(e => e.name).sort();
      // The inline face, walked exactly as the message stream renders it:
      // Pop payloads → artifact rows; payload-less legacy Pop/Card rows → the
      // rainbow filename (popArtifactFromInput's degrade, same as the index);
      // every user row's attachments → the shared user-media row (injected
      // rows included — they render the same faces).
      const inline = [];
      const collectRow = (row) => {
        if (!row) return;
        row.querySelectorAll('.pop-img, .pop-video, .file-card').forEach(el => {
          if (el.classList.contains('pop-img') || el.classList.contains('pop-video')) {
            inline.push(el.dataset.nfName || el.alt || '');
          } else {
            inline.push((el.querySelector('.fc-name') || {}).textContent || '');
          }
        });
      };
      for (const m of msgs) {
        if (m.type === 'tool') {
          if (window.__pop.isPopPayload(m.content)) {
            const payload = window.__pop.parsePopPayload(m.content);
            collectRow(window.__pop.buildPopArtifactRow(payload));
          } else {
            const art = window.__pop.popArtifactFromInput(m.label, m.input);
            if (art) inline.push(art.filePath.split('/').pop());
          }
        } else if (m.type === 'user') {
          collectRow(window.__pop.buildUserMediaRow(m.attachments || []));
        }
      }
      return { inline: [...new Set(inline)].filter(Boolean).sort(), agentEntries };
    });
    expect(read.inline.length, 'the inline face rendered media + file cards').toBeGreaterThan(0);
    expect(read.inline, 'inline artifact set == index entry set (PLAN §3.1)')
      .toEqual(read.agentEntries);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ structural: the standalone Pop tab is retired ═══════════ */

test.describe('structure — no pop tab anywhere', () => {
  test('CONTENT_TABS literal carries no pop; locales carry no tabPop/tabImages', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(async () => {
      const cs = await (await fetch('/src/main/resources/web/js/chatSearch.js')).text();
      const m = cs.match(/const CONTENT_TABS = \[([^\]]*)\]/);
      const zh = await (await fetch('/src/main/resources/web/js/locales/zh-CN.js')).text();
      const en = await (await fetch('/src/main/resources/web/js/locales/en.js')).text();
      return {
        tabs: m ? m[1] : '',
        zhHasTabPop: zh.includes("'search.tabPop'"),
        enHasTabPop: en.includes("'search.tabPop'"),
        zhHasTabImages: zh.includes("'search.tabImages'"),
        enHasTabImages: en.includes("'search.tabImages'"),
      };
    });
    expect(read.tabs, "CONTENT_TABS = ['all','media','files','links','date']")
      .toBe("'all', 'media', 'files', 'links', 'date'");
    expect(read.tabs.includes("'pop'"), 'no pop tab in the list').toBe(false);
    expect(read.tabs.includes("'images'"), 'no bare images tab in the list').toBe(false);
    expect(read.zhHasTabPop || read.enHasTabPop, 'search.tabPop key deleted (zh+en)').toBe(false);
    expect(read.zhHasTabImages || read.enHasTabImages, 'search.tabImages key deleted (zh+en)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('the rebuilt strip renders 全部/图片与视频/文件/链接/日期 — no Pop button', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());
    const tabs = await page.evaluate(() => window.__tabRead());
    expect(tabs.map(tb => tb.id), 'strip ids (rebuilt from CONTENT_TABS)')
      .toEqual(['search-tab-all', 'search-tab-media', 'search-tab-files', 'search-tab-links', 'search-tab-date']);
    expect(tabs.map(tb => tb.text), 'strip labels (zh default, same t() source)')
      .toEqual(['全部', '图片与视频', '文件', '链接', '日期']);
    expect(tabs.some(tb => tb.id.includes('pop')), 'no Pop button in the DOM').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ the media grid: categories, groups, chips, keyword ═══════════ */

test.describe('media tab — grid over the unified index', () => {
  test('media grid shows image/video entries with 本周 group + source badges', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());
    expect(await page.evaluate(() => window.__switchTab('media')), 'media tab clickable').toBe(true);

    const grid = await page.evaluate(() => window.__gridRead());
    const types = grid.cards.map(c => c.type);
    // media tab = the four media entries of the seed (user-shot, agent.png,
    // agent.mp4, injected.png), newest ts first.
    expect([...types].sort(), 'media tab = images + videos only').toEqual(['image', 'image', 'image', 'video']);
    expect(types[0], 'the injected row is a day old — newest, first').toBe('image');
    expect(grid.cards.every(c => ['user', 'agent'].includes(c.source)), 'every card carries a source').toBe(true);
    expect(new Set(grid.cards.map(c => c.source)), 'both sources present (user + agent)').toEqual(new Set(['user', 'agent']));
    expect(grid.headers.length, 'the media entries all sit in the current week → one group').toBe(1);
    expect(grid.headers, 'the week header is 本周').toContain('本周');
    expect(grid.chips, 'three source chips, all-source active, row visible')
      .toEqual([
        { source: '', active: true, hidden: false },
        { source: 'user', active: false, hidden: false },
        { source: 'agent', active: false, hidden: false },
      ]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('files tab lists user + agent files; links tab lists the URL leg', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());
    await page.evaluate(() => window.__switchTab('files'));
    let grid = await page.evaluate(() => window.__gridRead());
    expect(grid.cards.map(c => c.type), 'files tab = file entries only').toEqual(['file', 'file', 'file']);
    expect(grid.cards.map(c => c.name).sort(), 'user and agent files share the tab')
      .toEqual(['agent-doc.pdf', 'legacy.bin', 'user-notes.pdf'].sort());
    // The month bucket proves 按月 grouping: legacy.bin is 40 days old, the
    // other two files are in-week.
    expect(grid.headers.length, 'two time groups in the files tab (本周 + month)').toBe(2);
    expect(grid.headers[0], 'the in-week files group under 本周').toBe('本周');
    expect(grid.headers[1], 'the 40-day-old file groups under a month label').toMatch(/月$/);

    await page.evaluate(() => window.__switchTab('links'));
    grid = await page.evaluate(() => window.__gridRead());
    expect(grid.cards.map(c => c.type), 'links tab = the URL leg').toEqual(['link']);
    expect(grid.cards[0].name, 'the link title').toBe('example spec');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('source chips filter the grid (全部来源/用户/助手)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());
    await page.evaluate(() => window.__switchTab('media'));

    await page.evaluate(() => window.__clickChip('user'));
    let grid = await page.evaluate(() => window.__gridRead());
    expect(grid.cards.length, 'user chip → only the user image').toBe(1);
    expect(grid.cards[0].source, 'user-sourced').toBe('user');

    await page.evaluate(() => window.__clickChip('agent'));
    grid = await page.evaluate(() => window.__gridRead());
    expect(grid.cards.length, 'agent chip → agent image + video + injected image').toBe(3);
    expect(grid.cards.every(c => c.source === 'agent'), 'agent-sourced').toBe(true);

    await page.evaluate(() => window.__clickChip(''));
    grid = await page.evaluate(() => window.__gridRead());
    expect(grid.cards.length, 'all-sources restores the full set').toBe(4);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('in-category keyword filters entry fields', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());
    await page.evaluate(() => window.__switchTab('files'));
    await page.evaluate(() => window.__setKeyword('notes'));
    const grid = await page.evaluate(() => window.__gridRead());
    expect(grid.cards.map(c => c.name), "keyword 'notes' hits only the user notes file")
      .toEqual(['user-notes.pdf']);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('click faces: media card → shared lightbox; file card → workspace-open-item', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());
    await page.evaluate(() => window.__switchTab('media'));
    await page.evaluate(() => {
      const card = document.querySelector('#search-results .search-media-card');
      card.click();
    });
    await new Promise(r => setTimeout(r, 60));
    const lb = await page.evaluate(() => ({
      open: !!document.querySelector('.nf-lightbox'),
      hasCanvas: !!document.querySelector('.nf-lightbox .nf-lightbox-canvas'),
    }));
    expect(lb.open, 'grid media card opens the SAME lightbox face (AC-S1 panel parity)').toBe(true);
    expect(lb.hasCanvas, 'Canvas secondary entry on the overlay').toBe(true);
    await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
    await new Promise(r => setTimeout(r, 30));
    expect(await page.evaluate(() => !!document.querySelector('.nf-lightbox')), 'Esc closes').toBe(false);

    await page.evaluate(() => window.__switchTab('files'));
    await page.evaluate(() => {
      window.__openEvents.length = 0;
      const card = document.querySelector('#search-results .search-media-card');
      card.click();
    });
    await new Promise(r => setTimeout(r, 30));
    const opened = await page.evaluate(() => window.__openEvents);
    expect(opened.length, 'grid file card fired the Canvas single point').toBe(1);
    expect(opened[0].absPath, 'absPath carried (AC-S2 parity)').toBe('/tmp/a/agent-doc.pdf');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ regressions: all/date tabs keep the message stream ═══════════ */

test.describe('all/date tabs — message stream unchanged', () => {
  test('all tab still browse-streams message rows; date tab keeps its popover affordance', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__openPanel());

    const allRead = await page.evaluate(() => ({
      results: document.querySelectorAll('#search-results .search-result').length,
      chipsHidden: document.getElementById('search-source-chips').hidden,
    }));
    expect(allRead.results, 'the all tab browse-streams message rows').toBeGreaterThan(0);
    expect(allRead.chipsHidden, 'source chips stay hidden outside media tabs').toBe(true);

    const dateTab = await page.evaluate(() => {
      const el = document.getElementById('search-tab-date');
      return { hasPopup: el.getAttribute('aria-haspopup'), label: el.textContent };
    });
    expect(dateTab.hasPopup, 'date tab keeps the calendar affordance').toBe('dialog');
    expect(dateTab.label, 'date label').toBe('日期');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
