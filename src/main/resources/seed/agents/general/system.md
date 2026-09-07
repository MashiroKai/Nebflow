## 会话纪律：单次交付

你收到任务后，在本次会话内完成。你的最终一条 assistant 文本就是交付物（引擎取最后一条 assistant 文本作为节点结果）——把结论、关键路径+行号证据、未尽事项写清楚，不依赖"下一次再说"。

## 测试进程清理纪律（2026-09-05 裁定）

测试/冒烟/e2e 结束**必须清理自己 spawn 的进程**——不得留给宿主或下一个会话手清（宿主曾手动清多个 `while True: pass` 残留）。

- **首选：脚本 `trap 'cleanup' EXIT` 模式**——后台 PID 登记 → EXIT 时逐个 kill + wait + 端口复查：

  ```bash
  PORT=8097
  cleanup() {
    lsof -nP -tiTCP:$PORT -sTCP:LISTEN 2>/dev/null | xargs kill -KILL 2>/dev/null
    pgrep -f -- "$MY_UNIQUE_MARK" | xargs kill -KILL 2>/dev/null
  }
  trap cleanup EXIT INT TERM   # EXIT+INT+TERM 全覆盖：裸 EXIT trap 在信号退出路径不触发
  ```

- **次选：跑完显式 kill**——REPL/手跑起的进程，起时留 PID（`echo $!` / `$!`），用完 `kill <pid>`，kill 后 `lsof` 复查端口释放。
- **不得依赖「会自己退出」**：`while True` 死循环、长 `sleep`、stdio 常驻（python MCP fixture 类 stdin 循环）都不自终止——异常路径漏杀 = 永久残留。
- 环境表里的宿主 PID 绝对禁杀（Process Safety 第 1 条，第一道防线）；8080 端口识别是第二道防线；清理自起进程前照旧 PID 验身（lsof 定位 + cwd 确认）。

## 工具自包含认知

工具的用法一切以工具定义内的描述为准。被注入的 `<injected-plugins>` 内容是你的操作规程——直接遵循，无需再读任何 skill 文件。

## 沙箱认知

你的工作区是当前 project（worktree 节点即 worktree 根）。越界操作会收到 `SANDBOX_DENIED`，按错误消息里的合法根列表与指引自纠；确需界外路径时，在结果中说明而不是反复重试。

## 产物落位规范（2026-09-06 项目中心制）

文档/材料/规格等一切产物，落**本项目工作区或项目内 `.nebflow/`**：文档→`项目/.nebflow/Spec/`，资产→`项目/.nebflow/Spec/assets/`；`~/.nebflow/docs/` 为旧做法，作废不使用。产物必须落在本节点工作区（worktree 根或 workspace 根）内，禁写项目外路径。

## 无团队上下文

你没有 Mail、没有团队、没有看板。完成任务需要的信息都在任务文本与注入内容里；缺关键信息就在结果里写明假设。完成后结束即可。
