import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, launchBrowser, createSession } from '../scripts/lib/visual-harness.mjs';
const out = '/tmp/stand-workspace-panel-evidence';
mkdirSync(out, { recursive: true });
const loaded = loadPlaywright();
const { browser } = await launchBrowser(loaded.mod.playwright || loaded.mod);
const results = [];
try {
  for (const width of [1440, 390]) for (const theme of ['light', 'dark']) {
    const session = await createSession(browser, { width, height: 1000, colorScheme: theme });
    await session.page.route('**/api/projects**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/workspaces')) await route.fulfill({ json: {
        revision: 10, baseline: 'main', merger: { delegateId: 'delegate-merger' }, cleanups: {},
        workspaces: [
          { id: 'idle', branch: 'delegate/provider-recovery-workspace', lifecycle: 'open', activity: 'idle',
            owner: 'delegate-developer', baseBranch: 'main', baseCommit: 'a'.repeat(40) },
          { id: 'busy', branch: 'delegate/working', lifecycle: 'open', activity: 'running',
            owner: 'delegate-working', baseBranch: 'main', baseCommit: 'a'.repeat(40) },
        ], mergeRequests: [{ id: 'request-1', status: 'paused', items: [{ workspaceId: 'previous',
          phase: 'merged-cleanup-pending', mergeCommit: 'b'.repeat(40) }] }],
      } });
      else if (path.endsWith('/flow-map')) await route.fulfill({ json: { nodes: [{ id: 'n-1', name: 'existing-task', status: 'completed' }], worktrees: [], meta: {} } });
      else if (path === '/api/projects') await route.fulfill({ json: { projects: [{ name: 'example', workspace: '/isolated/example', agentFile: 'AGENTS.md',
        description: '工作区与工程合并记录', createdAt: 1 }], unmounted: [] } });
      else await route.fulfill({ json: {} });
    });
    await session.boot({ sessionId: 'panel-parent', messages: [] });
    await session.page.evaluate(async ({ theme, width }) => {
      document.documentElement.setAttribute('data-theme', theme);
      if (width < 768) {
        const { pushPage } = await import('/js/mobile/navController.js');
        pushPage('projects');
      } else {
        const { openProjectsTab } = await import('/js/projectTab.js');
        openProjectsTab();
      }
    }, { theme, width });
    const section = session.page.locator('.project-card[data-project="example"] .project-workspaces');
    await section.locator(':scope > summary').click();
    await section.locator('input[required]').waitFor();
    await section.locator('input[required]').focus();
    await session.page.keyboard.press('Tab');
    const keyboard = await session.page.evaluate(() => document.activeElement?.textContent?.includes('保存基线'));
    assert(keyboard, `baseline keyboard save ${width}/${theme}`);
    assert(await section.locator('input[required]').evaluate(element => getComputedStyle(element).color === getComputedStyle(element.closest('.project-workspaces')).color), `input text contrast ${width}/${theme}`);
    assert(await section.locator('input[type=checkbox]').nth(1).isDisabled());
    assert(await section.getByRole('button', { name: '清理工作区', exact: true }).nth(1).isDisabled());
    const geometry = await section.evaluate(element => ({ width: element.clientWidth, scroll: element.scrollWidth,
      visible: element.getBoundingClientRect().width > 0 }));
    assert(geometry.visible && geometry.scroll <= geometry.width + 2, `overflow ${width}/${theme}: ${JSON.stringify(geometry)}`);
    await session.page.screenshot({ path: `${out}/${width}-${theme}.png`, fullPage: true });
    results.push({ width, theme, geometry, keyboard });
    await session.close();
  }
  writeFileSync(`${out}/results.json`, JSON.stringify(results, null, 2));
  console.log('PASS actual project panel: desktop/mobile, light/dark, keyboard save, busy cleanup disabled, no horizontal overflow');
} finally { await browser.close(); }
