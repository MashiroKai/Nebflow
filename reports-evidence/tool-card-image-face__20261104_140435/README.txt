tool-card-image-face 批 · 证据（作者令 2026-11-04 12:46「图片是还是会存在工具卡片泄露出来的问题」）

跑法
  npx playwright test tests/tool-card-image-face.spec.mjs                        # 绿腿（本树）
  NB_UX_ROOT=<基线树> NB_TOOLIMG_SHOT_DIR=…/before npx playwright test …        # 红腿（git archive 223bf8c63 src/main/resources/web；fixtures 同字节复制过去）

before/  = 基线树（未修）实拍 + 读数
  green-7-mail.png       Mail 工具卡正文里两张真 <img> 直接铺在卡内（refs=0 imgs=2）
  green-8-contained.png  .detail-cmd 无断点长串画出卡壳（scrollWidth 2649 / clientWidth 540 = +2109px）
  green-1-fold.png       折叠预览面（md 图片语法原样留在卡里，零引用件）
  green-5-noimage.png    守卫腿：非图片卡零新面（两棵树同形）

after/   = 本树（已修）实拍
  green-7-mail.png       「🖼 图片 ×2」引用件（相邻两图合成一件），卡内零 <img>
  green-8-contained.png  长串自断行，全部收进卡壳
  green-3-lightbox.png   引用件点击 → 灯箱（图片的唯一大图出口，零导航）
  green-1-fold.png / green-5-noimage.png 折叠面与守卫面

日志
  red-leg.log             改前：7 failed / 1 passed（基线树 223bf8c63）
  green-leg.log           改后：8 passed
  post-merge-regression.log  合并 main 后：本套 8 + toolline-detail-stream 14 + tool-card-leak-on-reload 6 = 22 passed
