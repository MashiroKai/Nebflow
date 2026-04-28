// === Lottie spinner JSON (rotating ring) ===
const spinnerJson = {
  "v":"5.7.4","fr":60,"ip":0,"op":60,"w":48,"h":48,"nm":"spinner","ddd":0,"assets":[],
  "layers":[{
    "ddd":0,"ind":1,"ty":4,"nm":"ring","sr":1,
    "ks":{
      "o":{"a":0,"k":100},
      "r":{"a":1,"k":[{"i":{"x":[0.833],"y":[0.833]},"o":{"x":[0.167],"y":[0.167]},"t":0,"s":[0]},{"t":60,"s":[360]}]},
      "p":{"a":0,"k":[24,24,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}
    },
    "ao":0,
    "shapes":[{
      "ty":"gr",
      "it":[
        {"ty":"el","d":1,"s":{"a":0,"k":[36,36]},"p":{"a":0,"k":[0,0]}},
        {"ty":"st","c":{"a":0,"k":[1,1,1,1]},"o":{"a":0,"k":100},"w":{"a":0,"k":3},"lc":2,"lj":2,"d":[{"n":"d","v":{"a":0,"k":90}},{"n":"g","v":{"a":0,"k":50}}]},
        {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}
      ]
    }],
    "ip":0,"op":60,"st":0,"bm":0
  }]
};

marked.setOptions({ breaks: true, gfm: true, headerIds: false });

const lottieSpinner = lottie.loadAnimation({
  container: document.getElementById('lottie-spinner'),
  renderer: 'svg', loop: true, autoplay: false,
  animationData: spinnerJson
});
lucide.createIcons();

const chat = document.getElementById('chat');
const input = document.getElementById('input');
const sendBtn = document.getElementById('send-btn');
const stopBtn = document.getElementById('stop-btn');
const voiceBtn = document.getElementById('voice-btn');
const attachBtn = document.getElementById('attach-btn');
const attPreview = document.getElementById('attachment-preview');
const statusWrap = document.getElementById('status-wrap');
const statusText = document.getElementById('status-text');
const connEl = document.getElementById('conn');
const voiceOverlay = document.getElementById('voice-overlay');
const voiceText = document.getElementById('voice-text');

const LS_KEY = 'nebflow_v3';
const LS_THINKING_KEY = 'nebflow_thinking';
let currentAiBubble = null;
let aiText = '';
let busy = false;
let ws = null;
let recognition = null;
let pendingAttachments = [];
let thinkingMode = JSON.parse(localStorage.getItem(LS_THINKING_KEY) || 'null');

// ---------- Persistence ----------
function saveMsg(entry) {
  const arr = JSON.parse(localStorage.getItem(LS_KEY) || '[]');
  arr.push(entry);
  localStorage.setItem(LS_KEY, JSON.stringify(arr));
}
function loadMsgs() {
  return JSON.parse(localStorage.getItem(LS_KEY) || '[]');
}

// ---------- Rendering ----------
function renderUserBubble(text, attachments) {
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  if (text) {
    const t = document.createElement('div');
    t.textContent = text;
    bubble.appendChild(t);
  }
  (attachments || []).forEach(att => {
    if (att.type === 'image' && att.preview) {
      const img = document.createElement('img');
      img.src = att.preview;
      bubble.appendChild(img);
    } else {
      const tag = document.createElement('span');
      tag.className = 'file-tag';
      tag.textContent = 'file: ' + (att.name || 'file');
      bubble.appendChild(tag);
    }
  });
  row.appendChild(bubble);
  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
  saveMsg({type:'user', text, attachments: (attachments||[]).map(a=>({type:a.type,name:a.name}))});
}

function appendAiText(text) {
  aiText += text;
  if (currentAiBubble && currentAiBubble.classList.contains('thinking-placeholder')) {
    currentAiBubble.classList.remove('thinking-placeholder');
    currentAiBubble.innerHTML = '';
  }
  if (!currentAiBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    currentAiBubble = document.createElement('div');
    currentAiBubble.className = 'bubble ai';
    row.appendChild(currentAiBubble);
    chat.appendChild(row);
  }
  const askBox = currentAiBubble.querySelector('.option-box');
  if (askBox) askBox.remove();
  const cursor = '<span class="cursor"></span>';
  currentAiBubble.innerHTML = marked.parse(aiText || '', {headerIds: false}) + cursor;
  if (askBox) currentAiBubble.appendChild(askBox);
  chat.scrollTop = chat.scrollHeight;
}
function finishAi() {
  if (currentAiBubble) {
    const askBox = currentAiBubble.querySelector('.option-box');
    if (askBox) askBox.remove();
    currentAiBubble.innerHTML = marked.parse(aiText || '', {headerIds: false});
    if (askBox) currentAiBubble.appendChild(askBox);
    saveMsg({type:'ai', text: aiText});
    currentAiBubble = null;
    aiText = '';
  }
}

let currentToolCard = null;

// Small lottie spinner for tool pending state
const toolSpinnerJson = {
  "v":"5.7.4","fr":60,"ip":0,"op":60,"w":14,"h":14,"nm":"tool-spinner","ddd":0,"assets":[],
  "layers":[{
    "ddd":0,"ind":1,"ty":4,"nm":"ring","sr":1,
    "ks":{
      "o":{"a":0,"k":100},
      "r":{"a":1,"k":[{"i":{"x":[0.833],"y":[0.833]},"o":{"x":[0.167],"y":[0.167]},"t":0,"s":[0]},{"t":60,"s":[360]}]},
      "p":{"a":0,"k":[7,7,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[29,29,100]}
    },
    "ao":0,
    "shapes":[{
      "ty":"gr",
      "it":[
        {"ty":"el","d":1,"s":{"a":0,"k":[36,36]},"p":{"a":0,"k":[0,0]}},
        {"ty":"st","c":{"a":0,"k":[0.3,0.3,0.3,1]},"o":{"a":0,"k":100},"w":{"a":0,"k":3},"lc":2,"lj":2,"d":[{"n":"d","v":{"a":0,"k":90}},{"n":"g","v":{"a":0,"k":50}}]},
        {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}
      ]
    }],
    "ip":0,"op":60,"st":0,"bm":0
  }]
};

function formatDiff(content) {
  if (!content) return null;
  const lines = content.split('\n');
  // Single-line-number diff: "lineno |content" or "lineno |-content" or "lineno |+content"
  const isLineDiff = lines.some(l => /^\s*\d+\s*\|[+-]?/.test(l));
  if (!isLineDiff) return null;
  const html = lines.map(line => {
    const m = line.match(/^(\s*\d+)\s*\|(.*)$/);
    if (!m) return '<div class="diff-line"><span class="diff-lineno"></span><span class="diff-content">' + esc(line) + '</span></div>';
    let cls = 'diff-content';
    const text = m[2];
    if (text.startsWith('-')) cls = 'diff-content diff-del';
    else if (text.startsWith('+')) cls = 'diff-content diff-add';
    return '<div class="diff-line"><span class="diff-lineno">' + esc(m[1].trim()) + '</span><span class="' + cls + '">' + esc(text) + '</span></div>';
  }).join('');
  return '<pre>' + html + '</pre>';
}

function buildToolDetail(inputJson, label) {
  if (!inputJson) return '';
  try {
    const input = JSON.parse(inputJson);
    const parts = [];
    if (input.file_path) parts.push('file: ' + input.file_path);
    if (input.command) parts.push('cmd: ' + input.command);
    if (input.url) parts.push('url: ' + input.url);
    if (input.query) parts.push('query: ' + input.query);
    if (input.pattern) parts.push('pattern: ' + input.pattern);
    if (input.path) parts.push('path: ' + input.path);
    if (parts.length === 0) return '';
    return '<div class="detail-cmd">' + esc(parts.join('  ')) + '</div>';
  } catch (e) {
    return '';
  }
}

function attachToolClick(card) {
  let pressTimer = null;
  let pressStart = 0;
  const LONG_PRESS_MS = 400;

  function onMouseDown(e) {
    pressStart = Date.now();
    pressTimer = setTimeout(() => { pressTimer = null; }, LONG_PRESS_MS);
  }
  function onMouseUp(e) {
    const duration = Date.now() - pressStart;
    if (pressTimer && duration < LONG_PRESS_MS) {
      const body = card.querySelector('.body');
      if (body) body.classList.toggle('open');
    }
    clearTimeout(pressTimer);
    pressTimer = null;
  }
  function onTouchStart(e) {
    pressStart = Date.now();
    pressTimer = setTimeout(() => { pressTimer = null; }, LONG_PRESS_MS);
  }
  function onTouchEnd(e) {
    const duration = Date.now() - pressStart;
    if (pressTimer && duration < LONG_PRESS_MS) {
      const body = card.querySelector('.body');
      if (body) body.classList.toggle('open');
    }
    clearTimeout(pressTimer);
    pressTimer = null;
  }

  card.addEventListener('mousedown', onMouseDown);
  card.addEventListener('mouseup', onMouseUp);
  card.addEventListener('touchstart', onTouchStart, {passive:true});
  card.addEventListener('touchend', onTouchEnd);
}

function renderTool(label, summary, content, isError, inputJson) {
  if (currentToolCard) {
    currentToolCard.remove();
    currentToolCard = null;
  }
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const diffHtml = formatDiff(content);
  const detailHtml = buildToolDetail(inputJson, label);
  const bodyText = diffHtml ? '' : (content ? esc(content.length > 120 ? content.slice(0,120) + '...' : content) : '');
  const bodyHtml = (detailHtml + (diffHtml || (bodyText ? '<pre>' + bodyText + '</pre>' : ''))) || '';
  const hasBody = !!bodyHtml;
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + esc(label) + ' &mdash; ' + esc(summary) + '</div>' +
    (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
  row.appendChild(card);
  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;

  if (hasBody) attachToolClick(card);
  saveMsg({type:'tool', label, summary, content, isError, input: inputJson});
}

function renderToolPending(label) {
  if (currentAiBubble && currentAiBubble.classList.contains('thinking-placeholder')) {
    const row = currentAiBubble.closest('.row');
    if (row) row.remove();
    currentAiBubble = null;
    aiText = '';
  }
  if (currentToolCard) currentToolCard.remove();
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  card.style.background = '#e8e8e8';
  const spinnerId = 'tool-spin-' + Date.now();
  card.innerHTML = '<span class="icon"><div style="width:14px;height:14px;" id="' + spinnerId + '"></div></span>' +
    '<div class="content"><div class="label">' + esc(label) + '</div></div>';
  row.appendChild(card);
  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
  currentToolCard = row;
  lottie.loadAnimation({
    container: document.getElementById(spinnerId),
    renderer: 'svg', loop: true, autoplay: true,
    animationData: toolSpinnerJson
  });
}
function renderError(msg) {
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  card.textContent = msg;
  row.appendChild(card);
  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
  saveMsg({type:'error', content: msg});
}

// ---------- Universal Option Box (AskUser / Permission / SlashCmd) ----------
// Renders an inline option picker. Used by AskUser tool, /thinking, permission prompts.
function showOptions(container, questions, onConfirm, doneLabel, onCancel) {
  const box = document.createElement('div');
  box.className = 'option-box';
  const answers = new Array(questions.length).fill(null);
  const confirmLabel = doneLabel || 'Confirm';

  questions.forEach((item, qi) => {
    const q = document.createElement('div');
    q.className = 'option-q';
    q.textContent = item.question;
    box.appendChild(q);

    const optsDiv = document.createElement('div');
    optsDiv.className = 'option-opts';
    item.options.forEach((opt, oi) => {
      const btn = document.createElement('button');
      btn.className = 'option-btn';
      const isStr = typeof opt === 'string';
      const label = isStr ? opt : opt.label;
      const desc = isStr ? '' : (opt.desc || opt.description || '');
      btn.innerHTML = esc(label) + (desc ? '<div style="font-size:11px;color:#888;margin-top:2px;font-weight:normal;">' + esc(desc) + '</div>' : '');
      btn.onclick = () => {
        answers[qi] = label;
        optsDiv.querySelectorAll('.option-btn').forEach((el, i) => {
          el.classList.toggle('picked', i === oi);
        });
        checkAllAnswered();
      };
      optsDiv.appendChild(btn);
    });
    box.appendChild(optsDiv);
  });

  const btnRow = document.createElement('div');
  btnRow.className = 'option-btn-row';

  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'option-cancel';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.onclick = () => {
    box.querySelectorAll('.option-btn, .option-confirm').forEach(el => { el.disabled = true; });
    cancelBtn.disabled = true;
    confirmBtn.disabled = true;
    if (onCancel) onCancel();
  };

  const confirmBtn = document.createElement('button');
  confirmBtn.className = 'option-confirm';
  confirmBtn.innerHTML = '<i data-lucide="check"></i><span>' + esc(confirmLabel) + '</span>';
  confirmBtn.disabled = true;
  confirmBtn.onclick = () => {
    box.querySelectorAll('.option-btn').forEach(el => { el.disabled = true; });
    cancelBtn.disabled = true;
    confirmBtn.disabled = true;
    confirmBtn.style.display = 'none';
    cancelBtn.style.display = 'none';

    const ansDiv = document.createElement('div');
    ansDiv.className = 'option-answer';
    ansDiv.textContent = '-> ' + answers.join(', ');
    box.appendChild(ansDiv);

    if (onConfirm) onConfirm(answers);
  };
  btnRow.appendChild(cancelBtn);
  btnRow.appendChild(confirmBtn);
  box.appendChild(btnRow);
  container.appendChild(box);
  if (typeof lucide !== 'undefined') lucide.createIcons();
  chat.scrollTop = chat.scrollHeight;

  function checkAllAnswered() {
    confirmBtn.disabled = !answers.every(a => a !== null);
  }
}

function renderAskUser(items) {
  // Create a standalone bubble for AskUser, not tied to currentAiBubble
  // so LLM response appears below after user confirms
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  row.appendChild(bubble);
  chat.appendChild(row);
  showOptions(bubble, items, (answers) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({type:'askUserAnswer', answers}));
    }
  }, 'Confirm', () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({type:'askUserAnswer', answers: ['__cancelled__']}));
    }
  });
  saveMsg({type:'askUser', items});
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
function setStatus(text) {
  statusText.textContent = text || '';
  statusWrap.classList.add('on');
  lottieSpinner.play();
}
function clearStatus() {
  statusWrap.classList.remove('on');
  lottieSpinner.stop();
}
function setBusy(b) {
  busy = b;
  input.disabled = b;
  if (b) {
    sendBtn.style.display = 'none';
    stopBtn.style.display = 'flex';
  } else {
    sendBtn.style.display = 'flex';
    stopBtn.style.display = 'none';
    input.focus();
  }
}

// ---------- Slash Commands ----------
const slashCommands = {
  '/clear': {
    desc: 'Clear LLM context (keeps chat history)',
    run: () => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({type:'command', command:'clear'}));
      }
      renderSystemBubble('Context cleared. LLM memory reset.');
    }
  },
  '/thinking': {
    desc: 'Toggle extended thinking (Deep mode)',
    run: () => {
      if (!currentAiBubble) {
        const row = document.createElement('div');
        row.className = 'row ai';
        currentAiBubble = document.createElement('div');
        currentAiBubble.className = 'bubble ai';
        row.appendChild(currentAiBubble);
        chat.appendChild(row);
      }
      showOptions(currentAiBubble, [
        {question: 'Extended thinking', options: [
          {label:'Enable', desc:'Deep analysis with thinking tokens'},
          {label:'Disable', desc:'Normal response mode'}
        ]}
      ], (answers) => {
        const mode = answers[0];
        thinkingMode = mode === 'Enable' ? {type: 'enabled', budget_tokens: 16000} : null;
        localStorage.setItem(LS_THINKING_KEY, JSON.stringify(thinkingMode));
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({type: 'setThinking', thinking: thinkingMode}));
        }
        renderSystemBubble('Thinking: ' + mode.toLowerCase());
      }, 'Confirm');
    }
  }
};

function renderSystemBubble(text) {
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  card.style.background = '#e3f2fd';
  card.style.color = '#1565c0';
  card.textContent = text;
  row.appendChild(card);
  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
  saveMsg({type:'system', content: text});
}

function handleSlash(text) {
  const cmd = text.trim().split(/\s/)[0];
  if (slashCommands[cmd]) {
    slashCommands[cmd].run();
    return true;
  }
  return false;
}

// ---------- Slash Autocomplete ----------
const slashDropdown = document.getElementById('slash-dropdown');
let slashSelectedIndex = -1;
let slashMatches = [];

function updateSlashDropdown() {
  const text = input.value;
  if (!text.startsWith('/')) {
    closeSlashDropdown();
    return;
  }
  const query = text.slice(1).toLowerCase();
  slashMatches = Object.entries(slashCommands)
    .filter(([cmd]) => cmd.slice(1).toLowerCase().startsWith(query))
    .map(([cmd, info]) => ({ cmd, desc: info.desc }));
  if (slashMatches.length === 0) {
    closeSlashDropdown();
    return;
  }
  slashDropdown.innerHTML = '';
  slashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    div.innerHTML = '<span class="slash-cmd">' + esc(item.cmd) + '</span><span class="slash-desc">' + esc(item.desc) + '</span>';
    div.onclick = () => { pickSlashCommand(i); };
    div.onmouseenter = () => { setSlashHighlight(i); };
    slashDropdown.appendChild(div);
  });
  slashSelectedIndex = 0;
  slashDropdown.classList.add('on');
}
function closeSlashDropdown() {
  slashDropdown.classList.remove('on');
  slashSelectedIndex = -1;
  slashMatches = [];
}
function setSlashHighlight(index) {
  slashSelectedIndex = index;
  const items = slashDropdown.querySelectorAll('.slash-item');
  items.forEach((el, i) => { el.classList.toggle('active', i === index); });
}
function pickSlashCommand(index) {
  if (index < 0 || index >= slashMatches.length) return;
  const cmd = slashMatches[index].cmd;
  input.value = '';
  closeSlashDropdown();
  input.focus();
  if (slashCommands[cmd]) slashCommands[cmd].run();
}
input.addEventListener('input', updateSlashDropdown);
document.addEventListener('click', (e) => {
  if (!input.contains(e.target) && !slashDropdown.contains(e.target)) {
    closeSlashDropdown();
  }
});

// ---------- Restore ----------
function restoreFromStorage() {
  const msgs = loadMsgs();
  msgs.forEach(m => {
    if (m.type === 'user') {
      const row = document.createElement('div');
      row.className = 'row user';
      const bubble = document.createElement('div');
      bubble.className = 'bubble user';
      if (m.text) {
        const t = document.createElement('div');
        t.textContent = m.text;
        bubble.appendChild(t);
      }
      (m.attachments || []).forEach(att => {
        const tag = document.createElement('span');
        tag.className = 'file-tag';
        tag.textContent = 'file: ' + (att.name || 'file');
        bubble.appendChild(tag);
      });
      row.appendChild(bubble);
      chat.appendChild(row);
    } else if (m.type === 'ai') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      bubble.innerHTML = marked.parse(m.text || '', {headerIds: false});
      row.appendChild(bubble);
      chat.appendChild(row);
    } else if (m.type === 'tool') {
      // Inline render to avoid triggering saveMsg again
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      const isError = m.isError;
      const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                           : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
      const diffHtml = formatDiff(m.content);
      const detailHtml = buildToolDetail(m.input, m.label);
      const bodyText = diffHtml ? '' : (m.content ? esc(m.content.length > 120 ? m.content.slice(0,120) + '...' : m.content) : '');
      const bodyHtml = (detailHtml + (diffHtml || (bodyText ? '<pre>' + bodyText + '</pre>' : ''))) || '';
      const hasBody = !!bodyHtml;
      card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
        '<div class="content"><div class="label">' + esc(m.label) + ' &mdash; ' + esc(m.summary) + '</div>' +
        (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
      row.appendChild(card);
      chat.appendChild(row);
      if (hasBody) attachToolClick(card);
    } else if (m.type === 'askUser') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      row.appendChild(bubble);
      chat.appendChild(row);
      showOptions(bubble, m.items, null, 'Confirm');
      // Disable all buttons since this is historical
      bubble.querySelectorAll('.option-btn, .option-confirm').forEach(el => {
        el.disabled = true;
        el.style.opacity = '0.5';
      });
    } else if (m.type === 'error') {
      renderError(m.content);
    } else if (m.type === 'system') {
      const row = document.createElement('div');
      row.className = 'row error';
      const card = document.createElement('div');
      card.className = 'error-card';
      card.style.background = '#e3f2fd';
      card.style.color = '#1565c0';
      card.textContent = m.content;
      row.appendChild(card);
      chat.appendChild(row);
    }
  });
  chat.scrollTop = chat.scrollHeight;
}

// ---------- Attachments ----------
function renderAttachmentPreview() {
  attPreview.innerHTML = '';
  pendingAttachments.forEach((att, idx) => {
    if (att.type === 'image' && att.preview) {
      const wrap = document.createElement('div');
      wrap.style.position = 'relative';
      const img = document.createElement('img');
      img.src = att.preview;
      img.className = 'att-thumb';
      wrap.appendChild(img);
      const rm = document.createElement('div');
      rm.className = 'att-remove';
      rm.textContent = 'x';
      rm.onclick = () => {
        pendingAttachments.splice(idx, 1);
        renderAttachmentPreview();
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    } else {
      const wrap = document.createElement('div');
      wrap.className = 'att-file';
      wrap.innerHTML = '<span>[f]</span><span>' + esc(att.name) + '</span>';
      const rm = document.createElement('span');
      rm.textContent = ' x';
      rm.style.cursor = 'pointer';
      rm.style.color = '#f44336';
      rm.onclick = () => {
        pendingAttachments.splice(idx, 1);
        renderAttachmentPreview();
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    }
  });
}

attachBtn.onclick = () => {
  const f = document.createElement('input');
  f.type = 'file';
  f.accept = 'image/*,.txt,.md,.json,.scala,.js,.ts,.py,.java,.go,.rs,.csv,.xml,.yaml,.yml,.html,.css';
  f.onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = () => {
        pendingAttachments.push({
          type: 'image', mimeType: file.type,
          data: reader.result.split(',')[1],
          name: file.name, preview: reader.result
        });
        renderAttachmentPreview();
      };
      reader.readAsDataURL(file);
    } else {
      const reader = new FileReader();
      reader.onload = () => {
        pendingAttachments.push({
          type: 'text', mimeType: file.type || 'text/plain',
          data: reader.result, name: file.name
        });
        renderAttachmentPreview();
      };
      reader.readAsText(file);
    }
  };
  f.click();
};

// ---------- Voice ----------
function startVoice(e) {
  e.preventDefault();
  if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
    alert('Voice input not supported in this browser. Try Chrome.');
    return;
  }
  recognition = new (window.SpeechRecognition || window.webkitSpeechRecognition)();
  recognition.lang = 'zh-CN';
  recognition.continuous = true;
  recognition.interimResults = true;
  voiceOverlay.classList.add('on');
  voiceText.textContent = 'Listening...';

  recognition.onresult = (ev) => {
    let final = '';
    let interim = '';
    for (let i = ev.resultIndex; i < ev.results.length; i++) {
      const t = ev.results[i][0].transcript;
      if (ev.results[i].isFinal) final += t;
      else interim += t;
    }
    const display = (final + interim).trim();
    voiceText.textContent = display || 'Listening...';
    if (final) input.value = final;
    else if (interim) input.value = interim;
  };
  recognition.onerror = (ev) => {
    voiceText.textContent = 'Error: ' + ev.error;
    setTimeout(() => stopVoice(), 1000);
  };
  recognition.onend = () => {
    voiceOverlay.classList.remove('on');
    voiceBtn.classList.remove('recording');
  };
  recognition.start();
  voiceBtn.classList.add('recording');
}
function stopVoice() {
  if (recognition) { recognition.stop(); recognition = null; }
  voiceOverlay.classList.remove('on');
  voiceBtn.classList.remove('recording');
}
voiceBtn.addEventListener('mousedown', startVoice);
voiceBtn.addEventListener('mouseup', stopVoice);
voiceBtn.addEventListener('mouseleave', stopVoice);
voiceBtn.addEventListener('touchstart', startVoice, {passive:false});
voiceBtn.addEventListener('touchend', stopVoice);

// ---------- WebSocket ----------
function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(`${proto}//${location.host}/ws`);
  ws.onopen = () => {
    connEl.classList.remove('off');
    // Restore persisted thinking mode
    if (thinkingMode) {
      ws.send(JSON.stringify({type: 'setThinking', thinking: thinkingMode}));
    }
  };
  ws.onclose = () => {
    connEl.classList.add('off');
    setTimeout(connect, 2000);
  };
  ws.onerror = () => { connEl.classList.add('off'); };
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    switch(msg.type) {
      case 'textDelta':
        appendAiText(msg.delta);
        break;
      case 'textDone':
        finishAi();
        break;
      case 'toolStart':
        renderToolPending(msg.label);
        break;
      case 'thinking':
        // Show thinking spinner in a new AI bubble if none exists
        if (!currentAiBubble) {
          const row = document.createElement('div');
          row.className = 'row ai';
          currentAiBubble = document.createElement('div');
          currentAiBubble.className = 'bubble ai thinking-placeholder';
          currentAiBubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
          row.appendChild(currentAiBubble);
          chat.appendChild(row);
          chat.scrollTop = chat.scrollHeight;
        }
        break;
      case 'toolEnd':
        if (msg.label && msg.label.startsWith('AskUser')) break;
        renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input);
        break;
      case 'done':
        finishAi();
        setBusy(false);
        clearStatus();
        break;
      case 'error':
        finishAi();
        renderError(msg.message);
        setBusy(false);
        clearStatus();
        break;
      case 'interrupted':
        finishAi();
        setBusy(false);
        clearStatus();
        break;
      case 'askUser':
        renderAskUser(msg.items);
        break;
    }
  };
}

// ---------- Send ----------
function send() {
  const text = input.value.trim();
  if ((!text && pendingAttachments.length === 0) || busy) return;
  if (handleSlash(text)) {
    input.value = '';
    return;
  }
  renderUserBubble(text, pendingAttachments);
  ws.send(JSON.stringify({
    content: text,
    attachments: pendingAttachments.map(a => ({
      mimeType: a.mimeType, data: a.data, name: a.name
    }))
  }));
  input.value = '';
  pendingAttachments = [];
  attPreview.innerHTML = '';
  setBusy(true);
  currentAiBubble = null;
  aiText = '';
}

sendBtn.onclick = send;
stopBtn.onclick = () => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({content: '__interrupt__'}));
  }
};

let composing = false;
input.addEventListener('compositionstart', () => { composing = true; });
input.addEventListener('compositionend', () => { composing = false; });
input.onkeydown = (e) => {
  if (slashDropdown.classList.contains('on')) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSlashHighlight((slashSelectedIndex + 1) % slashMatches.length);
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSlashHighlight((slashSelectedIndex - 1 + slashMatches.length) % slashMatches.length);
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      pickSlashCommand(slashSelectedIndex);
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      closeSlashDropdown();
      return;
    }
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    if (composing || e.isComposing || e.keyCode === 229) {
      e.preventDefault();
      return;
    }
    e.preventDefault();
    send();
  }
};

// Init
restoreFromStorage();
connect();
input.focus();
