// profileState.js — 方案档案运行时态（认知二期产品批①选档器）
// 零依赖模块：utils.js 渲染管线（human 过滤层折叠默认）与 presentationProfile.js
// （WS/设置面板）共同读写的唯一态面——不 import 任何兄弟模块，杜绝 utils ↔
// ws 导入环。档定义的内置默认 = 引擎 PresentationProfile 五档的镜像（前端
// 在 WS 数据到达前按默认档渲染；数据到达后 setCurrentProfile 覆盖）。

let current = {
  key: 'expert-minimal',
  maxLines: 3,
  keyNumbers: 2,
  foldDefault: 'anomaly-top',
  imageDensity: 'low',
  reminderCadence: 'quiet',
  supervision: 'exception',
  pendingStyle: 'blocking',
  teachingAllowed: false
};

// 代际计数：切档/改覆盖 ⇒ +1——utils.js 的 markdown LRU 缓存键掺此值，
// 旧档缓存自然失效（切档即时生效的缓存面）。
let generation = 0;

export function setCurrentProfile(def) {
  if (def && typeof def === 'object') {
    current = { ...current, ...def };
    generation += 1;
  }
}

export function getCurrentProfile() {
  return current;
}

export function profileGeneration() {
  return generation;
}
