# Nebflow→NebLink 更名参数化改造（已批准 2026-08-17）

> 用户拍板：批准按 4 批派发 ｜ 子系统走路线 B（沿用 neblink）｜ 改名时包名一并改 ｜ 新域名暂占位
> 完整分析版见 /tmp/rebrand-param-plan.md（含图表、示例行号）。本文件为实施派发版。

## 已确认决策
- D1 参数化 4 批全做，派 nebflow-project，批1/2 可并行，批3 依赖批1，批4 依赖全部
- D2 设备互联子系统名保持 `neblink`；协议字段（X-Neblink-Device、/api/device/*、neblinkServer）本次不动，但收口到 Protocol.scala
- D3 rebrand.sh 覆盖包目录树 rename（package nebflow.* ×328 文件 → neblink）
- D4 brand.conf domain 用占位 `neblink.example`，改名前用户定值

## 批次任务

### 批 1（L1 展示层参数化，~1 天）
- 新增 `src/main/scala/nebflow/core/Branding.scala`；`brand.conf` 经 sbt 打进 JAR resources
- gateway 静态资源服务渲染 index.html 时注入 `window.__BRAND__`
- `src/main/resources/web/` 118 处品牌文案、`<title>`（index.html:5）、README 33 处、telemetry UA（WebSearchTool.scala:257）收口到 Branding/window.__BRAND__
- 官网 `lib/config.ts` SITE 补 NEBLINK_URL 等字段（app/api/profile/route.ts 及 devices/update/route.ts 的硬编码 ×2）
- 验收：`rg -i nebflow src/main/resources/web` 仅剩品牌注入相关；curl 首页含 window.__BRAND__；sbt test 全绿；冒烟真实启动+curl /api/health

### 批 2（L2 构建层参数化，~1.5 天，可与批 1 并行）
- 新增 `project/Branding.scala`：build.sbt name/organization/jar 名（现在 name:="nebflow"）由 brand.conf 驱动
- CI 三 workflow（ci.yml/release.yml/auto-release.yml）读 brand.conf → env；artifact 名、coscmd -b、-Dnebflow.webdist=1 全参数化
- packaging/build-dmg.sh（:62 --name Nebflow、:66 --main-class、:72 --mac-package-name）、build-msi.sh 同构改造
- release/install.{sh,ps1} 模板化（COS 桶 nebflow-releases-1411212853、GitHub MashiroKai/Nebflow、wrapper 名 install.sh:348、~/.nebflow install.sh:151,365）
- Makefile/Dockerfile/scripts/nebflow-inject.sh 同步
- 验收：brand.conf 临时改 productName→sbt assembly jar 名随变（验证后还原）；`rg -i nebflow .github/ packaging/ release/` 仅剩 conf 读取处；sbt test 全绿

### 批 3（L3 运行时兼容层，~2 天，依赖批 1）
- PathUtil（paths.scala:109-114）双读：新目录优先，miss 回落 ~/.nebflow；首次启动一次性迁移（copy 非 move + .migrated 标记）
- Branding.env()：新前缀 miss 回落 NEBFLOW_ 前缀（9 个变量：NEBFLOW_HOME/HEADLESS/TOKEN/GATEWAY_PORT/URL/VERSION/TELEMETRY/PID/GATEWAY_HOST）
- 配置文件名双读：nebflow.json → 新名（字面量 ×24 处收口到 Branding.configFileName）
- web/js/branding.js：localStorage 旧键迁移（nebflow_token 等 11 键，读旧写新删旧——token 丢失=登出）
- 新增 Protocol.scala：收口 X-Neblink-Device（RemoteExecutor.scala:369）、/api/device/* 路径、neblinkServer 字段名、默认 serverUrl（RestApiRoutes.scala:2287-2289）
- install 命令 URL 4 处重复（WebSocketRoutes.scala:2538-2541、SystemCommands.scala:60-63,110-111、RemoteUpdateAction.scala:23-26）收口
- skill 目录回退：skills/nebflow/visual-style 被引用 ×8，新名 miss 回落旧名
- 验收：旧格式 ~/.nebflow 测试夹具下启动→会话/认证/device 身份（client-id）保留；NEBFLOW_HOME 仍被识别；迁移逻辑单测；冒烟链=真实启动+curl+Playwright（断言 window.__BRAND__ 注入、#chat-input 渲染、无 console error、WS 建立）

### 批 4（一键脚本+文档，~1 天，依赖全部）
- scripts/rebrand.sh：--dry-run（替换清单 diff 不落盘）/ --apply（前置校验→包树 rename→残留扫描→sbt clean assembly test→冒烟+迁移冒烟→dmg/msi→渲染安装脚本→输出人工 checklist）
- neblink-server Rust 侧品牌常量模块（域名/CORS/静态页文案）；docker-compose NEBLINK_*、.env 域名字段化
- REBRAND.md 人工 checklist：DNS 301、GitHub org+transfer、OAuth App 回调、COS 新桶双写（旧桶保留≥12月）、VPS Caddy 双域名灰度、官网部署、新 logo 三件套
- 验收：--dry-run 输出完整清单；--apply 在临时 clone 跑通全链路（sbt test 全绿+冒烟通过）

## 硬性红线（每批验收第一项）
真实启动验证不可跳过：java -jar 真实构建产物启动 → curl /api/health 200 → Playwright 渲染断言。迁移批次必须含旧数据夹具迁移冒烟。

## 外部资源（不在参数化范围，仅 checklist）
GitHub MashiroKai/Nebflow transfer、OAuth App Ov23liu3nC6jzBmNIQNB 回调、COS 桶 nebflow-releases-1411212853、域名 nebflow.space/neblink.nebflow.space（阿里云 DNS→VPS 203.0.113.10）、官网/neblink-server 部署、VPS 凭据见主仓 NEBLINK_HANDOVER.md（gitignored，勿外泄）
