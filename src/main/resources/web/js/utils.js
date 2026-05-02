import state from './state.js';

// === Lottie spinner JSON (rotating ring) ===
export const spinnerJson = {
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

// === Lottie spinner initialization ===
let lottieSpinner = null;

export function initSpinner() {
  lottieSpinner = lottie.loadAnimation({
    container: document.getElementById('lottie-spinner'),
    renderer: 'svg', loop: true, autoplay: false,
    animationData: spinnerJson
  });
}

export function playSpinner() {
  if (lottieSpinner) lottieSpinner.play();
}

export function stopSpinner() {
  if (lottieSpinner) lottieSpinner.stop();
}

// === Markdown initialization ===
export function initMarkdown() {
  marked.setOptions({ breaks: true, gfm: true, headerIds: false });
}

// === KaTeX math rendering — protect math blocks from Markdown processing ===
export function renderMarkdownWithMath(text) {
  if (!text) return '';
  if (typeof marked === 'undefined') return escapeHtml(text);
  const mathBlocks = [];
  // Protect display math ($$...$$)
  let protected_ = text.replace(/\$\$([\s\S]+?)\$\$/g, (m, math) => {
    mathBlocks.push({ display: true, math: math.trim() });
    return `MATHBLOCK${mathBlocks.length - 1}END`;
  });
  // Protect inline math ($...$), skip escaped \$
  // Note: uses capturing group instead of lookbehind for Safari < 16.4 compatibility
  protected_ = protected_.replace(/(^|[^\\])\$([^\$\n]+?)\$/g, (fullMatch, prefix, math) => {
    mathBlocks.push({ display: false, math: math.trim() });
    return prefix + `MATHBLOCK${mathBlocks.length - 1}END`;
  });
  let html = marked.parse(protected_, { headerIds: false });
  // Restore math blocks as KaTeX
  mathBlocks.forEach((block, i) => {
    const token = `MATHBLOCK${i}END`;
    if (typeof katex !== 'undefined') {
      try {
        const rendered = katex.renderToString(block.math, {
          displayMode: block.display,
          throwOnError: false,
          trust: true
        });
        html = html.replace(token, rendered);
      } catch (e) {
        html = html.replace(token, block.display ? `$$${block.math}$$` : `$${block.math}$`);
      }
    } else {
      html = html.replace(token, block.display ? `$$${block.math}$$` : `$${block.math}$`);
    }
  });
  return html;
}

// === HTML escaping ===
export function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// Shorthand alias for escapeHtml
export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

// === Diff formatting ===
export function formatDiff(content) {
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

// === Tool detail builder ===
export function buildToolDetail(inputJson, label) {
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

// === Tool card click handler (long-press to toggle body) ===
export function attachToolClick(card) {
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

// === Scroll helpers ===
export function shouldAutoScroll() {
  const chat = state.dom.chat;
  const threshold = 60;
  return chat.scrollHeight - chat.scrollTop - chat.clientHeight < threshold;
}

export function smartScroll() {
  const chat = state.dom.chat;
  requestAnimationFrame(() => {
    if (state.scrollSnapped || shouldAutoScroll()) {
      chat.scrollTop = chat.scrollHeight;
      state.scrollSnapped = true;
    }
  });
}
