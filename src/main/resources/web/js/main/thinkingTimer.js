// main.js 抽取(FE组件化批次七 2026-09-28):thinking 计时器族 模块。
// stream-ux redesign（2026-10-03，A 侧原 main.js :137-:145 改动随簇迁入本模块）：
// 旧的 `.thinking-placeholder` 气泡与其 interval 计时器**已退役**——「思考中」面
// 改由 turn 行的 dots 项承担（workline.js startWorklineDots，计时器自持彼处）；
// 启动助手 startThinkingTimer 随之删除（全仓零调用点），stop 助手保留为**无害
// 空操作**——chat.js 的 placeholder 分支与 input.js 清理等既有调用点照旧可读。
export function stopThinkingTimer() {}
