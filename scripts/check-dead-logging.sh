#!/usr/bin/env bash
# 死日志门禁（v2，2026-09-10 收紧）：src/main 的 Scala 源码禁止 NebulaLogger 双包装形态。
#
# ── 病根 ────────────────────────────────────────────────────────────────
# NebulaLogger 的 info/warn/error/debug 返回 IO[Unit]——再包一层
# IO(...) / IO.delay(...) 得到 IO[IO[Unit]]，外层运行后内层被丢弃，
# 日志永不执行（编译仍通过：期望型 Unit 处的值丢弃 / handleErrorWith 的
# B 型擦除吞掉类型错位）。全部出现在错误恢复与看护路径 = 出问题时静默无日志。
# 实证（2026-09-10）：shell.scala 4 处死日志落在「无进展看护 / hard timeout /
# 后台任务回调失败」三条路径上，命令被误杀时一条日志都留不下。
#
# ── v1 → v2 为什么收紧（v1 假绿实录 2026-09-10）────────────────────────
# v1 用单行 grep 锚定 `IO\(logger\.(info|warn|error|debug)\(` 等 5 个模式，
# 对当时的树实跑 exit 0（绿），而同树 shell.scala 有 4 处死日志：
#   - 漏检根因①单行锚定：`IO.delay(` 与 `shellLogger.warn(` 不在同一行；
#   - 漏检根因②接收者名硬编码：真实接收者是 `shellLogger`
#     （`private val shellLogger = NebflowLogger.forName("nebflow.shell")`）、
#     以及内联的 `NebflowLogger.forName("nebflow.shell").warn(...)`。
# v2 改为**词法扫描**：不锁行、不锁接收者名——接收者类型从本仓声明里解析，
# 而不是写死标识符清单。首跑即在树上命中 12 处（不止已知 4 处）。
#
# ── v2 覆盖范围 ────────────────────────────────────────────────────────
#   ① 多行形态：`IO.delay(` / `IO(` 之后换行再出现 logger 调用（v1 漏检）；
#   ② 任意 logger 接收者：局部 val（如 shellLogger、lifecycleLog）、
#      成员路径（如 SessionRecorder.logger）、内联链
#      （NebflowLogger.forName("...").warn(...) / nebflow.core.NebflowLogger...）；
#   ③ 三种 IO 构造子：`IO(`、`IO.delay(`、`IO.blocking(`（形参形态）。
# 判定单元：被检出的 logger 调用必须是该 IO 构造子的**直接实参表达式**
# （相对该括号深度 0，即中间不再嵌套任何括号）——这正是「内层 IO 被丢弃」的
# 充要位置；同一括号内更深处的调用不属于本门禁（见「已知边界」）。
#
# ── 豁免（不报）──────────────────────────────────────────────────────
#   - NebulaLogger 定义处：`class/object/trait NebflowLogger` 所在文件
#     （内容判定）∪ src/main/scala/nebflow/core/logging.scala（路径判定）。
#     定义体内 `IO.delay(logger.info(...))` 的 `logger` 是 slf4j Logger（返回
#     Unit），IO 包装是正确用法——不是死日志。
#   - `*Sync(...)` 变体：info/warn/error/debug **Sync** 返回 Unit，
#     `IO(logger.warnSync(msg))` 是合法形态（模式以方法名 + `(` 锚定，Sync 天然不匹配）。
#   - IO 值被消费的形态：外层 IO 之后紧跟 `.flatten` / `.unsafeRunSync()` /
#     `.unsafeRunAndForget()`（内层 IO 会被执行），或 logger 调用自身被
#     `.unsafeRunSync()` 消费——这些不是死日志。
#
# ── 已知边界（本门禁不覆盖，人工审查域；照 v1 体例保留并细化）──────────
#   - **块形态 `IO { ... }` / `IO.delay { ... }` / `IO.blocking { ... }` 的内部**
#     不做语句级数据流分析：`{ ...; logger.info(x) }` 里裸语句被丢弃、
#     或块值为 logger 调用（IO[IO[Unit]]）都在此列。判据是多行语句位 +
#     Unit 期望型值丢弃，grep/词法层不可靠；修复形态 = `*Sync` 变体或 `*>` 序列化。
#   - 直接实参之外的嵌套位置，如 `IO(x.map(_ => logger.warn(...)))`（深度 > 0）。
#   - 接收者类型解析不到的形态：外部库返回 NebflowLogger 的工厂
#     （非 `= ... NebflowLogger` 形式的绑定）——命名解析基于本仓声明，
#     跨仓/反射构造的接收者可能漏检。
#   - 字符串/注释内的同形文本不报（扫描前已剔除），这是有意为之（非源码语义）。
#
# ── 用法 ──────────────────────────────────────────────────────────────
#   bash scripts/check-dead-logging.sh                 # 默认扫 src/main（CI 口径）
#   bash scripts/check-dead-logging.sh <dir|file>...   # 指定根（复算/夹具用，可多个）
#   DEAD_LOG_ROOTS="src/main /tmp/fixture" bash scripts/check-dead-logging.sh
# 退出码：0 = 干净；1 = 命中（门禁红）；3 = 配置/环境错误（如根不存在、无 python3）。
# 缺解释器或根不存在时**拒绝报绿**（exit 3），避免把「扫不动」伪装成「干净」。
#
# 实现：bash 入口（根解析 + 输出格式）+ 内联 python3 词法扫描器（多行/深度/
# 接收者类型解析，grep 做不可靠）。CI 与本地同逻辑：.github/workflows/ci.yml
# 的 dead-logging-gate job 调用本脚本（不带参数 = src/main）。
set -euo pipefail
cd "$(dirname "$0")/.."

ROOTS=("$@")
if [ "${#ROOTS[@]}" -eq 0 ]; then
  if [ -n "${DEAD_LOG_ROOTS:-}" ]; then
    # shellcheck disable=SC2206  # 允许空格分隔多根
    ROOTS=(${DEAD_LOG_ROOTS})
  else
    ROOTS=("src/main")
  fi
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "✗ dead-logging gate ERROR: python3 not found — refusing to report a clean gate" >&2
  exit 3
fi

RC=0
REPORT=$(python3 - "${ROOTS[@]}" <<'PY'
import bisect, os, re, subprocess, sys

# logger 调用：接收者路径 + 方法名 + 紧跟的 `(`；方法名后直接锚 `(`，故 *Sync 天然不匹配。
CALL_RE = re.compile(
    r"((?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*(?:info|warn|error|debug)\s*\($")
INLINE_RE = re.compile(
    r"(?:nebflow\s*\.\s*core\s*\.\s*)?NebflowLogger\s*\.\s*forName\s*\([^()]*\)\s*\.\s*(?:info|warn|error|debug)\s*\($"
    r"|(?:nebflow\s*\.\s*core\s*\.\s*)?NebflowLogger\s*\.\s*(?:info|warn|error|debug)\s*\($")
IO_PAREN = ("IO(", "IO.delay(", "IO.blocking(")          # 形参形态 IO 构造子
CONSUMED = re.compile(r"^\s*\.\s*(?:flatten|unsafeRunSync|unsafeRunAndForget)\s*\(")
DEF_FILE = re.compile(r"\b(?:class|object|trait)\s+NebflowLogger\b")
EXEMPT_PATHS = ("src/main/scala/nebflow/core/logging.scala",)


def strip_source(text):
    """剔除注释与字符串字面量（含内插/三引号），保留换行与字符位——避免字符串/注释误报。"""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\n":
            out.append("\n"); i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i); j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            depth, j = 1, i + 2   # Scala 块注释可嵌套
            while j < n and depth:
                if text.startswith("/*", j): depth += 1; j += 2
                elif text.startswith("*/", j): depth -= 1; j += 2
                else: j += 1
            out.append("".join(" " if ch != "\n" else "\n" for ch in text[i:j])); i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append("".join(" " if ch != "\n" else "\n" for ch in text[i:j])); i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\": j += 2; continue
                if text[j] == '"': j += 1; break
                j += 1
            out.append("".join(" " if ch != "\n" else "\n" for ch in text[i:j])); i = j
        elif c == "'" and i + 2 < n and text[i + 2] == "'":
            out.append("   "); i += 3
        else:
            out.append(c); i += 1
    return "".join(out)


def logger_bindings(text):
    """本文件里绑定到 NebflowLogger 的标识符（含参数位 `x: NebflowLogger`）。"""
    names = set()
    for m in re.finditer(
        r"\b(?:val|var|def|given)\s+([A-Za-z_]\w*)\s*(?::\s*[\w.]*NebflowLogger\b)?[^=\n]*=\s*[^=\n]{0,160}?NebflowLogger",
        text):
        names.add(m.group(1))
    for m in re.finditer(r"\b([A-Za-z_]\w*)\s*:\s*[\w.]*NebflowLogger\b", text):
        names.add(m.group(1))
    return names


def declared_names(text):
    names = set()
    for m in re.finditer(r"\b(?:val|var|def|given)\s+([A-Za-z_]\w*)", text):
        names.add(m.group(1))
    for m in re.finditer(r"(?:\(|,)\s*([A-Za-z_]\w*)\s*:\s*[A-Z]", text):
        names.add(m.group(1))
    return names


def scan_text(text, is_logger):
    code = strip_source(text)
    lines = text.splitlines()
    starts = [0]
    for idx, ch in enumerate(code):
        if ch == "\n":
            starts.append(idx + 1)

    stack, pending, consumed = [], [], set()
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if c in "([{":
            io_open = False
            for pat in IO_PAREN:
                if code[max(0, i - len(pat) + 1): i + 1] == pat:
                    st = i - len(pat) + 1
                    if st == 0 or not (code[st - 1].isalnum() or code[st - 1] in "._"):
                        io_open = True
                    break
            if io_open:
                stack.append([True, i])
            else:
                prefix = code[max(0, i - 200): i + 1]
                recv = None
                if INLINE_RE.search(prefix):
                    recv = "NebflowLogger.forName(...)"
                else:
                    m = CALL_RE.search(prefix)
                    if m and is_logger(m.group(1)):
                        recv = re.sub(r"\s+", "", m.group(1))
                if recv is not None and stack and stack[-1][0]:
                    pending.append({"frame": id(stack[-1]), "pos": i,
                                    "ln": bisect.bisect_right(starts, i),
                                    "io_ln": bisect.bisect_right(starts, stack[-1][1]),
                                    "recv": recv})
                stack.append([False, i])
        elif c in ")]}":
            if stack:
                fr = stack.pop()
                if fr[0] and CONSUMED.match(code[i + 1: i + 40]):
                    consumed.add(id(fr))
        i += 1

    hits = []
    for h in pending:
        if h["frame"] in consumed:
            continue                      # 外层 IO 被 .flatten/.unsafeRun* 消费 → 内层会跑
        if CONSUMED.match(code[h["pos"] + 1: h["pos"] + 40]):
            continue                      # logger IO 自身被 unsafeRun* 消费 → 会跑
        hits.append(h)
    return [(h["ln"], h["io_ln"], h["recv"], lines[h["ln"] - 1].strip() if h["ln"] - 1 < len(lines) else "")
            for h in hits]


def files_under(root):
    try:  # 仓内：跟随 git 索引（含未跟踪但未忽略的新文件）；失败/仓外则退化为目录遍历
        out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", root],
                             capture_output=True, text=True, check=True).stdout
        fs = sorted(f for f in out.split("\0") if f.endswith(".scala"))
        if fs:
            return fs
    except Exception:
        pass
    return sorted(os.path.join(dp, f) for dp, _, fs in os.walk(root) for f in fs if f.endswith(".scala"))


def main(argv):
    roots = argv[1:] or ["src/main"]
    files = []
    for r in roots:
        if os.path.isdir(r):
            files += files_under(r)
        elif os.path.isfile(r):
            files.append(r)
        else:
            print("dead-logging gate ERROR: scan root not found: " + r, file=sys.stderr)
            return 3
    files = sorted(set(files))
    texts = {}
    for f in files:
        try:
            texts[f] = open(f, encoding="utf-8", errors="replace").read()
        except OSError as e:
            print("dead-logging gate ERROR: cannot read " + f + ": " + str(e), file=sys.stderr)
            return 3

    global_nb = set()   # 跨文件兜底：成员/抽象成员声明的接收者（如 ctx.log）
    for f, t in texts.items():
        if not DEF_FILE.search(t):
            global_nb |= logger_bindings(t)

    hits, scanned = [], 0
    for f in sorted(texts):
        t = texts[f]
        if DEF_FILE.search(t) or f.endswith(EXEMPT_PATHS):
            continue                                  # 豁免：logger 定义处
        scanned += 1
        local_nb, local_decl = logger_bindings(t), declared_names(t)

        def is_logger(path, local_nb=local_nb, local_decl=local_decl):
            segs = path.split(".")
            last, root = segs[-1], segs[0]
            if last in local_nb:
                return True
            if len(segs) == 1:
                if last in local_decl:
                    return False                      # 本文件声明为别的类型（如 slf4j Logger）
                return last in global_nb
            if last in local_decl and root not in local_nb:
                return False
            return last in global_nb

        for (ln, io_ln, recv, content) in scan_text(t, is_logger):
            hits.append((f, ln, io_ln, recv, content))

    for (f, ln, io_ln, recv, content) in hits:
        print("%s:%d: %s" % (f, ln, content))
        print("    ^ outer IO wrapper opened at %s:%d  [receiver: %s]" % (f, io_ln, recv))
    print("dead-logging gate: %d double-wrap site(s); scanned %d Scala file(s); roots: %s"
          % (len(hits), scanned, " ".join(roots)), file=sys.stderr)
    return 1 if hits else 0


sys.exit(main(sys.argv))
PY
) || RC=$?

case "$RC" in
  0)
    echo "✓ dead-logging gate clean: no NebulaLogger double-wrap under ${ROOTS[*]}"
    ;;
  1)
    echo "✗ dead-logging gate FAILED — NebulaLogger double-wrap (IO[IO[Unit]]) under ${ROOTS[*]}:"
    echo "$REPORT"
    echo ""
    echo "NebulaLogger.info/warn/... already return IO[Unit] — wrapping them in"
    echo "IO(...) / IO.delay(...) builds IO[IO[Unit]] and the inner IO is never"
    echo "run (dead log). Drop the outer wrapper and chain the call into the IO"
    echo "chain (*> / .as / handleErrorWith fallback), or use the *Sync variant"
    echo "in pure-expression positions."
    exit 1
    ;;
  *)
    echo "✗ dead-logging gate ERROR (exit $RC): scanner could not complete — not reporting green" >&2
    [ -n "$REPORT" ] && echo "$REPORT" >&2
    exit 3
    ;;
esac
