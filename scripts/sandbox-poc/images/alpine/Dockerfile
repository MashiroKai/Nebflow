# nb-poc-sbx:alpine — M1 PoC 轻量工具链镜像（alpine/musl 优先路线，裁定 A4-A5）
# 构建入口：scripts/sandbox-poc/20-build-images.sh（传入 REPOSITORIES / SBT_LAUNCHER_URL）
# 设计对应：Spec 20260907_docker-level-sandbox.md §2.1（L0-L2：base+国内源 / 最小工具链 / coursier 源烘焙；缓存不进镜像层）
ARG ALPINE_TAG=latest
FROM alpine:${ALPINE_TAG}

# L0: apk 国内源（USTC）——不动宿主 daemon.json 红线的镜像内等价物
RUN sed -i 's#https://dl-cdn.alpinelinux.org#https://mirrors.ustc.edu.cn#g' /etc/apk/repositories \
 && apk add --no-cache openjdk21-jdk bash git curl ca-certificates tzdata procps coreutils python3 \
 && mkdir -p /opt/sbt

# L1: sbt 启动器（单 jar，非完整发行版；URL 由构建脚本按镜像源可达性决定）
ARG SBT_LAUNCHER_URL
ADD ${SBT_LAUNCHER_URL} /opt/sbt/sbt-launch.jar
RUN printf '#!/bin/sh\n# SBT_OPTS 由调用方注入（值内不含空格；与官方 sbt 脚本消费语义对齐）\nexec java $SBT_OPTS -jar /opt/sbt/sbt-launch.jar "$@"\n' > /usr/local/bin/sbt \
 && chmod +x /usr/local/bin/sbt

# L2: coursier 源烘焙（detect-mirror.sh 结果；Makefile W6 先例进镜像层）
ARG REPOSITORIES
ENV COURSIER_REPOSITORIES=${REPOSITORIES}

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
WORKDIR /work
CMD ["sleep", "infinity"]
