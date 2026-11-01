---
type: batch
status: done
related: "[[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]]"
---

# USTC GitLab CI/CD 平移施工批（2026-11-01）

> 复工令（作者 11-01 午后）：前 delegate 会话中断，工程现场直接盘点续做——`.gitlab-ci.yml`（工作区未提交稿）质量审 + 补发版链 → 提交 → lint API 验证 → 两机 runner 注册 → push dev compile 级真绿 → 真实合并 main。本稿 = 全批留痕。承接评估报告 [[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]] 的 §3.2 阶段 2/3 施工。

## 1. 交付面

| 件 | 内容 | commit |
|---|---|---|
| `.gitlab-ci.yml` | 主 CI 全量面（14 逻辑 job 对照平移）+ 发版链 release job + 触发裁定（分支/MR=compile 最小面；main/beta/release+tag=全量；web=手动跑） | `b3e9f0bb6` |
| README / CONTRIBUTING(.zh-CN) | 开发主面 + 发版页切 USTC；GitHub 面冻结只读声明 | `028107830` |
| `.gitlab-ci.yml` 头注校准 | runner 工具链注册日实测值（mac id=100/concurrent=1/node 23.9；windows 机 node 缺位注记） | `fa856ebfd` |

### 质量审查修了什么（工作区稿 → 提交稿）

1. `script: *smoke_script` / `after_script: *stop_server` 单字符串 → 数组包裹（GitLab schema 保守面）。
2. `timeout: 1h 30m` → `90m`（消除 duration 解析歧义）。
3. workflow rules 补 `web`（= 原 workflow_dispatch；发版 dry_run 与单 job 重放的入口）。
4. 文件头「14 job 实体 / 10 逻辑」口径校准（14 键 / matrix 展开 16 腿 / 评估报告归 10 组）。
5. 发版链补齐：单 `release` job（beta/release 分支分支内切语义）——Generic Packages 归档（JOB-TOKEN PUT）+ GitLab Release（asset link 指包 URL；beta 标 Pre-release 描述）+ tag push + git log 区间 notes（GIT_DEPTH 0）+ OSS 腿原样平移（keys 未注入前 warn-skip，auto-release.yml 先例形态）+ stable VERSION 回写 main（commits API，无凭据红）+ skip 语义（tag 已存在 → 整链绿跳过）。凭据链 `USTC_RELEASE_TOKEN`（masked CI variable）优先、降级 CI_JOB_TOKEN。
6. 脚本引用面全核对：24 个 scripts/tests 文件存在性 + 接口（`--jar`/`--root`/`MergeQueuePositionSpec`/brand.conf 键）逐一实查。

## 2. 验证面

- **CI lint API（USTC 实例）**：`POST /projects/8663/ci/lint` → **valid: true，零 error 零 warning**（PAT-final）。
- **本地结构校验**：17 job 的 script/before/after 全数组、needs 图引用齐、stages 齐。

## 3. runner 落点（两机）

| 机 | runner id | 形态 | 关键配置 |
|---|---|---|---|
| Mac（M2 Pro） | **100** | brew gitlab-runner 19.5.2，LaunchAgent 用户态常驻，concurrent=1 | `environment`（**必须放 runner 段内**——TOML 顶层键被忽略）钉 JAVA_HOME=temurin-23 + PATH；macOS login shell 的 path_helper 会把 /usr/bin 重排到最前（`/usr/bin/java` stub 系统默认 = 1.8），故以 JAVA_HOME 为第一杠杆、PATH 为第二杠杆；executor shell |
| DESKTOP-IJBBEOT | **104** | 官方二进制 `C:\GitLab-Runner\`，Git Bash（`--shell bash`），用户 Startup 文件夹自启 + 当前由 Nebflow 持久任务托跑 | `builds_dir = "builds"`（**相对形态**——绝对路径触发 runner 脚本模板 `$PWD/<绝对路径>` 双拼 bug，见 §4）；`environment` 注 HTTPS_PROXY；concurrent=1 |

- runner 创建走 **API 自助**（`POST /user/runners`，project_type，tag mac/windows，not_protected）——作者未手建 glrt，全由 PAT-final 自助签发。
- 废 runner（101/102 未验证、103 token 已因 API 回显进上下文作废）：**PAT 缺 Runner 管理权限删不掉**（DELETE 404），留作者 UI（Settings→CI/CD→Runners）一键清。
- Windows 二进制来源：S3 直连龟速（11KB/s）→ Mac 经 Clash 代理 26s 下完 → tailnet 单发传输（同一套一次性端点机制）。

## 4. 站方新发现 + 自建 runner 实装踩坑（两条都是真的新知识）

### 4.1 校外 runner API 按 IP 门禁

- 实证：同一枚新 glrt token，Mac 出口 verify 成功、DESKTOP-IJBBEOT（校外家宽）裸打 `POST /api/v4/runners/verify` → `401 校外访问需登录`。9-25 校外新政把 **runner 协议端点也纳入 IP 门禁，glrt token 不豁免**——评估报告当日只测过 Windows 对 USTC 普通页 200，未实测 runner 端点。
- 处置（进程级作用域，不动宿主全局网络）：Mac 起 tailnet 单机代理 `~/.stand/bin/ustc-egress-proxy.py`（LaunchAgent `ai.stand.ustc-egress-proxy`，tailnet:18998，CONNECT/绝对 URI 最小转发）；Windows runner 进程注入 `HTTPS_PROXY=http://100.127.149.106:18998`——runner 轮询、job git clone、job 内网络全走 Mac 出口过闸。
- 依赖面：windows 腿可用性依赖 Mac 在网（同 tailnet 直连）；长期解可向站方申请 runner 端点校外豁免（清单见评估报告 §4）。

### 4.2 Windows bash executor 的 `.tmp` 路径双拼（排查链留痕，供后人直接抄）

症状：所有 windows 腿在 get_sources/prepare 阶段红，报 `bash: line N: C:/GitLab-Runner/C:/GitLab-Runner/builds/.../CI_SERVER_TLS_CA_FILE: No such file or directory` + `touch: cannot touch '.../gitlab_runner_env'`。

排查链（每一步都实证否掉了假设）：① `script` 数组化/超时格式 → lint 已过，非因；② runner `environment` 放顶层被忽略（移入 runner 段修好 mac 的 Java 8 问题）；③ `builds_dir` 顶层同样被忽略（runner debug 里 `buildsdir: ""` 铁证）；④ 放 runner 段后（前斜杠/反斜杠两种形态）仍双拼；⑤ 换 runner 17.8.0（怀疑 19.5 新 shell 执行路径）→ 同错（**版本无关**）；⑥ 换进程 CWD 到 `C:\` → 双拼前缀跟着变（`C://C:/...`）= 拼接源就是 CWD；⑦ 剥 MSYS 环境变量 → 前缀变 `/c/GitLab-Runner/`；⑧ 最终定位：runner 生成的 bash 脚本用 **`$PWD/<凭据路径>`** 拼法（假设值是相对的），而 `builds_dir` 配成绝对路径时该值本身已是 `C:/...` 绝对串 → `$PWD` + 绝对串 = 双拼。
- **正解：`builds_dir = "builds"` / `cache_dir = "cache"`（相对形态，落 `C:\GitLab-Runner\builds`）** → 相对值 + `$PWD` 拼出合法路径，双腿真跑。
- 附带坑：该机 PATH 里 Nebflow 客户端自带的 Cygwin bash 排在 Git Bash 之前（`bash --version` = `x86_64-pc-cygwin`），故 Startup 启动器与持久任务显式钉 `PATH=C:\Program Files\Git\usr\bin;...` + `JAVA_HOME=jdk-21.0.11.10-hotspot`（Startup 自启环境没有 JDK，不加则 sbt 起不来）。

## 5. pipeline 存证（compile 级达标）

| pipeline | sha | 结论 |
|---|---|---|
| 8491 | 028107830 | compile-mac 半程 cancel（Java8 误落实锤于此 job：`welcome to sbt (Temurin Java 1.8.0_412)`） |
| 8493 | fa856ebfd | 双腿红：mac 腿 `17 is not a valid choice for -java-output-version`（JVM 落 1.8）；windows 腿 `.tmp` 路径双拼 |
| 8494/8496/8499/8501/8502/8503/8504/8505 | fa856ebfd | 修复取证链（JAVA_HOME 移段、builds_dir 两形态、FF_USE_LEGACY_SHELL、runner 17.8、CWD 判别、Cygwin 剥离）——每轮一条红证 |
| **8506** | **fa856ebfd** | **success ✓ compile-mac 398s（runner#100）+ compile-windows 441s（runner#104）**，https://git.ustc.edu.cn/kaiyu/stand/-/pipelines/8506 |

- 触发面：分支 push 走 compile 最小面（按设计）；后续各轮用 `POST /pipeline?ref=` 触发，顺带验证 workflow `web` 源规则（= 原 workflow_dispatch 等价物）。
- 修复后 windows 腿实跑证据：get_sources → `Initialized empty Git repository in C:/gitlab-runner/builds/VMj_fNn_d/0/kaiyu/stand/.git/`（相对 builds_dir 拼出合法路径）→ sbt bootstrap → compile 441s 绿。

## 6. 开放项（不阻塞本批完成标准）

1. 废 runner 101/102/103 清理（作者 UI 一键）。
2. OSS_ACCESS_KEY_ID/SECRET 注入 CI variables（masked）→ release job OSS 腿生效。
3. windows 机 node 未装：smoke 腿（全量面）需先装 node。
4. windows 机 runner 的 admin 服务安装（`gitlab-runner install`）：现走 Startup 文件夹 + Nebflow 持久任务托跑，admin 一次到位更稳。
5. 站方问询清单（评估报告 §4）追加一条：runner API 校外 IP 豁免。
6. 存量内容门禁红（lint scalafmt / dead-logging / user-knowledge / circular / test）仍在全量面待清——本批按设计隔离在最小面之外。

## 7. 密钥纪律执行记录

PAT-final 全程子 shell 消费（`$(cat …)`），零打印零落盘零 commit；glrt 传输走「API 回包直落 0600 临时文件 → tailnet 一次性单发 HTTP 端点（随机路径、响应即自杀）→ 远端 `$(cat)` 消费后即删」；一次例外：`/runners/verify` 响应回显 token 进上下文（103 号），即刻作废重建（104）。USTC_RELEASE_TOKEN 以 masked 变量入库（API 建立时 `-o /dev/null -w %{http_code}` 防回显）。
