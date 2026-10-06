# demo · timing — ADC 采样与 SPI 读回握手（WaveDrom 实渲染）

单文档 2 图 ≤ 3，图预算合规。本 demo 是 `timing/SKILL.md` 的验收样例，固定三段式：信号表 → 时序图 → 逐段解读。两图均由 wavedrom-cli 3.2.0 真实渲染落盘（命令见 §6），SVG 已注入暗色适配，wavejson 源码完整附于各图之后。

- [1 信号表](#1-信号表)
- [2 演示参数（唯一数据源）](#2-演示参数唯一数据源)
- [3 图 1 · 转换触发与采样窗](#3-图-1-转换触发与采样窗)
- [4 图 2 · SPI 读回握手](#4-图-2-spi-读回握手)
- [5 建立 / 保持与因果链核对](#5-建立--保持与因果链核对)
- [6 复现命令](#6-复现命令)

## 1 信号表

| 信号 | 驱动方 | 有效电平/边沿 | 功能 |
|---|---|---|---|
| CONVST | 主机 | 下降沿触发 | 下降沿启动采样；上升沿采样保持并启动转换 |
| BUSY | ADC | 高有效 | 转换进行中；下降沿 = 数据就绪 |
| DRDY | ADC | 低有效 | 数据待取指示；本模型在读回完成后置高 |
| CS | 主机 | 低有效 | 片选，界定 SPI 读回窗口 |
| SCLK | 主机 | 上升沿锁存 | 20 MHz 移位时钟，16 周期移出一字 |
| DOUT | ADC | — | 串行数据输出，MSB 在前，CS 低期间有效 |

## 2 演示参数（唯一数据源）

| 参数 | 值 | 说明 |
|---|---|---|
| SCLK 频率 | 20 MHz | 演示设定 → 周期 = 50 ns |
| t_ACQ 采样窗（CONVST 低电平宽度） | 300 ns = 6 slot | 演示设定 |
| t_CONV 转换时间（BUSY 高电平） | 4.00 µs | 演示设定（图 1 断轴压缩，非等比例） |
| t_d(BUSY)：CONVST ↑ → BUSY ↑ | 50 ns = 1 slot | 演示设定 |
| t_D：DRDY ↓ → CS ↓ | 50 ns = 1 slot | 演示设定 |
| t_CSS：CS ↓ → 首个 SCLK ↑ | 50 ns = 1 slot | 演示设定 |
| 末下降沿 → CS ↑ 读毕保持 | 50 ns = 1 slot | 演示设定 |
| CS ↑ → DRDY ↑ | 50 ns = 1 slot | 演示设定 |
| 读回位宽 | 16 bit，MSB 在前 | 演示设定 |
| SPI 模式 | CPOL=0，CPHA=0（上升沿锁存） | 演示设定 |
| 转换结果码 | 0x0A7C = 2684 | 演示设定 |
| VREF | 4.096 V | 演示设定 |

派生量（由上表精确推导，无舍入）：

- LSB = $4.096\ \mathrm{V} \div 65536 = 62.5\ \mu\mathrm{V}$
- $V_{IN} = 2684 \times 62.5\ \mu\mathrm{V} = 167.75\ \mathrm{mV}$
- 读回耗时 = 16 slot × 50 ns = 800 ns；半周期 = 50 ns ÷ 2 = 25 ns

声明：本 demo 全部参数为教学演示自设定值，数据源即上表，不引用任何具体器件 datasheet；写真实器件时按其手册时序参数替换参数表后再画图。

## 3 图 1 · 转换触发与采样窗

![图 1 CONVST 触发采样与 BUSY 转换握手](~/.nebflow/plots/20261004_timing_adc_conv.svg)

逐段解读（时间分辨率：1 slot = 50 ns）：

- **采样窗段（A→B）**：CONVST 下降沿（A）启动采样，SHA 开始采集输入；窗口 = CONVST 低电平 6 slot，t_ACQ = 300 ns。此段 BUSY 保持低——转换尚未开始，采样与转换在时间上不重叠。
- **转换启动沿（B）**：CONVST 上升沿是分界点：采样保持 + 转换启动。BUSY 滞后 1 slot（t_d(BUSY) = 50 ns）跟随拉高，因果方向为 CONVST ↑ → BUSY ↑，与文字一致。
- **转换段（B→C，断轴）**：BUSY 高 = 逐次逼近进行中，真实时长 t_CONV = 4.00 µs；图上以 `|` 断轴压缩、非等比例，真实时长以本标注与 §2 参数表为准。BUSY 下降沿（C）= 数据就绪，事件在此交棒给图 2 的 DRDY。

### 图 1 WaveJSON 源码

```json
{
  "head": { "text": "图 1  CONVST 触发采样与 BUSY 转换握手（slot = 50 ns；转换段断轴压缩）" },
  "signal": [
    { "name": "CONVST", "wave": "10.....1..........", "node": ".A.....B.........." },
    { "name": "BUSY",   "wave": "0.......1.|1.0....", "node": ".............C...." }
  ],
  "edge": [
    "A<->B 采样窗 t_ACQ = 300 ns",
    "B<->C t_CONV = 4.00 µs"
  ],
  "foot": { "text": "CONVST ↓ 开始采样；CONVST ↑ 采样保持并启动转换（BUSY 滞后 1 slot = 50 ns）；BUSY ↓ 数据就绪。BUSY 段断轴压缩，非等比例。" }
}
```

## 4 图 2 · SPI 读回握手

![图 2 SPI 读回握手](~/.nebflow/plots/20261004_timing_spi_read.svg)

逐段解读（时间分辨率：1 slot = 1 SCLK 周期 = 50 ns；因果链 d→c→s→f→e→r）：

- **就绪（d）**：DRDY ↓ 向主机声明数据就绪（承接图 1 的 BUSY ↓）。
- **响应（d→c）**：主机经 t_D = 50 ns 拉低 CS，打开读回窗口——主机是这里的主动方，DRDY 只是请求。
- **建立（c→s）**：CS ↓ 后空 1 slot（t_CSS = 50 ns）才出现首个 SCLK ↑，保证片选建立时间；DOUT 已在 CS ↓ 后驱动 MSB。
- **移位（s→f）**：16 个 SCLK 周期 = 800 ns；主机在每个上升沿锁存（CPHA=0），DOUT 每经一个下降沿移出下一位，MSB 在前；本例移出结果码 0x0A7C（= 2684，对应 $V_{IN} = 167.75\ \mathrm{mV}$，见 §2）。
- **收尾（f→e→r）**：末下降沿后再保持 1 slot（50 ns）主机才拉高 CS（读毕保持）；CS ↑ 后 1 slot DRDY ↑ = 数据已取走（演示模型），握手闭合。

### 图 2 WaveJSON 源码

```json
{
  "head": { "text": "图 2  SPI 读回握手（CPOL=0 / CPHA=0，SCLK = 20 MHz，slot = 1 SCLK 周期 = 50 ns）" },
  "signal": [
    { "name": "DRDY", "wave": "10...................1", "node": ".d...................r" },
    { "name": "CS",   "wave": "1.0.................1.", "node": "..c.................e." },
    { "name": "SCLK", "wave": "0..pppppppppppppppp0..", "node": "...s...............f.." },
    { "name": "DOUT", "wave": "xx=...............x...", "data": ["0x0A7C (16 bit, MSB→LSB)"] }
  ],
  "edge": [
    "d~>c t_D = 50 ns",
    "c~>s t_CSS = 50 ns",
    "s<->f 16 bit = 800 ns",
    "f~>e 读毕保持 50 ns"
  ],
  "foot": { "text": "主机在 SCLK 上升沿锁存（CPHA=0）；DOUT 在 CS ↓ 后驱动 MSB，每经下降沿移出下一位，末位锁存后释放。DRDY ↑ = 主机已取走数据（演示模型）。" }
}
```

## 5 建立 / 保持与因果链核对

| 检查项 | 图上值 | 判定 |
|---|---|---|
| 片选建立：CS ↓ → 首个 SCLK ↑ | 50 ns | = t_CSS（§2）✓ |
| DOUT 位建立（下降沿切换 → 下个上升沿锁存） | 半周期 25 ns | 由 20 MHz 精确导出 ✓ |
| DOUT 位保持 | 半周期 25 ns | 同上 ✓ |
| 读毕保持：末下降沿 → CS ↑ | 50 ns | = §2 设定 ✓ |
| 采样 / 转换不重叠 | 采样在 CONVST 低段，转换在 BUSY 高段 | 两窗口零重叠 ✓ |

全链因果（6 步）：CONVST ↓ 启动采样 → CONVST ↑ 保持并启动转换 → BUSY ↑ 转换进行中 → BUSY ↓ / DRDY ↓ 数据就绪 → CS ↓ + 16×SCLK 读回 0x0A7C → CS ↑ / DRDY ↑ 握手闭合。任一环节的先后顺序如需改动，必须同步改图（wavejson）与文字，两侧零矛盾。

## 6 复现命令

```bash
cd "/c/Users/Kai/.nebflow/projects/general/.nebflow/tmp"
npx -y wavedrom-cli -i adc_conv.json -s adc_conv.svg
npx -y wavedrom-cli -i spi_read.json -s spi_read.svg
cp adc_conv.svg C:/Users/Kai/.nebflow/plots/20261004_timing_adc_conv.svg
cp spi_read.svg C:/Users/Kai/.nebflow/plots/20261004_timing_spi_read.svg
C:/Users/Kai/.nebflow/tools/viz-venv/Scripts/python.exe C:/Users/Kai/.nebflow/skills/visual-report-suite/timing/wavedrom_dark.py C:/Users/Kai/.nebflow/plots/20261004_timing_adc_conv.svg C:/Users/Kai/.nebflow/plots/20261004_timing_spi_read.svg
```
