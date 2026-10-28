#!/usr/bin/env python3
# validate_plugin.py — Nebflow 插件包机械校验（M1-M15 + C 组装载器镜像，三档 profile）
#
# 定位：PluginRegistry.scala 装载规则的「上游前置镜像」，不是平行第二权威。
# 任何 M/C 规则与官方装载器行为冲突时，以装载器为准并回改本脚本。
# 镜像锚点（Scala 相对 repo 根）：
#   - canonical schema        PluginRegistry.scala:66   (CanonicalSchema)
#   - canonical mcp schema    PluginRegistry.scala:70   (CanonicalMcpSchema)
#   - 保留 env 占位键          PluginRegistry.scala:79   (PluginPlaceholderEnvKeys)
#   - name 约束 §5.5          PluginRegistry.scala:287-292 (validPluginName)
#   - digest 算法             PluginRegistry.scala:237-248 (computeDigest)
#   - shell-like / 凭据红标启发 PluginRegistry.scala:83-89
# 新增锚点：
#   - 官方身份闸（nebflow- 保留前缀 + digest 现算允许列表，OFFICIAL_IMPERSONATION）
#                             OfficialPackages.scala:51,115-116（fail-closed :34）
#   - 工具白名单 §B.6          PluginRegistry.scala:104（越界拒载 :754-760 PLUGIN_TOOLS_ILLEGAL）
#   - viewers 内联上限 32KB    PluginRegistry.scala:124（:796-813 告警面）
#   - manifest 已知键闭合集    PluginRegistry.scala:133-145（capability deprecated 注记 :127-131）
#   - 字段类型 fatal 面        PluginRegistry.scala:649-676（stringField :871-878）
#   - 包根已知条目五件         PluginRegistry.scala:583（org.nebflow 子条目 :587-591；
#                               第五件＝Plugin.md 恰一（六-3 逐字白名单）；装载器侧
#                               扩面已于卫生批 2026-10-28 同步落地（六-1）——
#                               KNOWN_ROOT_ENTRIES 与装载器 known-set 逐字镜像）
#   - mcp.json 顶层闭合键      PluginRegistry.scala:924-930（entry 未知字段 :980-983）
#   - server entry 校验        PluginRegistry.scala:987-1077（args/headers 类型、stdio `./` 围栏 :996-1001、cwd 围栏 :1013-1028）
#   - mcp url loopback-https   PluginRegistry.scala:1083-1094 (validMcpUrl)
#   - 路径围栏 §4.1            PluginRegistry.scala:46-47,592-607,688-712（symlink 越界：manifest=拒载档，SKILL.md/mcp.json=跳过档）
#   - skills/ §7.1 skip+warn   PluginRegistry.scala:687-694（顶层纯文件被 filter(os.isDir) 静默过滤）
#   - manifest name ≠ 目录名    PluginRegistry.scala:642-644
#
# 三档 profile（批1 D4 甲案：单脚本演进；严格度分档按计划§2.1）：
#   --profile official  官方面硬闸（适用=OFFICIAL_FACE 9 包产品名单；名单外包不适用
#                       此档）。M1-M15 全量 + C 组 fatal 镜像；
#                       退出码≠0 即阻断；WARN 逐条处置或挂豁免记录（流程面）。
#   --profile creator   creator 产出交付闸（默认档）。M1-M15 全量 + fatal 镜像；退出码
#                       0 为交付闸；惯例面警告（C1-C3/C6）须处置或说明。
#   --profile user      用户自装包提示级。同引擎全项输出，全部降为提示、无 FAIL 语义，
#                       退出码恒 0；建议配 --report-file 落文件（不刷屏）。
#
# C 组（C9-C15 全收＋C1-C6/C8/C16；C7 缓——包级行数预算候观测，未实现）：
#   C1 skills/ 非 skill 目录（§7.1 skip+warn 镜像）      [WARN]
#   C2 skills/ 顶层纯文件（loader 静默过滤）              [WARN]
#   C3 裸相对引用（应 ${SKILL_DIR} 形态；附带断链/跨包）   [WARN]
#   C4 机器绑定绝对路径（盘符、/Us+ers/、/ho+me/ 家目录型）  [WARN]
#   C5 二进制资产 / seed text-mode（双模：分发树内=硬闸）  [FAIL/WARN]
#   C6 frontmatter name == 目录名（agentskills.io 严约束） [WARN]
#   C8 manifest name == 目录名（:642-644 告警镜像）        [WARN]
#   C9 官方身份闸镜像（现脚本对此假绿→补）                [FAIL]
#   C10 manifest 字段类型 fatal 面                        [FAIL]
#   C11 未知条目告警面（manifest 键/包根/org.nebflow/mcp 顶层+entry）[WARN]
#   C12 capability 残留（deprecated；新包禁写、存量容忍）    [WARN]
#   C13 org.nebflow 镜像（tools 越白名单=拒载；viewers 上限=告警）[FAIL/WARN]
#   C14 M14 补差（loopback-https / args / headers / `./` 围栏）   [FAIL]
#   C15 符号链接/越围栏（plugin.json 越界升硬闸，余为跳过档）      [FAIL/WARN]
#   C16 M15 语境豁免（教学/正则字面量→降人工复核提示，不计 FAIL）
#   C17 description 长度软顶（>200 提示收敛；双模式批 2026-10-28）  [WARN]
#   C18 Plugin.md 引用 SKILL.md 路径存在性（断链提示）              [WARN]
#
# M9 定稿：BOUNDARY_RE 零逻辑改动——自足边界句（说清本包不管什么
# 即达标）照旧 PASS；「另配/请改用」留存标记集合，存量含分流的描述兼容；提名相邻
# 插件降为可选增强、非要求（设计原则：每个插件不需要提到别的插件，描述好自己就行）。
#
# M8 口径注记：触发词「核心词全命中」是 description-quality skill / 人审判定项
# （词表按包能力域圈定），不进本脚本硬依赖——脚本只机械拦截「无触发场景句」。
# M12 号位注记：M12 现为虚位保留；未来行数类新规则可复用该号位，复用须全链申报。

import argparse
import hashlib
import json
import os
import re
import sys

CANONICAL_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
CANONICAL_MCP_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
RESERVED_PREFIX = "nebflow-plugin-"
# 官方白名单基线 = creator 自身 + seed manifest 现声明集（运行时会尝试读邻近
# seed/manifest.json 动态并入 plugins: 声明，见 discover_official）。——M4 生成期
# 更窄防线（nebflow-plugin- 前缀），批1 不动。
OFFICIAL_BASELINE = {"nebflow-plugin-creator", "visual-report", "slideblocks"}
# C9 保留 namespace 前缀（OfficialPackages.scala:51 逐字「nebflow-」；🔴 禁收窄/改写）
RESERVED_OFFICIAL_PREFIX = "nebflow-"
# 官方面 9 包名单：--profile official 的适用面。名单外包（engineering-methods /
# nebflow-frontend-dev / nebflow-qa）不属 seed 官方面。仅作适用面核对提示用，
# 不改 M4 白名单语义。
OFFICIAL_FACE = frozenset({
    "browser-use", "computer-use", "design-spec", "explorer-toolkit",
    "nebflow-plugin-creator", "web-search-toolkit", "document-production",
    "visual-report", "slideblocks",
})
# §B.6 工具白名单（PluginRegistry.scala:104；越界 → PLUGIN_TOOLS_ILLEGAL 拒载 :754-760）
TOOL_WHITELIST = {"WebSearch", "WebFetch", "Curl"}
# viewers 内联声明上限（PluginRegistry.scala:124 MaxViewerDeclarationBytes）
MAX_VIEWER_BYTES = 32 * 1024
# 包根已知条目五件（PluginRegistry.scala:583；第五件＝Plugin.md 恰一——装载器侧
# 扩面已于卫生批 2026-10-28 落地（六-1）；逐字大小写敏感，目录化/变体照 C11 告警）
KNOWN_ROOT_ENTRIES = {"plugin.json", "mcp.json", "skills", "org.nebflow", "Plugin.md"}
# org.nebflow 允许子条目（PluginRegistry.scala:587-591；漏扩=贡献静默失效）
KNOWN_NS_ENTRIES = {"tools.json", "viewers.json"}
# manifest 闭合 12 键（PluginRegistry.scala:133-146 KnownManifestKeys；useWhen =
# 双模式批 2026-10-28 description 增量键，容忍读法）
KNOWN_MANIFEST_KEYS = {
    "$schema", "name", "version", "description", "capability",
    "author", "homepage", "repository", "license", "keywords", "useWhen", "extensions",
}
# mcp.json 顶层闭合键（PluginRegistry.scala:924-930）
KNOWN_MCP_TOP_KEYS = {"$schema", "mcpServers"}
# server entry 已知字段（PluginRegistry.scala:981）
KNOWN_SERVER_KEYS = {"type", "command", "args", "env", "cwd", "url", "headers"}

BLACKLIST_RE = re.compile("强大|智能|先进|高效|完善|全面|最好|完美|易用|灵活")
TRIGGER_RE = re.compile("适用于|使用场景|当.{2,30}时|用于")
BOUNDARY_RE = re.compile("不适用于|不属于|另配|请改用|勿用于")
PREDICATE_RE = re.compile("节点(获得|可)")
SKILLS_DETAIL_RE = re.compile("内含 skills?：")
SKILL_NAME_IN_DESC_RE = re.compile(r"([A-Za-z0-9][A-Za-z0-9._-]*)（")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")
NAME_CHARS_RE = re.compile(r"^[a-z0-9.-]+$")
MCP_TRANSPORTS = {"stdio", "streamable-http", "sse"}
SHELL_LIKE_COMMANDS = {
    "sh", "bash", "zsh", "dash", "ksh", "csh", "tcsh", "pwsh", "powershell", "cmd",
    "curl", "wget", "nc", "ncat", "netcat", "telnet", "ssh",
}
CREDENTIAL_KEY_RE = re.compile(r"(?i)(passw(or)?d|secret|token|api.?key|access.?key|private.?key|credential|auth)")
PLACEHOLDER_ENV_KEYS = {"PLUGIN_ROOT", "PLUGIN_DATA"}
# M15 模式按字面量拼接构造（而非整串写出）——本脚本自身也在包内、也被 M15 扫描，
# 整串写出会自我命中。
M15_RE = re.compile("|".join(["TB" + "D", "TO" + "DO", "FIX" + "ME", "<pla" + "ceholder>"]))
# C16 豁免判定用的引号集（教学提及形态：占位标记被引号包裹——QA 自查清单类）
_C16_QUOTES = ('"', "'", "`", "「", "『", "」", "』")
# C3 裸相对引用：markdown 链接/图片目标 + 反引号路径（${SKILL_DIR} 换装形态不在列）
_C3_LINK_RE = re.compile(r"\]\((\.{1,2}/[^)\s]+)\)")
_C3_CODE_RE = re.compile(r"`(\.{1,2}/[^`\s]+)`")
# C4 机器绑定绝对路径：盘符 / /Us+ers/<n>/ /ho+me/<n>/ 家目录型（按拼接构造防自命中）
_C4_USERS = "/" + "Us" + "ers/"
_C4_HOME = "/" + "ho" + "me/"
_C4_PATH_TAIL = r"[^\s`\"')\]，。；：]*"
_C4_RE = re.compile(
    r"(?<![0-9A-Za-z])(?:(?:[A-Za-z]:[\\/]|" + _C4_USERS + r"|" + _C4_HOME + r")" + _C4_PATH_TAIL + r")"
)
# C5 二进制资产扩展名黑名单（seed text-mode 约束面；文本装卸通道禁载二进制）
BINARY_EXT = frozenset({
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "tif", "tiff",
    "pdf", "psd", "ai", "sketch", "blend", "fbx", "glb", "gltf",
    "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "class",
    "so", "dylib", "dll", "exe", "bin", "dat", "db", "sqlite", "sqlite3",
    "woff", "woff2", "ttf", "otf", "eot",
    "mp3", "mp4", "m4a", "mov", "avi", "mkv", "wav", "ogg", "webm",
    "pyc", "pyo", "o", "a", "obj", "lib", "swf", "h5p",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx",
})
MAX_DESC_CHARS = 400

PROFILE_MODES = {
    "official": "gate",
    "creator": "gate",
    "user": "hint",
}

results = []          # (id, name, ok, warn, detail)
warn_lines = []       # 人审提示（红标启发式，不计 FAIL）
_report_fh = None     # --report-file 落文件句柄（user 档建议配用）


def out(line):
    """统一输出：stdout + 可选落文件（--report-file）。"""
    print(line)
    if _report_fh is not None:
        _report_fh.write(line + "\n")


def record(cid, name, ok, detail, warn=False):
    """记一条读数。ok=False 且 warn=True → WARN（告警面，不计 FAIL）；
    ok=False 且 warn=False → FAIL（gate 档计退出码；user 档降 HINT）。"""
    results.append((cid, name, ok, warn, detail))
    if ok:
        tag = "PASS"
    elif warn:
        tag = "WARN"
    elif PROFILE_MODES.get(_profile, "gate") == "hint":
        tag = "HINT"
    else:
        tag = "FAIL"
    out(f"[{tag}] {cid} {name} — {detail}")


_profile = "creator"


def walk_files(pkg):
    files = []
    for root, dirs, names in os.walk(pkg):
        dirs.sort()
        for n in sorted(names):
            fp = os.path.join(root, n)
            if os.path.isfile(fp) and not os.path.islink(fp):
                rel = os.path.relpath(fp, pkg).replace(os.sep, "/")
                files.append((rel, fp))
    files.sort(key=lambda x: x[0])
    return files


def compute_digest(pkg):
    """镜像 PluginRegistry.computeDigest：排序 relPath\\0bytes\\0 喂 SHA-256。"""
    h = hashlib.sha256()
    files = walk_files(pkg)
    for rel, fp in files:
        h.update(rel.encode("utf-8") + b"\x00")
        with open(fp, "rb") as f:
            h.update(f.read())
        h.update(b"\x00")
    return h.hexdigest(), len(files)


def discover_official(pkg):
    """M4 官方白名单：基线集 + 就近发现的 seed/manifest.json 声明集 + --allow。"""
    names = set(OFFICIAL_BASELINE)
    source = "built-in baseline"
    d = os.path.abspath(pkg)
    for _ in range(6):
        for cand in (
            os.path.join(d, "src", "main", "resources", "seed", "manifest.json"),
            os.path.join(d, "seed", "manifest.json"),
        ):
            if os.path.isfile(cand):
                try:
                    with open(cand, encoding="utf-8") as f:
                        data = json.load(f)
                    declared = [i.split(":", 1)[1] for i in data.get("items", [])
                                if isinstance(i, str) and i.startswith("plugins:")]
                    names.update(declared)
                    source = f"baseline + {os.path.relpath(cand, os.path.abspath(pkg))}"
                except Exception as e:
                    source = f"baseline (seed manifest read failed: {e})"
                return names, source
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return names, source


def discover_builtin_tree_from_pkg(pkg):
    """自目标包目录向上找 seed/plugins（分发树探测，兜底臂）。"""
    d = os.path.abspath(pkg)
    for _ in range(8):
        for cand in (
            os.path.join(d, "src", "main", "resources", "seed", "plugins"),
            os.path.join(d, "seed", "plugins"),
        ):
            if os.path.isdir(cand):
                return cand
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return None


def discover_builtin_tree_from_script():
    """自本脚本位置回溯（脚本随 creator 包分发、住在分发树内——优先臂）。"""
    here = os.path.dirname(os.path.abspath(__file__))
    up = here
    for _ in range(4):  # scripts → plugin-packaging → skills → <creator 包> → plugins
        up = os.path.dirname(up)
    if os.path.basename(up) == "plugins" and os.path.basename(os.path.dirname(up)) == "seed":
        return up
    return None


def discover_builtin_tree(pkg):
    """C9 用：定位分发内置官方包目录（OfficialPackages.scala:57 BuiltinRoot）。
    优先脚本自身所在树（= 本脚本所属产品的分发树，身份闸的权威代理源），
    兜底目标包向上找。找不到 → None（调用方按 fail-closed 语义处置）。"""
    return discover_builtin_tree_from_script() or discover_builtin_tree_from_pkg(pkg)


def builtin_manifest_names(tree):
    """分发树内各包的 manifest name 集合（键规则同 OfficialPackages.computeAllowlist：
    manifest name 优先、退目录名）。"""
    names = set()
    if not tree:
        return names
    for d in sorted(os.listdir(tree)):
        dp = os.path.join(tree, d)
        if not os.path.isdir(dp):
            continue
        found = ""
        mp = os.path.join(dp, "plugin.json")
        if os.path.isfile(mp):
            try:
                with open(mp, encoding="utf-8") as f:
                    n = json.load(f).get("name")
                if isinstance(n, str) and n.strip():
                    found = n.strip()
            except Exception:
                pass
        names.add(found or d)
    return names


def contained_under(pkg, p):
    """§4.1 围栏：realpath 解析后必须仍在插件根内（PluginRegistry.scala:46-47）。"""
    base = os.path.realpath(pkg)
    rp = os.path.realpath(p)
    return rp == base or rp.startswith(base + os.sep)


def parse_frontmatter(text):
    if not text.startswith("---"):
        return None
    lines = text.splitlines()
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        return None
    fm = {}
    for line in lines[1:end]:
        if ":" in line:
            k, v = line.split(":", 1)
            fm[k.strip()] = v.strip()
    return fm


def valid_plugin_name(n):
    """镜像 validPluginName（§5.5）。"""
    return (
        bool(n) and len(n) <= 64
        and bool(NAME_CHARS_RE.match(n))
        and n[0].isalnum() and n[-1].isalnum()
        and "--" not in n and ".." not in n
    )


def actual_skills(pkg):
    sd = os.path.join(pkg, "skills")
    out_ = []
    if os.path.isdir(sd):
        for name in sorted(os.listdir(sd)):
            if os.path.isfile(os.path.join(sd, name, "SKILL.md")):
                out_.append(name)
    return out_


def desc_skill_names(desc):
    m = SKILLS_DETAIL_RE.search(desc)
    if not m:
        return []
    segment = desc[m.end():]
    stop = segment.find("。")
    if stop >= 0:
        segment = segment[:stop]
    return SKILL_NAME_IN_DESC_RE.findall(segment)


def valid_mcp_url(u):
    """镜像 validMcpUrl 语义：绝对 http/https、无 userinfo、无 fragment。"""
    if not isinstance(u, str) or not u:
        return False
    if not (u.startswith("http://") or u.startswith("https://")):
        return False
    authority = u.split("://", 1)[1].split("/", 1)[0]
    if "@" in authority or "#" in u:
        return False
    return True


def mcp_url_loopback_https_ok(u):
    """C14 增量镜像（PluginRegistry.scala:1083-1094）：非 loopback host 必须 https。
    host 提取镜像 java.net.URI.getHost 语义（userinfo 剥离、端口不入 host、IPv6 带
    方括号）。形态问题（非 http/https、userinfo、fragment）归 M14，此处不重复判。"""
    if not isinstance(u, str):
        return True
    m = re.match(r"^(https?)://([^/?#]+)", u)
    if not m:
        return True
    scheme, authority = m.group(1), m.group(2)
    host = authority.split("@")[-1]
    if host.startswith("["):
        host = host.split("]")[0] + "]"
    else:
        host = host.split(":")[0]
    loopback = host in ("localhost", "::1", "[::1]") or host.startswith("127.")
    if scheme == "http" and not loopback:
        return False
    return True


def _fence_escape(rel):
    """`./` 形态的插件根围栏（镜像 resolvePluginRelative + os.SubPath 语义：
    `..` 段 / 绝对形态 = 越界）。rel 已剥 `./` 前缀。"""
    if os.path.isabs(rel):
        return True
    return ".." in rel.split("/")


def check_mcp_json(pkg, manifest):
    path = os.path.join(pkg, "mcp.json")
    if not os.path.exists(path):
        record("M14", "mcp.json 镜像校验", True, "无 mcp.json——组件缺席，跳过")
        return None
    if manifest and manifest.get("mcpServers"):
        pass  # manifest 顶层不该有 mcpServers（那是 mcp.json 的字段）；仅提示不计分
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        record("M14", "mcp.json 镜像校验", False, f"JSON 不可解析：{e}")
        return None
    if not isinstance(data, dict):
        record("M14", "mcp.json 镜像校验", False, "顶层不是 JSON object")
        return None
    if data.get("$schema") != CANONICAL_MCP_SCHEMA:
        record("M14", "mcp.json 镜像校验", False,
               f"$schema 非 canonical（期望 {CANONICAL_MCP_SCHEMA}）")
        return None
    servers = data.get("mcpServers")
    if not isinstance(servers, dict):
        record("M14", "mcp.json 镜像校验", False, "mcpServers 缺失或非 object")
        return None
    bad = []
    for sname, entry in servers.items():
        if not isinstance(entry, dict):
            bad.append(f"{sname}: entry 非 object")
            continue
        tpe = entry.get("type")
        if tpe not in MCP_TRANSPORTS:
            bad.append(f"{sname}: transport 非法（{tpe!r}，允许 stdio|streamable-http|sse）")
            continue
        if tpe == "sse":
            warn_lines.append(f"M14 mcp server '{sname}': sse 传输本客户端不支持（装载时跳过+告警）——建议改 streamable-http")
        env = entry.get("env")
        if env is not None:
            if not isinstance(env, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in env.items()):
                bad.append(f"{sname}: env 必须是 string→string object")
            else:
                for k in env:
                    if k in PLACEHOLDER_ENV_KEYS:
                        bad.append(f"{sname}: env 声明了保留占位键 '{k}'（§9.1，装载必拒）")
                    elif CREDENTIAL_KEY_RE.search(k):
                        warn_lines.append(f"M14 mcp server '{sname}': env 键 '{k}' 疑似凭据——人审确认（红标启发式）")
        cmd = entry.get("command")
        if tpe == "stdio":
            if not isinstance(cmd, str) or not cmd:
                bad.append(f"{sname}: stdio 缺 command")
            elif re.search(r"\s", cmd):
                bad.append(f"{sname}: command 含空白（必须是单一可执行 token）")
            elif cmd:
                base = cmd.split("/")[-1]
                if base in SHELL_LIKE_COMMANDS:
                    warn_lines.append(f"M14 mcp server '{sname}': command '{base}' 指向 shell/网络类可执行——人审确认（红标启发式）")
        if tpe in ("streamable-http", "sse"):
            url = entry.get("url")
            if not valid_mcp_url(url):
                bad.append(f"{sname}: transport {tpe} 缺合法 http/https url（绝对地址、无 userinfo、无 fragment）")
            cwd = entry.get("cwd")
            if cwd is not None:
                if not isinstance(cwd, str) or not (
                    cwd.startswith("./") or cwd.startswith("${PLUGIN_ROOT}") or cwd.startswith("${PLUGIN_DATA}")
                ):
                    bad.append(f"{sname}: cwd 须以 ./ 或 ${{PLUGIN_ROOT}} 或 ${{PLUGIN_DATA}} 开头")
    if bad:
        record("M14", "mcp.json 镜像校验", False, "；".join(bad))
    else:
        record("M14", "mcp.json 镜像校验", True, f"{len(servers)} 个 server entry 全部通过镜像规则")
    return servers


def check_c9(pkg, name):
    """C9 官方身份闸镜像（OfficialPackages.scala:51,115-116）：`nebflow-` 前缀 =
    官方保留 namespace——必须在分发内置允许列表且目录 digest 逐字相等，否则装载器
    拒载（OFFICIAL_IMPERSONATION）。非保留前缀不经本闸（:115-116）。
    允许列表对分发树**现算**（同源同版本零漂移，:25-34）；树不可定位 → fail-closed
    （:34 目录缺失 ⇒ 空表 ⇒ 保留前缀包一律拒载）。"""
    if not name.startswith(RESERVED_OFFICIAL_PREFIX):
        record("C09", "官方身份闸镜像", True,
               f"name={name!r} 非 {RESERVED_OFFICIAL_PREFIX} 保留前缀，不经本闸")
        return
    tree = discover_builtin_tree(pkg)
    if not tree:
        record("C09", "官方身份闸镜像", False,
               f"{name!r} 命中保留前缀 {RESERVED_OFFICIAL_PREFIX} 且无法就近定位分发内置 "
               "seed/plugins 树（现算允许列表源）——无法证明身份；装载器对保留前缀 "
               "fail-closed（OfficialPackages.scala:34）将拒载（OFFICIAL_IMPERSONATION）")
        return
    allow = {}
    for d in sorted(os.listdir(tree)):
        dp = os.path.join(tree, d)
        if not os.path.isdir(dp):
            continue
        mname = d
        mp = os.path.join(dp, "plugin.json")
        if os.path.isfile(mp):
            try:
                with open(mp, encoding="utf-8") as f:
                    n = json.load(f).get("name")
                if isinstance(n, str) and n.strip():
                    mname = n.strip()
            except Exception:
                pass
        allow[mname] = compute_digest(dp)[0]
    my_digest = compute_digest(pkg)[0]
    if name not in allow:
        record("C09", "官方身份闸镜像", False,
               f"不在分发内置允许列表（树内 {len(allow)} 包）——装载器将拒载：保留前缀疑冒名，"
               f"换非保留名重装（OFFICIAL_IMPERSONATION；在盘 digest {my_digest[:12]}…）")
    elif allow[name] != my_digest:
        record("C09", "官方身份闸镜像", False,
               f"内容 digest 与分发内置官方包失配（内置 {allow[name][:12]}… vs 在盘 "
               f"{my_digest[:12]}…）——目录在安装后被改动；恢复原包或改非保留名"
               "（OFFICIAL_IMPERSONATION）")
    else:
        record("C09", "官方身份闸镜像", True,
               f"分发内置允许列表命中且 digest 逐字相等（{my_digest[:12]}…）")


def check_c10(manifest):
    """C10 manifest 字段类型 fatal 面镜像（PluginRegistry.scala:649-676,871-878）：
    version/description/homepage/repository/license present 须 string；keywords
    present 须 string 数组；author present 须 object 且仅 name/email/url string
    字段。违反 = manifest invalid → 装载器拒载。"""
    if manifest is None:
        record("C10", "manifest 字段类型 fatal 面", True, "manifest 前置失格（M01 已计 FAIL）——跳过")
        return
    bad = []
    for k in ("version", "description", "homepage", "repository", "license"):
        v = manifest.get(k)
        if v is not None and not isinstance(v, str):
            bad.append(f"{k} 须 string（现 {type(v).__name__}）——manifest invalid，装载器拒载")
    kw = manifest.get("keywords")
    if kw is not None and not (isinstance(kw, list) and all(isinstance(x, str) for x in kw)):
        bad.append("keywords 须 string 数组——manifest invalid，装载器拒载")
    au = manifest.get("author")
    if au is not None:
        if not isinstance(au, dict):
            bad.append(f"author 须 object（现 {type(au).__name__}）——manifest invalid，装载器拒载")
        else:
            unknown = [k for k in au if k not in ("name", "email", "url")]
            nonstr = [k for k in au if not isinstance(au[k], str)]
            if unknown:
                bad.append(f"author 仅允许 name/email/url 字段（多余：{'、'.join(unknown)}）——manifest invalid，装载器拒载")
            if nonstr:
                bad.append(f"author 字段值须 string（非 string：{'、'.join(nonstr)}）——manifest invalid，装载器拒载")
    if bad:
        record("C10", "manifest 字段类型 fatal 面", False, "；".join(bad))
    else:
        record("C10", "manifest 字段类型 fatal 面", True,
               "version/description/homepage/repository/license/keywords/author 类型全合规（present 者）")


def check_c11(pkg, manifest, mcp_data):
    """C11 未知条目告警面（宽容装载 + 告警的镜像，§B.8-5）：manifest 键（闭合 11 键
    :133-145）/ 包根五件（:583；第五件 Plugin.md 恰一文件、逐字命名）/ org.nebflow 子条目（:587-591）/ mcp.json 顶层键
    （:924-930）与 server entry 字段（:980-983）。"""
    unknowns = []
    for k in (manifest or {}):
        if k not in KNOWN_MANIFEST_KEYS:
            unknowns.append(f"manifest 未知字段 '{k}'（宽容忽略+告警）")
    for e in sorted(os.listdir(pkg)):
        if e not in KNOWN_ROOT_ENTRIES:
            unknowns.append(f"包根未知条目 '{e}'（宽容跳过+告警）")
        elif e == "Plugin.md" and os.path.isdir(os.path.join(pkg, e)):
            unknowns.append("包根 'Plugin.md' 目录化（白名单仅认恰一文件、逐字命名，六-3）")
    ns = os.path.join(pkg, "org.nebflow")
    if os.path.isdir(ns):
        for f in sorted(os.listdir(ns)):
            if f not in KNOWN_NS_ENTRIES:
                unknowns.append(f"org.nebflow 未知条目 '{f}'（贡献静默失效告警）")
    if isinstance(mcp_data, dict):
        for k in mcp_data:
            if k not in KNOWN_MCP_TOP_KEYS:
                unknowns.append(f"mcp.json 未知顶层字段 '{k}'（宽容忽略+告警）")
        servers = mcp_data.get("mcpServers")
        if isinstance(servers, dict):
            for sname, entry in servers.items():
                if isinstance(entry, dict):
                    for k in entry:
                        if k not in KNOWN_SERVER_KEYS:
                            unknowns.append(f"mcp server '{sname}' 未知字段 '{k}'（宽容忽略+告警）")
    if unknowns:
        record("C11", "未知条目告警面", False, "；".join(unknowns[:8]) + ("…" if len(unknowns) > 8 else ""), warn=True)
    else:
        record("C11", "未知条目告警面", True,
               "manifest 键／包根五件（第五件 Plugin.md 恰一文件、逐字命名）／org.nebflow／mcp.json（顶层+entry）全部命中已知集合")
    # C11b 包根必要文件（作者令 2026-10-21「plugin.md = 插件必要文件机制」）：
    # Plugin.md 必须在位（恰一文件、逐字命名）——缺失 = FAIL（分配注入读包根
    # Plugin.md，缺文件 fail-closed 拒注入；大写为正字，小写 plugin.md 容忍但提示）。
    has_md = os.path.isfile(os.path.join(pkg, "Plugin.md"))
    lower_only = (not has_md) and os.path.isfile(os.path.join(pkg, "plugin.md"))
    if has_md:
        record("C11b", "包根必要文件 Plugin.md", True, "包根 Plugin.md 在位（恰一文件，分配注入面）")
    elif lower_only:
        record("C11b", "包根必要文件 Plugin.md", False,
               "包根只有小写 plugin.md（引擎回退同认，但白名单正字为 Plugin.md）——重命名为 'Plugin.md'", warn=True)
    else:
        record("C11b", "包根必要文件 Plugin.md", False,
               "包根缺 Plugin.md（插件必要文件：分配注入 = 该文件内容，缺文件 fail-closed 拒注入）——按 §3.4 生成")


def check_c12(manifest):
    """C12 capability 残留（deprecated 键，PluginRegistry.scala:127-131：登记在
    KnownManifestKeys 容忍装载，读取/承载/渲染逻辑全部退役）。新包禁写、存量容忍。"""
    if manifest is None:
        record("C12", "capability 残留", True, "manifest 前置失格——跳过")
        return
    if "capability" in manifest:
        record("C12", "capability 残留", False,
               "deprecated 键 'capability' 残留（description 是唯一描述源）——存量容忍装载，新包禁写；重封装时删除", warn=True)
    else:
        record("C12", "capability 残留", True, "无 capability 残留")


def _resolve_ns_file(pkg, ns, declared, fallback):
    """镜像 tools.json/viewers.json 解析形态（PluginRegistry.scala:715-739,767-795）：
    manifest extensions 声明文件名（org.nebflow/ 命名空间相对为主、插件根相对兼容），
    未声明回落默认名；两处均围栏校验。返回命中路径或 None。"""
    cands = []
    if isinstance(declared, str) and declared:
        cands.append(os.path.join(ns, declared))
        cands.append(os.path.join(pkg, declared))
    cands.append(os.path.join(ns, fallback))
    for c in cands:
        if os.path.isfile(c) and contained_under(pkg, c):
            return c
    return None


def check_c13(pkg, manifest):
    """C13 org.nebflow 镜像：tools.json 越工具白名单 = 拒载（PLUGIN_TOOLS_ILLEGAL，
    PluginRegistry.scala:754-760；白名单 :104）；解析失败/类型失格 = 告警+忽略
    （:747-764，组件级、插件继续）。viewers.json 超内联上限/不可解析/顶层非 object
    = 告警（:796-813）。"""
    ns = os.path.join(pkg, "org.nebflow")
    ext = (manifest or {}).get("extensions")
    ext = ext if isinstance(ext, dict) else {}
    bad, warns = [], []

    tools_p = _resolve_ns_file(pkg, ns, ext.get("org.nebflow/tools"), "tools.json")
    declared_tools = ext.get("org.nebflow/tools")
    if tools_p is None:
        if isinstance(declared_tools, str) and declared_tools:
            warns.append(f"extensions 'org.nebflow/tools' 声明 '{declared_tools}' 但无可读文件——tools 扩展被忽略")
        record_ok_tools = True
    else:
        try:
            with open(tools_p, encoding="utf-8") as f:
                tjson = json.load(f)
            tools = tjson.get("tools") if isinstance(tjson, dict) else None
            if not (isinstance(tools, list) and all(isinstance(t, str) for t in tools)):
                warns.append("tools.json 'tools' 非 string 数组——tools 扩展被忽略（decode failed）")
            else:
                illegal = [t for t in tools if t not in TOOL_WHITELIST]
                if illegal:
                    bad.append(
                        f"org.nebflow/tools 请求非白名单工具：{'、'.join(illegal)}——装载器拒载"
                        f"（PLUGIN_TOOLS_ILLEGAL；允许：{'、'.join(sorted(TOOL_WHITELIST))}）"
                    )
        except Exception as e:
            warns.append(f"tools.json 不可解析（{e}）——tools 扩展被忽略")

    viewers_p = _resolve_ns_file(pkg, ns, ext.get("org.nebflow/viewers"), "viewers.json")
    declared_viewers = ext.get("org.nebflow/viewers")
    if viewers_p is None:
        if isinstance(declared_viewers, str) and declared_viewers:
            warns.append(f"extensions 'org.nebflow/viewers' 声明 '{declared_viewers}' 但无可读文件——viewer 贡献被忽略")
    else:
        size = os.path.getsize(viewers_p)
        if size > MAX_VIEWER_BYTES:
            warns.append(f"viewers.json {size} 字节，超 {MAX_VIEWER_BYTES} 字节内联上限——viewer 贡献被忽略（大件走 /api/plugin-assets）")
        else:
            try:
                with open(viewers_p, encoding="utf-8") as f:
                    vjson = json.load(f)
                if not isinstance(vjson, dict):
                    warns.append("viewers.json 顶层非 object——viewer 贡献被忽略")
            except Exception as e:
                warns.append(f"viewers.json 不可解析（{e}）——viewer 贡献被忽略")

    if bad:
        record("C13", "org.nebflow 镜像", False, "；".join(bad))
        for w in warns:
            warn_lines.append(f"C13 {w}")
    elif warns:
        record("C13", "org.nebflow 镜像", False, "；".join(warns), warn=True)
    else:
        record("C13", "org.nebflow 镜像", True, "tools 扩展（有则白名单内）与 viewers 扩展（有则合规）全部通过镜像规则")


def check_c14(pkg, mcp_servers):
    """C14 M14 补差（镜像缺口四处）：非 loopback host 须 https（validMcpUrl
    :1083-1094）；args/headers 类型（:1002-1004,:1060-1062）；stdio `./` command 与
    cwd `./` 形态的插件根围栏（:996-1001,:1013-1028——cwd 围栏仅 stdio 分支）。"""
    if not mcp_servers:
        record("C14", "M14 补差（https/args/headers/围栏）", True, "无 mcp.json 或前置失格（M14 已判）——跳过")
        return
    bad = []
    for sname, entry in mcp_servers.items():
        if not isinstance(entry, dict):
            continue  # M14 已判
        tpe = entry.get("type")
        if tpe == "stdio":
            cmd = entry.get("command")
            if isinstance(cmd, str) and cmd.startswith("./") and _fence_escape(cmd[2:]):
                bad.append(f"{sname}: stdio command '{cmd}' 越插件根（§4.1）——entry 将被跳过")
            args = entry.get("args")
            if args is not None and not (isinstance(args, list) and all(isinstance(a, str) for a in args)):
                bad.append(f"{sname}: args 须 string 数组——entry 将被跳过")
            cwd = entry.get("cwd")
            if isinstance(cwd, str) and cwd.startswith("./") and _fence_escape(cwd[2:]):
                bad.append(f"{sname}: cwd '{cwd}' 越插件根（§4.1）——entry 将被跳过")
        elif tpe in ("streamable-http", "sse"):
            url = entry.get("url")
            if isinstance(url, str) and url and not mcp_url_loopback_https_ok(url):
                bad.append(f"{sname}: url 非 loopback host 须 https——entry 将被跳过")
            headers = entry.get("headers")
            if headers is not None and not (
                isinstance(headers, dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in headers.items())
            ):
                bad.append(f"{sname}: headers 须 string→string object——entry 将被跳过")
    if bad:
        record("C14", "M14 补差（https/args/headers/围栏）", False, "；".join(bad))
    else:
        record("C14", "M14 补差（https/args/headers/围栏）", True, "url loopback-https／args／headers／`./` 围栏镜像全部通过")


def check_c15(pkg):
    """C15 符号链接/越围栏（§4.1，PluginRegistry.scala:46-47,592-607,688-712）：
    全树报告符号链接；realpath 越插件根——plugin.json 越界 = 拒载档（FAIL，
    :604-607）；SKILL.md/mcp.json/其余越界 = 跳过/忽略档（WARN）。链入包内的链接
    仅报告不判罚。"""
    links = []
    for root, dirs, names in os.walk(pkg):
        for n in list(dirs) + list(names):
            fp = os.path.join(root, n)
            if os.path.islink(fp):
                links.append((os.path.relpath(fp, pkg).replace(os.sep, "/"), fp))
    esc_hard, esc_soft, inbound = [], [], []
    for rel, fp in links:
        if contained_under(pkg, fp):
            inbound.append(rel)
        elif rel == "plugin.json":
            esc_hard.append(rel)
        else:
            esc_soft.append(rel)
    if esc_hard:
        record("C15", "符号链接/越围栏", False,
               f"plugin.json 符号链接越插件根：{'、'.join(esc_hard)}——装载器拒载（§4.1 containment，:604-607）")
    elif esc_soft:
        record("C15", "符号链接/越围栏", False,
               f"符号链接越插件根（跳过/忽略档）：{'、'.join(esc_soft)}——越界件按 §4.1 各自跳过", warn=True)
    else:
        extra = f"；链入包内链接 {len(inbound)} 条（仅报告）" if inbound else ""
        record("C15", "符号链接/越围栏", True, f"全树 {len(links)} 条符号链接零越界{extra}" if links else "全树无符号链接")


def _m16_exempt(line):
    """C16 语境豁免判定。返回豁免类别或 None：
    - 'quoted'：该行全部 M15 命中均被引号包裹（QA 自查清单的教学提及形态）；
    - 'regex'：标记出现在 /…/ 定界的正则字面量内（校验脚本自带占位检测正则等）。
    豁免 ≠ 删除：命中仍转人工复核提示（WARN 面），只是不计 FAIL。"""
    ms = list(M15_RE.finditer(line))
    if not ms:
        return None
    if all(m.start() > 0 and m.end() < len(line)
           and line[m.start() - 1] in _C16_QUOTES and line[m.end()] in _C16_QUOTES
           for m in ms):
        return "quoted"
    for sm in re.finditer(r"/[^/\n]{0,160}/[gimsuy]*", line):
        if M15_RE.search(sm.group(0)):
            return "regex"
    return None


def scan_m15(pkg):
    """M15 全包占位扫描 + C16 语境豁免分流。返回 (real_hits, exempt_hits)。"""
    real, exempt = [], []
    for rel, fp in walk_files(pkg):
        try:
            with open(fp, "rb") as f:
                content = f.read().decode("utf-8", errors="ignore")
        except Exception:
            continue
        for i, line in enumerate(content.splitlines(), start=1):
            if not M15_RE.search(line):
                continue
            kind = _m16_exempt(line)
            if kind:
                exempt.append(f"{rel}:{i}({kind})")
            else:
                real.append(f"{rel}:{i}")
    return real, exempt


def check_c1(pkg):
    """C1 skills/ 非 skill 目录（§7.1 skip+warn 镜像，PluginRegistry.scala:687-694）。"""
    sd = os.path.join(pkg, "skills")
    if not os.path.isdir(sd):
        record("C01", "skills/ 非 skill 目录", True, "无 skills/ 目录——跳过")
        return
    bad = [d for d in sorted(os.listdir(sd))
           if os.path.isdir(os.path.join(sd, d)) and not os.path.isfile(os.path.join(sd, d, "SKILL.md"))]
    if bad:
        record("C01", "skills/ 非 skill 目录", False,
               f"{'、'.join(bad)} 无 SKILL.md——装载器每次扫描 skip+告警（§7.1）；迁入某 skill 的 references/ 或移出包", warn=True)
    else:
        record("C01", "skills/ 非 skill 目录", True, "skills/ 一级目录全部为 skill 目录（含 SKILL.md）")


def check_c2(pkg):
    """C2 skills/ 顶层纯文件（loader filter(os.isDir) 前置静默丢弃，零告警零协议身份）。"""
    sd = os.path.join(pkg, "skills")
    if not os.path.isdir(sd):
        record("C02", "skills/ 顶层纯文件", True, "无 skills/ 目录——跳过")
        return
    files = [f for f in sorted(os.listdir(sd)) if os.path.isfile(os.path.join(sd, f))]
    if files:
        record("C02", "skills/ 顶层纯文件", False,
               f"{'、'.join(files)} 被 loader 静默过滤（不装载、不告警）——三选一：上浮包根 Plugin.md（跨 skill 共用使用指导）/ 归位某 skill 的 references/（单 skill 专属）/ 移出包", warn=True)
    else:
        record("C02", "skills/ 顶层纯文件", True, "skills/ 顶层无纯文件")


def check_c3(pkg, skill_files):
    """C3 裸相对引用（应 ${SKILL_DIR} 形态）：注入面（skills 顶层 */SKILL.md 正文）找 `../`、`./` 包内引用，
    normpath 验目标存在（附带抓断链）；跨包引用单列。
    注入/非注入区分：${SKILL_DIR} 单点替换只发生在 SKILL.md 注入文本——
    非注入 md（包根共用件（Plugin.md）、references/、样张）的文内相对引用属合法文件相对形态
    （Read 工具按文件位置解析），单列读数、不计入判定；判定面仍为注入面原口径。"""
    def _scan(files):
        bare, broken, cross = [], [], []
        for sname, fp, _text in files:
            base = os.path.dirname(fp)
            rel_base = os.path.relpath(base, pkg).replace(os.sep, "/")
            for rex in (_C3_LINK_RE, _C3_CODE_RE):
                for m in rex.finditer(_text):
                    raw = m.group(1)
                    target = os.path.normpath(os.path.join(base, raw))
                    if contained_under(pkg, target):
                        if os.path.exists(target):
                            bare.append(f"{rel_base}/{sname}:{raw}")
                        else:
                            broken.append(f"{rel_base}/{sname}:{raw}")
                    else:
                        cross.append(f"{rel_base}/{sname}:{raw}")
        return bare, broken, cross

    inj_bare, inj_broken, inj_cross = _scan(skill_files)

    # 非注入 md：全包 .md 去除注入面（包根共用件（Plugin.md）、references/、样张等）
    inj_paths = {os.path.normpath(fp) for _s, fp, _t in skill_files}
    non_files = []
    for rel, fp in walk_files(pkg):
        if not rel.lower().endswith(".md") or os.path.normpath(fp) in inj_paths:
            continue
        try:
            with open(fp, encoding="utf-8") as f:
                t = f.read()
        except Exception:
            continue
        non_files.append((os.path.basename(fp), fp, t))
    non_bare, non_broken, non_cross = _scan(non_files)

    parts = []
    if inj_bare:
        parts.append(f"裸相对引用 {len(inj_bare)} 处（注入面，应 ${'{SKILL_DIR}'}/ 形态，如 {'、'.join(inj_bare[:3])}{'…' if len(inj_bare) > 3 else ''}）")
    if inj_broken:
        parts.append(f"断链 {len(inj_broken)} 处（注入面，目标不存在，如 {'、'.join(inj_broken[:3])}{'…' if len(inj_broken) > 3 else ''}）")
    if inj_cross:
        parts.append(f"跨包引用 {len(inj_cross)} 处（${'{SKILL_DIR}'} 不跨包，单列：{'、'.join(inj_cross[:3])}{'…' if len(inj_cross) > 3 else ''}）")
    non_parts = []
    if non_bare:
        non_parts.append(f"裸相对引用 {len(non_bare)} 处（如 {'、'.join(non_bare[:2])}{'…' if len(non_bare) > 2 else ''}）")
    if non_broken:
        non_parts.append(f"断链 {len(non_broken)} 处（如 {'、'.join(non_broken[:2])}{'…' if len(non_broken) > 2 else ''}）")
    if non_cross:
        non_parts.append(f"跨包引用 {len(non_cross)} 处")
    non_note = ""
    if non_parts:
        non_note = "；非注入面（共用件/样张，文件相对形态合法、不计入判定）：" + "；".join(non_parts)
    if parts:
        record("C03", "裸相对引用/断链/跨包", False, "；".join(parts) + non_note, warn=True)
    else:
        record("C03", "裸相对引用/断链/跨包", True,
               "SKILL.md 注入面零裸相对引用、零断链、零跨包引用" + (non_note if non_note else "；非注入面零命中"))


def check_c4(pkg):
    """C4 机器绑定绝对路径：盘符、/Us+ers/<n>/、/ho+me/<n>/ 家目录型逐行扫（${SKILL_DIR} 占位
    行剔除后扫描——占位形态不属机器绑定）。"""
    hits = {}
    total = 0
    sample = []
    for rel, fp in walk_files(pkg):
        try:
            with open(fp, "rb") as f:
                content = f.read().decode("utf-8", errors="ignore")
        except Exception:
            continue
        n = 0
        for i, line in enumerate(content.splitlines(), start=1):
            stripped = line.replace("${SKILL_DIR}", "")
            m = _C4_RE.search(stripped)
            if m:
                n += 1
                if len(sample) < 5:
                    sample.append(f"{rel}:{i}:{m.group(0)[:60]}")
        if n:
            hits[rel] = n
            total += n
    if hits:
        top = "、".join(f"{k}×{v}" for k, v in sorted(hits.items(), key=lambda x: -x[1])[:5])
        record("C04", "机器绑定绝对路径", False,
               f"{total} 处（{top}{'…' if len(hits) > 5 else ''}）；样例：{'；'.join(sample[:3])}——改实例事实表述或按 data root/PATH 探测", warn=True)
    else:
        record("C04", "机器绑定绝对路径", True, "全包零机器绑定绝对路径（盘符、家目录型）")


def check_c5(pkg, name):
    """C5 二进制资产 / seed text-mode 约束（双模）：strict UTF-8 失败、前 8KB 含 null、
    扩展名黑名单 → 命中。包属于某分发内置树（包所在树优先，脚本所属树兜底——
    覆盖 live 安装位自检）= 硬闸（SeedService 文本装卸通道禁载二进制）；否则 = 警告
    （用户装包模式）。"""
    hard = False
    for tree in (discover_builtin_tree_from_pkg(pkg), discover_builtin_tree_from_script()):
        if not tree:
            continue
        dirs = {d for d in os.listdir(tree) if os.path.isdir(os.path.join(tree, d))}
        if os.path.basename(pkg) in dirs or name in builtin_manifest_names(tree):
            hard = True
            break
    hits = []
    for rel, fp in walk_files(pkg):
        ext = rel.rsplit(".", 1)[-1].lower() if "." in rel else ""
        if ext in BINARY_EXT:
            hits.append(f"{rel}（扩展名黑名单）")
            continue
        try:
            with open(fp, "rb") as f:
                data = f.read()
            data.decode("utf-8")  # strict
            if b"\x00" in data[:8192]:
                hits.append(f"{rel}（前 8KB 含 null）")
        except UnicodeDecodeError:
            hits.append(f"{rel}（非 strict UTF-8）")
        except Exception:
            continue
    mode = "硬闸（包在分发内置树内，seed text-mode 约束）" if hard else "警告（用户装包模式）"
    if hits:
        record("C05", "二进制资产/text-mode", False,
               f"{len(hits)} 处命中（{mode}）：{'、'.join(hits[:6])}{'…' if len(hits) > 6 else ''}",
               warn=not hard)
    else:
        record("C05", "二进制资产/text-mode", True, f"全包零二进制资产（strict UTF-8／无 null／无黑名单扩展名；{mode}）")


def check_c6(frontmatters):
    """C6 frontmatter name == 目录名（agentskills.io 严约束；装载器不校验——skill id
    用目录名，展示名用 frontmatter name，PluginRegistry.scala:660-663 注记）。"""
    bad = []
    for sname, _fp, fm in frontmatters:
        n = (fm or {}).get("name", "").strip() if fm else ""
        if fm is not None and n and n != sname:
            bad.append(f"{sname}: frontmatter name '{n}' ≠ 目录名")
    if bad:
        record("C06", "frontmatter name==目录名", False, "；".join(bad) + "（agentskills.io 约束；装载器以目录名为 skill id）", warn=True)
    else:
        record("C06", "frontmatter name==目录名", True, "全部 skill 的 frontmatter name 与目录名一致（有 frontmatter 者）")


def check_c8(pkg, name):
    """C8 manifest name == 目录名（PluginRegistry.scala:642-644：以 manifest 为准并告警）。"""
    dirbase = os.path.basename(pkg)
    if name and dirbase and name != dirbase:
        record("C08", "manifest name==目录名", False,
               f"manifest name {name!r} ≠ 目录名 {dirbase!r}——装载器以 manifest name 为准并告警（引用歧义防护）", warn=True)
    else:
        record("C08", "manifest name==目录名", True, f"manifest name 与目录名一致（{dirbase!r}）")


def check_c17(manifest):
    """C17 description 长度软检查（双模式批 2026-10-28 增量，[WARN]）。

    description > 200 字符 = 提示性告警（非 FAIL）：五段式规范以 400 为硬顶
    （M07/MAX_DESC_CHARS），200 内为「分配问对面」可读性最优段——超 200 只提示
    收敛，不阻断交付（容忍读法：增量键纪律，软检查永不变成拒载面）。
    """
    desc = (manifest or {}).get("description") or ""
    n = len(desc)
    if n > 200:
        record("C17", "description 长度（≤200 软顶）", False,
               f"长度={n}/200——超软顶：分配问对面可读性收敛建议（硬顶 400 由 M07 计）；"
               "可把 skill 明细交给「内含 skills：」句压缩主句", warn=True)
    else:
        record("C17", "description 长度（≤200 软顶）", True, f"长度={n}/200")


def check_c18(pkg):
    """C18 Plugin.md 引用 SKILL.md 路径存在性（双模式批 2026-10-28 增量，[WARN]）。

    Plugin.md（包级能力页）里以 `skills/<id>/SKILL.md` 形态引用的路径必须真实
    在包内——断链 = 面板/文档读数落空。仅 [WARN]：文档面引用，不触装载语义
    （skills/ 装载自扫目录，不读 Plugin.md）。
    """
    pm = os.path.join(pkg, "Plugin.md")
    if not os.path.isfile(pm):
        record("C18", "Plugin.md 引用 SKILL.md 路径存在性", True, "无 Plugin.md——零引用零断链面")
        return
    try:
        with open(pm, encoding="utf-8") as f:
            text = f.read()
    except Exception as e:
        record("C18", "Plugin.md 引用 SKILL.md 路径存在性", False, f"Plugin.md 不可读：{e}", warn=True)
        return
    refs = sorted(set(re.findall(r"skills/[\w.\-]+/SKILL\.md", text)))
    broken = [r for r in refs if not os.path.isfile(os.path.join(pkg, r))]
    if broken:
        record("C18", "Plugin.md 引用 SKILL.md 路径存在性", False,
               f"断链 {len(broken)}/{len(refs)}：{'、'.join(broken)}——引用的 SKILL.md 不在包内", warn=True)
    else:
        record("C18", "Plugin.md 引用 SKILL.md 路径存在性", True,
               f"{len(refs)} 处引用全部在包内" if refs else "零 SKILL.md 路径引用")


def main():
    global _profile, _report_fh
    ap = argparse.ArgumentParser(description="Nebflow 插件包机械校验 M1-M15 + C 组镜像（三档 profile）")
    ap.add_argument("pkg", help="插件包目录（含 plugin.json）")
    ap.add_argument("--profile", choices=("official", "creator", "user"), default="creator",
                    help="严格度分档：official=官方面硬闸（适用 9 包）／creator=creator 产出交付闸"
                         "（默认）／user=提示级（退出码恒 0）")
    ap.add_argument("--allow", action="append", default=[], metavar="NAME",
                    help="追加官方白名单名（保留前缀豁免），可重复")
    ap.add_argument("--report-file", metavar="PATH", default=None,
                    help="全量报告落文件（user 档建议配用：报告落文件不刷屏）")
    args = ap.parse_args()
    _profile = args.profile
    if args.report_file:
        _report_fh = open(args.report_file, "w", encoding="utf-8")
    mode = PROFILE_MODES[_profile]
    out(f"[INFO] profile={_profile}（"
        + {"official": "官方面硬闸：M1-M15 全量+C 组 fatal 镜像，退出码≠0 阻断；WARN 逐条处置或挂豁免记录",
           "creator": "creator 交付闸：M1-M15 全量+fatal 镜像，退出码 0 为交付闸；惯例面警告（C1-C3/C6）须处置或说明",
           "user": "提示级：同引擎全项输出、全部降提示、无 FAIL 语义，退出码恒 0"}[_profile] + "）")
    out("[INFO] C07 缓（D8）：包级行数预算候观测，本版未实现")
    pkg = os.path.abspath(args.pkg)

    if not os.path.isdir(pkg):
        out(f"[FAIL] 前置 包目录不存在：{pkg}")
        sys.exit(1)
    manifest_path = os.path.join(pkg, "plugin.json")
    if not os.path.isfile(manifest_path):
        out("[FAIL] 前置 plugin.json 缺失")
        sys.exit(1)

    if _profile == "official":
        dirbase = os.path.basename(pkg)
        manifest_name_hint = ""
        try:
            with open(manifest_path, encoding="utf-8") as f:
                manifest_name_hint = (json.load(f).get("name") or "")
        except Exception:
            pass
        if dirbase not in OFFICIAL_FACE and manifest_name_hint not in OFFICIAL_FACE:
            out("[INFO] 此包不在官方面 9 包名单内——official 档不适用此包")

    manifest = None
    # M1 manifest 可解析、顶层 object
    try:
        with open(manifest_path, encoding="utf-8") as f:
            manifest = json.load(f)
        ok = isinstance(manifest, dict)
        record("M01", "manifest 可解析且顶层 object", ok, "OK" if ok else "顶层不是 object")
    except Exception as e:
        record("M01", "manifest 可解析且顶层 object", False, f"JSON 解析失败：{e}")

    desc = (manifest or {}).get("description") or ""
    name = (manifest or {}).get("name") or ""

    # M2 $schema canonical（字符串全等）
    schema = (manifest or {}).get("$schema") or ""
    record("M02", "$schema canonical", schema == CANONICAL_SCHEMA,
           schema if schema == CANONICAL_SCHEMA else f"实际：{schema!r}")

    # M3 name §5.5
    record("M03", "name 合法（§5.5）", valid_plugin_name(name),
           f"name={name!r}" if valid_plugin_name(name) else f"name={name!r} 违反 1-64/小写字符集/首尾字母数字/禁连续 -- 与 ..")

    # M4 保留前缀
    official, official_src = discover_official(pkg)
    official.update(args.allow)
    if name.startswith(RESERVED_PREFIX) and name not in official:
        record("M04", "保留前缀拦截", False,
               f"{name!r} 命中保留前缀 {RESERVED_PREFIX} 且不在官方白名单（{official_src}）——换名重生成")
    else:
        note = "官方批次白名单命中" if name in official else "未命中保留前缀"
        record("M04", "保留前缀拦截", True, f"{note}（白名单源：{official_src}）")

    # M5 version semver
    version = (manifest or {}).get("version") or ""
    record("M05", "version 存在且 semver", bool(SEMVER_RE.match(version)), f"version={version!r}")

    # M6 定位句：非空 + 含 —— + 定位句含谓词
    first_sentence = desc.split("。", 1)[0] if desc else ""
    m6_ok = bool(desc) and "——" in first_sentence and bool(PREDICATE_RE.search(first_sentence))
    record("M06", "定位句（—— + 谓词）", m6_ok,
           f"首句={first_sentence[:60]!r}{'…' if len(first_sentence) > 60 else ''}")

    # M7 明细句：≤400 字符 + 含「内含 skills：」
    m7_len = len(desc) <= MAX_DESC_CHARS
    m7_detail = SKILLS_DETAIL_RE.search(desc) is not None
    record("M07", "明细句（≤400 字符 + 内含 skills：）", m7_len and m7_detail,
           f"长度={len(desc)}/{MAX_DESC_CHARS}，内含 skills：={'有' if m7_detail else '无'}")

    # M8 触发场景句存在性（核心词全命中是 skill/人审判定项，不在此拦）
    m8 = TRIGGER_RE.search(desc) is not None
    record("M08", "触发场景句存在", m8,
           f"命中：{TRIGGER_RE.search(desc).group(0)!r}" if m8 else "无 适用于/使用场景/当…时/用于 任一形态")

    # M9 边界句（自足自scope；BOUNDARY_RE 零逻辑改动——计划①+③合并定稿）
    m9m = BOUNDARY_RE.search(desc)
    record("M09", "边界句（自足自scope）", m9m is not None,
           f"命中：{m9m.group(0)!r}（自足边界句即达标；提名相邻插件为可选增强，非要求）" if m9m
           else "无 不适用于/不属于/勿用于（自足）或 另配/请改用（存量分流兼容）任一形态——"
                "边界句要求：说清本包不管什么任务/信号即达标，不要求提名其他插件")

    # M10 空泛词黑名单
    banned = BLACKLIST_RE.findall(desc)
    record("M10", "空泛词黑名单", not banned,
           "零命中" if not banned else f"命中：{'、'.join(sorted(set(banned)))}")

    # M11 skills 明细一致性
    actual = actual_skills(pkg)
    declared = desc_skill_names(desc)
    m11_ok = bool(actual) and set(declared) == set(actual)
    record("M11", "skills 明细一致性", m11_ok,
           f"声明={sorted(declared)} 实际={actual}" if m11_ok else
           f"不一致——描述声明={sorted(declared)}，skills/ 实际={actual}")

    # M13 逐 SKILL.md（同时收集文本/frontmatter 供 C3/C6 复用）
    skill_files = []  # (dirname, filepath, text, frontmatter)
    sd = os.path.join(pkg, "skills")
    if os.path.isdir(sd):
        for name_ in sorted(os.listdir(sd)):
            fp = os.path.join(sd, name_, "SKILL.md")
            if os.path.isfile(fp):
                with open(fp, encoding="utf-8") as f:
                    text = f.read()
                skill_files.append((name_, fp, text, parse_frontmatter(text)))
    for sname, _fp, text, _fm in skill_files:
        fm = _fm
        m13_ok = fm is not None and fm.get("name", "").strip() != "" and fm.get("description", "").strip() != ""
        record(f"M13", f"frontmatter 下限 {sname}/SKILL.md", m13_ok,
               "name+description 非空" if m13_ok else
               "frontmatter 缺失或缺非空 name/description（装载器 skip 规则上游拦截）")

    # M14 mcp.json 镜像校验（返回 servers 供 C11/C14 复用）
    mcp_servers = check_mcp_json(pkg, manifest)

    # M15 占位残留（全包文本文件；C16 语境豁免分流：教学/正则字面量不计 FAIL）
    real_hits, exempt_hits = scan_m15(pkg)
    if real_hits:
        record("M15", "占位残留", False,
               f"命中：{'、'.join(real_hits[:8])}{'…' if len(real_hits) > 8 else ''}"
               + (f"（另 {len(exempt_hits)} 处经 C16 语境豁免转人工复核）" if exempt_hits else ""))
    elif exempt_hits:
        record("M15", "占位残留", True,
               f"零真占位——{len(exempt_hits)} 处命中经 C16 语境豁免（教学/正则字面量），转人工复核提示")
    else:
        record("M15", "占位残留", True, "全包零残留")

    # ── C 组（批1 D8：C9-C15 全收＋C1-C6/C8/C16；C7 缓）──────────────
    check_c1(pkg)
    check_c2(pkg)
    check_c3(pkg, [(s, fp, t) for s, fp, t, _fm in skill_files])
    check_c4(pkg)
    check_c5(pkg, name)
    check_c6([(s, fp, fm) for s, fp, _t, fm in skill_files])
    check_c8(pkg, name)
    check_c9(pkg, name)
    check_c10(manifest)
    mcp_data = None
    mp = os.path.join(pkg, "mcp.json")
    if os.path.isfile(mp):
        try:
            with open(mp, encoding="utf-8") as f:
                mcp_data = json.load(f)
        except Exception:
            mcp_data = None
    check_c11(pkg, manifest, mcp_data if isinstance(mcp_data, dict) else None)
    check_c12(manifest)
    check_c13(pkg, manifest)
    check_c14(pkg, mcp_servers)
    check_c15(pkg)
    # C17/C18（双模式批 2026-10-28 增量软检查：description 长度软顶 + Plugin.md
    # 引用 SKILL.md 路径存在性——全部 [WARN]，永不阻断交付）
    check_c17(manifest)
    check_c18(pkg)
    # C16 记录（豁免分流结果已在 M15 行体现；命中清单转人工复核提示）
    if exempt_hits:
        for h in exempt_hits[:8]:
            warn_lines.append(f"C16 M15 语境豁免（教学/正则字面量，人工复核）: {h}")
        record("C16", "M15 语境豁免", True,
               f"{len(exempt_hits)} 处豁免（引号包裹教学提及／正则字面量）——已转人工复核提示，不计 FAIL")
    else:
        record("C16", "M15 语境豁免", True, "无豁免对象（M15 零命中，或命中均属真占位计入 FAIL）")

    # 附加产出：包 digest（可与 GET /api/plugins 返回的 digest 字段比对闭环）
    digest, file_count = compute_digest(pkg)
    out("")
    out(f"[INFO] digest   sha256:{digest}  files={file_count}")
    for w in warn_lines:
        out(f"[WARN] {w}")

    fails = [r for r in results if not r[2] and not r[3]]
    warns = len(warn_lines)
    total = len(results)
    out("")
    if mode == "hint":
        n_fail = len(fails)
        out(f"== RESULT(profile={_profile}): 提示级 — {total} checks（{n_fail} 项提示、{warns} 条人审提示；无 FAIL 语义），退出码恒 0 ==")
        sys.exit(0)
    out(f"== RESULT(profile={_profile}): {'PASS' if not fails else 'FAIL'} — {total} checks, {total - len(fails)} PASS, {len(fails)} FAIL, {warns} human-review hint(s) ==")
    sys.exit(0 if not fails else 1)


if __name__ == "__main__":
    main()
