---
name: example-skill
description: 一句话说清本 skill 做什么（what）与何时用（when）——这是唯一进 catalog 的文本，缺 name 或 description 任一键装载器会静默跳过本 skill。
---

# example-skill

（正文写作要点——生成时替换为本 skill 的真实内容，本模板即骨架：）

1. 节点在 spawn 时一次性收到本文全文注入（非按需读取）——体量红线 ≤300 行，
   深内容下沉 references/（节点按需读），可执行脚本放 scripts/，输出模板资源
   放 assets/。
2. 正文写步骤与判据，不写散文；每一步给出可判定的完成标准。
3. 需要引用本 skill 目录内资源时用 ${SKILL_DIR} 变量（装载时替换为目录绝对路径），
   例如：`python3 ${SKILL_DIR}/scripts/your_check.py`。

## Steps

1. 第一步——做什么 + 判据。
2. 第二步——做什么 + 判据。
3. 失败路径——异常时怎么办（上报/重试/降级纪律）。
