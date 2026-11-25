#!/usr/bin/env node
// Stand-owned process groups only; never use this to manage pre-existing services.
import fs from 'node:fs';
import path from 'node:path';
import {spawn, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

export function parse(argv) {
  const [action, ...rest] = argv;
  if (action === 'list' && rest.length === 0) return {action};
  if (['clean', 'forget'].includes(action) && rest.length === 1 && /^[1-9]\d*$/.test(rest[0])) return {action, pid: Number(rest[0])};
  if (action === 'start') {
    const split = rest.indexOf('--');
    if (split !== 4 || rest[0] !== '--node' || rest[2] !== '--tag' || !rest[1] || !rest[3] || !rest[5]) throw Error('start --node NODE --tag TAG -- COMMAND [ARGS...]');
    return {action, node: rest[1], tag: rest[3], command: rest.slice(5)};
  }
  throw Error('usage: start --node NODE --tag TAG -- COMMAND [ARGS...] | list | clean PID');
}
export function readRoster(file) {
  if (!fs.existsSync(file)) return [];
  const records = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!Array.isArray(records)) throw Error('invalid roster');
  return records;
}
export function writeRoster(file, records) {
  fs.mkdirSync(path.dirname(file), {recursive: true, mode: 0o700});
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(records, null, 2) + '\n', {mode: 0o600, flag: 'wx'});
  fs.renameSync(tmp, file);
}
function identity(pid) {
  const r = spawnSync('ps', ['-p', String(pid), '-o', 'pgid=', '-o', 'lstart='], {encoding: 'utf8'});
  if (r.status !== 0) return null;
  const match = r.stdout.trim().match(/^(\d+)\s+(.+)$/);
  return match ? {pgid: Number(match[1]), started: match[2]} : null;
}
async function main() {
  const args = parse(process.argv.slice(2));
  const file = process.env.STAND_PROCESS_ROSTER;
  if (!file || !path.isAbsolute(file)) throw Error('STAND_PROCESS_ROSTER must be an absolute, private roster path');
  // Lock per roster: concurrent mutations must never overwrite one another.
  fs.mkdirSync(path.dirname(file), {recursive: true, mode: 0o700});
  const lock = `${file}.lock`;
  fs.mkdirSync(lock, {mode: 0o700});
  try {
    const records = readRoster(file);
    if (args.action === 'list') { console.log(JSON.stringify(records, null, 2)); return; }
    if (args.action === 'start') {
      const child = spawn(args.command[0], args.command.slice(1), {detached: true, stdio: 'ignore'});
      await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
      const id = identity(child.pid);
      if (!id || id.pgid !== child.pid) { try { process.kill(-child.pid, 'SIGTERM'); } catch {} throw Error('cannot verify new process group'); }
      const record = {pid: child.pid, pgid: id.pgid, node: args.node, tag: args.tag, started: id.started, admitted: new Date().toISOString()};
      try { writeRoster(file, [...records, record]); } catch (e) { try { process.kill(-child.pid, 'SIGTERM'); } catch {} throw e; }
      child.unref();
      console.log(JSON.stringify(record));
    } else {
      const record = records.find(r => r.pid === args.pid);
      if (!record || record.pid !== record.pgid) throw Error('PID not owned by this roster');
      const id = identity(record.pid);
      if (args.action === 'forget') {
        const group = spawnSync('ps', ['-axo', 'pgid='], {encoding: 'utf8'});
        if (group.status !== 0 || group.stdout.trim().split(/\s+/).includes(String(record.pgid))) throw Error('group still present or cannot verify');
        writeRoster(file, records.filter(r => r.pid !== record.pid));
        console.log(`removed verified absent group ${record.pgid}`);
        return;
      }
      if (!id || id.pgid !== record.pgid || id.started !== record.started) throw Error('identity mismatch or leader gone; refuse signal and retain record');
      process.kill(-record.pgid, 'SIGTERM');
      // Keep record until caller verifies termination, then invoke forget.
      console.log(`SIGTERM sent to verified group ${record.pgid}; record retained for verification`);
    }
  } finally { fs.rmdirSync(lock); }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error(`ERROR ${e.message}`); process.exitCode = 1; });
