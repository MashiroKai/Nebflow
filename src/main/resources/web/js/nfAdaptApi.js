// nfAdaptApi.js — 下游反适配脚本的**公开扩展面**（R1 门面化，2026-11-02）。
//
// 断裂面：triple-star 的 front 镜像在下游维护一份产品差异反适配层
// `apps/stand/ui/nf-adapt.js`，`images/stand-app/build_oci.py:138` 把它硬编码
// 投到镜像的 `ui/js/nf-adapt.js`。它原来靠 13 处**兄弟相对 import** 取上游符号
// （`import('./ws.js')` 一类）；而镜像装的 UI 是本仓的产物树 `build/web-dist`
// —— 里面有 `assets/*.js`，**没有 `js/` 目录**，那 13 处 0/13 可解析，于是镜像
// 装不了任何打包产物。
//
// 本文件就是那道焊缝：把下游实际用到的 7 个模块 / 10 个符号**集中发布**到
// `window.__nfAdaptApi`。下游改成只读 window ⇒ 源树（web/js/ 在盘）与产物树
// （无 js/ 目录）双面通吃，`build_oci.py` 一行不用动。
//
// 纪律：
//  · 只做「取值与发布」，零业务逻辑：不连 WS、不读 DOM、不建监听，模块体只
//    装配一个命名空间对象（唯一副作用 = 那次 window 赋值，即本文件存在的理由）。
//  · 每个成员走 getter（惰性取值）：模块初始化阶段零触碰子系统；发布的是
//    **引用**而非快照（state 是模块级可变对象，下游必须写回同一个对象）。
//  · 字段名 = 下游实际使用名。**改这里的字段名等于破坏下游契约**：消费方在
//    triple-star 仓（跨仓 grep 不到），改名前必须先给下游留降级期。
//  · 懒加载族（flowMapTab / delegateChainView）保持 import() 边界——它们本就
//    不在入口 chunk 的静态图上（只有 delegateChainView 家族动态引），静态收进
//    来会把整个 flow map 家族拽进入口包。

import { sendWs, onMessage } from './ws.js';
import state from './state.js';
import { renderSessionSidebar, saveNewProvider } from './sidebar.js';
import { getAllModelRefs } from './modelPanel.js';
import { showProviderModal } from './sidebar/providersUi.js';

// ── 命名空间对象：成员全部 getter，取值发生在下游读取那一刻 ──────────────
const ws = {
  /** @returns {typeof sendWs} */
  get sendWs() {
    return sendWs;
  },
  /** @returns {typeof onMessage} */
  get onMessage() {
    return onMessage;
  },
};

const sidebar = {
  /** @returns {typeof renderSessionSidebar} */
  get renderSessionSidebar() {
    return renderSessionSidebar;
  },
  /** @returns {typeof saveNewProvider} */
  get saveNewProvider() {
    return saveNewProvider;
  },
};

const modelPanel = {
  /** @returns {typeof getAllModelRefs} */
  get getAllModelRefs() {
    return getAllModelRefs;
  },
};

const providersUi = {
  /** @returns {typeof showProviderModal} */
  get showProviderModal() {
    return showProviderModal;
  },
};

/**
 * 下游契约面。消费方（镜像内 `ui/js/nf-adapt.js`）**只能**通过这里取上游符号：
 * 它跑在 bundle 之外，任何 `import('./x.js')` 在产物树里都不解析。
 */
const nfAdaptApi = {
  /** ws.js：帧发送 + 帧订阅 */
  get ws() {
    return ws;
  },
  /** state.js 默认导出（同一个可变对象——下游读写都落在这份状态上） */
  get state() {
    return state;
  },
  /** sidebar.js：会话列表渲染 + provider 落盘（写路径单点） */
  get sidebar() {
    return sidebar;
  },
  /** modelPanel.js：可选模型引用表 */
  get modelPanel() {
    return modelPanel;
  },
  /** sidebar/providersUi.js：provider 编辑弹窗 */
  get providersUi() {
    return providersUi;
  },
  /** 懒加载：Flow Map 标签页（保持懒 chunk 边界，见文件头纪律） */
  loadFlowMapTab: () => import('./flowMapTab.js'),
  /** 懒加载：delegate 链视图 */
  loadDelegateChainView: () => import('./delegateChainView.js'),
};

window.__nfAdaptApi = nfAdaptApi;

export default nfAdaptApi;
