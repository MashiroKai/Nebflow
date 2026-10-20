// mini-ws.mjs — minimal RFC6455 WebSocket + static HTTP server for the
// reconnect-collapse family tests. Zero npm deps (the repo's node_modules has
// no `ws`; devDependencies list it but the worktree never installs).
//
// Serves three faces on ONE throwaway high port (never the 8080 host):
//   - static files from a root dir (harness + real web/ modules),
//   - GET /api/nf-authcheck → 204 (ws.js probeCookieAuth's happy path),
//   - WS upgrade on /ws → a scripted peer that answers ping→pong and
//     getHistory→historyPage, and can be told to drop sockets abruptly
//     (强制重连循环的服务器侧半边；客户端侧半边 = ws.js forceReconnect()).
//
// Frame codec covers exactly what ws.js speaks: unmasked server→client text
// frames (7-bit / 16-bit / 64-bit lengths), masked client→server text frames,
// close + ping/pong control frames. No fragmentation (ws.js never fragments).

import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.map': 'application/json',
  '.woff2': 'font/woff2',
};

/** Encode one unmasked text frame (server→client). */
export function encodeTextFrame(str) {
  const payload = Buffer.from(str, 'utf8');
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81; header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81; header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

/** Parse masked client frames out of a growing buffer. Calls onText(str). */
export function createFrameParser(onText, onClose) {
  let buf = Buffer.alloc(0);
  return (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (buf.length < 2) return;
      const opcode = buf[0] & 0x0f;
      const masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7f;
      let off = 2;
      if (len === 126) {
        if (buf.length < 4) return;
        len = buf.readUInt16BE(2); off = 4;
      } else if (len === 127) {
        if (buf.length < 10) return;
        len = Number(buf.readBigUInt64BE(2)); off = 10;
      }
      const maskLen = masked ? 4 : 0;
      if (buf.length < off + maskLen + len) return;
      if (opcode === 0x8) { // close
        if (onClose) onClose();
        buf = buf.subarray(off + maskLen + len);
        continue;
      }
      if (opcode === 0x9) { // ping → caller handles; skip payload here
        buf = buf.subarray(off + maskLen + len);
        continue;
      }
      let payload = buf.subarray(off + maskLen, off + maskLen + len);
      if (masked) {
        const key = buf.subarray(off, off + 4);
        const un = Buffer.alloc(len);
        for (let i = 0; i < len; i++) un[i] = payload[i] ^ key[i % 4];
        payload = un;
      }
      if (opcode === 0x1) onText(payload.toString('utf8'));
      buf = buf.subarray(off + maskLen + len);
    }
  };
}

/**
 * Create the throwaway server.
 *
 * @param {object} opts
 * @param {string} opts.root          static root (repo/worktree root)
 * @param {(peer: object, msg: any) => void} opts.onMessage  scripted peer replies
 * @returns {Promise<{port:number, close:Promise<void>, dropAll:()=>void,
 *                    sockets:Set<object>, close:()=>void}>}
 */
export function createReplayServer({ root, onMessage }) {
  const sockets = new Set();
  const log = []; // 诊断轨迹：conn/http/ws 事件（spec 失败时可读）

  const server = net.createServer((socket) => {
    let upgraded = false;
    let head = Buffer.alloc(0);
    let parseFrames = null;

    socket.on('data', (chunk) => {
      if (upgraded) { if (parseFrames) parseFrames(chunk); return; }
      head = Buffer.concat([head, chunk]);
      const idx = head.indexOf('\r\n\r\n');
      if (idx < 0) return;
      const reqText = head.subarray(0, idx).toString('utf8');
      const [reqLine, ...headerLines] = reqText.split('\r\n');
      const [method, reqPath] = reqLine.split(' ');
      const headers = {};
      for (const line of headerLines) {
        const c = line.indexOf(':');
        if (c > 0) headers[line.slice(0, c).trim().toLowerCase()] = line.slice(c + 1).trim();
      }
      const rest = head.subarray(idx + 4);
      head = Buffer.alloc(0);

      const isWsUpgrade = headers.upgrade?.toLowerCase() === 'websocket'
        && headers['sec-websocket-key'];
      if (isWsUpgrade) {
        log.push(`ws-upgrade ${reqPath}`);
        upgraded = true;
        const accept = crypto.createHash('sha1')
          .update(headers['sec-websocket-key'] + WS_GUID).digest('base64');
        socket.write(
          'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
        const peer = {
          send: (obj) => socket.write(encodeTextFrame(JSON.stringify(obj))),
          close: () => { try { socket.end(Buffer.from([0x88, 0x00])); } catch { /* gone */ } },
          drop: () => socket.destroy(),   // abrupt: client sees onclose, no close frame
        };
        sockets.add(peer);
        socket.on('close', () => sockets.delete(peer));
        socket.on('error', () => sockets.delete(peer));
        parseFrames = createFrameParser(
          (text) => {
            let msg = null;
            try { msg = JSON.parse(text); } catch { return; }
            log.push(`ws-recv ${msg && msg.type}`);
            if (msg && msg.type === 'ping') { peer.send({ type: 'pong' }); return; }
            if (msg && msg.type === 'getHistory') {
              peer.send({
                type: 'historyPage',
                sessionId: msg.sessionId,
                messages: replayFixtureMessages(),
                offset: 0,
                total: 6,
                hasMore: false,
              });
              log.push('ws-send historyPage');
              return;
            }
            if (onMessage) onMessage(peer, msg);
          },
          () => { try { socket.end(Buffer.from([0x88, 0x00])); } catch { /* gone */ } },
        );
        if (rest.length) parseFrames(rest);
        return;
      }

      // ── plain HTTP faces ──
      const pathOnly = (reqPath || '/').split('?')[0];
      if (pathOnly === '/api/nf-authcheck') {
        log.push('http /api/nf-authcheck 204');
        socket.write('HTTP/1.1 204 No Content\r\n' +
          'x-nebflow-credential: session\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
        socket.end();
        return;
      }
      const rel = path.join(root, decodeURIComponent(pathOnly));
      if (!rel.startsWith(path.resolve(root))) {
        socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
        socket.end();
        return;
      }
      fs.readFile(rel, (err, data) => {
        if (err) {
          socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
          socket.end();
          return;
        }
        const type = MIME[path.extname(rel).toLowerCase()] || 'application/octet-stream';
        socket.write('HTTP/1.1 200 OK\r\n' +
          `Content-Type: ${type}\r\nContent-Length: ${data.length}\r\nConnection: close\r\n\r\n`);
        socket.end(data);
      });
    });
    socket.on('error', () => { /* probes racing the shutdown — ignore */ });
  });

  /** Abruptly destroy every open WS peer (强制断开 = client onclose loop). */
  const dropAll = () => { for (const p of [...sockets]) p.drop(); };

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({
        port,
        log,
        dropAll,
        sockets,
        close: () => new Promise((res) => {
          dropAll();
          server.close(() => res());
        }),
      });
    });
  });
}

/** getHistory 的重放载荷：真 history 形状（SessionRecorder 口径）——
 *  带 text 的 ai 行（thinking 随行）+ thinking-only 行 + tool 行。 */
export function replayFixtureMessages() {
  return [
    { type: 'user', text: '帮我看一下文件', timestamp: 1750000000000 },
    {
      type: 'ai',
      text: '先读一下入口文件。',
      thinking: '我先梳理一下当前文件的结构：\n\n- 入口在 main.scala\n- 核心逻辑分三层',
      timestamp: 1750000001000,
    },
    {
      type: 'tool',
      label: 'Read',
      summary: 'src/main/scala/Main.scala',
      content: 'package example\n\nobject Main extends App {\n  println("hello")\n}\n',
      isError: false,
      input: {},
    },
    { type: 'user', text: '再想一下边界条件', timestamp: 1750000002000 },
    {
      type: 'ai',
      text: '',
      thinking: '完整思考过程：第一步检查空输入，第二步检查越界，第三步结论成立。',
      timestamp: 1750000003000,
    },
  ];
}
