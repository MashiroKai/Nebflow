// workspacePicker.js — 应用内目录浏览器（workspace-picker 批次的目录选择件）。
//
// 触发：ProjectCreate「选择工作区」卡点击 → chat.js 直接打开本弹窗（2026-09-06
// 作者拍板：复用应用内目录浏览器，不走系统目录对话框）。也可复用于其他需要目录
// 选择的场景（openPicker({sessionId, onPick, onCancel})）。
//
// 链路：sendWs({type:'wsBrowse.list', path}) → wsBrowseList{path,home,entries,error}
//       sendWs({type:'wsBrowse.mkdir', path, name}) → wsBrowseMkdir{path,ok,error}
// 响应帧走 GLOBAL 路由（无 sessionId 不换视图）；本模块单例弹窗 + 单一在飞请求，
// 动态 onMessage 订阅 + 超时兜底（probeCanvasFile 先例）。
//
// 交互：面包屑导航（蚀刻条内联 " / " 分隔，逐级可点，home 折叠为「主目录」，当前段
// 纯文本不可点）/ 上级 = 列表首行 ".." 项（path-picker 基准形态）/ 新建文件夹
// （footer 左侧入口，列表顶部行内输入，Enter 创建 / Esc 取消，创建成功自动进入新
// 目录）/ 点行进目录 / 「选中此目录」确认当前目录 → onPick(path)。
//
// 视觉：逐值对齐 path-picker 文件浏览器模态（设计基准，modal.css #path-picker-* /
// .pp-item 族）——共享玻璃卡（.wsp-panel 挂进 modal.css 共享选择器组）、蚀刻面包屑
// 条、蚀刻列表容器 + hairline 行、footer 中性钮 + sapphire 主钮。布局样式 .wsp-*
// 见 chat.css；面板材质由 modal.css 共享玻璃卡组承载，亮暗双主题随全局 token。
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';
import state from './state.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

let openCtx = null; // 当前打开的弹窗上下文（单例重入保护）

/** 请求目录列表；单一在飞，4s 超时兜底（probeCanvasFile 同款形态）。 */
function listDir(sessionId, path) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; unsub(); resolve(v); } };
    const unsub = onMessage('wsBrowseList', (msg) => finish(msg));
    setTimeout(() => finish({ error: 'timeout' }), 4000);
    sendWs({ type: 'wsBrowse.list', path, sessionId });
  });
}

/** 新建文件夹；返回 {ok, path?, error?}。 */
function mkdir(sessionId, path, name) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; unsub(); resolve(v); } };
    const unsub = onMessage('wsBrowseMkdir', (msg) => finish(msg));
    setTimeout(() => finish({ ok: false, error: 'timeout' }), 4000);
    sendWs({ type: 'wsBrowse.mkdir', path, name, sessionId });
  });
}

/** 从绝对路径推导面包屑（home 前缀折叠为「主目录」单节）。 */
function buildCrumbs(path, home) {
  if (!path) return [];
  if (home && (path === home || path.startsWith(home + '/'))) {
    const rest = path === home ? '' : path.slice(home.length);
    const segs = rest.split('/').filter(Boolean);
    const crumbs = [{ label: t('workspacePicker.home'), path: home }];
    let acc = home;
    for (const s of segs) { acc = (acc === '/' ? '' : acc) + '/' + s; crumbs.push({ label: s, path: acc }); }
    return crumbs;
  }
  // 不在 home 下：从根逐级展开
  const segs = path.split('/').filter(Boolean);
  const crumbs = [{ label: '/', path: '/' }];
  let acc = '';
  for (const s of segs) { acc += '/' + s; crumbs.push({ label: s, path: acc }); }
  return crumbs;
}

/**
 * 打开目录浏览器弹窗（单例：重开时先关旧的并触发旧 onCancel）。
 * @param {object} opts { sessionId, startPath='~', onPick(path), onCancel() }
 */
export function openPicker(opts = {}) {
  closePicker();
  const ctx = {
    sessionId: opts.sessionId || (state.activeSessionId ?? undefined),
    onPick: opts.onPick || (() => {}),
    onCancel: opts.onCancel || (() => {}),
    current: opts.startPath || '~',
    home: '',
  };

  const overlay = document.createElement('div');
  overlay.className = 'wsp-overlay';
  overlay.innerHTML =
    '<div class="wsp-panel" role="dialog" aria-modal="true" aria-label="' + escapeHtml(t('workspacePicker.browseTitle')) + '">' +
      '<div class="wsp-title"></div>' +
      '<div class="wsp-crumbs"></div>' +
      '<div class="wsp-list"></div>' +
      '<div class="wsp-foot">' +
        '<button type="button" class="wsp-mkdir">+ ' + escapeHtml(t('workspacePicker.newFolder')) + '</button>' +
        '<span class="wsp-cur"></span>' +
        '<span class="wsp-foot-btns">' +
          '<button type="button" class="wsp-cancel">' + escapeHtml(t('workspacePicker.cancel')) + '</button>' +
          '<button type="button" class="wsp-pick">' + escapeHtml(t('workspacePicker.selectHere')) + '</button>' +
        '</span>' +
      '</div>' +
    '</div>';
  overlay.querySelector('.wsp-title').textContent = t('workspacePicker.browseTitle');
  document.body.appendChild(overlay);

  const listEl = overlay.querySelector('.wsp-list');
  const crumbsEl = overlay.querySelector('.wsp-crumbs');
  const curEl = overlay.querySelector('.wsp-cur');
  openCtx = ctx;

  const close = (picked) => {
    if (openCtx !== ctx) return;
    openCtx = null;
    overlay.remove();
    if (picked) ctx.onPick(picked); else ctx.onCancel();
  };

  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(null); }); // 点遮罩 = 取消
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-cancel')).onclick = () => close(null);
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-pick')).onclick = () => close(ctx.current);
  /** @type {HTMLElement} */ (overlay.querySelector('.wsp-mkdir')).onclick = () => startMkdir();

  /** 渲染面包屑（path-picker 基准形态：可点段 sapphire 链接，当前段纯文本，" / " 分隔）。 */
  function renderCrumbs() {
    crumbsEl.innerHTML = '';
    const crumbs = buildCrumbs(ctx.current, ctx.home);
    crumbs.forEach((c, i) => {
      if (i > 0) crumbsEl.appendChild(document.createTextNode(' / '));
      if (i === crumbs.length - 1) {
        crumbsEl.appendChild(document.createTextNode(c.label)); // 当前段：纯文本不可点
      } else {
        const seg = document.createElement('span');
        seg.className = 'wsp-crumb';
        seg.textContent = c.label;
        seg.title = c.path;
        seg.addEventListener('click', () => navigate(c.path));
        crumbsEl.appendChild(seg);
      }
    });
  }

  /** 行内新建文件夹：列表顶部插入输入行（不弹 prompt），Enter 创建 / Esc 取消。 */
  function startMkdir() {
    if (listEl.querySelector('.wsp-mkdir-row')) return;
    const row = document.createElement('div');
    row.className = 'wsp-row wsp-mkdir-row';
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'wsp-mkdir-input';
    input.placeholder = t('workspacePicker.folderPlaceholder');
    const ok = document.createElement('button');
    ok.type = 'button';
    ok.className = 'wsp-mkdir-ok';
    ok.textContent = t('workspacePicker.create');
    const no = document.createElement('button');
    no.type = 'button';
    no.className = 'wsp-mkdir-no';
    no.textContent = '×';
    row.append(input, ok, no);
    listEl.prepend(row);
    input.focus();
    const submit = async () => {
      const name = input.value.trim();
      if (!name) { row.remove(); return; }
      const res = await mkdir(ctx.sessionId, ctx.current, name);
      if (openCtx !== ctx) return;
      if (res && res.ok && res.path) {
        navigate(res.path); // 创建成功直接进入新目录
      } else {
        row.remove();
        curEl.textContent = t('workspacePicker.mkdirFail') + (res && res.error ? ' (' + res.error + ')' : '');
      }
    };
    ok.onclick = submit;
    no.onclick = () => row.remove();
    // ⑤ 组字期间 Enter/Esc 交还输入法（非组字态行为逐键不变）。
    bindImeGuard(input);
    input.addEventListener('keydown', (e) => {
      if (isImeComposing(e, input)) return;
      if (e.key === 'Enter') submit();
      else if (e.key === 'Escape') row.remove();
    });
  }

  /** 请求目录并渲染列表（面包屑同步；首行 ".." 上级项，path-picker 基准形态）。 */
  async function navigate(path) {
    if (openCtx !== ctx) return;
    ctx.current = path;
    curEl.textContent = ''; // 路径由面包屑承载；本元素仅作 mkdir 失败等瞬时报错
    listEl.innerHTML = '<div class="wsp-row wsp-empty">' + escapeHtml(t('workspacePicker.loading')) + '</div>';
    const res = await listDir(ctx.sessionId, path);
    if (openCtx !== ctx) return; // 已关闭/重开：丢弃旧响应

    if (res && res.error) {
      listEl.innerHTML = '<div class="wsp-row wsp-empty">' + escapeHtml(t('workspacePicker.readFail')) + '</div>';
      curEl.textContent = t('workspacePicker.readFail') + ' (' + res.error + ')';
      return;
    }
    if (res.home) ctx.home = res.home;
    ctx.current = res.path || path;
    renderCrumbs();

    listEl.innerHTML = '';
    // 上级目录项（文件系统根隐藏）——path-picker 基准：列表首行 ".." 项
    if (ctx.current !== '/') {
      const parentRow = document.createElement('button');
      parentRow.type = 'button';
      parentRow.className = 'wsp-row wsp-dir wsp-up-row';
      parentRow.innerHTML =
        '<span class="wsp-row-icon">..</span>' +
        '<span class="wsp-row-name">..</span>';
      parentRow.onclick = () => {
        const parts = ctx.current.replace(/\/$/, '').split('/');
        parts.pop();
        navigate(parts.join('/') || '/');
      };
      listEl.appendChild(parentRow);
    }

    const entries = res.entries || [];
    if (!entries.length) {
      const empty = document.createElement('div');
      empty.className = 'wsp-row wsp-empty';
      empty.textContent = t('workspacePicker.empty');
      listEl.appendChild(empty);
      return;
    }
    for (const name of entries) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'wsp-row wsp-dir';
      row.innerHTML =
        '<span class="wsp-row-icon">' +
          '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7a2 2 0 0 1 2-2h4l2.2 2.5H19a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>' +
        '</span>' +
        '<span class="wsp-row-name">' + escapeHtml(name) + '</span>';
      const child = (ctx.current === '/' ? '' : ctx.current) + '/' + name;
      row.onclick = () => navigate(child);
      listEl.appendChild(row);
    }
  }

  navigate(ctx.current); // 首屏：主目录（后端展开 ~）
}

/** 关闭当前弹窗（若有）并触发其 onCancel——测试/重入用。 */
export function closePicker() {
  if (!openCtx) return;
  const ctx = openCtx;
  openCtx = null;
  const overlay = document.querySelector('.wsp-overlay');
  if (overlay) overlay.remove();
  ctx.onCancel();
}
