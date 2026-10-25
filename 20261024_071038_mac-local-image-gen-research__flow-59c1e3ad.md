# Mac 本地生图推理方案对比调研（Draw Things / ComfyUI / MLX 生态 / Diffusers-MPS）

> 调研日期：2026-10-24。所有论断附来源 URL；「未查到」项如实标注，不含推测性数字。
> 速度量级均为社区/官方实测的**秒/张**口径，配置差异大，仅用于工程选型量级判断。

---

## 1. Draw Things（drawthings.ai）

| 项 | 结论 | 来源 |
|---|---|---|
| 项目 URL | https://github.com/drawthingsai/draw-things-community（社区仓库）；https://drawthings.ai/（官网）；App Store 免费+IAP | 见下 |
| 接口形态 | **三路自动化**：① App 内置 gRPC API Server（设置中可开）；② **A1111 兼容 HTTP API**（`/sdapi/v1/txt2img`、`/sdapi/v1/img2img`，源码 `Libraries/HTTPAPIServer/Sources/HTTPAPIServer.swift` 实现）；③ `draw-things-cli`（本地推理+LoRA 训练，`generate --model ... --prompt ...`）与 `gRPCServerCLI`（预编译二进制 + Docker，默认端口 7859）。另有 Swift SDK `media-generation-kit`（local/remote/cloud 三后端）和 App 内 JavaScript Scripts API | github.com/drawthingsai/draw-things-community；github.com/drawthingsai/media-generation-kit |
| 快捷指令 | **原生 Shortcuts action 未查到**；可经内置 HTTP API 用快捷指令的「Get Contents of URL」间接自动化 | 官方文档站未见（wiki.drawthings.ai 被反爬，未 100% 排除） |
| 开源/许可证 | **部分开源**：核心推理引擎（模型/sampler/trainer 重实现）在 community 仓库，**GPL-3.0**（可申请 LGPL-v3 替代授权）；App UI 层在私有 mono-repo。media-generation-kit 为 LGPL-3.0 | 同仓库 README |
| MLX 支持 | **未查到，可判定不支持**：官方文档仅有自研 Swift/Metal 引擎 + 可选 Core ML（限 SD 1.x/2.x 最优场景） | community-docs 7.CoreML.md |
| 模型支持 | SD 1.5/2.x、SDXL、SD3、FLUX.1/FLUX.2、Wan 2.2、Qwen Image、Z Image、LTX 等（community-models 目录持续收录） | apps.apple.com/us/app/draw-things/id6444050820；github.com/drawthingsai/community-models |
| 速度量级 | 官方基准为不可读截图；第三方实测（M1 Pro）：Z Image Turbo 768²/4步 ≈61s，Qwen Image 768²/2步 ≈31s，FLUX.2 klein 9B 768²/4步 ≈50s（newvfx.com/topic/131796）。早期 SD1.5 512² 在 M1 的具体秒数**未查到可靠数字** | newvfx.com/topic/131796 |

## 2. ComfyUI（comfyanonymous/ComfyUI）

| 项 | 结论 | 来源 |
|---|---|---|
| 项目 URL | https://github.com/comfyanonymous/ComfyUI；文档 https://docs.comfy.org | — |
| 本地服务器 | 启动即监听 **http://127.0.0.1:8188**（`python main.py`；或 `comfy-cli`：`comfy install` 后 `comfy launch`，Apple Silicon 自动装 PyTorch nightly+MPS；桌面 App 也行）。headless：`--disable-auto-launch`；对外：`--listen 0.0.0.0` | docs.comfy.org/development/comfyui-server/comms_overview.md；docs.comfy.org/comfy-cli/getting-started.md |
| API 形态 | **POST /prompt** 提交 workflow（**API format**：节点 ID 为 key 的字典 `{"class_type":..., "inputs":{...}}`，由 UI `Export Workflow (API)` 导出）+ `client_id`；WebSocket `/ws` 收进度（execution_start/progress/executed 等，二进制帧为预览图）；另有 `/history`、`/upload/image`、`/view`、`/queue`、`/interrupt`、`/object_info` 等。官方有三种调用范式示例代码 | docs.comfy.org/development/comfyui-server/comms_routes.md；…/workflow-api-format.md；…/api-examples.md |
| M 系芯片后端 | **PyTorch MPS**，官方 FAQ 明确「ComfyUI 不用 MLX」。调优：`--use-pytorch-cross-attention`（M3/M4 提速 30–50%）、`--force-fp16`（M1 无 bf16）、`PYTORCH_ENABLE_MPS_FALLBACK=1`。**Core ML 后端未查到** | docs.comfy.org/installation/system_requirements.md；workflowlab.dev |
| 内存需求 | SDXL 16GB 可跑（偏紧）；Flux schnell fp8 ≈9GB 权重 + T5 fp16 9.9GB，**16GB 是实用下限**，官方建议 t5xxl 用 fp8 版；Flux dev 1024² 建议 24GB+ | comfyanonymous.github.io/ComfyUI_examples/flux/；workflowlab.dev |
| 速度量级（社区实测） | Flux schnell 1024²/4步/fp16：M1 Pro 16GB ≈4.5min，M2 Pro ≈3.2min，M3 Pro ≈2.1min，M4 Pro 24GB ≈1.2min，**M4 Max 48GB ≈35s**；SDXL 1024²/20步 M4 Pro 48GB ≈18s；Flux-dev ≈45s（juejin 实测，与上表口径有出入，保守采信 30–80s/张）；M1 Ultra 128GB Flux Schnell ≈81.5s（官方论坛） | workflowlab.dev/deploy/comfyui-mac-apple-silicon-mps-speed；juejin.cn/post/7662691781213945892；forum.comfy.org/t/speed-benchmarks/1708 |
| 许可证 | **GPL-3.0**（仓库 LICENSE 原文核实） | raw.githubusercontent.com/comfyanonymous/ComfyUI/master/LICENSE |

## 3. MLX 生态

| 项目 | 结论 | 来源 |
|---|---|---|
| 组织勘误 | **`mlx-labs` 组织不存在（GitHub API 404）**；官方组织是 ml-explore 与 mlx-community | api.github.com/orgs/mlx-labs |
| mlx-examples/stable_diffusion | ml-explore 官方示例级实现：**支持 SDXL-turbo 与 SD-2.1，不支持 Flux**；Python 脚本 CLI（`txt2image.py "prompt" --n_images 4 -q`）+ `StableDiffusion` 类 API；支持 4/8bit 量化，8GB M1 Mac mini 可跑 SDXL-turbo | github.com/ml-explore/mlx-examples/tree/main/stable_diffusion（README 已亲自核验） |
| Apple ml-stable-diffusion | 已迁至 **apple-aiml-research/ml-stable-diffusion**；**Core ML** 方案（python 转换 + Swift Package），支持 SD 1.4–2.1 与 **SDXL base 1.0**，不支持 Flux/SD3；MIT License，仍在维护（17.9k stars，最近 push 2026-09，issues 已关）。官方基准（SDXL 1024²/20步/fp16，Core ML CPU+GPU）：**M1 Max 46s / M2 Max 37s / M1 Ultra 25s / M2 Ultra 20s** | github.com/apple-aiml-research/ml-stable-diffusion |
| Flux 经 MLX 的成熟度 | **BFL 官方无 MLX 移植**（官方 repo 仅 PyTorch+TensorRT；schnell 权重 Apache-2.0，dev 非商用）；社区事实标准 **mflux**（mflux-community/mflux，MIT，2.5k stars，2026-08 迁入社区组织）：FLUX.1 全线 + FLUX.2/Z-Image/Qwen Image 等，CLI + Python API，量化 `-q 4/8/16`；但 mflux 官方注明 **FLUX.1 已进入维护模式**。mlx-community 上原 FLUX.1 量化仓库现返回 401（删除/转私有，现状无法确证）。**Flux-MLX 的一手速度基准未查到** | github.com/mflux-community/mflux；github.com/black-forest-labs/flux；huggingface.co/api/models?author=mlx-community&search=flux |
| 其他 | **DiffusionKit（argmaxinc）已 2026-03 archived**，支持 SD3-medium 与 FLUX.1-schnell/dev；riccardomusmeci/mlx-image 是视觉分类库，**不是扩散模型**（命名易混淆） | github.com/argmaxinc/DiffusionKit；github.com/riccardomusmeci/mlx-image |

## 4. Diffusers (Python) + MPS

| 项 | 结论 | 来源 |
|---|---|---|
| 项目 URL | https://github.com/huggingface/diffusers；文档 huggingface.co/docs/diffusers/optimization/mps | — |
| 接口形态 | Python 库：`pipe.to("mps")`，推荐 `torch.float16`；<64GB 内存开 `enable_attention_slicing()`；稳定版 PyTorch 2.0+ 即可（nightly 要求已是历史）。许可证 **Apache-2.0**（LICENSE 原文核实；注意模型权重各自有许可） | huggingface.co/docs/diffusers/optimization/mps；github.com/huggingface/diffusers/blob/main/LICENSE |
| SDXL 可行性 | 完全可行。Apple 从未公布 MPS 基准（官方数字只有 Core ML，见 §3，可作性能上限参照）。16GB 机器跑 SDXL 会 swap（实际占用约 24GB），不实用 | reddit.com/r/StableDiffusion/comments/14z9lsj（仅摘要可得）；github.com/apple-aiml-research/ml-stable-diffusion |
| Flux 可行性与坑 | 可行但内存是硬门槛：全组件 fp32 约需 50GB（官方），M3/36GB fp32 直接 OOM（BFL issue #80）。**已知坑**：① Flux VAE fp16 出黑图（须 bf16/fp32，InvokeAI #7208）；② T5 fp16 激活截断（官方建议文本编码器保 fp32）；③ MPS 算子缺失/内存暴涨（HF×PyTorch 官方博客 mps-pytorch-2-15，建议 `PYTORCH_ENABLE_MPS_FALLBACK=1`）。实测：M4 Max 48GB，schnell bf16 1024²/4步 ≈**12–18s**（同机 ComfyUI fp8 8–14s、mflux 5–7s） | huggingface.co/docs/diffusers/api/pipelines/flux；github.com/black-forest-labs/flux/issues/80；github.com/invoke-ai/InvokeAI/issues/7208；huggingface.co/blog/mps-pytorch-2-15；xialei.com/blog/flux-ai-image-model-macbook-m4-max-local |
| 优化手段 | `enable_attention_slicing()`（常反而提速 ~20%）、VAE slicing/tiling、`enable_model_cpu_offload()`/`apply_group_offloading`、`PYTORCH_MPS_HIGH_WATERMARK_RATIO`（默认1.7）/LOW_WATERMARK、PyTorch ≥2.15（修复 MPS 上扩散模型内存/正确性 bug）、升级 nightly | 同上与 docs.pytorch.org/docs/2.14/mps_environment_variables.html |

---

## 5. 横向对比速查

| 方案 | 接口形态 | 自动化友好度 | Apple Silicon 后端 | Flux 支持 | 许可证 |
|---|---|---|---|---|---|
| Draw Things | HTTP(A1111 兼容) + gRPC + CLI + Swift SDK + JS Scripts | ★★★★★（无需自己起服务） | 自研 Metal / Core ML（无 MLX） | ✅（FLUX.1/2 全系） | 引擎 GPL-3.0，SDK LGPL-3.0，App 免费+IAP |
| ComfyUI | HTTP `POST /prompt`（workflow JSON）+ WebSocket | ★★★★（标准服务器模式，workflow 即 API） | PyTorch MPS | ✅（schnell/dev，16GB 下限） | GPL-3.0 |
| mflux (MLX) | Python CLI + API | ★★★★（脚本化最易） | **MLX**（M 系原生最快路径之一） | ✅（FLUX.1 维护模式；FLUX.2/Z-Image 活跃） | MIT |
| mlx-examples SD | Python 脚本/类 API | ★★★ | MLX | ❌（仅 SD/SDXL-turbo） | MIT |
| Apple ml-stable-diffusion | Swift Package + Python 转换 | ★★（偏 App 嵌入） | **Core ML**（ANE+GPU） | ❌（至 SDXL） | MIT |
| Diffusers+MPS | Python 库 | ★★★（需自己包服务） | PyTorch MPS | ✅（坑多，需调优） | Apache-2.0（库） |

量级结论（Flux schnell 1024²，M4 Max 48GB 档）：mflux ≈5–7s < ComfyUI fp8 8–14s ≈ diffusers bf16 12–18s；SDXL 20 步：ComfyUI ≈18s，Core ML 官方 M2 Ultra ≈20s（参照上限）。16GB 机器：仅 SD1.5/SDXL-turbo/量化 Flux schnell 勉强可用。

---

## 6. 托管 API ↔ 本地推理混合路由——工程要点

1. **路由分层**：按「隐私敏感 / 离线必须 / 模型能力」三维决策。隐私或离线场景落本地（Draw Things CLI / ComfyUI localhost / mflux），其余落托管 API；路由器做成可配置的 policy，而不是硬编码 if-else。
2. **隐私与离线回落**：本地链路保证 prompt 与产出图不出机（Draw Things、ComfyUI 127.0.0.1、mflux 均无外呼）；托管 API 侧要假设 prompt 会被留存/训练——敏感 prompt（人名、内部文档描述）默认本地。离线回落需预热模型常驻或接受首次加载冷启动（Flux 权重数 GB~数十 GB），移动端/笔记本注意休眠后掉服务。
3. **能力差异（路由必须感知的）**：
   - **NSFW/内容策略**：托管 API（OpenAI 等）有严格拒绝层，本地无任何过滤——产品层要自己决定是否在本地路径补安全过滤，否则等于绕过平台政策。
   - **写实质量**：本地 Flux/SDXL 与顶级托管模型（GPT-image、Seedream 等）仍有代差，高质量写实需求宜走托管；本地适合风格化、草稿、隐私件。
   - **中文 prompt**：托管中文模型对中文 prompt 理解原生；Flux/SDXL 以英文 CLIP/T5 为主，中文 prompt 需先经 LLM 翻译或走 Qwen-Image/Z-Image 等国产模型（Draw Things 与 mflux 均已支持），路由层可按 prompt 语言分流。
4. **接口归一**：各本地方案接口异构（A1111 REST / Comfy workflow JSON / mflux CLI）。建议抽象统一生图适配器（`generate(prompt, model, size) -> image`），内部按可用性选后端；ComfyUI 的 workflow JSON 可作为「复杂工作流」逃生通道保留。
5. **容量与排队**：本地单并发（统一内存是共享池，PyTorch MPS 还有 watermark/GC 毛刺），托管 API 可水平扩。混合架构里本地层要有队列+超时+降级到托管的熔断，避免一次 Flux 大请求堵死全部流量。
6. **成本**：本地边际成本近零但占用机器；托管按张计费。大批量、低敏感度任务可排队到本地夜间跑；交互式、高敏感度走本地常驻小模型（SDXL-turbo 级），低敏感高质量走托管。
7. **模型许可陷阱**：本地≠免授权——FLUX.1-dev 非商用许可、SD3 社区许可、各 LoRA 各自条款；路由层/metadata 要记录模型来源，商用场景逐模型核许可。

---

## 7. 来源 URL 清单（全量）

**Draw Things**
- https://drawthings.ai/ ；https://apps.apple.com/us/app/draw-things/id6444050820
- https://github.com/drawthingsai/draw-things-community （GPL-3.0、CLI/gRPCServerCLI）✅已亲自核验
- https://github.com/drawthingsai/draw-things-community/blob/main/Libraries/HTTPAPIServer/Sources/HTTPAPIServer.swift
- https://github.com/drawthingsai/media-generation-kit ；https://github.com/drawthingsai/draw-things-comfyui
- https://github.com/drawthingsai/community-docs ；…/Documentation.docc/7.CoreML.md ；…/8.Scripts.md
- https://github.com/drawthingsai/community-models ；https://wiki.drawthings.ai/wiki/How_Powerful_Is_My_Device（被反爬未读）
- https://www.newvfx.com/topic/131796 （M1 Pro 第三方实测）

**ComfyUI**
- https://docs.comfy.org/development/comfyui-server/comms_overview.md ✅已亲自核验（127.0.0.1:8188）
- https://docs.comfy.org/development/comfyui-server/comms_routes.md ；…/comms_messages.md ；…/api-examples.md ；…/startup-flags.md
- https://docs.comfy.org/development/api-development/workflow-api-format.md
- https://docs.comfy.org/installation/manual_install.md ；…/system_requirements.md ；…/desktop/macos.md
- https://docs.comfy.org/comfy-cli/getting-started.md
- https://comfyanonymous.github.io/ComfyUI_examples/flux/
- https://raw.githubusercontent.com/comfyanonymous/ComfyUI/master/README.md ；…/LICENSE
- https://www.workflowlab.dev/deploy/comfyui-mac-apple-silicon-mps-speed ；https://juejin.cn/post/7662691781213945892
- https://forum.comfy.org/t/speed-benchmarks/1708 ；https://www.reddit.com/r/StableDiffusion/comments/14z9lsj/（仅摘要）

**MLX 生态**
- https://github.com/ml-explore/mlx-examples/tree/main/stable_diffusion ✅已亲自核验（SDXL-turbo/SD-2.1）
- https://github.com/apple-aiml-research/ml-stable-diffusion （Core ML 官方基准表）
- https://github.com/mflux-community/mflux ；…/src/mflux/models/flux/README.md
- https://github.com/argmaxinc/DiffusionKit ；https://huggingface.co/argmaxinc/mlx-FLUX.1-schnell
- https://github.com/black-forest-labs/flux ；https://github.com/riccardomusmeci/mlx-image
- https://huggingface.co/api/models?author=mlx-community&search=flux ；https://api.github.com/orgs/mlx-labs（404 证据）

**Diffusers / MPS**
- https://huggingface.co/docs/diffusers/optimization/mps ；…/api/pipelines/flux ；…/v0.11.0/en/optimization/mps
- https://github.com/huggingface/diffusers/blob/main/LICENSE
- https://machinelearning.apple.com/research/stable-diffusion-coreml-apple-silicon
- https://github.com/black-forest-labs/flux/issues/80 ；https://github.com/invoke-ai/InvokeAI/issues/7208
- https://huggingface.co/blog/mps-pytorch-2-15
- https://docs.pytorch.org/docs/2.14/mps_environment_variables.html ；…/notes/mps.html
- https://xialei.com/blog/flux-ai-image-model-macbook-m4-max-local ；https://www.apatero.com/blog/flux-apple-silicon-m1-m2-m3-m4-complete-performance-guide-2025
- https://stackoverflow.com/questions/75987248/

## 8. 未查到 / 证据不足清单

1. Draw Things 原生 Shortcuts action（wiki 被反爬，未 100% 排除）；Draw Things 官方分型号秒/张基准（截图为 Discord 图，数值不可读）。
2. ComfyUI 官方分芯片速度矩阵（仅社区实测）；juejin「Flux-schnell 4步 ≈9s」与同档 1.2min 数据矛盾，未采信。
3. Flux 经 MLX（mflux）的一手速度基准表；mlx-community 原 FLUX.1 量化仓库现状（API 401）。
4. Apple 官方从未发布 MPS（vs Core ML）对照基准；diffusers+MPS 在 M1/M2 16GB 上的 Flux 可复现实测。
5. bitsandbytes/quanto 量化在 MPS 上的官方支持矩阵。
