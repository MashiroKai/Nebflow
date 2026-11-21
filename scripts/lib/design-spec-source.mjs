// design-spec-source.mjs — 设计规范正文的定位器（2026-11-21 起单源在本仓）。
//
// 史（读这一行就够）：single-source-rehome 批（2026-11-18）曾把规范**正文**（毛玻璃 /
// 输入面 / 面板族 / 按钮扁平 / 渐进式披露 / 解释性文字 六篇 + 几何刻度账本 + 令牌账本
// css）外置进 stand-dev 插件包，本仓只留判定面，8 道静态闸靠「装没装插件」决定跑不跑。
// 实测代价：插件 v1.5.0 里一份正文都没有（正文只在源仓一条未合入的旁支上）⇒ 8 道闸长期
// 记 SKIP，判据等于零检查。作者裁定（2026-11-21）「先查源仓 → 其次是落仓内」之后，正文
// 取回本仓 `design-language/`（唯一一份），闸随之全部实跑。
//
// 仓内布局（= `design-language/`，保留原插件包的相对形状，闸的 relPath 零改动）：
//   <仓根>/design-language/references/frontend/<spec>.md   规范条文（7 篇）
//   <仓根>/design-language/assets/design-system/           令牌账本 tokens.css + 组件原型 components.css
//
// 定位顺序（自上而下，先命中且**文件确实存在**者胜）：
//   ① --spec-root=<dir>   命令行显式指定 —— 只给**测试/一次性覆盖**用（契约测试把规范
//                         拷进一次性包根，跑同一份判据，与判定面 --root=<dir> 分离）
//   ② <仓根>/design-language   正常路径。仓内正文恒在（tracked），故 8 道闸在任何完整
//                         checkout 里都实跑，没有「环境缺席」这一档。
// 两条都落空（只可能是 checkout 残缺或 design-language/ 被删）⇒ 调用方按**红**处置：
// 闸打印原因并以退出码 2 结束，`scripts/run-checks.mjs` 不再把它折成 SKIP（envSkip 已撤
// ——「正文不在 = 判据消失」必须可见，不许静默通过）。

import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** 仓根（本文件在 <仓根>/scripts/lib/ 下，故上两级）。 */
const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

/** 规范正文目录（仓内单源；相对仓根）。 */
const SPEC_DIR = 'design-language';

/** 判定「这是一个规范目录」的最小锚：条文目录存在。 */
function looksLikeSpecDir(dir) {
  return Boolean(dir) && existsSync(join(dir, 'references', 'frontend'));
}

/** 规范目录候选（有序；先命中先赢）。 */
function candidateSpecDirs(argv) {
  const dirs = [];
  const specRoot = argv.find((a) => a.startsWith('--spec-root='));
  if (specRoot) dirs.push(specRoot.slice('--spec-root='.length));
  dirs.push(join(REPO_ROOT, SPEC_DIR));
  return dirs;
}

/** 解析规范目录；找不到返回 null。 */
export function resolveSpecSource(argv = process.argv.slice(2)) {
  return candidateSpecDirs(argv).find(looksLikeSpecDir) || null;
}

/**
 * 解析规范目录内一件文件（relPath 用 `/` 分隔，相对 `<仓根>/design-language`）。
 * 目录或文件缺失都返回 null —— 缺一件就是判据残缺，调用方统一按红处置。
 */
export function resolveDesignSpec(relPath, argv = process.argv.slice(2)) {
  const dir = resolveSpecSource(argv);
  if (!dir) return null;
  const abs = join(dir, ...relPath.split('/'));
  return existsSync(abs) ? abs : null;
}

/** 缺规范时的统一提示（stdout/stderr 都印，人读与日志都看得见）。 */
export function specMissingMessage(specDesc) {
  return `规范正文缺失（${specDesc}）—— 单源在仓内 ${SPEC_DIR}/，随仓库提交分发，`
    + `不依赖任何已安装插件。缺件只可能是 checkout 残缺或该目录被删：`
    + `恢复 ${SPEC_DIR}/（git checkout -- ${SPEC_DIR}）后重跑；--spec-root=<dir> 仅供测试覆盖。`;
}
