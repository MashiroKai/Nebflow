#!/usr/bin/env python3
"""
Issue 模板构建与校验脚本。

用法:
    cd docs/issues/templates && python3 _build.py
    python3 _build.py --check-issues  # 额外校验实际 issue 文件

功能:
    1. 从 _base.md 提取公共区块并注入各类型模板
    2. 校验模板质量（13 项检查）
    3. 校验实际 issue 文件无残留占位符（--check-issues）
"""

import argparse
import re
import sys
from datetime import date
from pathlib import Path
from typing import Dict, List, Set, Tuple

TEMPLATES_DIR = Path(__file__).parent
BASE_FILE = TEMPLATES_DIR / "_base.md"
GLOSSARY_FILE = TEMPLATES_DIR / "_glossary.md"
ISSUES_DIR = TEMPLATES_DIR.parent

TEMPLATE_FILES = [
    "bug.md",
    "feature.md",
    "refactor.md",
    "perf.md",
    "tech-debt.md",
    "quickfix.md",
    "test.md",
]

# 模板类型 → 状态机名称
TYPE_STATUS_MAP: Dict[str, str] = {}

# 模板类型 → 允许的关闭原因
TYPE_CLOSE_REASONS: Dict[str, Set[str]] = {}

# 所有模板必须包含的关键区块（标题关键词）
REQUIRED_SECTIONS = {
    "bug.md": ["元信息", "一句话描述", "范围", "风险与缓解", "关联", "变更文件", "关闭原因", "复盘"],
    "perf.md": ["元信息", "一句话描述", "范围", "风险与缓解", "关联", "变更文件", "关闭原因", "复盘"],
    "feature.md": ["元信息", "一句话描述", "范围", "风险与缓解", "关联", "变更文件", "关闭原因", "复盘"],
    "refactor.md": ["元信息", "一句话描述", "范围", "风险与缓解", "关联", "变更文件", "关闭原因", "复盘"],
    "tech-debt.md": ["元信息", "一句话描述", "范围", "风险与缓解", "关联", "变更文件", "关闭原因", "复盘"],
    "quickfix.md": ["元信息", "一句话描述", "风险与缓解", "关联", "变更文件", "关闭原因"],
    "test.md": ["元信息", "一句话描述", "范围", "风险与缓解", "关联", "变更文件", "关闭原因", "复盘"],
}

# 模板中允许保留的占位符（在 _base.md 的 BLOCK 中，会被注入到所有模板）
ALLOWED_TEMPLATE_PLACEHOLDERS = [
    re.compile(r"@username\b"),
    re.compile(r"\bFoo\.(scala|md|java|kt|ts|js|py)\b"),
    re.compile(r"\bBar\.(scala|md|java|kt|ts|js|py)\b"),
    re.compile(r"\bBaz\.(scala|md|java|kt|ts|js|py)\b"),
    re.compile(r"\bQux\.(scala|md|java|kt|ts|js|py)\b"),
    re.compile(r"`xxx\.md`"),
    re.compile(r"`yyy\.md`"),
    re.compile(r"`zzz\.md`"),
    re.compile(r"`www\.md`"),
]

# 实际 issue 文件中禁止残留的占位符
PLACEHOLDER_PATTERNS = ALLOWED_TEMPLATE_PLACEHOLDERS.copy()


def extract_blocks(base_content: str) -> Dict[str, str]:
    pattern = r"<!--\s*BLOCK:\s*(\w+)\s*-->(.*?)<!--\s*END_BLOCK\s*-->"
    blocks = {}
    for match in re.finditer(pattern, base_content, re.DOTALL):
        name = match.group(1)
        content = match.group(2).strip()
        blocks[name] = content
    return blocks


def extract_glossary_statuses(glossary_content: str) -> Dict[str, List[str]]:
    """从 glossary 提取各类型状态列表（仅提取状态流转章节）。"""
    statuses = {}
    in_status_section = False
    current_title = None
    current_states = []

    for line in glossary_content.split("\n"):
        if line.strip() == "## 状态流转":
            in_status_section = True
            continue
        if in_status_section and line.startswith("## "):
            break
        if not in_status_section:
            continue

        if line.startswith("### "):
            if current_title and current_states:
                statuses[current_title] = current_states
            current_title = line.replace("### ", "").strip()
            current_states = []
            continue

        m = re.search(r"\|\s*\*\*(\S+)\*\*\s*\|", line)
        if m and current_title:
            current_states.append(m.group(1))

    if current_title and current_states:
        statuses[current_title] = current_states

    return statuses


def extract_glossary_close_reasons(glossary_content: str) -> Set[str]:
    reasons = set()
    in_section = False
    for line in glossary_content.split("\n"):
        if line.strip() == "## 关闭原因":
            in_section = True
            continue
        if in_section and line.startswith("## "):
            break
        if in_section:
            m = re.search(r"\|\s*\*\*(\S+)\*\*\s*\|", line)
            if m:
                reasons.add(m.group(1))
    return reasons


def extract_glossary_type_mappings(glossary_content: str) -> Tuple[Dict[str, str], Dict[str, Set[str]]]:
    """从 glossary 提取模板类型映射：模板文件 -> 状态机名称，模板文件 -> 关闭原因集合。"""
    type_status_map: Dict[str, str] = {}
    type_close_reasons: Dict[str, Set[str]] = {}

    in_section = False
    for line in glossary_content.split("\n"):
        if line.strip() == "## 模板类型映射":
            in_section = True
            continue
        if in_section and line.startswith("## "):
            break
        if not in_section:
            continue

        # 匹配表格行: | `bug.md` | Bug / Performance Regression | 已修复, 重复, ... |
        m = re.match(
            r"\|\s*`([^`]+)`\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*$",
            line
        )
        if m:
            template_file = m.group(1).strip()
            status_machine = m.group(2).strip()
            reasons_str = m.group(3).strip()
            reasons = {r.strip() for r in reasons_str.split(",") if r.strip()}
            type_status_map[template_file] = status_machine
            type_close_reasons[template_file] = reasons

    return type_status_map, type_close_reasons


def extract_glossary_terms(glossary_content: str) -> Dict[str, Set[str]]:
    """从 glossary 提取所有术语枚举值，按类别返回。"""
    terms: Dict[str, Set[str]] = {
        "priority": set(),
        "severity": set(),
        "impact": set(),
        "frequency": set(),
        "risk": set(),
        "action": set(),
        "label": set(),
    }

    section_map = {
        "优先级（Priority）": "priority",
        "严重程度（Severity）": "severity",
        "影响范围（Impact）": "impact",
        "复现频率": "frequency",
        "风险等级（Risk Level）": "risk",
        "文件操作（Action）": "action",
        "标签（Label）": "label",
    }

    current_section = None
    for line in glossary_content.split("\n"):
        stripped = line.strip()
        # 检测章节标题
        for section_title, key in section_map.items():
            if stripped == f"## {section_title}":
                current_section = key
                break
        else:
            if stripped.startswith("## "):
                current_section = None

        if current_section:
            # 提取加粗的术语值（支持多字术语如"仅报告一次"）
            m = re.search(r"\|\s*\*\*([^*]+)\*\*\s*\|", line)
            if m:
                terms[current_section].add(m.group(1).strip())

    return terms


def check_glossary_terms(content: str, glossary_terms: Dict[str, Set[str]]) -> List[str]:
    """校验模板中使用的术语值与 glossary 一致。"""
    errors = []

    # 校验优先级
    priority_pattern = re.compile(r"P[0-3]-[^/\s]+")
    found_priorities = set(priority_pattern.findall(content))
    expected_priorities = glossary_terms.get("priority", set())
    for p in found_priorities - expected_priorities:
        errors.append(f"  未知优先级 '{p}'")

    # 校验严重程度
    severity_words = glossary_terms.get("severity", set())
    if severity_words:
        severity_pattern = re.compile(r'\b(' + '|'.join(re.escape(w) for w in severity_words) + r')\b')
        for match in severity_pattern.finditer(content):
            word = match.group(1)
            if word not in severity_words:
                errors.append(f"  严重程度术语 '{word}' 不在 glossary 中")

    # 校验影响范围
    impact_words = glossary_terms.get("impact", set())
    if impact_words:
        impact_pattern = re.compile(r'(' + '|'.join(re.escape(w) for w in impact_words) + r')')
        for match in impact_pattern.finditer(content):
            phrase = match.group(1)
            if phrase not in impact_words:
                errors.append(f"  影响范围术语 '{phrase}' 不在 glossary 中")

    # 校验风险等级：在"风险与缓解"表格的上下文中，检查表格行中的风险等级词
    risk_words = glossary_terms.get("risk", set())
    if risk_words:
        in_risk_table = False
        for line in content.split("\n"):
            if line.strip().startswith("## ") and "风险与缓解" in line:
                in_risk_table = True
                continue
            if in_risk_table and line.strip().startswith("## "):
                in_risk_table = False
            if in_risk_table and "|" in line:
                # 跳过表头分隔行（如 |------|----------|----------|）
                if re.match(r"^\s*\|[\s\-:|]+\|\s*$", line):
                    continue
                cells = [c.strip() for c in line.split("|") if c.strip()]
                if not cells:
                    continue
                # 跳过表头行（包含中文标题词）
                header_words = {"风险", "严重程度", "缓解措施"}
                if any(word in cells for word in header_words):
                    continue
                # 检查是否至少有一个 cell 包含有效的风险等级词
                has_risk_word = False
                for cell in cells:
                    for word in risk_words:
                        if word in cell:
                            has_risk_word = True
                            break
                    if has_risk_word:
                        break
                if not has_risk_word:
                    errors.append(f"  风险与缓解表格行缺少有效的风险等级词: {line.strip()}")

    return errors


def find_duplicate_sections(content: str) -> Tuple[List[str], List[str]]:
    """检测重复的二级和三级标题。"""
    lines = content.split("\n")
    seen_h2: Dict[str, bool] = {}
    seen_h3: Dict[str, bool] = {}
    dup_h2: List[str] = []
    dup_h3: List[str] = []

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("## ") and not stripped.startswith("### "):
            if stripped in seen_h2:
                dup_h2.append(stripped)
            else:
                seen_h2[stripped] = True
        elif stripped.startswith("### "):
            if stripped in seen_h3:
                dup_h3.append(stripped)
            else:
                seen_h3[stripped] = True

    return dup_h2, dup_h3


def extract_template_states(content: str) -> List[str]:
    """
    从元信息表格中提取状态值列表。
    返回空列表表示引用了 glossary（由调用方校验）。
    """
    lines = content.split("\n")
    in_meta = False

    for line in lines:
        if "## [创建] 元信息" in line or "## 元信息" in line:
            in_meta = True
            continue
        if in_meta and line.startswith("## ") and "元信息" not in line:
            break
        if not in_meta:
            continue

        if "状态" in line and "|" in line:
            parts = line.split("|")
            for part in parts:
                stripped = part.strip()
                if "/" in stripped and not stripped.startswith("见") and not stripped.startswith("状态"):
                    return [s.strip() for s in stripped.split("/") if s.strip()]
            break

    return []


def extract_template_close_reasons(content: str) -> Set[str]:
    """从关闭原因区块提取所有可选原因。"""
    reasons: Set[str] = set()
    in_close_section = False
    for line in content.split("\n"):
        if "## [结束] 关闭原因" in line or "## 关闭原因" in line:
            in_close_section = True
            continue
        if in_close_section and line.startswith("## "):
            break
        if in_close_section:
            m = re.search(r"- \[ \]\s*(\S+)", line)
            if m:
                reasons.add(m.group(1))
    return reasons


def check_required_sections(content: str, template_name: str) -> List[str]:
    """校验模板是否包含所有关键区块。"""
    errors = []
    required = REQUIRED_SECTIONS.get(template_name, [])

    for section in required:
        pattern = rf"^##\s*(\[[^\]]+\]\s+)?{re.escape(section)}"
        if not re.search(pattern, content, re.MULTILINE | re.IGNORECASE):
            errors.append(f"  缺失关键区块: {section}")

    return errors


def check_optional_markers(content: str) -> List[str]:
    """校验可选区块标注的一致性。"""
    errors = []
    lines = content.split("\n")

    sections = []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("## ") and not stripped.startswith("### "):
            hint = ""
            for j in range(i + 1, len(lines)):
                next_line = lines[j].strip()
                if next_line == "":
                    continue
                if next_line.startswith("> "):
                    hint = next_line
                break
            sections.append((stripped, hint))

    for title, hint in sections:
        has_optional_marker = "（可选）" in title
        has_optional_hint = "可跳过" in hint or "按需填写" in hint or "可选" in hint

        if has_optional_marker and not has_optional_hint:
            errors.append(f"  区块标题标记为（可选），但正文中无提示: {title}")
        if has_optional_hint and not has_optional_marker:
            errors.append(f"  区块正文提示可跳过，但标题未标记（可选）: {title}")

    return errors


def check_section_order(content: str, template_name: str) -> List[str]:
    """校验关键区块的出现顺序是否一致。

    只检查核心创建时区块的顺序，结束时的区块位置相对灵活。
    """
    errors = []
    # 定义必须按此顺序出现的核心区块
    core_order = [
        "## [创建] 元信息",
        "## [创建] 一句话描述",
        "## [创建] 范围",
    ]

    lines = content.split("\n")
    found_positions: Dict[str, int] = {}

    for i, line in enumerate(lines):
        stripped = line.strip()
        for section in core_order:
            base_section = section.replace("## [创建] ", "")
            if stripped.startswith("## [创建] ") and base_section in stripped:
                if section not in found_positions:
                    found_positions[section] = i

    # 检查核心区块顺序
    prev_pos = -1
    for section in core_order:
        if section in found_positions:
            pos = found_positions[section]
            if pos < prev_pos:
                errors.append(f"  核心区块顺序异常: '{section}' 位置不对")
            prev_pos = pos

    # 检查风险与缓解必须在关联之前
    risk_pos = None
    related_pos = None
    changes_pos = None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("## ") and "风险与缓解" in stripped:
            risk_pos = i
        if stripped.startswith("## ") and "关联" in stripped:
            related_pos = i
        if stripped.startswith("## ") and stripped.replace("## ", "") == "变更文件":
            changes_pos = i

    if risk_pos is not None and related_pos is not None and risk_pos > related_pos:
        errors.append("  区块顺序异常: '风险与缓解' 应在 '关联' 之前")

    if changes_pos is not None and related_pos is not None and changes_pos < related_pos:
        errors.append("  区块顺序异常: '变更文件' 应在 '关联' 之后")

    return errors


def check_template_placeholders(content: str, template_name: str) -> List[str]:
    """校验模板文件中是否有残留的未注入占位符（排除 _base.md 注入的）。"""
    errors = []

    # 先注入 _base.md 的区块，然后检查是否还有占位符残留
    # 但这里我们直接检查：如果占位符出现在非 INJECT 区域，则报错
    lines = content.split("\n")
    in_inject_block = False

    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("<!-- INJECT:"):
            in_inject_block = True
            continue
        if in_inject_block:
            in_inject_block = False
            continue

        for pattern in ALLOWED_TEMPLATE_PLACEHOLDERS:
            for match in pattern.finditer(line):
                errors.append(
                    f"  模板残留占位符 '{match.group(0)}' 于 {template_name}:{i + 1}"
                )

    return errors


def validate_template(
    template_path: Path,
    glossary_statuses: Dict[str, List[str]],
    glossary_reasons: Set[str],
    glossary_terms: Dict[str, Set[str]],
) -> List[str]:
    errors = []
    content = template_path.read_text(encoding="utf-8")
    template_name = template_path.name

    # 1. 检测重复标题
    dup_h2, dup_h3 = find_duplicate_sections(content)
    for dup in dup_h2:
        errors.append(f"  重复二级标题: {dup}")
    for dup in dup_h3:
        errors.append(f"  重复三级标题: {dup}")

    # 2. 校验生命周期标记
    expected_markers = {"[创建]", "[结束]"}
    found_markers = set()
    for line in content.split("\n"):
        if line.startswith("## "):
            for marker in {"[创建]", "[结束]"}:
                if marker in line:
                    found_markers.add(marker)
    missing = expected_markers - found_markers
    if missing:
        errors.append(f"  缺失生命周期标记: {sorted(missing)}")

    # 3. 校验状态值与 glossary 一致
    status_section = TYPE_STATUS_MAP.get(template_name)
    if status_section:
        if status_section not in glossary_statuses:
            errors.append(f"  状态机映射错误: '{status_section}' 在 glossary 中无定义")
        else:
            expected_states = set(glossary_statuses[status_section])
            template_states = extract_template_states(content)
            if template_states:
                template_states_set = set(template_states)
                for s in template_states_set - expected_states:
                    errors.append(f"  未知状态 '{s}'")
                for s in expected_states - template_states_set:
                    errors.append(f"  缺失状态 '{s}'")
            else:
                if "见 `_glossary.md` → 状态流转" not in content and "见 _glossary.md → 状态流转" not in content:
                    errors.append("  状态字段未引用 glossary 状态流转")

    # 4. 校验关闭原因
    expected_reasons = TYPE_CLOSE_REASONS.get(template_name, set())
    template_reasons = extract_template_close_reasons(content)
    for r in template_reasons - glossary_reasons:
        errors.append(f"  关闭原因 '{r}' 不在 glossary 中")
    for r in template_reasons & (glossary_reasons - expected_reasons):
        errors.append(f"  关闭原因 '{r}' 不适用于 {template_name}")
    for r in expected_reasons - template_reasons:
        errors.append(f"  缺失关闭原因 '{r}'")

    # 5. 校验必填字段
    if "优先级" not in content:
        errors.append("  缺失优先级字段")
    if "`_glossary.md`" not in content:
        errors.append("  未引用 _glossary.md")

    # 6. 校验关键区块
    errors.extend(check_required_sections(content, template_name))

    # 7. 校验可选区块标注
    errors.extend(check_optional_markers(content))

    # 8. 校验 INJECT 标记
    inject_matches = re.findall(r"<!--\s*INJECT:\s*\w+\s*-->", content)
    for m in inject_matches:
        errors.append(f"  未注入的区块: {m}")

    # 9. 校验 glossary 术语
    errors.extend(check_glossary_terms(content, glossary_terms))

    # 10. 校验区块顺序
    errors.extend(check_section_order(content, template_name))

    return errors


def inject_blocks(template_path: Path, blocks: Dict[str, str]) -> str:
    content = template_path.read_text(encoding="utf-8")

    def replacer(match: re.Match) -> str:
        block_name = match.group(1)
        return blocks.get(block_name, match.group(0))

    result = re.sub(r"<!--\s*INJECT:\s*(\w+)\s*-->", replacer, content)

    # 将创建日期的 YYYY-MM-DD 占位符替换为当天日期
    today = date.today().isoformat()
    result = re.sub(
        r"(\|\s*创建日期\s*\|\s*)YYYY-MM-DD(\s*\|)",
        rf"\g<1>{today}\2",
        result,
    )

    return result


def check_issue_placeholders(issues_dir: Path) -> List[str]:
    """校验实际 issue 文件中无残留占位符。"""
    errors = []
    issue_files = sorted(issues_dir.glob("*.md"))

    for issue_path in issue_files:
        # 跳过模板目录和指南文件
        if issue_path.name in ("000-issue-template.md",):
            continue

        content = issue_path.read_text(encoding="utf-8")
        for pattern in PLACEHOLDER_PATTERNS:
            for match in pattern.finditer(content):
                errors.append(f"  {issue_path.name}: 残留占位符 '{match.group(0)}' 于位置 {match.start()}")

    return errors


# issue 关联关系正则：匹配 `xxx.md` 或 `commit/PR` 引用
ISSUE_REF_PATTERN = re.compile(
    r"(?:Depends on|Blocks|Related to|Supersedes)\s+`([^`]+\.md)`"
)


def check_cross_references(issues_dir: Path) -> List[str]:
    """校验 issue 文件中的交叉引用是否指向真实存在的文件。"""
    errors = []
    issue_files = sorted(issues_dir.glob("*.md"))
    valid_names = {p.name for p in issue_files}

    for issue_path in issue_files:
        if issue_path.name in ("000-issue-template.md",):
            continue

        content = issue_path.read_text(encoding="utf-8")
        for match in ISSUE_REF_PATTERN.finditer(content):
            ref_name = match.group(1)
            if ref_name not in valid_names:
                errors.append(
                    f"  {issue_path.name}: 引用不存在的 issue '{ref_name}' 于位置 {match.start()}"
                )

    return errors


def main():
    parser = argparse.ArgumentParser(description="Issue 模板构建与校验")
    parser.add_argument("--check-issues", action="store_true", help="额外校验实际 issue 文件的占位符")
    args = parser.parse_args()

    if not BASE_FILE.exists():
        print(f"错误: 基础模板不存在: {BASE_FILE}", file=sys.stderr)
        sys.exit(1)

    if not GLOSSARY_FILE.exists():
        print(f"错误: 术语表不存在: {GLOSSARY_FILE}", file=sys.stderr)
        sys.exit(1)

    base_content = BASE_FILE.read_text(encoding="utf-8")
    glossary_content = GLOSSARY_FILE.read_text(encoding="utf-8")

    blocks = extract_blocks(base_content)
    if not blocks:
        print("警告: _base.md 中未找到任何 BLOCK 标记", file=sys.stderr)

    glossary_statuses = extract_glossary_statuses(glossary_content)
    glossary_reasons = extract_glossary_close_reasons(glossary_content)
    glossary_terms = extract_glossary_terms(glossary_content)

    # 从 glossary 加载模板类型映射，覆盖空的全局变量
    global TYPE_STATUS_MAP, TYPE_CLOSE_REASONS
    TYPE_STATUS_MAP, TYPE_CLOSE_REASONS = extract_glossary_type_mappings(glossary_content)

    print(f"发现 {len(blocks)} 个公共区块")
    print(f"发现 {len(glossary_statuses)} 个状态机定义")
    print(f"发现 {len(glossary_reasons)} 个关闭原因")
    print(f"发现 {sum(len(v) for v in glossary_terms.values())} 个术语枚举值")
    print()

    all_errors = []
    for tmpl_name in TEMPLATE_FILES:
        tmpl_path = TEMPLATES_DIR / tmpl_name
        if not tmpl_path.exists():
            print(f"跳过: {tmpl_name} 不存在")
            continue

        print(f"处理: {tmpl_name}")

        new_content = inject_blocks(tmpl_path, blocks)
        if new_content != tmpl_path.read_text(encoding="utf-8"):
            tmpl_path.write_text(new_content, encoding="utf-8")
            print("  已更新公共区块")

        errors = validate_template(tmpl_path, glossary_statuses, glossary_reasons, glossary_terms)
        if errors:
            all_errors.extend([f"{tmpl_name}: {e}" for e in errors])

    # 校验实际 issue 文件
    if args.check_issues:
        print()
        print("校验实际 issue 文件...")
        issue_errors = check_issue_placeholders(ISSUES_DIR)
        if issue_errors:
            all_errors.extend(issue_errors)
        else:
            print("  所有 issue 文件无残留占位符")

        print()
        print("校验 issue 交叉引用...")
        xref_errors = check_cross_references(ISSUES_DIR)
        if xref_errors:
            all_errors.extend(xref_errors)
        else:
            print("  所有交叉引用有效")

    print()
    if all_errors:
        print("=" * 50)
        print("校验失败:")
        for e in all_errors:
            print(f"  {e}")
        sys.exit(1)
    else:
        print("所有校验通过")


if __name__ == "__main__":
    main()
