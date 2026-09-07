# sandbox-poc — M1 Docker 级沙箱 PoC（零产品代码改动）

Spec 基准：`.nebflow/Spec/20260907_docker-level-sandbox.md`（本 worktree 内冻结版；裁定 A1-A10 已落入 §5，A4-A5 改向 = per-task 轻量容器 + retention 复用模型）。

## 布局

| 文件 | PoC | 内容 |
|---|---|---|
| `images/{alpine,ubuntu}/Dockerfile` | e | 轻量工具链镜像（apk/apt USTC 源 + JDK21 + git + sbt-launch 单 jar + coursier 源烘焙；缓存不进镜像层） |
| `20-build-images.sh` | e | 双镜像 build 计时/大小/兼容探针 → `results/e-*.{tsv,txt}` |
| `30-stats-probe.sh` | b | `docker stats --no-stream` 采样开销与稳定性（progressProbe 依据）→ `results/b-stats-probe.{tsv,md}` |
| `40-lifecycle-demo.sh` | c | per-task 生命周期：create→任务→retention→再激活复用（fs 状态保持断言）→TTL auto-destroy → `results/c-lifecycle.{tsv,md}` |
| `50-sbt-matrix.sh` | a | sbt 真实负载三臂：host-bare / ct-bind-target(VirtioFS) / ct-vol-target(volume 盖 target) + alpine 附臂 → `results/a-sbt-matrix.tsv`（A6 数据） |
| `60-qa-container-e2e.sh` + `61-qa-screenshot.cjs` | d | QA 隔离实例容器化全链：fat JAR→容器实例→发布端口→探活→verify-web-assets→宿主 playwright 截图→零残留→宿主 8080 无恙 → `results/d-qa-e2e.{tsv,md}` + 截图 png |
| `90-cleanup.sh` | — | 资源清理 + 记账收口（容器/卷清空；镜像保留记账） |
| `lib/common.sh` | — | 共享助手（命名前缀 `nb-poc-`、计时、TSV、docker df 记账） |
| `results/` | — | 全部数据产物（TSV/MD/PNG）+ `resource-ledger.md`（docker system df 快照链） |

## 执行顺序

```bash
bash scripts/sandbox-poc/20-build-images.sh     # 先出镜像（a/c/d 依赖）
bash scripts/sandbox-poc/30-stats-probe.sh      # ~40s
bash scripts/sandbox-poc/40-lifecycle-demo.sh   # ~50s（retain 15s + ttl 20s 演示值）
bash scripts/sandbox-poc/50-sbt-matrix.sh       # 长跑（三臂 compile+test+assembly + alpine 附臂）
# ⚠️ 50 跑完后 alpine 臂的 clean 会抹掉 ctbind.assembly 产物——60 之前先补：
#   cd <worktree> && SBT_OPTS=<同 host 臂> sbt assembly   （hostcache 热，~1min）
bash scripts/sandbox-poc/60-qa-container-e2e.sh # 依赖 assembly 产物
bash scripts/sandbox-poc/90-cleanup.sh          # 收口
```

## 红线（脚本内置遵守）

- 零产品代码改动：产物仅 `scripts/sandbox-poc/**` 与 `.nebflow/Spec/20260907_docker-level-sandbox.md`。
- 宿主 :8080 = 宿主实例，全程只读快照断言（60 脚本首尾各一次），无任何信号。
- docker 资源：一律 `nb-poc-` 前缀；作者存量容器/镜像只读；daemon.json 不动。
- 发布端口：lsof pre-flight 选空闲 809x（避开 8080/8094 与在飞批 8095-8097）。
- 凭据不打印：qa-home staging（nebflow.json/auth.json 副本）只 cp、不回显，用毕即焚。
- 写入边界：工作区 `target/`、`/tmp/nb-sbx-poc/**`、docker 卷——不触 src/、不触既有 scripts/。
