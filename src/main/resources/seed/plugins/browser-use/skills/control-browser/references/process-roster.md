# 自有进程组与花名册

`control-browser/scripts/process-roster.mjs` 只管理本工具启动的自有进程，Node 的 `detached: true` 在 macOS 创建独立会话和进程组；不适用于既有服务、宿主或其他会话。使用绝对路径将 `STAND_PROCESS_ROSTER` 指向私有用户目录下的花名册；不要并发修改，锁存在时停止排查。不要把凭据放在参数里。

```sh
export STAND_PROCESS_ROSTER="$HOME/.stand/browser-use/roster.json"
node <包根>/skills/control-browser/scripts/process-roster.mjs start --node <nodeId> --tag httpd -- python3 -m http.server <自有端口> --directory <静态根>
node <包根>/skills/control-browser/scripts/process-roster.mjs list
node <包根>/skills/control-browser/scripts/process-roster.mjs clean <pid>
# 以 ps 和端口检查确认进程组与监听均消失后：
node <包根>/skills/control-browser/scripts/process-roster.mjs forget <pid>
```

`clean` 仅在 PID、进程组及启动时刻与记录一致时向该组发送 TERM，并保留记录；`forget` 仅在进程组不存在时移除记录。PID 复用、组首领先退出、权限或查询失败时拒绝自动发信号；人工核实，不可扩大清理范围。被包装程序若自行脱离进程组（例如持久 Chrome）不受该组清理，必须按其实际 PID、端口和 profile 单独核验并清理。崩溃遗留 lock 需确认无并发操作后人工处理。

纯函数测试：`node --test <包根>/skills/control-browser/scripts/process-roster.test.mjs`。真实进程组集成及浏览器冒烟须在授权的新会话中执行：在新会话专用私有目录设置上述变量，使用 `start --node smoke --tag smoke -- node -e 'setInterval(()=>{},1000)'`，记录返回 PID；依次运行 `list`、`clean PID`，用 `ps -p PID -o pid=,pgid=` 验证退出，再运行 `forget PID` 和 `list`。任何身份不符或未退出即停止，不对无关进程发信号。
