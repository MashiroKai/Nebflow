// input.js 拆分（FE组件化批次四 2026-09-27）：slash 命令族（命令表+封存闸+注册+派发+自动补全） 可复用模块（行为保持）。
import { key } from '../branding.js';
import { activeView } from '../chatView.js';
import { sendWs } from '../ws.js';
import { cancelToolStreamRAF, renderSystemBubble } from '../chat.js';
import { escapeHtml } from '../utils.js';
import { t } from '../i18n.js';
import { enterAskMode, enterSkillMode, enterCompactMode } from './inputModes.js';

// ---------- Slash Commands ----------
/* SEALED / 封存待启用 (author ruling 2026-08-29 22:56): the /slash command
   menu is sealed - '/' is treated as plain text (zero popup, no command
   interception). Code kept intact for future re-enable:
     localStorage.setItem('nebflow_slash.enabled', '1')  // then reload
   WHITELIST SPLIT (author ruling 2026-09-17, D1-B): the master gate above stays
   OFF and keeps its old all-or-nothing meaning; on top of it, only the
   commands in SLASH_ALLOWED are reachable while it is off. Everything else
   (/ask, /onboarding, the dynamically registered skill/flow commands) stays
   exactly as sealed as before - the '/' dropdown lists the whitelist only and
   handleSlash returns false for every other name. */
const SLASH_ENABLED = () => { try { return localStorage.getItem(key('slash.enabled')) === '1'; } catch (e) { return false; } };
// Per-command whitelist (D1-B). Deliberately a standalone literal set, NOT a
// flag carried by the table entries: `registerSkillCommands` adds/deletes
// entries carrying `_skill` on every skillList frame, and a whitelist living in
// the table could be widened by such a re-registration. Keys = the command
// names exactly as typed (and exactly as used as table keys).
const SLASH_ALLOWED = new Set(['/clear', '/compact', '/model']);
/** May `cmd` be listed/dispatched? Whichever is in the whitelist · always;
 *  everything else only while the master gate is open. */
const slashAllowed = (cmd) => SLASH_ALLOWED.has(cmd) || SLASH_ENABLED();
const slashCommands = {
  '/ask': {
    desc: () => t('slash.ask'),
    run: () => {
      enterAskMode();
    }
  },
  '/onboarding': {
    desc: () => t('slash.onboarding'),
    run: () => {
      // Replay the fixed chat-native onboarding greeting (same as first run).
      import('../onboarding.js').then(m => m.replayOnboarding()).catch(() => {});
    }
  },
  // 回挂（作者令 2026-09-17：D1-B 白名单 + D2 历史语义 + D4 二段式）。表体逐字取自
  // 摘除前形态 `71afa4a9b^:src/main/resources/web/js/input.js:33-44`（`/clear`）与
  // `:45-50`（`/compact`），仅做两处机械适配：`/clear` 体内 `delete state.sessionTasks[...]`
  // 与 `renderTaskList([])` 两行不再接回 —— 两者已随旧任务区退役（台账 `state.js:160`
  // 与本文件 :12），`state.sessionTasks` 现已 undefined（保留 = 点按 `/clear` 抛 TypeError）。
  // 位置：追加在内置表末位（保持既有条目次序不动 ⇒ 总闸放行面 matches[0] 仍是 `/ask`）；
  // 默认（白名单）面列表按插入序 = `/clear` → `/compact`。
  '/clear': {
    desc: () => t('slash.clear'),
    run: () => {
      const v = activeView;
      sendWs({type:'command', command:'clear', sessionId: v.sessionId});
      // Clean up stream state — remove orphaned thinking placeholders and
      // reset stream variables so the next message starts fresh.
      if (window.__stopThinkingTimer) window.__stopThinkingTimer();
      cancelToolStreamRAF();
      v.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
        const row = el.closest('.row');
        if (row) row.remove();
      });
      v.stream.currentAiBubble = null;
      v.stream.aiText = '';
      v.stream.currentThinkingBubble = null;
      v.stream.thinkingText = '';
      v.stream.toolStreamText = '';
      v.stream.toolStreamToolName = '';
      renderSystemBubble(t('slash.clearDone'));
    }
  },
  '/compact': {
    desc: () => t('slash.compact'),
    run: () => {
      enterCompactMode();
    }
  }
};

/**
 * Register a UI-only slash command (mirrors the registerSkillCommands
 * table-insertion pattern). uiOnly = the run() opens UI / flips local state
 * and never injects a message, so the busy-session pass-through (send path)
 * applies just like /clear. The admission whitelist stays the literal
 * SLASH_ALLOWED set above — registering a command here never widens it.
 * @param {{ name: string, desc: () => string, uiOnly?: boolean, run: (text?: string) => void }} cmd
 */
export function registerCommand({ name, desc, uiOnly, run }) {
  if (!name || typeof run !== 'function') return;
  // Built-ins are never overridden by a late registration.
  if (slashCommands[name] && !slashCommands[name]._ui) return;
  slashCommands[name] = { _ui: !!uiOnly, desc, run };
}

// /model — UI-only: opens the model-chain panel, never injects a message
// (ruling ④-A: whitelisted alongside /clear /compact). The panel module is
// loaded on first use; a load failure stays silent (the input simply clears).
registerCommand({
  name: '/model',
  desc: () => t('slash.model'),
  uiOnly: true,
  run: () => {
    import('../modelPanel.js').then(m => m.openModelPanel()).catch(() => {});
  },
});

// /agents retired (ui-train-threefix A, 2026-10-07): the executor picker entry
// left the user faces (settings section + this command + its locale keys).
// ExecutorRegistry/detect (scala) untouched.

/** Register skill commands from the server-provided skill list. */
export function registerSkillCommands(skills) {
  // Remove previously registered skill commands
  Object.keys(slashCommands).forEach(key => {
    if (key.startsWith('/') && slashCommands[key]._skill) {
      delete slashCommands[key];
    }
  });
  skills.forEach(skill => {
    const cmd = '/' + skill.name;
    // Don't override built-in commands
    if (!slashCommands[cmd] || slashCommands[cmd]._skill) {
      slashCommands[cmd] = {
        _skill: true,
        _source: skill.source,
        _skillName: skill.name,
        desc: () => skill.description || t('slash.skillDefault'),
        whenToUse: skill.whenToUse || '',
        argumentHint: skill.argumentHint || '',
        run: () => enterSkillMode(skill.name, skill.description, skill.argumentHint, skill.source)
      };
    }
  });
}

// ---------- Slash Command Handler ----------
export function handleSlash(text) {
  const cmd = text.trim().split(/\s/)[0]; // 解析**先于**判定（D1-B：白名单按命令名判）
  if (!slashAllowed(cmd)) return false; // SEALED: '/' is plain text（白名单两条除外）
  if (slashCommands[cmd] && slashCommands[cmd].run) {
    slashCommands[cmd].run(text);
    return true;
  }
  return false;
}

// ---------- Slash Autocomplete ----------
export function updateSlashDropdown() {
  const input = activeView.dom.input;
  const text = input.value;
  if (!text.startsWith('/')) {
    closeSlashDropdown();
    return;
  }
  const query = text.slice(1).toLowerCase();
  activeView.slashMatches = Object.entries(slashCommands)
    .filter(([cmd]) => slashAllowed(cmd) && cmd.slice(1).toLowerCase().startsWith(query))
    .map(([cmd, info]) => ({ cmd, desc: typeof info.desc === 'function' ? info.desc() : info.desc, whenToUse: info.whenToUse || '', isSkill: !!info._skill, source: info._source || '', skillName: info._skillName || '' }));
  if (activeView.slashMatches.length === 0) {
    closeSlashDropdown();
    return;
  }
  const slashDropdown = activeView.dom.slashDropdown;
  slashDropdown.innerHTML = '';
  activeView.slashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    const badge = item.isSkill ? '<span class="slash-badge skill">' + escapeHtml(t('slash.skillBadge')) + '</span>' : '';
    const whenToUseHtml = item.whenToUse ? '<span class="slash-when">' + escapeHtml(item.whenToUse) + '</span>' : '';
    const deleteHtml = (item.isSkill && item.source === 'user')
      ? '<span class="slash-delete" title="' + escapeHtml(t('slash.deleteSkill')) + '" data-skill="' + escapeHtml(item.skillName) + '"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg></span>'
      : '';
    div.innerHTML = '<div style="display:flex;align-items:center"><span class="slash-cmd">' + escapeHtml(item.cmd) + '</span>' + badge + '</div><span class="slash-desc">' + escapeHtml(item.desc) + '</span>' + whenToUseHtml + deleteHtml;
    div.onclick = () => { pickSlashCommand(i); };
    div.onmouseenter = () => { setSlashHighlight(i); };
    if (deleteHtml) {
      const delBtn = div.querySelector('.slash-delete');
      if (delBtn) {
        delBtn.onclick = (e) => {
          e.stopPropagation();
          handleDeleteSkill(item.skillName);
        };
      }
    }
    slashDropdown.appendChild(div);
  });
  activeView.slashSelectedIndex = 0;
  slashDropdown.classList.add('on');
}

export function closeSlashDropdown() {
  activeView.dom.slashDropdown.classList.remove('on');
  activeView.slashSelectedIndex = -1;
  activeView.slashMatches = [];
}

export function setSlashHighlight(index) {
  activeView.slashSelectedIndex = index;
  const items = activeView.dom.slashDropdown.querySelectorAll('.slash-item');
  items.forEach((el, i) => { el.classList.toggle('active', i === index); });

  const active = items[index];
  if (!active) return;

  const dd = activeView.dom.slashDropdown;
  // Calculate element's offset relative to the dropdown content area
  let relTop = 0;
  let el = active;
  while (el && el !== dd) {
    relTop += el.offsetTop;
    el = el.offsetParent;
  }
  const relBottom = relTop + active.offsetHeight;
  const scrollTop = dd.scrollTop;
  const visibleBottom = scrollTop + dd.clientHeight;

  if (relTop < scrollTop) {
    dd.scrollTop = relTop;
  } else if (relBottom > visibleBottom) {
    dd.scrollTop = relBottom - dd.clientHeight;
  }
}

export function pickSlashCommand(index) {
  if (index < 0 || index >= activeView.slashMatches.length) return;
  const cmd = activeView.slashMatches[index].cmd;
  activeView.dom.input.value = '';
  activeView.dom.input.style.height = 'auto';
  closeSlashDropdown();
  activeView.dom.input.focus();
  if (slashCommands[cmd] && slashCommands[cmd].run) slashCommands[cmd].run();
}

/** Delete a user-level skill after confirmation. */
function handleDeleteSkill(skillName) {
  const msg = t('slash.confirmDelete').replace('{skill}', skillName);
  window.__showConfirm?.('Delete Skill', msg, () => {
    sendWs({ type: 'deleteSkill', name: skillName });
    closeSlashDropdown();
  });
}
