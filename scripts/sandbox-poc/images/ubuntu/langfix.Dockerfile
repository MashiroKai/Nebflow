# langfix.Dockerfile — 既有 nb-poc-sbx:ubuntu 的 LANG 补丁层（重建捷径，非规范构建入口）
# 背景：规范 Dockerfile 已加 ENV LANG=C.UTF-8（重跑 20 脚本即可完整重建）；但重建时 apt 层
# 缓存失效会重走 USTC ports 源——PoC 当日 USTC TLS 间歇不可达（tuna=200 备选在案）。
# 本文件从既有镜像只叠 ENV 层：秒级、零网络、其余层逐字节不变（镜像语义=规范 Dockerfile 产物）。
# 用法：docker build -f images/ubuntu/langfix.Dockerfile -t nb-poc-sbx:ubuntu images
FROM nb-poc-sbx:ubuntu
# 修复内容见规范 Dockerfile L3 注释：glibc JDK POSIX locale × 非 ASCII 工作区路径 =
# sbt launcher getCanonicalFile 断言崩溃；C.UTF-8 为 glibc 内建。
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
