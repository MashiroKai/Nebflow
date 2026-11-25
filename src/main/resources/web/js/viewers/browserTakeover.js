// Ephemeral takeover viewer: no frame, key, or ticket persistence.
import { onMessage, sendWsIfOpen } from '../ws.js';
import { EVENTS } from '../events.js';

let awaiting = false;
export function requestBrowserTakeover(correlationId) {
  if (awaiting) return;
  awaiting = true;
  const unsubscribe = onMessage('browserTakeoverTicketResult', async message => {
    if (message.correlationId !== correlationId) return;
    unsubscribe();
    awaiting = false;
    if (message.error) { window.alert(message.error); return; }
    const { openWorkspaceItem } = await import('../canvas.js');
    await openWorkspaceItem({ id: `browser-takeover:${correlationId}`, itemType: 'browser-takeover', correlationId, ticket: message.ticket });
  });
  if (!sendWsIfOpen({ type: 'browserTakeoverTicket', correlationId })) {
    unsubscribe(); awaiting = false;
    window.alert('连接不可用，请重新尝试');
  }
}

export function mountBrowserTakeover(pane, ticket, correlationId) {
  const status = document.createElement('div');
  const image = document.createElement('img');
  const finish = document.createElement('button');
  finish.textContent = '完成';
  image.tabIndex = 0;
  image.style.cssText = 'display:block;max-width:100%;max-height:75vh;touch-action:none';
  pane.replaceChildren(status, image, finish);
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${scheme}//${location.host}/ws/browser-takeover`);
  let closed = false;
  let viewport = null;
  const send = data => { if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(data)); };
  socket.onopen = () => { send({ type: 'start', ticket }); ticket = null; };
  socket.onmessage = event => {
    const msg = JSON.parse(event.data);
    if (msg.type === 'frame') {
      viewport = msg.frame.metadata;
      image.src = `data:image/jpeg;base64,${msg.frame.data}`;
    }
    if (msg.type === 'status') status.textContent = msg.error || (msg.timedOut ? '该请求已超时，agent 已恢复运行' : '正在接管');
    if (msg.type === 'finish') window.dispatchEvent(new CustomEvent(EVENTS.BROWSER_TAKEOVER_FINISHED, { detail: { correlationId } }));
  };
  socket.onerror = () => { status.textContent = '画面流启动失败；可在运行 stand 的机器前操作真实浏览器窗口'; };
  const coords = event => {
    const r = image.getBoundingClientRect();
    const width = viewport?.deviceWidth || image.naturalWidth;
    const height = viewport?.deviceHeight || image.naturalHeight;
    return { x: Math.max(0, (event.clientX - r.left) * width / r.width), y: Math.max(0, (event.clientY - r.top) * height / r.height) };
  };
  const input = event => send({ type: 'input', event });
  let touch = null;
  image.onpointerdown = event => {
    image.focus(); image.setPointerCapture(event.pointerId);
    if (event.pointerType === 'touch') touch = { x: event.clientX, y: event.clientY, moved: false };
    else input({ type: 'mousePressed', ...coords(event), button: 'left', clickCount: 1 });
  };
  image.onpointermove = event => {
    if (touch && event.pointerType === 'touch') {
      const dx = touch.x - event.clientX, dy = touch.y - event.clientY;
      if (Math.abs(dx) + Math.abs(dy) > 1) { touch.moved = true; input({ type: 'mouseWheel', ...coords(event), deltaX: dx, deltaY: dy }); }
      touch.x = event.clientX; touch.y = event.clientY;
    } else input({ type: 'mouseMoved', ...coords(event), button: event.buttons ? 'left' : 'none' });
  };
  image.onpointerup = event => {
    if (touch && event.pointerType === 'touch') {
      if (!touch.moved) { input({ type: 'mousePressed', ...coords(event), button: 'left', clickCount: 1 }); input({ type: 'mouseReleased', ...coords(event), button: 'left', clickCount: 1 }); }
      touch = null;
    } else input({ type: 'mouseReleased', ...coords(event), button: 'left', clickCount: 1 });
  };
  image.onwheel = event => { event.preventDefault(); input({ type: 'mouseWheel', ...coords(event), deltaX: event.deltaX, deltaY: event.deltaY }); };
  image.onkeydown = event => { event.preventDefault(); if (event.key.length === 1 && !event.ctrlKey && !event.metaKey) input({ type: 'insertText', text: event.key }); else input({ type: 'keyDown', key: event.key, code: event.code }); };
  image.onkeyup = event => { if (event.key.length !== 1) input({ type: 'keyUp', key: event.key, code: event.code }); };
  finish.onclick = () => send({ type: 'finish' });
  return () => { if (!closed) { closed = true; socket.close(); image.removeAttribute('src'); } };
}
