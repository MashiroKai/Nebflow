// chatMount.js — 对话 UI 壳层的原位装配点（chat-ui-package 批①②，2026-11-16）。
//
// 时序契约：本模块**必须是 js/main.js 的首位 import**。ES 模块图按 import 声明
// 顺序深度优先求值 —— 本模块求值时，#input-bar / #search-overlay 两个静态壳
// （index.html 只留空锚点 + data-chatui-mount 标记）被 chatUi 工厂填充完毕，
// 之后才轮到 branding/state/… 以及任何会在**求值期**取这些节点的应用模块。
// 迁移前它们是静态标记（文档解析期即在）；本模块把「先于一切应用代码」显式化：
// 挪走本 import 或调换顺序 = 打破契约。
//
// 为什么不放在 index.html 里 inline import：prod 构建（scripts/build-web.mjs）
// 不拷贝 js/ 目录、且要求所有源模块进打包图 —— 本模块经 main.js 进图，孤儿
// 守卫同时覆盖。data-chatui-mount 标记是一次性引导属性（非 face 前缀 id 的
// 选择器写法，避免跨面 DOM 直查判据），装配完成即摘除，不留运行期面。
import { composer, searchShell } from './chatUi.js';

const composerMount = document.querySelector('[data-chatui-mount="composer"]');
if (composerMount) {
  composer({ variant: 'main', mount: composerMount });
  composerMount.removeAttribute('data-chatui-mount');
}
const searchMount = document.querySelector('[data-chatui-mount="search"]');
if (searchMount) {
  searchShell({ mount: searchMount });
  searchMount.removeAttribute('data-chatui-mount');
}
