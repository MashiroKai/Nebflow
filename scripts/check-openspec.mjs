#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const TOOLCHAIN = JSON.parse(readFileSync(join(ROOT, 'openspec/toolchain.json'), 'utf8'));

function execute(binary, args, options) {
  const result = spawnSync(binary, args, { ...options, encoding: 'utf8', timeout: 60_000, maxBuffer: 8 * 1024 * 1024 });
  if (result.error || result.signal || result.status !== 0) {
    throw new Error(`OpenSpec command failed: ${result.error?.message || result.signal || result.stderr || result.stdout || result.status}`);
  }
  return result.stdout;
}

export function validateOfficial(binary, { root = ROOT, home, expectedVersion = TOOLCHAIN.version, run = execute } = {}) {
  if (!/^\d+\.\d+\.\d+$/.test(expectedVersion)) throw new Error('Invalid pinned OpenSpec version');
  if (!home) throw new Error('An isolated OpenSpec home is required');
  const options = { cwd: root, env: { ...process.env, HOME: home, USERPROFILE: home,
    XDG_CONFIG_HOME: join(home, 'config'), XDG_CACHE_HOME: join(home, 'cache'),
    OPENSPEC_TELEMETRY: '0', DO_NOT_TRACK: '1', OPENSPEC_NO_UPDATE: '1' } };
  const version = run(binary, ['--version'], options).trim();
  if (version !== expectedVersion) throw new Error(`OpenSpec version ${version} does not match ${expectedVersion}`);
  const output = run(binary, ['validate', '--all', '--strict', '--json'], options);
  const report = JSON.parse(output);
  if (!Array.isArray(report.items) || !report.items.length || report.items.some(item => item.valid !== true) || report.summary?.totals?.failed !== 0) {
    throw new Error('OpenSpec did not validate every specification/change');
  }
  return report;
}

async function downloadBinary(directory) {
  const asset = TOOLCHAIN.assets[`${process.platform}-${process.arch}`];
  if (!asset || !/^[a-f0-9]{64}$/.test(asset.sha256)) throw new Error('No verified OpenSpec asset for this platform');
  const url = `${TOOLCHAIN.repository}/releases/download/v${TOOLCHAIN.version}/${asset.name}`;
  console.log(`Downloading pinned OpenSpec ${TOOLCHAIN.version}: ${asset.name}`);
  const response = await fetch(url, { signal: AbortSignal.timeout(60_000) });
  if (!response.ok) throw new Error(`OpenSpec download failed: HTTP ${response.status}`);
  const chunks = [];
  let bytes = 0;
  for await (const chunk of response.body) {
    bytes += chunk.length;
    if (bytes > 100 * 1024 * 1024) throw new Error('OpenSpec archive exceeds size limit');
    chunks.push(chunk);
  }
  const archive = Buffer.concat(chunks);
  if (createHash('sha256').update(archive).digest('hex') !== asset.sha256) throw new Error('OpenSpec archive SHA-256 mismatch');
  const archivePath = join(directory, asset.name);
  writeFileSync(archivePath, archive);
  const options = { cwd: directory };
  if (process.platform === 'win32') {
    execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      'Expand-Archive -LiteralPath $args[0] -DestinationPath $args[1]', archivePath, directory], options);
  } else {
    const members = execute('tar', ['-tzf', archivePath], options).trim().split('\n');
    if (!members.includes('openspec') || members.some(name => name.startsWith('/') || name.split('/').includes('..'))) throw new Error('Unsafe OpenSpec archive paths');
    execute('tar', ['-xzf', archivePath, '-C', directory, 'openspec'], options);
  }
  const binary = join(directory, process.platform === 'win32' ? 'openspec.exe' : 'openspec');
  if (process.platform !== 'win32') chmodSync(binary, 0o755);
  return binary;
}

export async function main(args = process.argv.slice(2)) {
  let binary;
  let download = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--download') download = true;
    else if (args[i] === '--binary' && args[i + 1] && !args[i + 1].startsWith('--')) binary = resolve(args[++i]);
    else throw new Error('Usage: check-openspec.mjs (--download | --binary /absolute/openspec)');
  }
  if (Boolean(binary) === download) throw new Error('Select exactly one of --download or --binary');
  const directory = mkdtempSync(join(tmpdir(), 'stand-openspec-'));
  const heartbeat = setInterval(() => console.log('OpenSpec validation is running'), 15_000);
  try {
    binary ??= await downloadBinary(directory);
    const report = validateOfficial(binary, { home: directory });
    console.log(`OpenSpec ${TOOLCHAIN.version}: ${report.items.length} items passed strict validation`);
    return 0;
  } finally {
    clearInterval(heartbeat);
    rmSync(directory, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === join(dirname(fileURLToPath(import.meta.url)), 'check-openspec.mjs')) {
  main().then(code => { process.exitCode = code; }).catch(error => {
    console.error(`OpenSpec ERROR: ${error.message}`);
    process.exitCode = 2;
  });
}
