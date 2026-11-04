过程泄漏修复批（作者令 2026-11-04 14:14）——证据目录
=====================================================
工作树：.nebflow/worktrees/delegate-nebflow-1737f0ec（分支 delegate/delegate-nebflow-1737f0ec）
基线（before）树：git archive main src/main/resources/web → /tmp/nb-fold-base（main = 3047906d0）
判据面：默认态（未点击）渲染后 DOM 指纹 —— tests/tool-process-fold.spec.mjs

跑法
----
绿腿：node node_modules/@playwright/test/cli.js test tests/tool-process-fold.spec.mjs
       → 10 passed
红腿：NB_UX_ROOT=/tmp/nb-fold-base PIN_EXPECT=red \
        node node_modules/@playwright/test/cli.js test tests/tool-process-fold.spec.mjs
       → 10 passed（PIN_EXPECT=red 时新面钉方向反转＝要求缺陷在场；红腿绿 = 缺陷复现成功）

读数对照（同一 fixture：thinking + 2 次工具调用 + 24 行长输出 + terminal 行）
-------------------------------------------------------------------------
                          before（main）              after（本批）
A2 冷刷新（该轮未收尾）
  header 摘要行           无（0 枚）                  1 枚（中性行，data-nf-turn-unconfirmed=1）
  工具卡折叠              0/2                        2/2
  思考行折叠              0/1                        1/1
  长输出卡高度            297px                      12px
  默认面 preview 高度     264px                      17px（单行）
  默认面正文行（pre）     8 行                       0 行
  过程面可见文本          761 字                     0 字
B2 断网重连（断线时仍在飞）
  工具卡折叠              0/2                        2/2
  长输出卡高度            297px                      12px
  过程面可见文本          761 字                     0 字
C 折叠态高度闸（展开整轮后逐卡默认面）
  长输出卡高度            297px（> 47px 闸）         12–30px（≤ 47px）
  默认面正文行            8 行                       0 行
E 失败轮（无 terminal、有 durationMs 徽章）
  默认面正文行            8 行                       0 行
  摘要面失败可见          —                          红 ✕ + 「失败」状态词 + label 带命令
A 流式完成态 vs 回放态指纹逐项相等 — 两者均 present=true/rowsTucked=2/2/preLines=0/visibleText=0

截图（双端 1440×900 / 375×812，各 4 面）
--------------------------------------
before/process-leak-{desktop,mobile}-1-replay-inflight.png   刷新时该轮仍在飞 = 完整过程摊开（P1 现场）
before/process-leak-{desktop,mobile}-2-replay-completed.png  该轮已完成（折叠）
before/process-leak-{desktop,mobile}-3-expanded-turn.png     点摘要行展开整轮
before/process-leak-{desktop,mobile}-4-expanded-card.png     卡内展开全文（24 行）
after/ 同名 8 张 = 改后同四面的默认态/展开态。

既有套件（零回归，逐条 exit 0）
------------------------------
canvas-ask-dock 76 pass / 0 fail；canvas-ask-resize 2 passed；
console-ask-grow 14 PASS / 0 FAIL；tool-card-image-face 8 passed；
turn-collapse-keep-text 11 passed；turn-single-badge 5 passed；
turn-terminal-replay 14 passed；tool-card-leak-on-reload 6 passed。

三门禁：check-design-tokens PASS(0)；check-msgstyle-single-source PASS(0)；
check-process-artifacts PASS(0，合并带入的 main 侧 docs/ 过程产物已摘索引)。
check-js-types：31 错 与基线树同集（chat.js:3848 等 7 文件，均非本批改动面；
本批触碰的 utils.js/turnGroup.js/persistence.js 零错），闸在 main 上即为红。
