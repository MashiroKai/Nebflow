// input.js 拆分（FE组件化批次四 2026-09-27）：slash 命令族（命令表+封存闸+注册+派发+自动补全） 可复用模块（行为保持）。
// #76 摘除批随簇迁入本模块（2026-10-10 合并移植）：/clear /compact 退役语义原样落位。
import { key } from '../branding.js';
import { activeView } from '../chatView.js';
import { sendWs } from '../ws.js';
import { escapeHtml } from '../utils.js';
import { t } from '../i18n.js';
import { enterAskMode, enterSkillMode } from './inputModes.js';

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
// 摘除（用户指令 2026-10-09 23:12）：/clear 与 /compact 已从命令面退役
// （白名单成员 + 内置表条目 + 引擎侧 case 一并摘除）；二者按封存面语义降级为
// 纯文本直发。表内保留的内置命令只剩 /model。
const SLASH_ALLOWED = new Set(['/model']);
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
  // 回挂面已摘除（用户指令 2026-10-09 23:12）：/clear 与 /compact 的内置表条目
  // （含下拉补全项与 desc 帮助文案）整体退役 —— 前身逐字形态见 git 历史
  // `71afa4a9b^:src/main/resources/web/js/input.js:33-50`；引擎侧
  // WsSessionChatHandlers.handleCommand 的 clear/compact case 同批摘除。
};

/**
 * Register a UI-only slash command (mirrors the registerSkillCommands
 * table-insertion pattern). uiOnly = the run() opens UI / flips local state
 * and never injects a message, so the busy-session pass-through (send path)
 * applies just like /model. The admission whitelist stays the literal
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
// (whitelisted alone since 2026-10-09: /clear /compact were removed from the
// command face by user instruction). The panel module is
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
  if (!slashAllowed(cmd)) return false; // SEALED: '/' is plain text（白名单命令除外）
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
