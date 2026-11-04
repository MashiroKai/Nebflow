#!/bin/sh
# Self-signed TLS material for the nearpath rig ingress (https page origin is
# required: mixed content / Private-Network-Access only trigger on https).
#
# SANs cover the three origins the rig can be driven from:
#   DNS:mystand.space   (Chromium --host-resolver-rules MAP → ingress; 生产门户同源)
#   DNS:localhost       (loopback host form)
#   IP:127.0.0.1        (loopback)
#   IP:<LAN IP>         (非本机地址空间 ⇒ 触发 PNA/LNA 轴；由调用方 append)
#
# Usage: sh make-cert.sh [extra-SAN-IP]
set -eu
OUT="${OUT:-/tmp/nearpath-fire/certs}"
mkdir -p "$OUT"
SAN="DNS:localhost,DNS:mystand.space,IP:127.0.0.1"
if [ "${1:-}" != "" ]; then SAN="$SAN,IP:$1"; fi
openssl req -x509 -newkey rsa:2048 -nodes -days 3 \
  -keyout "$OUT/rig-key.pem" -out "$OUT/rig-cert.pem" \
  -subj "/CN=nearpath-rig" \
  -addext "subjectAltName=$SAN" >/dev/null 2>&1
echo "cert=$OUT/rig-cert.pem key=$OUT/rig-key.pem san=$SAN"
