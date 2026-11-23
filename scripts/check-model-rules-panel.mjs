#!/usr/bin/env node
// check-model-rules-panel.mjs — 模型规则面板静态闸（P5/P6）。
// 断言：面板模块存在且挂入设置页；三态键两语种齐全；保存链路 GET/PUT/mode 路由存在；
// 422 行号错误回显；框图节点滚行钩子存在。第二参 = 可选根目录（自证用：指向副本树应变红）。
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(process.argv[2] || fileURLToPath(new URL('../', import.meta.url)));
const rd = (rel) => {
  const p = join(root, rel);
  return existsSync(p) ? readFileSync(p, 'utf8') : '';
};

const panel = rd('src/main/resources/web/js/sidebar/modelRulesPanel.js');
const sidebar = rd('src/main/resources/web/js/sidebar.js');
const zh = rd('src/main/resources/web/js/locales/zh-CN.js');
const en = rd('src/main/resources/web/js/locales/en.js');
const routes = rd('src/main/scala/nebflow/gateway/ConfigRoutes.scala');

const checks = [
  ['面板模块导出 mountModelRulesSection', /export function mountModelRulesSection/.test(panel)],
  ['面板请求 /api/model-rules 与 /api/model-rules/mode', panel.includes("'/api/model-rules'") && panel.includes("'/api/model-rules/mode'")],
  ['保存期 422 行号错误回显', /status === 422/.test(panel) && /data-err-line/.test(panel)],
  ['框图节点滚行钩子（data-src-line + scrollTop）', /data-src-line/.test(panel) && /scrollTop/.test(panel)],
  ['三态常量 follow/slots/rules', /MODES\s*=\s*\['follow',\s*'slots',\s*'rules'\]/.test(panel)],
  ['设置页挂载节 model-rules-section', sidebar.includes("id: 'model-rules-section'") && sidebar.includes('mountModelRulesSection(')],
  ['zh-CN 三态键', ['modelRules.mode.follow', 'modelRules.mode.slots', 'modelRules.mode.rules'].every((k) => zh.includes(`'${k}'`))],
  ['en 三态键', ['modelRules.mode.follow', 'modelRules.mode.slots', 'modelRules.mode.rules'].every((k) => en.includes(`'${k}'`))],
  ['后端 GET/PUT model-rules 与 mode 路由', routes.includes('"model-rules"') && routes.includes('"model-rules" / "mode"')],
];

let bad = 0;
for (const [name, ok] of checks) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
  if (!ok) bad++;
}
console.log(bad ? `model-rules-panel: ${bad} FAIL` : 'model-rules-panel: clean');
process.exit(bad ? 1 : 0);
