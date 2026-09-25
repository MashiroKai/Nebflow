/* Phase 5 解耦(行为保持重构,2026-09-25)。 */
package nebflow.core

// NebflowLogger 定义已整体上移 nebflow.shared(logging.scala,本文件原内容逐字迁移);
// 此处按裁定保留原名 type/val 转发(core→shared 为合法方向),既有
// `import nebflow.core.NebflowLogger` 与 FQN 引用照常解析,全仓引用面零改动。
type NebflowLogger = nebflow.shared.NebflowLogger

val NebflowLogger = nebflow.shared.NebflowLogger
