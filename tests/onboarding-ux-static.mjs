// onboarding-ux-static.mjs — onboarding-ux 批（作者 2026-10-05 五点）静态断言。
//
// 覆盖不适合/不值得起浏览器的结构性事实：
//   点 1  「配置模型不需要进记忆」 —— 记忆面（Soul/User 模板）零模型痕迹，
//         WS 收尾只把模型写进配置存储（apply 单参，modelRef 不进产物）。
//   点 3  「最近半年…不要这个问题」 —— 整键删除 + 节拍收口，无悬空引用。
//   点 2  「起名是会影响Header的标题的」 —— Header 走单一读点 + 改名事件。
//   点 4  「伪流式输出结束后，跳过打字的按钮应该消失」 —— onDone 单通道摘除。
//   点 5  UI 统一 —— onboarding 按钮走全站 token（发送键族绿实心 / 10px 常例）。
// 动态面（真实问卷流交互）见 onboarding-skip-typing.spec.mjs / onboarding-name-header.spec.mjs。
//
// Run: node tests/onboarding-ux-static.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SCALA = join(dirname(dirname(fileURLToPath(import.meta.url))), 'src', 'main', 'scala');
const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const readScala = (p) => readFileSync(join(SCALA, p), 'utf8');

const zh = read('js/locales/zh-CN.js');
const en = read('js/locales/en.js');
const ob = read('js/onboarding.js');
const sidebar = read('js/sidebar.js');
const css = read('css/chat.css');
const artifacts = readScala('nebflow/core/OnboardingArtifacts.scala');
const handlers = readScala('nebflow/gateway/WsConfigHandlers.scala');

let pass = 0;
const fails = [];
/** @param {string} id @param {string} what @param {boolean} ok */
function assert(id, what, ok) {
  if (ok) { pass += 1; console.log(`  PASS [${id}] ${what}`); }
  else { fails.push(id); console.log(`  FAIL [${id}] ${what}`); }
}
const has = (hay, needle) => hay.includes(needle);
/** selectorSrc 已经是 regex 源码（调用方自带转义），不要再逃逸一次。 */
const ruleBody = (hay, selectorSrc) => {
  const m = hay.match(new RegExp(selectorSrc + '\\s*\\{([^}]*)\\}'));
  return m ? m[1] : null;
};

console.log('点 3 · 「最近半年…」整题移除（无悬空引用）');
for (const [tag, src] of [['zh', zh], ['en', en]]) {
  assert(`P3.${tag}a`, `locales/${tag}：ob2.q.focus / lead / ph / label 四键整键删除`,
    !has(src, "'ob2.q.focus'") && !has(src, "'ob2.lead.focus'")
    && !has(src, "'ob2.ph.focus'") && !has(src, "'ob2.label.focus'"));
}
assert('P3.js', "onboarding.js：SCRIPT 无 id:'focus' 条目", !/id:\s*'focus'/.test(ob));
assert('P3.zh.b', 'zh write.5 收口（无「当前关注」）',
  has(zh, "'ob2.write.5': '✓ 称呼与身份'") && !/write\.5'.*当前关注/.test(zh));
assert('P3.en.b', 'en write.5 收口（无 current focus）',
  has(en, "'ob2.write.5': '✓ how to address you / day-to-day role'") && !/write\.5'.*current focus/.test(en));
assert('P3.zh.c', 'zh intro.you2 收口（最后几个 ⇒ 最后一个）',
  !has(zh, '最后几个问题') && has(zh, '最后一个问题不是关于我'));
assert('P3.en.c', 'en intro.you2 收口（last few ⇒ last question）',
  !has(en, 'The last few questions') && /The last question isn\\?'t about me/.test(en));
const userTpl = (artifacts.match(/def buildUser[\s\S]*?\.stripMargin/) || [''])[0];
assert('P3.scalaa', 'OnboardingArtifacts.scala：buildUser 模板区无「当前关注」段 / 无 focus 落点 / 无 Unfilled 占位',
  !!userTpl && !has(userTpl, '当前关注') && !/textOr\(answers, "focus"/.test(userTpl) && !has(userTpl, 'Unfilled'));

console.log('点 1 · 配置模型不进记忆（记忆面零模型痕迹）');
assert('P1.scalaa', 'OnboardingArtifacts.scala：buildSoul 模板无「- 大脑：…」行（行首判，scaladoc 叙述不计）',
  !/^- 大脑：/m.test(artifacts));
assert('P1.scalab', 'OnboardingArtifacts.scala：apply 单参（modelLabel 形参已删）',
  /def apply\(answers: Map\[String, Answer\]\): IO\[Result\]/.test(artifacts)
  && !/apply\(answers, modelLabel/.test(artifacts)
  && !/buildSoul\(answers, modelLabel/.test(artifacts));
assert('P1.scalac', 'OnboardingArtifacts.scala：buildSoul 单参',
  /def buildSoul\(answers: Map\[String, Answer\]\): String/.test(artifacts));
assert('P1.ws', 'WsConfigHandlers.scala：产物只吃 answers（modelRef 只走配置写回）',
  /\.apply\(answers\)/.test(handlers) && !/\.apply\(answers, modelRef\)/.test(handlers));
assert('P1.zh', 'zh write.2 收口（无「大脑」）', !/write\.2'.*大脑/.test(zh) && has(zh, "'ob2.write.2': '✓ 自我认知 — 名字 / 归属'"));
assert('P1.en', 'en write.2 收口（无 brain）', !/write\.2'.*brain/.test(en) && has(en, "'ob2.write.2': '✓ self-knowledge — name / belonging'"));

console.log('点 2 · 起名 ⇒ Header 标题（单一读点 + 改名事件）');
assert('P2.sb.a', 'sidebar.js：import rootName 的 rootDisplayName（单一读点）',
  has(sidebar, "import { rootDisplayName } from './rootName.js'"));
const uhsn = sidebar.match(/function updateHeaderSessionName\(\) \{[\s\S]*?\n\}/);
assert('P2.sb.b', 'sidebar.js：updateHeaderSessionName 根 agent 走 rootDisplayName()',
  !!uhsn && has(uhsn[0], "agentName === 'Nebula'") && has(uhsn[0], 'rootDisplayName()'));
assert('P2.sb.c', 'sidebar.js：监听 nebflow-root-name-change 就地刷新 Header',
  has(sidebar, "addEventListener('nebflow-root-name-change'"));
assert('P2.ob', 'onboarding.js：renameEverywhere 派发 nebflow-root-name-change',
  /function renameEverywhere[\s\S]*?nebflow-root-name-change/.test(ob));

console.log('点 4 · 伪流式结束 ⇒ 跳过按钮消失');
assert('P4.a', 'onboarding.js：skip 按钮仍在（data-skip-typing 创建面不变）', has(ob, "data-skip-typing"));
const simBlock = ob.match(/let skipBtn = null;[\s\S]*?return row;/);
assert('P4.b', 'onboarding.js：simBubble 的 onDone 单通道摘除按钮（自然完成/点击/Esc 同路）',
  !!simBlock && has(simBlock[0], 'skipBtn.remove()') && has(simBlock[0], 'onDone:'));
assert('P4.c', 'onboarding.js：skipBtn 先于 typeInto 声明（onDone 闭包可达）',
  !!simBlock && simBlock[0].indexOf('let skipBtn = null;') < simBlock[0].indexOf('typeInto('));

console.log('点 5 · UI 统一（全站 token / 发送键族绿实心 / 10px 常例）');
const prim = ruleBody(css, '\\.glass-control\\.ob-primary');
assert('P5.a', 'chat.css：ob-primary 规则体 = var(--color-primary) 绿实心（规则体零字面量绿）',
  !!prim && has(prim, 'var(--color-primary)') && !/rgba?\(/.test(prim));
const primHover = ruleBody(css, '\\.glass-control\\.ob-primary:hover:not\\(:disabled\\)');
assert('P5.b', 'chat.css：ob-primary hover 递进 var(--color-primary-hover)',
  !!primHover && has(primHover, 'var(--color-primary-hover)'));
const ghost = ruleBody(css, '\\.option-btn\\.opt-ghost');
const ghostHover = ruleBody(css, '\\.option-btn\\.opt-ghost:hover');
assert('P5.c', 'chat.css：opt-ghost 描边走 token（蓝灰字面量 rgba(145,170,210,…) 已删）',
  !!ghost && !/rgba\(/.test(ghost) && has(ghost, 'var(--color-border)')
  && !!ghostHover && !/rgba\(/.test(ghostHover));
const skipBtn = ruleBody(css, '\\.ob-skip');
assert('P5.d', 'chat.css：ob-skip 圆角 10px 常例', !!skipBtn && has(skipBtn, 'border-radius: 10px'));
const input = ruleBody(css, '\\.option-input');
assert('P5.e', 'chat.css：option-input 圆角 10px（与并排主按钮同几何）', !!input && has(input, 'border-radius: 10px'));
const small = ruleBody(css, '\\.option-btn\\.picked \\.option-text small');
assert('P5.f', 'chat.css：picked 次级文本 inherit+opacity（不再自持第二份字面量白）',
  !!small && has(small, 'color: inherit') && !/rgba\(/.test(small));
const geom = ruleBody(css, '\\.glass-control\\.ob-primary,\\s*\\.glass-control\\.ob-ghost');
assert('P5.g', 'chat.css：onboarding labeled 按钮统一几何（10px / 5px 12px / 13px / 500）',
  !!geom && has(geom, 'border-radius: 10px') && has(geom, 'padding: 5px 12px')
  && has(geom, 'font-size: 13px') && has(geom, 'font-weight: 500'));

console.log(`\n${pass} passed, ${fails.length} failed`);
if (fails.length) { console.log('FAILED: ' + fails.join(', ')); process.exit(1); }
