zhang-sanqi-runtime-recon — 张安齐给 stand 做的容器/环境系统 调研取证
批期：2026-11-04 14:0x–14:5x（作者令 14:08）
分支：delegate/delegate-nebflow-5022b4e0
性质：只读调研。零源码改动；本目录只是取证。

== 一、怎么找到的（可复跑） ==============================================

线索给的是 "USTC git（DOSTEL 仓 remote 指向 git.kaiyu/sipm 这类路径）"。实测：
  · 本仓 dev remote = git@git.ustc.edu.cn:kaiyu/stand.git  ⇒ 主机 = git.ustc.edu.cn（USTC GitLab）
  · /Users/kaiyu/.stand/projects/DOSTEL 不是 git 仓（只是 stand 项目注册目录，无 remote）
  · SSH 免密可用：ssh -T git@git.ustc.edu.cn → "Welcome to GitLab, @kaiyu!"

定位路径（见 readings/finding-path.txt）：
  1) 公开 API 搜项目命中 phenol1145/stand-app（"Stand Launcher application package"）
  2) 认证 API 搜用户 "张安齐" → id=3965 username=phenol1145 name=张安齐 state=active
     ⇒ 与 2026-10-28 审计名单上的张安齐（phenol1145）为同一账号
  3) 该账号名下四个仓全部 SSH 可读（clone --depth 1 成功）

取证用的凭据：/Users/kaiyu/.stand/secrets/.askuser/ustc-gitlab-pat-final
  只在 subshell 内经 $(cat ...) 注入 curl header，未打印、未落盘、未转发。

== 二、实物清单（克隆到 /tmp/zq-recon，SHAs 见 readings/repos.txt） ========

  phenol1145/stand-app      6e6c073a  2026-10-25 17:08  Stand 应用安装包（triple-app 组合声明）
  phenol1145/triple-star    e500e510a 2026-10-24 12:48  框架本体（Rust；分支 feat/stand-parity-flow=310cfbb0 是 stand-app 的来源）
  phenol1145/triple-images  859c1699  2026-10-25 17:08  镜像库（OCI layout，含 stand front + 14 服务）
  phenol1145/tpm            8ffe7c99  2026-10-24 16:03  Triple Package Manager

== 三、本目录 ============================================================

  readings/finding-path.txt      定位链路（API 查询与命中）
  readings/repos.txt             四仓 SHA / 体量 / 分支面
  readings/image-matrix.txt       17 个镜像的 platform / digest / 磁盘占用
  readings/unit-rootfs.txt        executord 镜像层逐条清单（证明"镜像里没有 Linux 发行版"）
  readings/arch-and-host.txt      本机宿主读数（arm64 / macOS 26.4 / docker 在位 / 无 lima）
  readings/cross-host-gaps.txt    跨机面的边界原文（linkd v0 不做网络传输 / storage 分布式=catalog v4 等）

== 四、一句话 ============================================================

它是"一个 Linux 用户态 × 三载体（Linux 原生 / WSL2 / macOS Lima-VZ）+ 每应用一个 3MB 级
payload 镜像"，不是"每个 stand 带一套完整 Linux 发行版"。
