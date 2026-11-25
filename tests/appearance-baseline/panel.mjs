#!/usr/bin/env node
// 阶段接口：不能采集的面板必须报错，不得以空结果冒充成功。
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = fileURLToPath(new URL('.', import.meta.url));
const [operation, name, output] = process.argv.slice(2);
const panels = join(dir, 'panels');
const results = resolve(output || join(tmpdir(), 'appearance-baseline-results'));
const ids = readdirSync(panels).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5)).sort();
function valid(id) {
  if (!ids.includes(id)) throw new Error(`未知面板: ${id}`);
  return id;
}
function capture(id, dest, shotDir) {
  valid(id);
  if (id.endsWith('-messages')) throw new Error('待 #3156 合入 feat/base 后重采');
  if (id !== 'desktop-todo') throw new Error('尚未实现真实入口和逐元素可见性验证');
  mkdirSync(shotDir, { recursive: true });
  const result = spawnSync(process.execPath, [join(dir, 'capture-desktop-todo.mjs'), dest, join(shotDir, `${id}-light.png`), join(shotDir, `${id}-dark.png`)], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error((result.stderr || result.stdout || `退出码 ${result.status}`).trim());
  return result.stdout.trim();
}
function baselinePath(id) { return join(dir, `${valid(id)}.draft.json`); }
function compare(id) {
  const path = baselinePath(id);
  if (!existsSync(path)) return { panel: id, status: 'SKIPPED', reason: '无已批准基线' };
  const base = JSON.parse(readFileSync(path, 'utf8'));
  if (base.approved === false) return { panel: id, status: 'SKIPPED', reason: '未批准' };
  if (base.approved !== true || !base.approvedBy || !base.approvedAt || !base.sourceCommit) throw new Error(`${id}: 批准字段无效`);
  mkdirSync(results, { recursive: true });
  const candidate = join(results, `${id}-actual.json`);
  capture(id, candidate, results);
  const actual = JSON.parse(readFileSync(candidate, 'utf8'));
  const differences = [];
  for (const [theme, variant] of Object.entries(base.variants)) {
    const got = actual.variants[theme];
    if (!got) { differences.push({ theme, reason: '主题缺失' }); continue; }
    for (const [element, value] of Object.entries(variant.elements)) {
      const current = got.elements[element];
      if (!current) { differences.push({ theme, element, reason: '元素缺失' }); continue; }
      for (const [property, expected] of Object.entries(value.styles)) {
        if (current.styles[property] !== expected) differences.push({ theme, element, selector: value.selector, property, expected, actual: current.styles[property] });
      }
      if (JSON.stringify(value.box) !== JSON.stringify(current.box)) differences.push({ theme, element, property: 'box', expected: value.box, actual: current.box });
    }
  }
  return { panel: id, status: differences.length ? 'FAIL' : 'PASS', differences };
}
try {
  if (operation === 'capture' || operation === 'update') {
    valid(name); mkdirSync(results, { recursive: true });
    const dest = operation === 'update' ? baselinePath(name) : join(results, `${name}.json`);
    if (operation === 'update' && existsSync(dest) && JSON.parse(readFileSync(dest, 'utf8')).approved === true) throw new Error('拒绝覆盖已批准基线；需先由作者决定更新方式');
    console.log(capture(name, dest, results));
  } else if (operation === 'compare') {
    const records = (name === 'all' ? ids : [valid(name)]).map(compare);
    mkdirSync(results, { recursive: true });
    writeFileSync(join(results, 'compare.json'), JSON.stringify(records, null, 2) + '\n');
    for (const row of records) console.log(`${row.status} ${row.panel}${row.reason ? `: ${row.reason}` : ''}`);
    if (records.some(row => row.status === 'FAIL')) process.exitCode = 1;
  } else if (operation === 'batch') {
    mkdirSync(results, { recursive: true });
    const records = ids.map(id => {
      try {
        const dest = baselinePath(id);
        if (existsSync(dest) && JSON.parse(readFileSync(dest, 'utf8')).approved === true) throw new Error('拒绝覆盖已批准基线');
        capture(id, dest, results); return { panel: id, status: 'PASS', screenshots: 2 };
      }
      catch (error) { return { panel: id, status: 'FAIL', screenshots: 0, reason: error.message }; }
    });
    writeFileSync(join(results, 'batch.json'), JSON.stringify(records, null, 2) + '\n');
    for (const row of records) console.log(`${row.panel}\t${row.status}\t${row.screenshots}\t${row.reason || ''}`);
    if (records.some(row => row.status === 'FAIL')) process.exitCode = 1;
  } else throw new Error('用法: node panel.mjs <capture|update|compare|batch> <面板名|all> <产物目录>');
} catch (error) { console.error(error.message); process.exitCode = 2; }
