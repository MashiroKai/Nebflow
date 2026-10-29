# RELEASE NOTES — stand 2026.10.26-beta.1

> 版本：`2026.10.26-beta.1`（自 `2026.10.21-beta.2`）
> 发布批：作者令 2026-10-26 01:15「beta.1 今晚发布」；构建主线 **KAI**（Mac 不再构建，10-25 定版）
> 出包：KAI `sbt -batch` assembly @ tree `434585055`（main `3dfa54b03` + 版本推进单提交）；流转 `ops/bin/stand-jar-pull`（SHA256 双端对账 + 原子落位 + 指针切换）；放行门：boot-smoke（隔离起停 + listener/health/WS）绿。
> 边界：#891（UI 修整）、#892（会话稳定）在飞，不 included，ride 下一版。

## 本版入列（9 件）

1. **感知 boot 崩溃修复（P0）** — PerceptionCore 构造期 `sweepLoop`/`spoolLoop` 自引用 StackOverflow → `IO.defer` 修复，boot 即崩类清零；4 回归钉。`127ff2c59`
2. **完成回执投递去重（#886）** — 持久投递标记 + 恢复重放闸 + 重投 ack 身份归位，重启后回执不再重复投递。`815cad90c`
3. **KAI 构建主线切换 + ops 工具链** — 构建主线正式切 KAI（bundle 通道同步 → `sbt -batch` 出包 → `stand-jar-pull` 拉回三步日常流）；ops 资产入库：`stand` 生命周期守卫（双启动/端口/身份门）、`stand-jdk` 自愈、`release.sh` 五子命令批准门、`boot-smoke` 冒烟门禁。`d4f490b56` `148e183fa` `53340b83d` `690d538de` `0439186dc`
4. **P0 复活双件** — 飞书入站图（#809）与带附件消息分片收发（#810）收口复活；微信探测器按腿 streak + `mediaFailureNotice` 闸位分离。`bc9215d9f` `1103d7450`
5. **生图能力复活（`STAND_IMAGEGEN` 门控）** — 重启后 refresh 恢复，OFF/ON 双形态电池全绿，缺省关、门控开。`ca4ab6c79` `586442fbf`
6. **canvas 文件刷新 + 源码提速** — 文件页签右下刷新钮 + 查看源码链路提速（作者令 2026-10-25 批）。`ae30d1caf`
7. **面板「候合并」状态** — 子智能体面板候合并状态与队列位次可视化 + merge-queue 滞留两病灶修复（工件处置兑现 + 滞留告警退避重试）。`424ed1c77` `772b70555`
8. **6138 端口主线** — 引擎默认网关端口 8080→6138 单一来源（`Defaults.GatewayPort`），ops/autostart/文档全线对齐。`d792187f7`
9. **微信腿修复** — msgloop 入站指数退避，side-car 风暴抑制。`a194783b3`

## 生命周期说明

- **在产 daemon 不动**：当前在产实例（sbt 前台，pid 72160，端口 6138，00:53 起）零触碰；新包已落发布位并切 `current-version` 指针，**下次 `stand stop && stand start` 生效**。
- 回滚：`~/.stand/bin/current-version` 写回 `2026.10.21-beta.2`（或上一指针，见 `previous-version`）后重启即回。
