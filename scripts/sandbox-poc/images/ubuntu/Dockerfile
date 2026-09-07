# nb-poc-sbx:ubuntu — M1 PoC 工具链镜像（ubuntu/glibc，musl 兼容兜底路线，裁定 A4-A5）
# 构建入口：scripts/sandbox-poc/20-build-images.sh；与 alpine 版同工具面，供 A5 双 build 对比
ARG UBUNTU_TAG=24.04
FROM ubuntu:${UBUNTU_TAG}

# L0: apt 国内源（USTC）—— 24.04 为 deb822 格式（ubuntu.sources），旧格式兜底；
#     arm64 基底走 ports.ubuntu.com（ubuntu-ports），USTC 有对应镜像路径
RUN set -eux; \
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
      sed -i 's#http://archive.ubuntu.com/ubuntu/#https://mirrors.ustc.edu.cn/ubuntu/#g; s#http://security.ubuntu.com/ubuntu/#https://mirrors.ustc.edu.cn/ubuntu/#g; s#http://ports.ubuntu.com/ubuntu-ports/#https://mirrors.ustc.edu.cn/ubuntu-ports/#g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    sed -i 's#http://archive.ubuntu.com/ubuntu/#https://mirrors.ustc.edu.cn/ubuntu/#g; s#http://security.ubuntu.com/ubuntu/#https://mirrors.ustc.edu.cn/ubuntu/#g; s#http://ports.ubuntu.com/ubuntu-ports/#https://mirrors.ustc.edu.cn/ubuntu-ports/#g' /etc/apt/sources.list 2>/dev/null || true; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless bash git curl ca-certificates tzdata procps python3; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p /opt/sbt

# L1: sbt 启动器（与 alpine 版同款）
ARG SBT_LAUNCHER_URL
ADD ${SBT_LAUNCHER_URL} /opt/sbt/sbt-launch.jar
RUN printf '#!/bin/sh\n# SBT_OPTS 由调用方注入（值内不含空格；与官方 sbt 脚本消费语义对齐）\nexec java $SBT_OPTS -jar /opt/sbt/sbt-launch.jar "$@"\n' > /usr/local/bin/sbt \
 && chmod +x /usr/local/bin/sbt

# L2: coursier 源烘焙
ARG REPOSITORIES
ENV COURSIER_REPOSITORIES=${REPOSITORIES}

# L3: locale 兜底（PoC-a 实证的必踩坑）：glibc JDK 在 POSIX locale 下 sun.jnu.encoding=ASCII，
#     非 ASCII 工作区路径（中文 worktree 名实证）sbt launcher getCanonicalFile().isDirectory()
#     断言即崩。C.UTF-8 是 glibc 内建（无需 locales 包）。musl/alpine JDK 天然 UTF-8 无此问题。
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

WORKDIR /work
CMD ["sleep", "infinity"]
