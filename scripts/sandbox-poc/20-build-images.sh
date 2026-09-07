#!/bin/bash
# 20-build-images.sh — PoC-e：alpine/ubuntu 双工具链镜像 build 对比（A5 终裁输入）
# 产物：results/e-image-build.tsv + e-compat.txt；镜像 nb-poc-sbx:{alpine,ubuntu}
# 复现：bash scripts/sandbox-poc/20-build-images.sh
set -euo pipefail
cd "$(dirname "$0")"
. lib/common.sh

# W9 workaround（PoC 现场取证）：seatbelt 沙箱写面不含 ~/.docker —— buildx 写
# ~/.docker/buildx/activity/.tmp-* 被拒（operation not permitted）→ docker build 失败。
# 修法=DOCKER_CONFIG 重定向 /tmp（W7 HOME 重定向同族）；docker provider 落地后此类
# workaround 天然消失（容器内 HOME 可写）。匿名构建，不复制宿主 config.json（凭据面最小化）。
export DOCKER_CONFIG=/tmp/nb-sbx-poc/docker-config
mkdir -p "$DOCKER_CONFIG"

TSV="$RESULTS/e-image-build.tsv"
[ -f "$TSV" ] || tsv_append "$TSV" "tag	build_s	size_mb	base_size_mb"

REPOS="$(poc_repos)"
echo "coursier repositories: $REPOS"

# sbt 启动器 URL：central 可达优先（今日实测），否则 aliyun
LAUNCHER_CENTRAL="https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.10.10/sbt-launch-1.10.10.jar"
LAUNCHER_ALIYUN="https://maven.aliyun.com/repository/public/org/scala-sbt/sbt-launch/1.10.10/sbt-launch-1.10.10.jar"
code=$(curl -sI -o /dev/null -w '%{http_code}' --max-time 5 "$LAUNCHER_CENTRAL" || echo 000)
if [ "$code" = "200" ]; then SBT_URL="$LAUNCHER_CENTRAL"; else SBT_URL="$LAUNCHER_ALIYUN"; fi
echo "sbt-launch url: $SBT_URL (central probe http=$code)"

# base 镜像就位（本地已有则零拉取；直连失败回退 daocloud 前缀）
ensure_base() {
  local img="$1" dao="docker.m.daocloud.io/library/$1"
  docker image inspect "$img" >/dev/null 2>&1 && { echo "base $img: local"; return 0; }
  echo "base $img: pulling (direct)..."
  docker pull "$img" || { echo "base $img: pulling (daocloud mirror)..."; docker pull "$dao" && docker tag "$dao" "$img"; }
}
ensure_base alpine:latest
ensure_base ubuntu:24.04

df_snapshot "e-build-start"

COMPAT="$RESULTS/e-compat.txt"; : > "$COMPAT"

for tag in alpine ubuntu; do
  case "$tag" in
    alpine) BASE=alpine:latest ;;
    ubuntu) BASE=ubuntu:24.04 ;;
  esac
  base_size=$(docker image inspect -f '{{.Size}}' "$BASE")
  t0=$(now)
  BUILDKIT_PROGRESS=plain docker build \
    -f "$POC_DIR/images/$tag/Dockerfile" \
    --build-arg REPOSITORIES="$REPOS" \
    --build-arg SBT_LAUNCHER_URL="$SBT_URL" \
    -t "$NB-sbx:$tag" "$POC_DIR/images" > "$LOGDIR/build-$tag.log" 2>&1
  t1=$(now)
  size=$(docker image inspect -f '{{.Size}}' "$NB-sbx:$tag")
  row="$tag	$(python3 -c "print(f'{$t1-$t0:.1f}')")	$(python3 -c "print(f'{$size/1048576:.0f}')")	$(python3 -c "print(f'{$base_size/1048576:.0f}')")"
  tsv_append "$TSV" "$row"; echo "[e] $row"

  # 兼容探针：JVM 版本 / libc / OS / sbt wrapper 可执行 + 非 ASCII 路径回归哨兵
  # （PoC-a 实证：ubuntu glibc JDK 在 POSIX locale 下 sun.jnu.encoding=ASCII，中文路径目录
  #   跑 sbt 时 launcher getCanonicalFile().isDirectory() 断言即崩；修复=镜像烘焙 LANG=C.UTF-8。
  #   此探针在 /tmp 中文目录内跑 `sbt about`——回归即红，防修复回退。）
  {
    echo "===== $NB-sbx:$tag ====="
    docker run --rm "$NB-sbx:$tag" sh -c \
      'java -version 2>&1 | head -2; echo ---; (ldd --version 2>&1 || true) | head -1; echo ---; grep PRETTY_NAME /etc/os-release; echo ---; which sbt; mkdir -p "/tmp/中文路径-测试" && cd "/tmp/中文路径-测试" && sbt about >/dev/null 2>&1; echo "sbt-nonascii-path-exit=$?"'
  } >> "$COMPAT" 2>&1
done

df_snapshot "e-build-end"
echo "[e] done. tsv=$TSV compat=$COMPAT"
