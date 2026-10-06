# motion-toolkit 踩坑记录（实测 2026-10-04）

上游环境实测节点（chain-n-cdc177a5）探针工程：
- Remotion 探针：`C:\Users\Kai\.nebflow\tmp\remotion-probe\`（Composition "Probe"，60 帧 @30fps，1280×720，out/probe.mp4 渲染 16s exit 0）
- manim 探针：`C:\Users\Kai\.nebflow\tmp\manim-probe\`（-ql 渲染 exit 0，854×480 h264 15fps）
- 实测结论文本：`C:\Users\Kai\.nebflow\projects\general\env_test_results_20261004.md`

本模块搭建/冒烟过程实测踩坑（均为当次真实报错，非推测）：

## 网络
1. **registry.npmjs.org 直连 stall**：npm i 挂 3.5 分钟无进展；换 `--registry https://registry.npmmirror.com` 后 12 秒装完 252 包（Remotion 脚手架）/ 3 秒装完 three。**一律走 npmmirror**（setup.cmd 已固化）。
2. **chrome-headless-shell 下载不可用**：`remotion browser ensure` 走 storage.googleapis.com 仅 ~17KB/s（53MB 时放弃）。**渲染必须 `--browser-executable` 指本机 Chrome**（封装已固化）。半成品 zip 在 `node_modules\.remotion\chrome-headless-shell\`，网络好时可重跑 ensure。

## Remotion / node
3. **spawnSync shell:true 不加引号**：Windows 下 npx 必须 shell:true，但 shell 模式按空格裸拼参数，`C:/Program Files/...` 被拆成 `C:/Program` → 报「path doesn't exist」。本模块首跑即踩；修法=逐参数手动加双引号（render-remotion.mjs 内 `q()`）。
4. **复杂 props 别走命令行 JSON**：Windows cmd 引号转义深；`--props` 支持**文件路径**，复杂参数写 props.json 传入。

## three.js
5. **three ≥ r167 双文件构建**：`build/three.module.js`（648K）相对导入同目录伴生文件 three.core.js（1424K）；data: URL 内的相对导入无法解析，直接内联主文件会白屏。修法=递归把相对导入改写为指向内联源码的 data: URL（build_three_html.py `_inline()`）。自包含产物 ~5.9MB（three 0.186.1）。
6. **0.186.1 无 three.module.min.js**：build/ 下只有 three.module.js / three.core.js（min 版不在新版本里）；旧教程里的 min 路径已失效。
7. **headless 目检**：`"C:/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --screenshot=out.png --window-size=900,700 --virtual-time-budget=6000 "file:///....html"`，WebGL 走 SwiftShader 可出图（本模块冒烟即此法，155KB 非空图）。

## matplotlib 降级栈
8. **FFMpegWriter 默认找 PATH 的 `ffmpeg`** → 本机无 → `FileNotFoundError: [WinError 2]`。修法=先把 `imageio_ffmpeg.get_ffmpeg_exe()` 写进 `matplotlib.rcParams["animation.ffmpeg_path"]`（mpl_anim.py 已固化）。
9. **MPLCONFIGDIR 在节点 shell 里常为空**（实测本会话为空）→ 套件全局 YaHei 配置（`C:\Users\Kai\.nebflow\tools\mplconfig\matplotlibrc`）不接线，中文标签出豆腐块。修法=import matplotlib 前 `os.environ.setdefault("MPLCONFIGDIR", ...)`（mpl_anim.py 已固化）。
10. **yuv420p 要求偶数宽高**：8×4.5in @110dpi = 880×495，495 奇数会使 x264 报错；封装统一加 `-vf scale=trunc(iw/2)*2:trunc(ih/2)*2`（产物 880×494）。

## 通用
11. **MSYS 传 Windows 路径**：命令前加 `MSYS2_ARG_CONV_EXCL='*'`（core §7）；本模块命令行示例均用正斜杠路径，实测两 shell 皆可。
12. **套件非 git 仓库**：模块改动不做 commit；node_modules 直接留在模块内（Remotion 脚手架 252 包 + three 1 包），装好即渲染。
