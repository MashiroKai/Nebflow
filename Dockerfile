FROM eclipse-temurin:21-jre-alpine

# L2 rebrand: brand values from repo-root brand.conf — pass them as build
# args so the jar glob follows the assembly name without editing this file:
#   docker build --build-arg lower_name=$(...) -t ... .
ARG lower_name=stand

WORKDIR /workspace

COPY target/scala-*/${lower_name}-assembly-*.jar /app/app.jar

# Create config directory
RUN mkdir -p /root/.config/${lower_name}

# 引擎默认网关口（端口对调落地批 2026-10-25，单一来源 Defaults.GatewayPort）
EXPOSE 6138

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:6138/api/health || exit 1

# 发布剔除（seal 批 2026-11-03，作者令「邮件和心跳进程功能，进入封存，发布剔除，本地
# sbt run 可用。」）：容器镜像 = 发布面，与本地 `sbt run` 共用同一 mainClass
# （`nebflow.Main`）⇒ 剔除只能落在装配层：以下两条 -D 写死在 ENTRYPOINT，镜像默认不
# 装配邮件/心跳后台进程。道岔口 = 覆盖 CMD/ENTRYPOINT 或显式加 -D…=true（本机 run 不受影响）。
ENTRYPOINT ["java", \
  "-Dnebflow.mail.process.enabled=false", \
  "-Dnebflow.heartbeat.enabled=false", \
  "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
  "-jar", "/app/app.jar"]
CMD ["--server"]
