# 修复后实测：merge seal 双形态探针（rig2 · ae2432039 构建 · port 27819）
- root 会话 43f67572-436d-4829-8054-36ca7b720e40；delegate-nebflow-4a1c2c22
- 探针 (a) merge=true（布尔）→ 拒绝：`NODE_MERGE_UNSUPPORTED`（…dispatch the work as Delegate tasks…）
- 探针 (b) merge="true"（字符串）→ 拒绝：`NODE_MERGE_UNSUPPORTED`（同上全文）
- 结论：字符串形态绕过已封死；两条腿同一可行动报错；既有链（merge=false/缺省）不受影响（NodeMergeSealSpec 对照腿 3/3 绿）。
- rig2 journal 锚：`Tool NodeEdit(seal-b) failed (11ms): … NODE_MERGE_UNSUPPORTED`（等价读数，rig2 日志）
- 对照：修复前 rig1（10a95018c）delegate#1 字符串 "true" 建位成功且 merge 意图静默丢（n-1108af95 落库 merge=false）。
