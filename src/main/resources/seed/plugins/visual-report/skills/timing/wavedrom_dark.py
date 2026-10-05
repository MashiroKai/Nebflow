#!/usr/bin/env python3
# wavedrom SVG 暗色适配注入（实现 core/SKILL.md §3「img 引用的 SVG 必须内嵌 dark media query」）。
# 用法：python wavedrom_dark.py <a.svg> [b.svg ...]
# 只在 </svg> 前追加一段 @media (prefers-color-scheme: dark) 的 CSS 覆盖，
# 不改任何几何 / 文本 / 数据内容；幂等（已含注入标记则跳过）。
import sys
import pathlib

MARK = "/* wavedrom-dark */"
CSS = (
    '<style type="text/css">' + MARK + "\n"
    "@media (prefers-color-scheme: dark) {\n"
    "  .s1,.s2,.s3,.s4{stroke:#b8bfd4}\n"          # 波形线 / 断轴斜线
    "  .s5{fill:#2a2e38}\n"                        # 间隙底
    "  .s6{fill:#e8eaed}\n"                        # 深色前景文本/标记
    "  .s7{fill:#1b1e26}\n"                        # 数据框底
    "  .s8{fill:#3a3a22}.s9{fill:#3a2e1e}.s10{fill:#1e2e3a}.s11{fill:#1e3638}\n"
    "  .s12{fill:#1e3622}.s13{fill:#32203a}.s14{fill:#3a2222}\n"  # 值色 → 暗色变体
    "  .s15,.info{fill:#a0a8e8}\n"                 # 数据文本
    "  .muted{fill:#8a8f98}.warning{fill:#f6b900}.error{fill:#ff6b6b}.success{fill:#4ec94e}\n"
    "  text{fill:#e8eaed}\n"                       # 兜底：head/foot/信号名
    "}\n"
    "</style>"
)


def main() -> None:
    for arg in sys.argv[1:]:
        f = pathlib.Path(arg)
        s = f.read_text(encoding="utf-8")
        if MARK in s:
            print(f"skip (already dark): {f}")
            continue
        if "</svg>" not in s:
            sys.exit(f"not an svg file: {f}")
        f.write_text(s.replace("</svg>", CSS + "</svg>", 1), encoding="utf-8")
        print(f"dark ok: {f}")


if __name__ == "__main__":
    main()
