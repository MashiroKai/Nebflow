# codex-spawn-fix：外部执行器 codex 两大死因修复（2026-10-19）

实证来源：调度方手动复现 codex 冒烟三连败，根因两条——① 无代理挂死；② stdin 永等。
本批外科手术两刀，勿扩散。

## 修法

### ① 代理注入（网络死因）
- 新配置节 nebflow.json 顶层 `externalExecutors.proxyEnv`（键值映射，缺省空 = 零注入）：
  ```json
  "externalExecutors": { "proxyEnv": { "http_proxy": "http://127.0.0.1:7890", "https_proxy": "http://127.0.0.1:7890" } }
  ```
- 新增 `ExternalSpawnConfig`（热读 + 宽容解码，仿 ExecutorRegistry.readSettings）；
  `ExternalSessionRunner.configureProcessBuilder`（由 buildProcess 委托）把**配置点名**的
  键值 put 进 `ProcessBuilder.environment()`——只在默认继承 env 上做点名加法，
  绝不清空、绝不扩大透传引擎进程 env 里的其他敏感变量。
- codex 适配器文档注明「订阅模式需代理」（ExternalExecutor.scala Codex 块注释）。

### ② stdin 立即关闭（挂起死因）
- `launchTurn` 在 `pb.start()` 后立即 `sealChildStdin(p)`（= `p.getOutputStream.close()`），
  所有外部执行器统一。
- 选型理由：关管道写端而非 `redirectInput(/dev/null)`——/dev/null 在 Windows 不存在，
  start() 会抛 IOException（shell.scala 的 Windows 分支正因同一教训另走 PIPE），引擎发
  Windows 版，引 /dev/null 即 Windows 回归；关写端跨平台且语义等价（子进程读 stdin 立即
  EOF）。三适配器零 stdin 依赖已确认：claude `-p` / zcode `-p` / codex `exec` 任务文本
  全走 argv，exec/print 模式均不读 stdin（claude/zcode 因此前未炸，codex 会
  「Reading additional input from stdin...」永等——引擎会话 40 秒变 orphan 的要因）。

## 验证

- spec：`sbt "testOnly nebflow.core.executor.*"` **50/50 全绿**（含 ExternalLegPrepareSpec
  合并轮 29s 重钉）。新增三钉（ExternalSpawnSpec）：
  - `proxyEnv pin`：点名键逐字注入 / 缺省零注入（哨兵键缺席断言）；
  - `proxyEnv reader pin`：坏形/错型/空键名降级不抛；
  - `sealChildStdin pin`：真 spawn `cat`（stdin 永等探针）——关闭后立即 EOF、exit 0；
    不关则 munit 超时钉响。
- 人工替代验证（引擎重启前）：按修复后 spawn 形态手动跑真 codex——
  `env https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 codex exec --json --skip-git-repo-check "Reply with exactly: SMOKE-OK" < /dev/null`
  ⇒ **SMOKE-OK，exit 0，JSONL 事件流正常**（见 manual-smoke-stdout.jsonl /
  manual-smoke-exit.txt）。代理注入 + stdin EOF 两要素与引擎修复后行为一一对应。

## 生效条件

引擎代码改动**需重启引擎**才能实证 codex 全链（spawn→流帧→settle）；重启前用上方手动
命令替代验证。配置（`externalExecutors.proxyEnv`）为热读——下一次 spawn 即生效。

## 涉及文件

- `src/main/scala/nebflow/core/executor/ExternalSpawnConfig.scala`（新增）
- `src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala`（configureProcessBuilder
  拆出 + sealChildStdin + launchTurn 关闭调用）
- `src/main/scala/nebflow/core/executor/ExternalExecutor.scala`（codex 适配器文档注代理前提）
- `src/test/scala/nebflow/core/executor/ExternalSpawnSpec.scala`（新增三钉）
