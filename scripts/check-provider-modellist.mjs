#!/usr/bin/env node
// check-provider-modellist.mjs — provider model-list discovery gate.
//
// The settings dialog's "auto-fetch model list" button was dead for every
// provider whose baseUrl is NOT already a version root: the gateway appended
// `"models"` to whatever baseUrl was posted, while the Anthropic face appends
// `/v1/` itself (measured 2026-09-13: kimi 404 and zhipu "HTTP 200 + body 404
// NOT_FOUND" on the old shape, both 200 with their real catalogues on
// `{baseUrl}/v1/models`; qwen, whose baseUrl ends in `/v1`, was the only one the
// old shape served). The fix moves the endpoint into a PER-FACE declaration
// (`nebflow.llm.providers.ModelListFaces` + the two adapters), so this gate
// fails if a global concat comes back, if a face stops declaring, or if the
// caller stops going through the declaration.
//
// Layer O (default, offline, CI): structural assertions over the source tree.
//   No network, no credentials, hermetic. Anchored on symbols/patterns, not on
//   line numbers, so ordinary edits do not trip it.
//   Red-proven by mutating a copy of the tree (see --root): reintroducing the
//   global concat, or dropping a face's declaration, turns the matching arm RED.
// Layer L (--live): the layers a static gate cannot see.
//   · env-face  — proxy reachability FIRST (this failure's same-family root
//                 cause is an environment one, so it must be able to go red
//                 before any code verdict is drawn; --mutate=proxy-down proves
//                 that arm is not vacuous);
//   · provider  — each configured provider: the DECLARED path vs the OLD global
//                 shape side by side. The old shape must NOT return a usable
//                 list for the anthropic-face providers — that per-provider arm
//                 is the in-run negative control (it is exactly the shape the
//                 defect shipped), the declared path must return one;
//   · stub      — local upstream fixtures (ok / empty / non-JSON / pseudo-200 /
//                 401 / timeout), asserted on the RECEIVED status+body so a
//                 fixture that silently fails to serve cannot be a false green;
//   · gateway   — POST /api/provider/models on a running instance against those
//                 fixtures, then against the real providers: that layer is the
//                 only product truth (the provider layer says which side a red
//                 belongs to). Needs MODELIST_GUARD_TOKEN; skipped without it.
//   Until the artifact is rebuilt and the instance restarted, the gateway layer
//   is expected RED for every provider — that red IS the reported defect, not a
//   gate malfunction.
//
// Credentials: read from the environment ONLY (MODELIST_GUARD_KEYS = JSON map
//   name -> key, MODELIST_GUARD_TOKEN = gateway bearer token). The script never
//   reads a key from disk and never prints one (only the provider names that
//   were supplied). Provider baseUrl/protocol are read from the config file —
//   those are not secrets.
//
// Exit codes: 0 = clean (no RED arm), 1 = violation (RED arms listed),
//   2 = malformed invocation (bad flag / missing required file).
//
// Usage:
//   node scripts/check-provider-modellist.mjs                     # offline gate (CI)
//   node scripts/check-provider-modellist.mjs --root DIR          # gate another tree
//   node scripts/check-provider-modellist.mjs --live              # + env/provider/stub
//   node scripts/check-provider-modellist.mjs --live --mutate=proxy-down
//   MODELIST_GUARD_KEYS='{"kimi":"…"}' MODELIST_GUARD_TOKEN='…' \
//     node scripts/check-provider-modellist.mjs --live
//   # build the key map from the local config at run time (never written down):
//   MODELIST_GUARD_KEYS="$(python3 -c 'import json,os;print(json.dumps({k:v["apiKey"] \
//     for k,v in json.load(open(os.path.expanduser("~/.nebflow/nebflow.json")))["llm"]["providers"].items()}))')" \
//     node scripts/check-provider-modellist.mjs --live

import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from 'node:http';
import { homedir } from 'node:os';

const execFileAsync = promisify(execFile);
const SCRIPT_DIR = fileURLToPath(new URL('.', import.meta.url));

// ---------------------------------------------------------------- invocation

const argv = process.argv.slice(2);
const flags = { live: false, root: resolve(SCRIPT_DIR, '..'), mutate: '' };
for (let i = 0; i < argv.length; i += 1) {
  const a = argv[i];
  if (a === '--live') flags.live = true;
  else if (a === '--root') {
    const v = argv[++i];
    if (!v) die('--root needs a directory');
    flags.root = resolve(v);
  } else if (a.startsWith('--mutate=')) flags.mutate = a.slice('--mutate='.length);
  else if (a === '--help' || a === '-h') {
    console.log('usage: node scripts/check-provider-modellist.mjs [--live] [--root DIR] [--mutate=proxy-down]');
    process.exit(0);
  } else die(`unknown argument: ${a}`);
}
if (flags.mutate && flags.mutate !== 'proxy-down') die(`unknown --mutate value: ${flags.mutate}`);

function die(msg) {
  console.error(`[FAIL] ${msg}`);
  process.exit(2);
}

// ------------------------------------------------------------------ reporting

const results = [];
function record(layer, name, verdict, detail) {
  results.push({ layer, name, verdict, detail });
  const tag = verdict === 'OK' ? '[OK]  ' : verdict === 'RED' ? '[RED] ' : '[SKIP]';
  console.log(`${tag} ${layer}/${name}: ${detail}`);
}

// ------------------------------------------------------------- source readers

const GATEWAY = 'src/main/scala/nebflow/gateway/RestApiRoutes.scala';
// re-pin (2026-09-24): the pseudo-200 / transport classifiers moved out of
// RestApiRoutes into ProviderProbe (behavior-preserving split, gate semantics
// unchanged) — the two arms that pin those messages scan both files.
const PROBE = 'src/main/scala/nebflow/gateway/ProviderProbe.scala';
// re-pin (2026-09-24): the /provider/models route (the caller this gate pins)
// moved out of RestApiRoutes into PresenceRoutes (behavior-preserving split,
// gate semantics unchanged) — the two caller arms scan both files.
const PRESENCE = 'src/main/scala/nebflow/gateway/PresenceRoutes.scala';
const FACES = 'src/main/scala/nebflow/llm/providers/ModelListFaces.scala';
const ANTHROPIC = 'src/main/scala/nebflow/llm/providers/AnthropicAdapter.scala';
const OPENAI = 'src/main/scala/nebflow/llm/providers/OpenAiAdapter.scala';

function read(rel) {
  const p = join(flags.root, rel);
  if (!existsSync(p) || !statSync(p).isFile()) throw new Error(`missing required file: ${rel}`);
  return readFileSync(p, 'utf8');
}

// ------------------------------------------------------- layer O (structural)

const STRUCTURAL = [
  {
    name: 'no-global-concat',
    what: 'the gateway must not append a "models" path to a raw baseUrl',
    run: (src) => {
      const files = [[GATEWAY, src], [PRESENCE, read(PRESENCE)]];
      const hits = files.flatMap(([file, text]) =>
        text
          .split('\n')
          .map((line, i) => [file, i + 1, line])
          .filter(([, , line]) => /\+\s*"models"/.test(line) || /"models"\s*\+/.test(line))
      );
      return hits.map(([file, ln, line]) => `${file}:${ln} concatenates a models path: ${line.trim()}`);
    },
    fix: 'declare the list endpoint per protocol face (`ModelListFaces` / the adapters) and build no path here'
  },
  {
    name: 'faces-declare-both',
    what: 'each protocol face declares its own model-list endpoints',
    run: () => {
      const out = [];
      const faces = read(FACES);
      const anth = read(ANTHROPIC);
      const oai = read(OPENAI);
      if (!/AnthropicAdapter\.modelListUrls\s*\(/.test(faces)) out.push(`${FACES}: does not dispatch to AnthropicAdapter.modelListUrls`);
      if (!/OpenAiAdapter\.modelListUrls\s*\(/.test(faces)) out.push(`${FACES}: does not dispatch to OpenAiAdapter.modelListUrls`);
      if (!/def\s+modelListUrls\s*\(/.test(anth)) out.push(`${ANTHROPIC}: face declares no modelListUrls`);
      if (!/def\s+modelListUrls\s*\(/.test(oai)) out.push(`${OPENAI}: face declares no modelListUrls`);
      if (!/\/v1\/models/.test(anth)) out.push(`${ANTHROPIC}: declaration lost the face's own /v1/models path`);
      if (!/\/models/.test(oai)) out.push(`${OPENAI}: declaration lost the version-root /models path`);
      return out;
    },
    fix: 'keep the declaration in the adapter that owns the face (an unlisted path is a silent regression)'
  },
  {
    name: 'caller-uses-declaration',
    what: 'the route asks the declaration instead of building a URL, and handles the unsupported state',
    run: (src) => {
      const out = [];
      const both = `${src}\n${read(PRESENCE)}`;
      if (!/ModelListFaces\.models\s*\(/.test(both)) out.push(`${GATEWAY}: route does not call ModelListFaces.models`);
      if (!/ModelListFaces\.noEndpointMessage\s*\(/.test(both)) out.push(`${GATEWAY}: no explicit refusal for a face that declares no list endpoint`);
      if (!/case\s+Nil\s*=>/.test(both)) out.push(`${GATEWAY}: the empty-declaration (unsupported) branch is gone`);
      return out;
    },
    fix: 'a face with no list endpoint must be refused with a readable message, never reported as an empty success'
  },
  {
    name: 'pseudo-200-classified',
    what: 'a 2xx body carrying the provider\'s own error envelope is a failure, not "no models"',
    run: (src) => {
      const out = [];
      const both = `${src}\n${read(PROBE)}`;
      if (!/isErrorEnvelope/.test(both)) out.push(`${GATEWAY}: no error-envelope classification (zhipu answers HTTP 200 with a body 404)`);
      if (!/error body/.test(both)) out.push(`${GATEWAY}: the error-envelope branch has no distinct message (it would read as "no models" again)`);
      if (!/saysNoEndpoint/.test(both)) out.push(`${GATEWAY}: no "endpoint missing" verdict (a declared alternate could never be reached)`);
      return out;
    },
    fix: 'classify a 2xx-with-error-body as a failure and only "endpoint missing" as a candidate advance'
  },
  {
    name: 'unreachable-is-readable',
    what: 'a transport failure is reported as an error state, never as a silent empty list',
    run: (src) => {
      const out = [];
      const both = `${src}\n${read(PROBE)}`;
      if (!/Provider unreachable/.test(both)) out.push(`${GATEWAY}: transport failures have no readable classification`);
      if (!/Provider returned no models/.test(both)) out.push(`${GATEWAY}: the genuine empty-list case lost its message`);
      return out;
    },
    fix: 'keep "unreachable" and "no models" as two distinct messages (they are different user actions)'
  }
];

function runStructural() {
  let gateway = '';
  try {
    gateway = read(GATEWAY);
  } catch (e) {
    record('static', 'files', 'RED', e.message);
    return;
  }
  for (const check of STRUCTURAL) {
    let violations;
    try {
      violations = check.run(gateway);
    } catch (e) {
      violations = [e.message];
    }
    if (violations.length === 0) record('static', check.name, 'OK', check.what);
    else
      record(
        'static',
        check.name,
        'RED',
        `${check.what} — ${violations[0]}${violations.length > 1 ? ` (+${violations.length - 1} more)` : ''} [fix: ${check.fix}]`
      );
  }
}

// ------------------------------------------------------------------- curl IO

async function curl({ url, headers = {}, proxy, maxTime = 25, method = 'GET', body }) {
  const args = ['-s', '-o', '-', '-w', '\n%{http_code}', '--max-time', String(maxTime), '-X', method, url];
  for (const [k, v] of Object.entries(headers)) args.push('-H', `${k}: ${v}`);
  if (body !== undefined) args.push('-H', 'Content-Type: application/json', '--data-binary', body);
  if (proxy) args.push('-x', proxy);
  const started = Date.now();
  const split = (out) => {
    const nl = String(out).lastIndexOf('\n');
    return { status: Number(String(out).slice(nl + 1)) || 0, body: String(out).slice(0, nl) };
  };
  try {
    const { stdout } = await execFileAsync('curl', args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    return { ...split(stdout), ms: Date.now() - started, err: '' };
  } catch (e) {
    const code = typeof e.code === 'number' ? e.code : null;
    const reason = e.killed ? 'killed' : code === 28 ? 'timeout' : code !== null ? `curl exit ${code}` : 'curl failed';
    return { ...split(e.stdout || ''), ms: Date.now() - started, err: reason };
  }
}

function jsonList(body) {
  // Provider replies carry `data[]`; the gateway's own answer wraps the same
  // list in `models[]`. Both count as "a usable list came back".
  try {
    const d = JSON.parse(body);
    const arr = Array.isArray(d?.data) ? d.data : Array.isArray(d?.models) ? d.models : null;
    if (!arr) return null;
    return arr.map((m) => (typeof m === 'string' ? m : m?.id)).filter((x) => typeof x === 'string' && x);
  } catch {
    return null;
  }
}

const mask = (keys) => (Object.keys(keys).length === 0 ? 'none supplied' : `${Object.keys(keys).join(',')} (values masked)`);

// ------------------------------------------------------- layer L: environment

function systemProxy() {
  try {
    const out = execFileSync('scutil', ['--proxy'], { encoding: 'utf8' });
    const host = /HTTPProxy\s*:\s*(\S+)/.exec(out)?.[1];
    const port = /HTTPPort\s*:\s*(\d+)/.exec(out)?.[1];
    const enabled = /HTTPEnable\s*:\s*1/.test(out) || /HTTPSEnable\s*:\s*1/.test(out);
    return enabled && host && port ? `${host}:${port}` : '';
  } catch {
    const env = process.env.https_proxy || process.env.HTTPS_PROXY || process.env.http_proxy || process.env.HTTP_PROXY;
    return env ? env.replace(/^https?:\/\//, '').replace(/\/$/, '') : '';
  }
}

let deadPortCache = null;
async function deadPort() {
  if (deadPortCache === null) {
    const srv = createServer();
    await new Promise((ok) => srv.listen(0, '127.0.0.1', ok));
    deadPortCache = srv.address().port;
    await new Promise((ok) => srv.close(ok));
  }
  return deadPortCache;
}

async function runEnvFace() {
  const mutate = flags.mutate === 'proxy-down';
  const real = systemProxy();
  const proxy = mutate ? `127.0.0.1:${await deadPort()}` : real;
  if (!proxy) {
    record('env', 'proxy', 'SKIP', 'no system proxy configured — direct egress is the measured path');
    return;
  }
  const label = mutate ? `${proxy} (mutated: nothing listens there)` : `${proxy}${real ? '' : ' (from env)'}`;
  // Three attempts: a tunnel that carries traffic only intermittently is still
  // an environment reading worth reporting, but one TLS blip must not condemn
  // the environment (the mutation arm fails all three by construction).
  const attempts = [];
  for (let i = 0; i < 3; i += 1) {
    const r = await curl({ url: 'https://www.gstatic.com/generate_204', proxy, maxTime: 15 });
    attempts.push(r.status === 204 ? `204 in ${r.ms} ms` : `status=${r.status}${r.err ? ` ${r.err}` : ''} in ${r.ms} ms`);
    if (r.status === 204) {
      record('env', 'proxy', 'OK', `${label} carries traffic (${attempts.join(' | ')})`);
      return;
    }
  }
  record(
    'env',
    'proxy',
    'RED',
    `${label} does not carry traffic in 3 attempts (${attempts.join(' | ')}) — ENVIRONMENT FACE: settle this before reading any code verdict (a dead proxy looks exactly like this defect)`
  );
}

// -------------------------------------------------------- layer L: providers

function readProviders() {
  const p = join(homedir(), '.nebflow', 'nebflow.json');
  if (!existsSync(p)) return null;
  try {
    const cfg = JSON.parse(readFileSync(p, 'utf8'));
    return Object.entries(cfg?.llm?.providers || {}).map(([name, v]) => ({
      name,
      raw: String(v?.baseUrl || ''),
      baseUrl: String(v?.baseUrl || '').replace(/\/+$/, ''),
      protocol: String(v?.protocol || 'anthropic')
    }));
  } catch {
    return null;
  }
}

/** Mirror of the per-face declaration (kept in lockstep with the adapters; the
  *  gateway layer below is the product truth, this only says which side a red
  *  belongs to). */
function declaredCandidates({ baseUrl, protocol }) {
  if (protocol === 'openai') {
    return baseUrl.endsWith('/v1') ? [`${baseUrl}/models`] : [`${baseUrl}/models`, `${baseUrl}/v1/models`];
  }
  const schemeEnd = baseUrl.indexOf('://');
  const after = schemeEnd < 0 ? '' : baseUrl.slice(schemeEnd + 3);
  const slash = after.indexOf('/');
  const origin = slash < 0 ? '' : baseUrl.slice(0, schemeEnd + 3 + slash);
  const own = `${baseUrl}/v1/models`;
  return origin ? [own, `${origin}/v1/models`] : [own];
}

/** The URL the DEFECT shipped: `{baseUrl}/` + "models" (the old normalisation
  *  added the trailing slash), i.e. the one shape a provider arm must reject. */
const legacyUrl = ({ baseUrl }) => `${baseUrl}/models`;

const authHeaders = (protocol, key) =>
  protocol === 'openai' ? { Authorization: `Bearer ${key}` } : { 'x-api-key': key, 'anthropic-version': '2023-06-01' };

/** Probe each configured provider's DECLARED candidates (and the defect's
  *  shape as a same-provider negative control), and return what the gateway
  *  layer below must then see through the product path: 'list' when the
  *  declaration answers with a catalogue, 'error' when not one candidate
  *  answered at all (transport state — the product must surface a readable
  *  error). No provider name or host is ever special-cased. */
async function runProviderArms(providers, keys) {
  const expectation = new Map();
  for (const p of providers) {
    const key = keys[p.name];
    if (!key) {
      record('provider', p.name, 'SKIP', `no key in MODELIST_GUARD_KEYS (${mask(keys)})`);
      continue;
    }
    const headers = authHeaders(p.protocol, key);
    const declared = declaredCandidates(p);
    const attempts = [];
    let reading = null;
    for (const url of declared) {
      const r = await curl({ url, headers, maxTime: 20 });
      const ids = r.status >= 200 && r.status < 300 ? jsonList(r.body) : null;
      attempts.push({ url, status: r.status, err: r.err });
      reading = { url, status: r.status, ids, err: r.err };
      if (ids && ids.length > 0) break;
    }
    const declaredUsable = Array.isArray(reading?.ids) && reading.ids.length > 0;
    // Classification is BEHAVIOURAL — never by provider name or host: a
    // declared candidate that ANSWERS with any HTTP status and still yields no
    // usable list is a verdict on the declaration (RED). Only "not one
    // declared candidate answered at all" (transport: proxy/DNS/TLS/timeout)
    // is an external reachability state, and even then the gateway layer below
    // must show it as a readable error rather than an empty list.
    const noAnswerAtAll = attempts.length > 0 && attempts.every((a) => a.status === 0);
    expectation.set(p.name, declaredUsable ? 'list' : noAnswerAtAll ? 'error' : 'unknown');
    if (declaredUsable) {
      record('provider', p.name, 'OK', `declared ${reading.url} -> HTTP ${reading.status}, ${reading.ids.length} models`);
    } else if (noAnswerAtAll) {
      record('provider', p.name, 'OK', `not one declared candidate answered (${attempts.map((a) => `${a.url} ${a.err || 'no answer'}`).join(' | ')}) — an external reachability state; the gateway layer must surface it as an error, never as an empty list`);
    } else {
      record('provider', p.name, 'RED', `declared ${reading?.url} -> HTTP ${reading?.status}${reading?.err ? ` ${reading.err}` : ''} (expected a usable list)`);
      continue;
    }
    if (noAnswerAtAll) {
      record('provider', `${p.name}~negative-control`, 'SKIP', 'the provider answers nothing, so the defect control cannot separate the defect from the fix here');
      continue;
    }
    const legacy = await curl({ url: legacyUrl(p), headers, maxTime: 20 });
    const legacyIds = legacy.status >= 200 && legacy.status < 300 ? jsonList(legacy.body) : null;
    const legacyUsable = Array.isArray(legacyIds) && legacyIds.length > 0;
    if (legacyUsable && p.baseUrl.endsWith('/v1')) {
      record('provider', `${p.name}~negative-control`, 'SKIP', `baseUrl is already the version root, so the defect's shape coincides with the declared one (this provider is the one the old rule served: HTTP ${legacy.status}, ${legacyIds.length} models)`);
    } else if (legacyUsable) {
      record('provider', `${p.name}~negative-control`, 'RED', `the defect's shape (${legacyUrl(p)}) still returns ${legacyIds.length} models — the arm cannot separate the defect from the fix`);
    } else {
      record('provider', `${p.name}~negative-control`, 'OK', `the defect's shape (${legacyUrl(p)}) -> HTTP ${legacy.status}${legacy.err ? ` ${legacy.err}` : ''}, no usable list`);
    }
  }
  return expectation;
}

// ------------------------------------------------------- layer L: stub + gateway

const STUB_ROUTES = [
  { path: '/ok/v1/models', status: 200, body: JSON.stringify({ object: 'list', data: [{ id: 'stub-model-a' }, { id: 'stub-model-b' }] }), expect: 'models' },
  { path: '/empty/v1/models', status: 200, body: JSON.stringify({ object: 'list', data: [] }), expect: 'no-models' },
  { path: '/badjson/v1/models', status: 200, body: 'not json at all', expect: 'invalid-json' },
  { path: '/pseudo/v1/models', status: 200, body: JSON.stringify({ code: 500, msg: '404 NOT_FOUND', success: false }), expect: 'no-endpoint' },
  { path: '/unauth/v1/models', status: 401, body: JSON.stringify({ error: { message: 'invalid api key' } }), expect: 'http-401' },
  { path: '/slow/v1/models', status: 200, body: '', expect: 'unreachable', slow: true }
];

function startStub() {
  const server = createServer((req, res) => {
    const route = STUB_ROUTES.find((r) => req.url === r.path);
    if (!route) {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'no such endpoint', type: 'resource_not_found_error' } }));
      return;
    }
    if (route.slow) return; // accepted, never answered (timeout fixture)
    res.writeHead(route.status, { 'content-type': 'application/json' });
    res.end(route.body);
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok({ server, port: server.address().port })));
}

/** Assert on the RECEIVED status+body: a fixture that fails to serve must be
  *  visible here, otherwise every gateway expectation built on it is fiction. */
async function runStubSelfCheck(base) {
  for (const route of STUB_ROUTES) {
    if (route.slow) {
      const r = await curl({ url: `${base}${route.path}`, maxTime: 3 });
      record('stub', route.path, r.status === 0 ? 'OK' : 'RED', r.status === 0 ? 'accepts the connection and never answers (timeout fixture, curl exit 28)' : `expected no answer, got HTTP ${r.status}`);
      continue;
    }
    const r = await curl({ url: `${base}${route.path}`, maxTime: 8 });
    const ok = r.status === route.status && r.body === route.body;
    record('stub', route.path, ok ? 'OK' : 'RED', ok ? `serves HTTP ${r.status} with the declared fixture body` : `served HTTP ${r.status} / ${JSON.stringify(r.body).slice(0, 80)} (declared ${route.status} / ${JSON.stringify(route.body).slice(0, 80)})`);
  }
}

function classifyGateway(body, status) {
  const text = `${body}`;
  if (status === 200) return Array.isArray(jsonList(text)) ? 'models' : 'unknown-200';
  if (/no model-list endpoint/i.test(text)) return 'no-endpoint';
  if (/no models/i.test(text)) return 'no-models';
  if (/Invalid JSON/i.test(text)) return 'invalid-json';
  if (/HTTP 401|HTTP 403/.test(text)) return 'http-401';
  if (/unreachable|timed out|timeout/i.test(text)) return 'unreachable';
  return `other(${text.slice(0, 60)})`;
}

async function runGatewayArms(stubBase, providers, keys, expectation) {
  const token = process.env.MODELIST_GUARD_TOKEN || '';
  const gwBase = (process.env.MODELIST_GUARD_BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
  if (!token) {
    record('gateway', 'auth', 'SKIP', 'no MODELIST_GUARD_TOKEN — the product-path arms need the instance token (the provider/stub/env arms above still ran)');
    return;
  }
  const post = (payload, maxTime = 30) =>
    curl({ url: `${gwBase}/api/provider/models`, method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: JSON.stringify(payload), maxTime });

  // Credential precondition: a rejected token must read as ONE arm, not as a
  // provider verdict per provider (a 403 is not a provider error state).
  const preflight = await post({ baseUrl: stubBase, protocol: 'anthropic' }, 20);
  if (preflight.status === 403) {
    record('gateway', 'auth', 'RED', `token rejected by ${gwBase} (HTTP 403 Unauthorized) — set MODELIST_GUARD_TOKEN to the gateway token (a JSON string at <dataRoot>/auth.json); the product-path arms cannot be read without it`);
    return;
  }

  for (const route of STUB_ROUTES) {
    const payload = { baseUrl: `${stubBase}${route.path.replace(/\/v1\/models$/, '')}`, protocol: 'anthropic' };
    const r = await post(payload, route.slow ? 45 : 30);
    const verdict = classifyGateway(r.body, r.status);
    const ok = verdict === route.expect;
    record('gateway', `stub${route.path.replace('/v1/models', '')}`, ok ? 'OK' : 'RED', `POST /api/provider/models -> HTTP ${r.status} [${verdict}] want [${route.expect}]${ok ? '' : ` body=${JSON.stringify(r.body).slice(0, 120)}`}`);
    if (route.expect === 'no-endpoint' && verdict === 'no-models') {
      record('gateway', 'pseudo-200-criterion', 'RED', "a 2xx body carrying the provider's own 404 is reported as \"no models\" again");
    }
  }

  // Loopback bypasses the proxy, so this arm exercises the DIRECT-connect
  // failure path. Assertion: a refused upstream must never come back as a 200
  // with a list (silent success); the exact status/body is reported every run,
  // because the pre-fix artifact answers 500 with an EMPTY body here (an
  // unreadable state, recorded as a finding rather than asserted green).
  const refused = await deadPort();
  const rRefused = await post({ baseUrl: `http://127.0.0.1:${refused}`, protocol: 'anthropic' }, 30);
  const refusedSilentSuccess = rRefused.status === 200 && Array.isArray(jsonList(rRefused.body));
  const refusedShape = `HTTP ${rRefused.status} body=${JSON.stringify(rRefused.body).slice(0, 60)}`;
  record(
    'gateway',
    'stub-refused',
    refusedSilentSuccess ? 'RED' : 'OK',
    `connection refused -> ${refusedShape}${refusedSilentSuccess ? ' (a silent 200 list IS the regression)' : rRefused.body.trim() === '' ? ' — UNREADABLE error state (empty body), see the report finding' : ' (readable error state)'}`
  );

  for (const p of providers) {
    const r = await post({ baseUrl: p.baseUrl, protocol: p.protocol, name: p.name });
    const verdict = classifyGateway(r.body, r.status);
    const readable = `${r.body}`.trim().length > 0;
    const expect = expectation.get(p.name);
    // Expectation comes from THIS RUN's provider-layer reading, never from a
    // provider name: a face whose declared candidates answered with a
    // catalogue must come back as a list through the product path; a face
    // that answered nothing at all must come back as a readable error.
    if (expect === 'list') {
      const n = jsonList(r.body)?.length ?? 0;
      record('gateway', p.name, verdict === 'models' ? 'OK' : 'RED',
        verdict === 'models'
          ? `HTTP ${r.status}, ${n} models through the product path`
          : `HTTP ${r.status} [${verdict}] — the declaration answers with a catalogue, so the product path must too ${JSON.stringify(r.body).slice(0, 120)}`);
    } else if (expect === 'error') {
      const ok = r.status !== 200 && readable;
      record('gateway', p.name, ok ? 'OK' : 'RED',
        ok
          ? `a provider that answers nothing must surface a readable error -> HTTP ${r.status} ${JSON.stringify(r.body).slice(0, 80)}`
          : r.status === 200
            ? `HTTP 200 for a provider that answers nothing — a silent success IS the regression ${JSON.stringify(r.body).slice(0, 120)}`
            : `HTTP ${r.status} with an EMPTY body — an unreadable error state`);
    } else {
      // Provider layer skipped (no key): only the shape is assertable here.
      const silent = r.status === 200 && verdict !== 'models';
      record('gateway', p.name, !silent && readable ? 'OK' : 'RED',
        !silent && readable
          ? `HTTP ${r.status} [${verdict}] readable (no key for this provider, so only the error shape is asserted here)`
          : silent
            ? `HTTP 200 without a list — a silent success IS the regression ${JSON.stringify(r.body).slice(0, 120)}`
            : `HTTP ${r.status} with an EMPTY body — an unreadable error state`);
    }
  }
}

// ----------------------------------------------------------------------- main

let stubCtx = null;
async function cleanupStub() {
  if (!stubCtx) return;
  stubCtx.server.closeAllConnections?.();
  await new Promise((ok) => stubCtx.server.close(ok));
  stubCtx = null;
}

async function main() {
  console.log(`check-provider-modellist | root=${flags.root}${flags.live ? ' live' : ' offline'}${flags.mutate ? ` mutate=${flags.mutate}` : ''}`);
  runStructural();
  if (flags.live) {
    await runEnvFace();
    const providers = readProviders();
    const keys = (() => {
      try {
        return JSON.parse(process.env.MODELIST_GUARD_KEYS || '{}');
      } catch {
        return {};
      }
    })();
    if (!providers) {
      record('provider', 'config', 'RED', 'could not read ~/.nebflow/nebflow.json (baseUrl/protocol are needed; keys still come from the environment)');
    } else {
      const expectation = await runProviderArms(providers, keys);
      stubCtx = await startStub();
      const base = `http://127.0.0.1:${stubCtx.port}`;
      await runStubSelfCheck(base);
      await runGatewayArms(base, providers, keys, expectation);
    }
  }
}

async function finish() {
  await cleanupStub();
  const reds = results.filter((r) => r.verdict === 'RED');
  const oks = results.filter((r) => r.verdict === 'OK').length;
  const skips = results.filter((r) => r.verdict === 'SKIP').length;
  console.log(`\nsummary: ${oks} OK, ${reds.length} RED, ${skips} SKIP`);
  if (reds.length > 0) {
    console.log('RED arms:');
    for (const r of reds) console.log(`  - ${r.layer}/${r.name}: ${r.detail}`);
    process.exit(1);
  }
  console.log('provider model-list discovery gate: clean');
  process.exit(0);
}

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    void cleanupStub().then(() => process.exit(sig === 'SIGINT' ? 130 : 143));
  });
}

main()
  .then(finish)
  .catch(async (e) => {
    record('harness', 'main', 'RED', `unhandled: ${e?.message || e}`);
    await finish();
  });
