// inputerror-minimal.spec.mjs — 输入框错误态极简化验收（2026-10-31 作者令
// 「顺带核对错误态输入框极简覆盖面：10-29 批 e12779943 只管对话框，输入框
// 错误态（红/警示样式）按极简简约朴素口径补齐」）。
//
// 上位法 = docs/design/20261029_errorfrozen-dialog-minimal-spec.zh-CN.md §2：
//   · 错误色预算每面至多一处语义色载体（图案载体：2px 描边 / 行首图标）；
//   · 正文字恒中性（--status-word），语义色不上正文；
//   · 装饰层（大色块底/红字/红进度块/下划线链接）退役，色相即状态。
//
// 三面（输入框族错误态盘点缺口）：
//   S1 附件队列失败项 .queue-item.failed（input.css + chatQueue.js 注记行）
//      —— 红字/红注记退役 → 红左缘 2px + 注记行首红图标 + 中性字；
//   S2 附件内联错误行 .att-error（input.css + input/attachments.js showAttError）
//      —— 红 12% 底块/红字退役 → etched 底 + 2px 红左缘 + 中性字；
//   S3 askUser 提问卡附件失败 chip（askAttachments.js fail()）
//      —— 红 60% 描边/实红进度块/红字/下划线链接退役 → 25% tint 描边 +
//      中性进度块 + 行首图标 + 中性错误词 + amber 无下划线重试 chip。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/inputerror-minimal.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const css = (p) => readFileSync(join(WEB, 'css', p), 'utf8');

test.use({ colorScheme: 'dark' }); // 暗档红 --status-error-icon #f26b6b（亮档 #d64545 同构）

// ── S0 源级纪律（token 单源；红字面/旧构造零残留）────────────────────────

test('S0 source: 输入框错误态面零红字正文构造，红走 --status-error-icon 图案载体', async () => {
  const inputCss = css('input.css');
  // S1 队列失败项：正文/注记词中性字（--status-word），红只在左缘描边 + 图标档。
  expect(inputCss).toMatch(/\.queue-item\.failed \{\s*box-shadow: inset 2px 0 0 0 var\(--status-error-icon/);
  expect(inputCss).toMatch(/\.queue-item\.failed \.queue-item-text \{\s*color: var\(--status-word/);
  expect(inputCss).toMatch(/\.queue-item-failed-note \{[^}]*color: var\(--status-word/);
  expect(inputCss).toMatch(/\.queue-item-failed-note \.st-ic \{[^}]*color: var\(--status-error-icon/);
  // S2 内联错误行：etched 底 + 2px 红左缘 + 中性字；红 12% 底块字面退役。
  expect(inputCss).toMatch(/\.att-error \{[^}]*border-left: 2px solid var\(--status-error-icon/);
  expect(inputCss).toMatch(/\.att-error \{[^}]*color: var\(--status-word/);
  expect(inputCss).not.toMatch(/\.att-error \{[^}]*rgba\(244, 67, 54/);
  // S3 askUser chip：JS 面换 --status-error-icon 构造，红字/实红进度/下划线退役。
  const askAtt = readFileSync(join(WEB, 'js', 'askAttachments.js'), 'utf8');
  expect(askAtt).toMatch(/status-error-icon,\s*#f26b6b/);
  expect(askAtt).toMatch(/glass-etched-border/);
  expect(askAtt).not.toMatch(/--color-error,\s*#f44336/);
  expect(askAtt).not.toMatch(/text-decoration:underline/);
  // 队列注记行首图标（chatQueue.js 注入 .st-ic circle-alert）。
  const queue = readFileSync(join(WEB, 'js', 'chatQueue.js'), 'utf8');
  expect(queue).toMatch(/queue-item-failed-note[\s\S]{0,400}className = 'st-ic'/);
  expect(queue).toMatch(/queue-item-failed-note[\s\S]{0,600}circle cx="12" cy="12" r="10"/);
});

// ── S1/S2 计算样式（真实生产 CSS 链：base + sapphire + input）────────────

test('S1/S2 computed: 失败项/错误行中性字 + 图案载体解析（暗档）', async ({ page }) => {
  await page.setContent(`
    <!doctype html><html><head></head><body>
      <div id="queue-bar">
        <div class="queue-item failed" data-queue-failed="1">
          <span class="queue-item-text">带附件的待发消息</span>
          <span class="queue-item-failed-note"><span class="st-ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/>
              <line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          </span>附件丢失</span>
        </div>
      </div>
      <div class="att-error">无法附加该文件</div>
    </body></html>`, { waitUntil: 'load' });
  await page.addStyleTag({ content: css('base.css') + css('sapphire.css') + css('input.css') });
  const m = await page.evaluate(() => {
    const cs = (sel) => getComputedStyle(document.querySelector(sel));
    const text = cs('.queue-item.failed .queue-item-text');
    const note = cs('.queue-item-failed-note');
    const ic = cs('.queue-item-failed-note .st-ic');
    const err = cs('.att-error');
    return {
      text: text.color,
      note: note.color,
      icon: ic.color,
      leftEdge: err.borderLeftWidth + ' ' + err.borderLeftStyle,
      errColor: err.color,
      shadow: cs('.queue-item.failed').boxShadow,
    };
  });
  // 中性字（暗档 --status-word = --color-frame-text-muted 深灰族），非红。
  expect(m.text).not.toBe('rgb(242, 107, 107)');
  expect(m.note).not.toBe('rgb(242, 107, 107)');
  // 红唯一落点 = 图标 + 左缘描边（图案载体）。
  expect(m.icon).toBe('rgb(242, 107, 107)'); // 暗档 --status-error-icon #f26b6b
  expect(m.leftEdge).toBe('2px solid');
  expect(m.errColor).not.toBe('rgb(242, 107, 107)');
  expect(m.shadow).toContain('inset');
});
