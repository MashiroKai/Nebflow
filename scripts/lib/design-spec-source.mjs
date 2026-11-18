// design-spec-source.mjs — 设计规范「单源外置」定位器。
//
// 背景（single-source-rehome 批 2026-11-18）：规范**正文**（毛玻璃 / 输入面 /
// 面板族 / 按钮扁平 / 渐进式披露 / 解释性文字 六篇 + 几何刻度账本）唯一一份已
// 迁进 stand-dev 插件的包内，本仓不再保留第二份拷贝（作者令「不要冲突和重复」
// ——两份同主题规范件必然漂移）。本仓留下的是**判定面**（扫 web/ 的静态闸），
// 规范面按下面的顺序外置定位。
//
// 包内布局（stand-dev 插件）：
//   <包根>/references/frontend/<spec>.md      规范条文（六篇）
//   <包根>/assets/design-system/              令牌账本 tokens.css + 组件原型 components.css
//
// 定位顺序（自上而下，先命中且**目标文件确实存在**者胜）：
//   ① --spec-root=<dir>      命令行显式指定（测试把规范拷进一次性树后跑同一判据）
//   ② STAND_DEV_PLUGIN_ROOT  直接给「包根」
//   ③ STAND_DEV_ROOT         给 stand-dev 检出根（<root>/skills/stand-dev 即包根）
//   ④ ~/.stand/plugins/stand-dev   已安装插件（<插件根>/skills/stand-dev 即包根）
// 四条都落空（CI 检出、未装插件）⇒ 调用方按「环境不可用」处置：
//   · 闸 standalone：打印原因并以退出码 2 结束（环境错误 = 规范面缺失，不是绿）；
//   · run-checks：把这些闸标 envSkip，退出码 2 记为 SKIP（CI 仍能跑完，不假绿成 PASS）。

import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/** stand-dev 包根相对插件根的路径（插件根/skills/stand-dev）。 */
const PKG_UNDER_PLUGIN = join('skills', 'stand-dev');

/** 判定「这是一个包根」的最小锚：规范条文目录存在。 */
function looksLikePkg(dir) {
  return Boolean(dir) && existsSync(join(dir, 'references', 'frontend'));
}

/** 包根候选（有序；不去重，先命中先赢）。 */
function candidatePkgRoots(argv) {
  const roots = [];
  const specRoot = argv.find((a) => a.startsWith('--spec-root='));
  if (specRoot) roots.push(specRoot.slice('--spec-root='.length));
  if (process.env.STAND_DEV_PLUGIN_ROOT) roots.push(process.env.STAND_DEV_PLUGIN_ROOT);
  if (process.env.STAND_DEV_ROOT) roots.push(join(process.env.STAND_DEV_ROOT, PKG_UNDER_PLUGIN));
  roots.push(join(homedir(), '.stand', 'plugins', 'stand-dev', PKG_UNDER_PLUGIN));
  return roots;
}

/** 解析 stand-dev 包根；找不到返回 null。 */
export function resolveSpecSource(argv = process.argv.slice(2)) {
  return candidatePkgRoots(argv).find(looksLikePkg) || null;
}

/**
 * 解析包内一件规范/资产文件的绝对路径（relPath 用 `/` 分隔，相对包根）。
 * 包根或文件缺失都返回 null —— 缺一件就是环境不完整，调用方统一按环境错误处置。
 */
export function resolveDesignSpec(relPath, argv = process.argv.slice(2)) {
  const pkg = resolveSpecSource(argv);
  if (!pkg) return null;
  const abs = join(pkg, ...relPath.split('/'));
  return existsSync(abs) ? abs : null;
}

/** 缺规范时的统一提示（stdout/stderr 都印，人读与日志都看得见）。 */
export function specMissingMessage(specDesc) {
  return `环境错误：设计规范单源缺失（${specDesc}）—— 规范正文唯一一份在 stand-dev 插件包内，`
    + '本仓不保留第二份。请设 STAND_DEV_ROOT=<stand-dev 检出根>（或 STAND_DEV_PLUGIN_ROOT=<包根>），'
    + '或安装 stand-dev 插件后重跑。';
}
