#!/usr/bin/env node
/**
 * 编译 ptc-javascript 的 TypeScript 源到 dist/（**源码是 TS，产物是 JS**；插件运行期不转译）。
 * 依赖临时安装（typescript + @types/node），编译完即清理；产物（dist/*.js）随包提交，
 * 装载侧不需要 npm / 构建步骤。
 *
 *   node scripts/ptc/build-ts.mjs
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const PKG = path.join(ROOT, 'plugins/ptc-javascript/skills/ptc-javascript');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'ptc-tsc-'));
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';

try {
  console.log(`[build-ts] installing typescript + @types/node into ${work}`);
  execFileSync(npm, ['init', '-y'], { cwd: work, stdio: 'ignore', shell: process.platform === 'win32' });
  execFileSync(npm, ['i', '--no-audit', '--no-fund', 'typescript@5.6', '@types/node@20'], {
    cwd: work, stdio: 'inherit', shell: process.platform === 'win32',
  });
  const tsc = path.join(work, 'node_modules', '.bin', process.platform === 'win32' ? 'tsc.cmd' : 'tsc');
  fs.rmSync(path.join(PKG, 'dist'), { recursive: true, force: true });
  execFileSync(tsc, ['-p', 'tsconfig.json', '--typeRoots', path.join(work, 'node_modules', '@types')], {
    cwd: PKG, stdio: 'inherit', shell: process.platform === 'win32',
  });
  fs.writeFileSync(path.join(PKG, 'dist', 'package.json'), '{ "type": "module" }\n');
  console.log(`[build-ts] ok → ${path.join(PKG, 'dist')}`);
} finally {
  fs.rmSync(work, { recursive: true, force: true });
}
