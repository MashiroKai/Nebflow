## 8. 开放问题清单（候作者裁）

| # | 问题 | 背景/选项 | 建议 |
|---|---|---|---|
| Q1 | `loc` 字段名与载荷兼容窗 | 旧引擎/旧前端混布期，`loc` 缺席走兜底保守判定（§5.2）；字段名是否用 `loc` 短键以省载荷 | 短键 `loc` + 兜底，M1 落 |
| Q2 | 设备通道取消信令 | 对端停发需要一条「取消取件」消息（WS op 或复用既有消息族）；中断后分块账保留多久 | 新增专用 op，账保留 = 会话周期 |
| Q3 | 整件 hash 的计算位置与信任 | 设备腿 hash 由发送端算（接收端复核分块校验）还是接收端算；大文件 sha256 耗时 | 发送端算+分块校验链绑定，接收端抽查 |
| Q4 | server 桶是否用 HTTP Cache-Control 替账本 | 双缓存（HTTP 300s + 账本 30min）语义重叠；或 server 面只靠账本、HTTP 改 no-store | 保持双层（HTTP 矡味低成本），不做替换 |
| Q5 | 「已下载过」的口径 | #542 档2 覆盖「预览过」；下载腿（saveBlob 到浏览器下载目录）不入账，字面语义差一口。是否把下载完成事件也写入账本（字节并不在手，仅记「曾下载」元数据）供提示「已在本机下载目录」？ | 记元数据不记字节，M2 可选 |
| Q6 | 45ms 是观测值非代码钉 | `≈45ms` 出自测量批（`evidence/20261019_082500_imgslow538-segmented-timing.md`），代码内无字面性能钉；是否落一个性能预算 spec（如本机全链 p95 < 100ms） | 落 M1 验收 spec 一条 |
| Q7 | >200MB 本机大件 | nf-file 直读上限 200MB（`FileRefs.scala:33`），超大 evidence/视频走哪条腿（Range 分段流？下载到账本？） | M3 口径：Range 流式 + 不入账（只在 HTTP 缓存） |
| Q8 | 移动端 Cache Storage 配额 | reports/ 已有 mobile mockup 线；iOS Safari 配额远小于桌面 256MiB 预算，分桶值需按平台缺省 | 分桶缺省按 UA 分档，M2 |
| Q9 | 清缓存入口 | 底盘注记「候作者示」（`nfFileCache.js:234`） | 建议 M2 随分桶一并做 |
| Q10 | 多标签索引竞态 | 现状可容忍（`nfFileCache.js:12-13` 申报）；改 IndexedDB 可解但引入迁移成本 | 维持现状，观察命中率后再议 |
| Q11 | v2.1 spec 文档本体不在库 | 代码大量引用「设备无关架构 v2.1」§编号（`FileTransferAction.scala:35,39` 等），worktree/主仓/`~/.nebflow/docs` 四处均无原文；§2.3/§2.4 口径以代码注释为准 | 建议作者入库或授权补录，防口径漂移 |
| Q12 | 410 过期与账本对账口径 | 账本命中不回源（`nfFileCache.js:14-17`）vs 服务端 410=终态（`SocialRoutes.scala:343`）：TTL 30min 窗内已过期件仍出帧 | M2 失效事件腿带上 `expired` 终态事件（§7 M2-3） |
| Q13 | 渠道入站图是否进 web 消息面 | feishu/weixin 落盘图现只以「已存至」文本行进 agent 会话（`WeixinIlinkBridgePlugin.scala:1360-1374`），web 端无渲染 | 属产品裁定，候作者示；位判 `local` 已备 |
