#!/bin/bash
# memsample.sh — 60s 粒度内存环形采样器（严格只读）。权威副本: <repo>/ops/memsample.sh
# 部署位: ~/.stand/bin/memsample.sh（launchd com.nebflow.memsample, StartInterval=60）
#
# 2026-11-04 内存即时缓解批 M4「修 memsample 探针端口」：
#   改前 hostRSS 探针**写死 tcp:8080**，而生产自 2026-10-23 端口对调起在 **6138**
#   ⇒ 该列全天 NA（12:43 内存事故复盘时最大的仪表缺口：宿主 JVM 的 RSS 曲线缺失）。
#   改后端口**从运行实例解析**（不写死）：
#     1) $MEMSAMPLE_PORT（显式注入，最高优先）
#     2) $STAND_HOME/.pid（活实例 pid 文件）→ `lsof -a -p <pid> -iTCP -sTCP:LISTEN` 取监听端口
#     3) $STAND_HOME/nebflow.pid（旧 pid 文件，pid 必须活着且是 java 才采信）
#     4) 已知缺省端口 ${MEMSAMPLE_DEFAULT_PORTS:-6138 8080} 逐个探 LISTEN
#     5) 全失败 → NA（行照写，绝不让采样器自己挂）
#   顺带按作者要求纳入**压缩内存**（macOS vm_compressor：这部分不落在任何进程 RSS 里，
#   12:43 事故时达 7.4GB，是隐形大户）与 swap（本来就有，列序不动，保持旧读者兼容）。
#
# 列（append-only 演进——旧 9 列位置不变，新列一律追加）：
#   ISO8601, swapUsedMB, swapTotalMB, swapPct, pressureLevel, load1,
#   hostRSS_MB, fseventsdRSS_MB, hostPid, hostPort, compressedMB, totalMemMB
#   注: 前 9 列 = 改前口径（逐字保留）；hostPid/hostPort 让读数可溯源，
#   compressedMB/totalMemMB 补上「不在任何进程 RSS 里」的两块。
#
# 契约（不变）:
#  - 绝不发信号给任何进程、绝不 sudo、绝不碰 stand 代码/配置/实例
#  - 任何单点探针失败 → 该字段 NA，整行仍完整写出
#  - 脚本永远 exit 0（会挂的采样器比没有更糟）
#  - 环形缓冲: >4320 行（72h）→ 只留最后 2880 行（48h），原地写回

LOG_DIR="${MEMSAMPLE_LOG_DIR:-$HOME/.stand/logs/memsample}"
LOG="${MEMSAMPLE_LOG:-${LOG_DIR}/memsample.log}"
STAND_HOME="${STAND_HOME:-$HOME/.stand}"
DEFAULT_PORTS="${MEMSAMPLE_DEFAULT_PORTS:-6138 8080}"
NA="NA"

mkdir -p "${LOG_DIR}" 2>/dev/null

########## 1. timestamp ##########
ts="$(date +%Y-%m-%dT%H:%M:%S%z 2>/dev/null)"
[ -n "${ts}" ] || ts="${NA}"

########## 2. swap used/total/pct (MB, 1 decimal) ##########
swap_fields="$(sysctl -n vm.swapusage 2>/dev/null | awk '{
    t=""; u="";
    for (i=1; i<=NF; i++) {
        if ($i == "total") t = $(i+2);
        if ($i == "used")  u = $(i+2);
    }
    sub(/M$/, "", t); sub(/M$/, "", u);
    if (t == "" || u == "") { print "NA,NA,NA"; }
    else {
        pct = (t+0 > 0) ? (u+0)/(t+0)*100 : 0;
        printf "%.1f,%.1f,%.1f\n", u+0, t+0, pct;
    }
}')"
[ -n "${swap_fields}" ] || swap_fields="${NA},${NA},${NA}"

########## 3. memory pressure level ##########
pressure="$(sysctl -n kern.memorystatus_vm_pressure_level 2>/dev/null)"
case "${pressure}" in
    ''|*[!0-9]*) pressure="${NA}" ;;
esac

########## 4. load1 ##########
load1="$(uptime 2>/dev/null | awk '{ for (i=1; i<=NF; i++) if ($i ~ /^averages?:$/) { print $(i+1); exit } }')"
[ -n "${load1}" ] || load1="${NA}"

########## 5. 宿主实例监听端口解析（M4 核心：不再写死 8080） ##########
listening_port_of() {   # $1=pid → 打印该 pid 的第一个 LISTEN 端口（-a 必须，否则 lsof 是 OR 语义）
    [ -n "${1}" ] || return 0
    lsof -nP -a -p "${1}" -iTCP -sTCP:LISTEN 2>/dev/null \
      | awk 'NR>1 { n=$9; sub(/.*:/, "", n); if (n ~ /^[0-9]+$/) { print n; exit } }'
}
pid_alive_java() {      # $1=pid → 活着的 java 进程才认（pid 复用防御）
    [ -n "${1}" ] || return 1
    kill -0 "${1}" 2>/dev/null || return 1
    ps -o comm= -p "${1}" 2>/dev/null | grep -q "java"
}

host_pid=""; host_port=""
if [ -n "${MEMSAMPLE_PORT:-}" ]; then
    host_port="${MEMSAMPLE_PORT}"
    host_pid="$(lsof -nP -tiTCP:"${host_port}" -sTCP:LISTEN 2>/dev/null | head -n 1)"
else
    for pf in "${STAND_HOME}/.pid" "${STAND_HOME}/nebflow.pid"; do
        [ -r "${pf}" ] || continue
        cand="$(tr -dc '0-9' < "${pf}" 2>/dev/null)"
        pid_alive_java "${cand}" || continue
        p="$(listening_port_of "${cand}")"
        if [ -n "${p}" ]; then host_pid="${cand}"; host_port="${p}"; break; fi
    done
    if [ -z "${host_port}" ]; then
        for p in ${DEFAULT_PORTS}; do
            cand="$(lsof -nP -tiTCP:"${p}" -sTCP:LISTEN 2>/dev/null | head -n 1)"
            if [ -n "${cand}" ]; then host_pid="${cand}"; host_port="${p}"; break; fi
        done
    fi
fi
[ -n "${host_pid}" ]  || host_pid="${NA}"
[ -n "${host_port}" ] || host_port="${NA}"

########## 6. host RSS (MB) ##########
host_rss="${NA}"
if [ "${host_pid}" != "${NA}" ]; then
    host_kb="$(ps -o rss= -p "${host_pid}" 2>/dev/null | tr -d ' ' | head -n 1)"
    if [ -n "${host_kb}" ]; then
        host_rss="$(awk -v k="${host_kb}" 'BEGIN{ printf "%.1f", k/1024 }' 2>/dev/null)"
        [ -n "${host_rss}" ] || host_rss="${NA}"
    fi
fi

########## 7. fseventsd RSS, KB -> MB（既有口径，保留） ##########
fs_rss="${NA}"
fs_kb="$(ps axo rss,comm 2>/dev/null | grep '[f]seventsd' | awk 'NR==1{print $1}')"
if [ -n "${fs_kb}" ]; then
    fs_rss="$(awk -v k="${fs_kb}" 'BEGIN{ printf "%.1f", k/1024 }' 2>/dev/null)"
    [ -n "${fs_rss}" ] || fs_rss="${NA}"
fi

########## 8. compressed memory（vm_compressor，MB）— 事故隐形大户 ##########
# vm_stat 的「Pages occupied by compressor」× 页大小；页大小从 `sysctl vm.pagesize` 取。
page_size="$(sysctl -n vm.pagesize 2>/dev/null)"
[ -n "${page_size}" ] || page_size=16384
compressed="$(vm_stat 2>/dev/null | awk -v ps="${page_size}" '
    /Pages occupied by compressor/ {
        n=$NF; gsub(/[.,]/, "", n);
        if (n ~ /^[0-9]+$/) printf "%.1f", n*ps/1048576; else print "NA";
        found=1; exit
    }
    END { if (!found) print "NA" }')"
case "${compressed}" in ''|*[!0-9.]*) compressed="${NA}" ;; esac

########## 9. total physical memory (MB) ##########
total_mem="$(sysctl -n hw.memsize 2>/dev/null | awk '{ if ($1 ~ /^[0-9]+$/ && $1 > 0) printf "%.0f", $1/1048576; else print "NA" }')"
[ -n "${total_mem}" ] || total_mem="${NA}"

########## 10. assemble + append（单行原子近似） ##########
line="${ts},${swap_fields},${pressure},${load1},${host_rss},${fs_rss},${host_pid},${host_port},${compressed},${total_mem}"
printf '%s\n' "${line}" >> "${LOG}" 2>/dev/null

########## 11. ring buffer: >4320 行（72h）→ 只留最后 2880 行（48h） ##########
line_count="$(wc -l < "${LOG}" 2>/dev/null | tr -d ' ')"
if [ -n "${line_count}" ] && [ "${line_count}" -gt 4320 ]; then
    tmp="${LOG}.tmp.$$"
    if tail -n 2880 "${LOG}" > "${tmp}" 2>/dev/null; then
        mv "${tmp}" "${LOG}" 2>/dev/null || rm -f "${tmp}" 2>/dev/null
    else
        rm -f "${tmp}" 2>/dev/null
    fi
fi

# 未脱离调用方（手工/自检跑）时回显一行，方便直接看到读数；launchd 下 stdout 进 /dev/null，无副作用。
[ "${MEMSAMPLE_QUIET:-0}" = "1" ] || printf '%s\n' "${line}"

exit 0
