自解释规则件批（作者令 2026-11-04 13:33「而且应该落实到我们的开发插件上」）· 机械自查原始证据
================================================================================

文件
----
red-stand-dev.txt      改规则文本前（M17 刚加入校验脚本、规则节尚未写）stand-dev 包原始输出 → FAIL
red-website.txt        同一时刻 website 包原始输出 → FAIL
green-stand-dev.txt    规则节 + M16 边界句改完后，运行时副本 ~/.stand/plugins/stand-dev 原始输出 → PASS（exit 0）
green-website.txt      同一时刻 website 运行时副本原始输出 → PASS（exit 0）
green-website-seed.txt 仓内 seed 副本 src/main/resources/seed/plugins/website 原始输出 → PASS（exit 0）
grep-proof.txt         「自解释」命中原样 + 四件锚点行号 + 版本号 + 规则自带 grep 词表在当日 main 的实跑读数

口径
----
- 判据脚本 = 本包自带 skills/stand-dev/scripts/validate_plugin.py（M1-M17 + C 组），
  website 包无自带脚本，用同一脚本校验（M17 的适用面按包名分派）。
- 红跑的红点 = 新加的 M17（规则节缺失，13/13 与 15/17 件缺）；绿跑 = M17 PASS
  且 M16 存量红清零。
- 三档 profile（creator / official / user）对本目录涉及的三个包副本均 exit 0。
