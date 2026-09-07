# perlfix.Dockerfile — 既有 nb-poc-sbx:alpine 的 perl 补丁层（重建捷径，同 ubuntu/langfix 模式）
# 背景：规范 Dockerfile 已把 perl 加入 apk 清单（#17 burner 依赖）；完整重建当日受 USTC TLS
# 间歇不可达影响（12:0x 实证；tuna=200），走补丁层：换 TUNA 源 apk add perl，秒级~分钟级。
# 用法：docker build -f images/alpine/perlfix.Dockerfile -t nb-poc-sbx:alpine images
FROM nb-poc-sbx:alpine
RUN sed -i 's#mirrors.ustc.edu.cn#mirrors.tuna.tsinghua.edu.cn#g' /etc/apk/repositories \
 && apk add --no-cache perl
