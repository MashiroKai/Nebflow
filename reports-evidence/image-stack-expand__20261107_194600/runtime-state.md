# 运行态与上线状态核对（2026-11-07 21:0x，只读取证，未重启、未碰用户数据）

## 宿主实例
98327 六 11月/ 7 15:26:58 2026   /Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java @/var/folders/ym/rfvzq57x4rgdq7cqcy9z68vc0000gn/T/sbt-arg
port 6138 LISTEN by PID 98327 (sbt run，从源码跑)

## 磁盘上的资源副本（served 面）
src/main/resources/web/css/design-system.css             mtime=Nov  6 19:16:25 2026 brace-depth=1
target/scala-3.5.2/classes/web/css/design-system.css     mtime=Nov  6 19:16:25 2026 brace-depth=1

## 结论
- 运行中的实例启动于 2026-11-07 15:26:58（早于修复提交 41fb6c313 / 20:37）
- main 源码与 target/classes 副本均为 depth=1（未闭合）；未连接宿主控制台，不能证明浏览器实际收到的字节或缓存状态。
- 修复仅在分支 delegate/image-expand-disappears；合并后需确认运行实例资源已更新及浏览器取到新样式。是否需要编译或重启须依部署方式核实，本次未重启。
- main HEAD = eec7cbf40 Merge branch 'delegate/desktop-social-canvas-blue'
