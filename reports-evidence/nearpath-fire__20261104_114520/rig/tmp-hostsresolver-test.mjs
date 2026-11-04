// tmp-hostsresolver-test.mjs — 验证 Chromium --host-resolver-rules 能否把
// mystand.space 映射到本机 ingress（不触发真实公网请求）。
import { createServer } from 'node:https';
import fs from 'node:fs';
import { chromium } from 'playwright';

const tls = { key: fs.readFileSync('/tmp/nearpath-fire/certs/rig-key.pem'), cert: fs.readFileSync('/tmp/nearpath-fire/certs/rig-cert.pem') };
let hits = [], errs = [], conns = [];
const srv = createServer(tls, (req, res) => { hits.push(req.headers.host + ' ' + req.url); res.end('MARKER-LOCAL-INGRESS'); });
srv.on('tlsClientError', (e) => errs.push(String(e).slice(0, 100)));
srv.on('connection', () => conns.push(1));
await new Promise((r) => srv.listen(7447, r));

const variants = [
  { name: 'map-all', args: ['--host-resolver-rules=MAP mystand.space 127.0.0.1:7447'] },
  { name: 'map-port', args: ['--host-resolver-rules=MAP mystand.space:443 127.0.0.1:7447'] },
  { name: 'map-port+dohoff', args: ['--host-resolver-rules=MAP mystand.space:443 127.0.0.1:7447', '--disable-features=DnsOverHttps'] },
  { name: 'map-all+ignorelimits', args: ['--host-resolver-rules=MAP mystand.space 127.0.0.1:7447', '--ignore-certificate-errors', '--no-proxy-server'] },
];
for (const v of variants) {
  hits = []; errs = []; conns = [];
  const b = await chromium.launch({ args: v.args });
  const ctx = await b.newContext({ ignoreHTTPSErrors: true });
  const p = await ctx.newPage();
  let body = '';
  try { await p.goto('https://mystand.space/rigtest', { timeout: 20000 }); body = (await p.content()).slice(0, 80); } catch (e) { body = 'ERR ' + String(e).slice(0, 100); }
  console.log(v.name, '| tcp=', conns.length, '| hits=', hits.length, '| tlsErr=', errs.slice(-1), '| body=', body.replace(/\s+/g, ' ').slice(0, 50));
  await b.close();
}
srv.close();
