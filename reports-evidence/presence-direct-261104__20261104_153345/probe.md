# presence 直连(P2P) 0% 可用 — 五层分层定位取证

只读取证。seat worktree: `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-4280aa54` (HEAD `3047906d0`)。
采集时间 2026-11-04 14:37–14:45（local = macOS / KAI = Windows）。

## 结论先行

**断在 tailnet 层：本机 Tailscale 为 `Stopped`，本机已脱离 tailnet（KAI 视角 `mashiros-macbook-pro ... offline, last seen 1d ago`）。**
对端一切正常（KAI 在 `0.0.0.0:6138` 正常监听，tailnet IP 未变更，实例在跑）。
LAN 兜底路径结构性不可用（两机不同网段）+ KAI 以太网 Public profile 对 Nebflow 显式 Block ⇒ tailnet 一断，没有任何 P2P 后备路径 ⇒ 直连 0%，只剩 relay。

---

## L1 整机可达性（本地侧）— 判定：不通（SYN 黑洞，非拒绝）

```
$ tailscale status
Tailscale is stopped.

$ tailscale ip -4
2026/11/04 14:37:43 no current Tailscale IPs; state: Stopped

$ ifconfig | grep 'inet '
	inet 127.0.0.1 netmask 0xff000000
	inet 100.65.49.141 netmask 0xffffc000 broadcast 100.65.63.255
```

`100.65.49.141 netmask 0xffffc000` ⇒ 本机 LAN = **100.65.0.0/18**（运营商 CGNAT 私有段）。
本机**没有任何 tailscale IPv4**（无 utun 持有 100.x 地址）⇒ 隧道未建立。
注：tailscale 网络扩展进程在跑（`31728 ... io.tailscale.ipn.macsys.network-extension`），但 IPN 状态为 Stopped（未登录/已断开）。

```
$ route -n get 100.91.165.120
   route to: 100.91.165.120
destination: 100.91.165.120
    gateway: 100.65.0.1
  interface: en0
      flags: <UP,GATEWAY,HOST,DONE,WASCLONED,IFSCOPE,IFREF,GLOBAL>

$ route -n get 192.168.1.145
   route to: 192.168.1.145
destination: 192.168.1.145
    gateway: 100.65.0.1
  interface: en0
      flags: <UP,GATEWAY,HOST,DONE,WASCLONED,IFSCOPE,IFREF,GLOBAL>
```

**关键：两个候选地址的出口都是 en0 → 网关 100.65.0.1（本机 LAN 的运营商 CGNAT 网关），没有任何 utun 路由。**
KAI 的 tailnet IP 100.91.165.120 没有落到 tailscale 隧道上，而是被当成"公网地址"丢给了运营商网关 ⇒ 必然黑洞。

```
$ ping -c 3 -W 1500 100.91.165.120
PING 100.91.165.120 (100.91.165.120): 56 data bytes
Request timeout for icmp_seq 0
Request timeout for icmp_seq 1

--- 100.91.165.120 ping statistics ---
3 packets transmitted, 0 packets received, 100.0% packet loss

$ nc -vz -w 3 100.91.165.120 6138
nc: connectx to 100.91.165.120 port 6138 (tcp) failed: Operation timed out
exit=1

$ nc -vz -w 3 192.168.1.145 6138
nc: connectx to 192.168.1.145 port 6138 (tcp) failed: Operation timed out
exit=1
```

**判据落定：两条都是 `Operation timed out`，不是 `Connection refused`。**
⇒ SYN 被丢弃（无路由/被运营商黑洞），**不是**"对端端口没监听"。若对端没监听，会有 RST 返回 `refused`。

```
$ netstat -rn | head
Destination        Gateway            Flags               Netif Expire
default            100.65.0.1         UGScg                 en0
100.65/18          link#14            UCS                   en0      !
100.65.0.1/32      link#14            UCS                   en0      !
100.65.0.1         c8:33:e5:8a:5d:45  UHLWIir              en0   1197
100.65.49.141/32   link#14            UCS                   en0      !
```

`192.168.1.145` 不在 `100.65.0.0/18` 内，也不在链路路由内 ⇒ 走 `default 100.65.0.1` ⇒ 从运营商网关发出 ⇒ 跨网段不可达。

## L2 对端是否在听（KAI 侧）— 判定：正常，`0.0.0.0` 通配绑定

```
$ netstat -ano | findstr 6138
  TCP    0.0.0.0:6138           0.0.0.0:0              LISTENING       14528
  TCP    192.168.1.145:4246     100.65.49.141:6138     SYN_SENT        14528
  TCP    192.168.1.145:4247     100.65.49.141:6138     SYN_SENT        14528
  TCP    [::]:6138              [::]:0                 LISTENING       14528
```

`LocalAddress = 0.0.0.0` 与 `[::]` ⇒ **不是只绑回环**，"对端只绑 127.0.0.1"的假设被直接证伪。
反面证据：KAI 自己在向本机 `100.65.49.141:6138` 发起连接且卡在 **SYN_SENT**（两条，滞留）——对端也在努力直连我们，同样打不通。

进程确认：

```
$ Get-Process -Id 14528
Id          : 14528
ProcessName : java
StartTime   : 2026/10/26 14:38:54
Path        : C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe
```

进程自 2026/10/26 14:38:54 起存活，6138 上无第二个监听者。

## L3 防火墙（KAI 侧）— 判定：tailnet 面放行，LAN 面被显式 Block

```
$ netsh advfirewall show allprofiles
Domain Profile Settings:
State                                 ON
Firewall Policy                       BlockInbound,AllowOutbound
...
Private Profile Settings:
State                                 ON
Firewall Policy                       BlockInbound,AllowOutbound
...
Public Profile Settings:
State                                 ON
Firewall Policy                       BlockInbound,AllowOutbound
```

三档 profile 全部 ON + BlockInbound（默认拒绝入站，必须有显式 Allow 才通）。

```
$ Get-NetConnectionProfile
Name      InterfaceAlias NetworkCategory IPv4Connectivity
----      -------------- --------------- ----------------
Tailscale Tailscale              Private     LocalNetwork
网络 50     以太网                     Public         Internet
```

**这是本层的关键切分：tailnet 网卡 = Private；LAN 以太网 = Public。**

```
$ netsh advfirewall firewall show rule name="Nebflow" verbose
Rule Name:  Nebflow   Direction: In   Profiles: Public    Protocol: TCP   LocalPort: Any   Program: C:\program files\nebflow\nebflow.exe   Action: Block
Rule Name:  Nebflow   Direction: In   Profiles: Public    Protocol: UDP   LocalPort: Any   Program: C:\program files\nebflow\nebflow.exe   Action: Block
Rule Name:  Nebflow   Direction: In   Profiles: Private   Protocol: UDP   LocalPort: Any   Program: C:\program files\nebflow\nebflow.exe   Action: Allow
```

应用级规则（`Get-NetFirewallApplicationFilter` 过滤 java|nebflow|neblink）相关行：

```
Nebflow                     Allow   Inbound Private  True
Nebflow                     Block   Inbound Public   True
OpenJDK Platform binary     Allow   Inbound Private  True
OpenJDK Platform binary     Block   Inbound Public   True
```

判定：
- **tailnet 路径（Tailscale 网卡, Private）** ⇒ Nebflow/OpenJDK 规则为 **Allow** ⇒ 防火墙**不**是 tailnet 直连的拦路虎。tailnet 通了，6138 就能进。
- **LAN 路径（以太网, Public）** ⇒ 显式 **Block** ⇒ 即便两机同网段也进不来。
- 本步 `Get-NetFirewallPortFilter` 因无管理员权限 `Access is denied`（只读受限），但应用级规则已足够定性；且与 L1 结论一致——包根本没到达 KAI，防火墙不是首断点。

## L4 tailnet 面（KAI 侧）— 判定：对端在网，本机离网

```
$ tailscale status（KAI）
100.91.165.120   kai                   MashiroKai@  windows  -
100.118.229.97   desktop-ijbbeot       MashiroKai@  windows  -
100.88.39.75     desktop-v7eucht       MashiroKai@  windows  -
100.127.149.106  mashiros-macbook-pro  MashiroKai@  macOS    offline, last seen 1d ago
100.92.65.22     pc-20250724etea       MashiroKai@  windows  offline, last seen 1d ago

$ tailscale ip -4（KAI）
100.91.165.120
```

两条硬结论：
1. **KAI 的 tailnet IP 确实是 100.91.165.120** ⇒ 本机持有的候选地址**没有陈旧**，"地址陈旧"假设被证伪。
2. **本机 `mashiros-macbook-pro`(100.127.149.106) 在 tailnet 里是 offline，last seen 1d ago** ⇒ 本机约 1 天前就掉出了 tailnet，与本地 `tailscale status: Stopped` 完全吻合（两侧独立采集，互相印证）。

```
$ Find-NetRoute -RemoteIPAddress 100.65.49.141（KAI）
IPAddress     InterfaceAlias NextHop     DestinationPrefix
---------     -------------- -------     -----------------
192.168.1.145 以太网
              以太网            192.168.1.1 0.0.0.0/0

$ Find-NetRoute -RemoteIPAddress 100.91.165.120
IPAddress      InterfaceAlias NextHop DestinationPrefix
---------      -------------- ------- -----------------
100.91.165.120 Tailscale      0.0.0.0 100.91.165.120/32
```

此步用于验证我提出的一个假设（"本机 LAN 100.65.0.0/18 与 Tailscale CGNAT 100.64.0.0/10 重叠，导致 KAI 上 100.65.49.141 被 tailscale 路由吞掉"）——**假设被证伪**：KAI 对 100.65.49.141 选的是以太网默认网关 192.168.1.1，没有进 tailscale。但该地址对 KAI 仍不可达（100.65.0.0/18 是 Mac 侧运营商私有段，从 192.168.1.0/24 只能送上公网）。
记录在此以免下游误采信该假设。

## L5 实例形态（KAI 侧）— 判定：正常

```
$ Get-CimInstance Win32_Process -Filter 'ProcessId=14528' | Select -ExpandProperty CommandLine
"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java" --add-opens=java.base/java.lang=ALL-UNNAMED -cp C:\Users\Kai\AppData\Local\stand\stand-assembly-2026.10.26-beta.2.jar nebflow.Main --home C:\Users\Kai\.stand --port 6138 --daemon-child
```

- 版本 `stand-assembly-2026.10.26-beta.2.jar` = 与本机同版。
- 启动参数**只有** `--port 6138`，**没有** `--host` / `--bind` 限制 ⇒ 走通配绑定（与 L2 的 `0.0.0.0` 一致）。
- 无"只接 relay 不接 p2p"的开关。

## L6 本机侧对称检查 — 判定：正常（通配绑定，非回环）

```
$ lsof -nP -iTCP:6138 -sTCP:LISTEN
COMMAND   PID  USER   FD   TYPE             DEVICE SIZE/OFF NODE NAME
java    78176 kaiyu  175u  IPv6 0xc2a437ddcfefe972      0t0  TCP *:6138 (LISTEN)
```

`*:6138`（IPv6 通配，双栈）⇒ 本机**不是**只绑回环，远端可达性在本机 bind 层没有问题。`java 78176` 正是本 Nebflow 实例自身 PID。

---

## 已被证据排除的清单（给作者验收）

| # | 假设 | 判定 | 证据 |
|---|------|------|------|
| 1 | KAI 只绑回环 127.0.0.1 | **排除** | L2: `0.0.0.0:6138` + `[::]:6138` LISTENING, PID 14528 |
| 2 | KAI 实例未起 / 端口不对 | **排除** | L2/L5: 监听正常，`--port 6138`，进程自 10-26 存活 |
| 3 | KAI 的 tailnet IP 陈旧（候选地址已变） | **排除** | L4: `tailscale ip -4` = `100.91.165.120`，与候选完全一致 |
| 4 | KAI LAN 地址候选 192.168.1.145 陈旧 | **排除** | L2/L4: `Find-NetRoute` 显示本机 `192.168.1.145` 在以太网上，候选正确 |
| 5 | 本机只绑回环（双向都不通） | **排除** | L6: `*:6138` 通配绑定 |
| 6 | 对端端口没监听（RST/refused） | **排除** | L1: 两条 `nc` 均为 `Operation timed out`，无 refused ⇒ SYN 被丢，不是 RST |
| 7 | KAI 防火墙拦死了所有入站 | **部分排除** | L3: tailnet 网卡=Private ⇒ Nebflow 规则 **Allow**；只有以太网=Public 才是 Block |
| 8 | tailscale CGNAT 网段与本机 LAN 重叠导致 KAI 路由被吞 | **排除** | L4: `Find-NetRoute 100.65.49.141` 走以太网默认网关，未进 tailscale |
| 9 | 实例形态/绑定参数问题（--host / 只接 relay 开关） | **排除** | L5: 命令行只有 `--port 6138` |
| 10 | relay 腿故障 | **排除**（基线） | relay 已连 `wss://neblink.nebflow.space`，业务未断（+1534ms 税） |

## 主因排序

1. **主因（唯一断点，已证实）：本机 Tailscale 停止运行，本机已脱离 tailnet 约 1 天。** 100.91.165.120 不再走隧道，被丢给运营商网关 100.65.0.1 黑洞化 ⇒ 3000ms SYN 超时。
2. **放大因素（已证实）：LAN 兜底路径结构性不可用。** 本机 100.65.0.0/18 vs KAI 192.168.1.0/24，跨三段路由；且 KAI 以太网=Public profile 对 Nebflow 显式 `Action: Block`。⇒ tailnet 掉线时**零后备路径**，直连必然 0%，只剩 relay。
3. **待作者操作后复验**：tailnet 恢复后是否真能直连（本节点未做任何改动，无法实测恢复态）。

## 作者可直接复制的修复命令（Windows/macOS，均未执行）

> 本节点全程只读，以下命令**一条都没有执行**。执行前请自行确认。

**A. 恢复本机 tailnet（主因，macOS，首要一条）**
```bash
# 若用 App Store / 独立 GUI 版：直接打开 Tailscale.app 并 Connect
open -a Tailscale
# 或 CLI 版：
tailscale up
# 验证：应打印 100.x.y.z（不再 no current Tailscale IPs；state 不再是 Stopped）
tailscale status
tailscale ip -4
# 回滚：tailscale down
```

**B. 验证 tailnet 层已通（与本机 6138 端口无关的一步，先过这关）**
```bash
tailscale ping 100.91.165.120
# 期望：pong from kai (100.91.165.120) via DERP(...) 或 via 192.168.1.x:41641
# 若仍是 no matching peer / timeout ⇒ 对端 kai 也需 tailscale up（KAI 侧当前在线，通常无需操作）
```

**C. 验证 6138 直连（tailnet 通之后再做）**
```bash
nc -vz -w 3 100.91.165.120 6138
# 期望：Connection to 100.91.165.120 port 6138 [tcp/*] succeeded!
# 若变成 "Connection refused" ⇒ 对端进程没在听（届时再查 L2）；
# 若仍 timed out ⇒ 查 KAI tailnet 网卡 profile 是否被改成 Public（见 D 的只读检查）
```

**D. KAI 侧可选加固（只在需要 LAN 兜底时才做；改防火墙需管理员 PowerShell）**
```powershell
# 现状只读确认（无需管理员）：
Get-NetConnectionProfile | Select Name,InterfaceAlias,NetworkCategory
# 若打算恢复 LAN 直连，需要把 Nebflow 的 Public 阻断规则改为放行（或把该网络设为 Private）。
# 放行为显式最小改动，回滚 = 把 -Action Allow 改回 Block：
New-NetFirewallRule -DisplayName "Nebflow TCP 6138" -Direction Inbound -Protocol TCP -LocalPort 6138 -Action Allow -Profile Public
# 回滚：
# Remove-NetFirewallRule -DisplayName "Nebflow TCP 6138"
```
> 注意：即便放开防火墙，LAN 直连仍需两机在同一网段——当前 100.65.0.0/18 与 192.168.1.0/24 不同段，**LAN 兜底在现有网络拓扑下不可能成立**。因此 A 是唯一有效路径。

**E. 最终验收（本机）**
```bash
# 看 presence 是否恢复直连：connectionsHasKey 应开始出现 true，Presence dial failed 应停止增长
grep -c "connectionsHasKey=true" <presence 日志>   # 期望 > 0
```

## 边界声明

未 kill 任何进程；未重启任何实例；未改任何防火墙/系统设置；未改任何绑定参数；未写任何仓库源码文件；凭据零回显。唯一产物为本文件。L4（地址发现代码层）未涉足，归 p2-audit。
