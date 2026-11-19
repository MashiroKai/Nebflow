#!/usr/bin/env node
// composer-unify-fake-neblink.mjs — composer/message-item 统一批·**假 neblink 服务端**
// （2026-11-18 · 复核意见「真实发送 + 后端确实接收/回读」改正点）。
//
// Why this exists: 好友/设备私信的存储后端是远端 neblink 服务器（本仓只有客户端），
// 隔离实例默认 --net off ⇒ `POST /api/friends/:id/messages` 到网关后无人接收。要让
// 「从联系人私信输入框真实发一条」走满 浏览器 → 真网关 HTTP 路由 → 真转发代理 →
// 真后端进程落库 → 回读 全链，本脚本在 127.0.0.1 充当那台 neblink 服务器：
// 只实现本链路必需的六只端点（会话交换 / 好友表 / 会话表 / 消息读面 / 消息写面 /
// 已读回执），其余一律 200 `{}`（与 tests/portal-identity-e2e-isolated.mjs 的假中继
// 同款口径）。落库 = 进程内数组 + 磁盘 JSON 双写，spec 断言读磁盘那份（进程重启
// 也不丢证据）。
//
// 隔离护栏：只绑 127.0.0.1；端口 18998（非保护口 6138/8080/8097）；数据只写
// /tmp/stand-cu2/fake-neblink-store.json。EnrollGuard 语义不受影响（127.0.0.1
// 不在生产域）。
//
// Run: node tests/composer-unify-fake-neblink.mjs   (前台常驻；Ctrl-C 退出)
import { createServer } from 'node:http';
import { writeFileSync, readFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const PORT = 18998;
const STORE = '/tmp/stand-cu2/fake-neblink-store.json';
mkdirSync(dirname(STORE), { recursive: true });

const FRIEND = { userId: 'u-cu-real', neblinkId: 'buddy-real-01', name: '统一批真实好友', avatarUrl: '', since: Math.floor(Date.now() / 1000) - 86400, blocked: false };
const CONV_ID = 'conv-cu-real';
const nowSec = () => Math.floor(Date.now() / 1000);

/** 落库真源（进程内；每次变更同步写盘）。 */
const store = existsSync(STORE) ? JSON.parse(readFileSync(STORE, 'utf8')) : {
  bootedAt: new Date().toISOString(),
  sessionExchanges: 0,
  receivedPosts: [],          // 每次 POST /api/friends/:id/messages 的完整请求证据
  messages: [
    { id: 101, senderId: FRIEND.userId, kind: 'text', body: '统一批·真实链路早前消息', createdAt: nowSec() - 600 },
  ],
  reads: 0,
};
const persist = () => writeFileSync(STORE, JSON.stringify(store, null, 2));
persist();

const server = createServer((req, res) => {
  const json = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    const url = req.url || '';
    console.log(`[fake-neblink] ${req.method} ${url}${body ? `  <- ${body.slice(0, 160)}` : ''}`);
    if (req.method === 'POST' && url === '/api/device/session') {
      store.sessionExchanges += 1; persist();
      return json(200, { token: 'sess-cu-fake-1', networkId: 'n-cu-fake', deviceId: 'dev-cu-fake-1', peers: [] });
    }
    if (req.method === 'GET' && url === '/api/friends') {
      return json(200, { friends: [FRIEND], incoming: [], outgoing: [] });
    }
    if (req.method === 'GET' && url === '/api/conversations') {
      const last = store.messages[store.messages.length - 1];
      return json(200, [{ conversationId: CONV_ID, friend: FRIEND, lastMessage: last, unreadCount: 0 }]);
    }
    let m = url.match(/^\/api\/conversations\/([^/]+)\/messages/);
    if (m && req.method === 'GET') {
      store.reads += 1; persist();
      return json(200, store.messages.map((x) => ({ ...x })));
    }
    m = url.match(/^\/api\/friends\/([^/]+)\/messages$/);
    if (m && req.method === 'POST') {
      let parsed = {}; try { parsed = JSON.parse(body); } catch { /* keep {} */ }
      const id = 1000 + store.messages.length + 1;
      const row = {
        id, senderId: 'me-cu', kind: 'text',
        body: String(parsed.body ?? ''),
        createdAt: nowSec(),
        ...(Array.isArray(parsed.attachments) && parsed.attachments.length ? { attachments: parsed.attachments } : {}),
      };
      store.messages.push(row);
      store.receivedPosts.push({
        at: new Date().toISOString(), friendId: decodeURIComponent(m[1]),
        body: parsed.body, clientMsgId: parsed.clientMsgId ?? null,
        attachments: parsed.attachments ?? null, assignedId: id, raw: body.slice(0, 500),
      });
      persist();
      return json(201, { messageId: id, conversationId: CONV_ID, createdAt: row.createdAt });
    }
    m = url.match(/^\/api\/conversations\/([^/]+)\/read$/);
    if (m && req.method === 'POST') return json(200, { ok: true });
    return json(200, {});
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[fake-neblink] listening on 127.0.0.1:${PORT}; store=${STORE}`);
});
