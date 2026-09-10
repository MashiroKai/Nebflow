#!/usr/bin/env python3
# validate_plugin.py — Nebflow 插件包机械校验（M1-M15 单层口径）
#
# 定位：PluginRegistry.scala 装载规则的「上游前置镜像」，不是平行第二权威。
# 任何 M 规则与官方装载器行为冲突时，以装载器为准并回改本脚本。
# 镜像锚点（Scala 相对 repo 根）：
#   - canonical schema        PluginRegistry.scala:66   (CanonicalSchema)
#   - canonical mcp schema    PluginRegistry.scala:70   (CanonicalMcpSchema)
#   - 保留 env 占位键          PluginRegistry.scala:79   (PluginPlaceholderEnvKeys)
#   - name 约束 §5.5          PluginRegistry.scala:287-292 (validPluginName)
#   - digest 算法             PluginRegistry.scala:237-248 (computeDigest)
#   - server entry 校验        PluginRegistry.scala:639-716 (validateServerEntry)
#   - shell-like / 凭据红标启发 PluginRegistry.scala:83-89
# 用法：python3 validate_plugin.py <插件包目录> [--allow <官方插件名>]...
# 退出码：全部 PASS → 0；任一 FAIL → 1。零第三方依赖（python3 标准库）。
#
# M8 口径注记：触发词「核心词全命中」是 description-quality skill / 人审判定项
# （词表按包能力域圈定），不进本脚本硬依赖——脚本只机械拦截「无触发场景句」。

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
# seed/manifest.json 动态并入 plugins: 声明，见 discover_official）。
OFFICIAL_BASELINE = {"nebflow-plugin-creator", "visual-report", "slideblocks"}

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
MAX_SKILL_LINES = 300
MAX_DESC_CHARS = 400

results = []          # (id, name, ok, warn, detail)
warn_lines = []       # 人审提示（红标启发式，不计 FAIL）


def record(cid, name, ok, detail, warn=False):
    results.append((cid, name, ok, warn, detail))
    tag = "PASS" if ok else "FAIL"
    if warn:
        tag = "WARN"
    print(f"[{tag}] {cid} {name} — {detail}")


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
    out = []
    if os.path.isdir(sd):
        for name in sorted(os.listdir(sd)):
            if os.path.isfile(os.path.join(sd, name, "SKILL.md")):
                out.append(name)
    return out


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


def check_mcp_json(pkg, manifest):
    path = os.path.join(pkg, "mcp.json")
    if not os.path.exists(path):
        record("M14", "mcp.json 镜像校验", True, "无 mcp.json——组件缺席，跳过")
        return
    if manifest and manifest.get("mcpServers"):
        pass  # manifest 顶层不该有 mcpServers（那是 mcp.json 的字段）；仅提示不计分
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        record("M14", "mcp.json 镜像校验", False, f"JSON 不可解析：{e}")
        return
    if not isinstance(data, dict):
        record("M14", "mcp.json 镜像校验", False, "顶层不是 JSON object")
        return
    if data.get("$schema") != CANONICAL_MCP_SCHEMA:
        record("M14", "mcp.json 镜像校验", False,
               f"$schema 非 canonical（期望 {CANONICAL_MCP_SCHEMA}）")
        return
    servers = data.get("mcpServers")
    if not isinstance(servers, dict):
        record("M14", "mcp.json 镜像校验", False, "mcpServers 缺失或非 object")
        return
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


def main():
    ap = argparse.ArgumentParser(description="Nebflow 插件包机械校验 M1-M15（单层口径）")
    ap.add_argument("pkg", help="插件包目录（含 plugin.json）")
    ap.add_argument("--allow", action="append", default=[], metavar="NAME",
                    help="追加官方白名单名（保留前缀豁免），可重复")
    args = ap.parse_args()
    pkg = os.path.abspath(args.pkg)

    if not os.path.isdir(pkg):
        print(f"[FAIL] 前置 包目录不存在：{pkg}")
        sys.exit(1)
    manifest_path = os.path.join(pkg, "plugin.json")
    if not os.path.isfile(manifest_path):
        print("[FAIL] 前置 plugin.json 缺失")
        sys.exit(1)

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

    # M9 边界/分流句
    m9m = BOUNDARY_RE.search(desc)
    record("M09", "边界/分流句", m9m is not None,
           f"命中：{m9m.group(0)!r}" if m9m else "无 不适用于/不属于/另配/请改用/勿用于 任一形态")

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

    # M12 + M13 逐 SKILL.md
    skill_files = []
    sd = os.path.join(pkg, "skills")
    if os.path.isdir(sd):
        for name_ in sorted(os.listdir(sd)):
            fp = os.path.join(sd, name_, "SKILL.md")
            if os.path.isfile(fp):
                skill_files.append((name_, fp))
    for sname, fp in skill_files:
        with open(fp, encoding="utf-8") as f:
            text = f.read()
        nlines = len(text.splitlines())
        record(f"M12", f"体量红线 {sname}/SKILL.md", nlines <= MAX_SKILL_LINES,
               f"{nlines}/{MAX_SKILL_LINES} 行")
        fm = parse_frontmatter(text)
        m13_ok = fm is not None and fm.get("name", "").strip() != "" and fm.get("description", "").strip() != ""
        record(f"M13", f"frontmatter 下限 {sname}/SKILL.md", m13_ok,
               "name+description 非空" if m13_ok else
               "frontmatter 缺失或缺非空 name/description（装载器 skip 规则上游拦截）")

    # M14 mcp.json 镜像校验
    check_mcp_json(pkg, manifest)

    # M15 占位残留（全包文本文件）
    hits = []
    for rel, fp in walk_files(pkg):
        try:
            with open(fp, "rb") as f:
                content = f.read().decode("utf-8", errors="ignore")
        except Exception:
            continue
        for i, line in enumerate(content.splitlines(), start=1):
            if M15_RE.search(line):
                hits.append(f"{rel}:{i}")
    record("M15", "占位残留", not hits,
           "全包零残留" if not hits else f"命中：{'、'.join(hits[:8])}{'…' if len(hits) > 8 else ''}")

    # 附加产出：包 digest（approve 后可与 GET /api/plugins 的 digest 人工比对闭环）
    digest, file_count = compute_digest(pkg)
    print("")
    print(f"[INFO] digest   sha256:{digest}  files={file_count}")
    for w in warn_lines:
        print(f"[WARN] {w}")

    fails = [r for r in results if not r[2] and not r[3]]
    warns = len(warn_lines)
    total = len(results)
    print("")
    print(f"== RESULT: {'PASS' if not fails else 'FAIL'} — {total} checks, {total - len(fails)} PASS, {len(fails)} FAIL, {warns} human-review hint(s) ==")
    sys.exit(0 if not fails else 1)


if __name__ == "__main__":
    main()
