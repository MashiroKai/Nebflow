#!/usr/bin/env bash
# verify-external-open.sh — 「外部文件拉进 canvas」的服务端真机读数（external-open 批 2026-11-19）。
#
# 起一个**隔离实例**（独立 --home + 独立端口，绝不碰生产 :6138 / PID 56786），
# 然后逐条打 `POST /api/nf-external/open`，把状态码 + `X-Nf-Reason` + 落盘读数
# 打到 stdout 并写进 evidence JSON。每条断言同时是报告里的「闸读数」。
#
# 用法：
#   JAR=target/scala-3.5.2/stand-assembly-<ver>.jar scripts/verify-external-open.sh
#   可选：PORT（默认 6199）、OUT（默认 evidence/20261119_external_open/）
#
# 退出码：0 = 全部符合预期；非 0 = 至少一条不符。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-6199}"
OUT="${OUT:-$ROOT/evidence/20261119_external_open}"
JAR="${JAR:-$(ls "$ROOT"/target/scala-*/stand-assembly-*.jar 2>/dev/null | head -1)}"
HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nf-ext-home-XXXXXX")"
mkdir -p "$OUT"
JSONL="$OUT/server-readings.jsonl"
: > "$JSONL"
FAILED=0

if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
  echo "no assembly jar found — run: sbt assembly" >&2
  exit 2
fi

if lsof -nP -i ":$PORT" >/dev/null 2>&1; then
  echo "port $PORT is already in use — pick another: PORT=6201 $0" >&2
  exit 2
fi

echo "== isolated instance: jar=$(basename "$JAR") port=$PORT home=$HOME_DIR"
java -jar "$JAR" --home "$HOME_DIR" --port "$PORT" --no-browser > "$HOME_DIR/server.log" 2>&1 &
SRV=$!
cleanup() {
  kill "$SRV" 2>/dev/null
  for _ in $(seq 1 20); do kill -0 "$SRV" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$SRV" 2>/dev/null
  wait "$SRV" 2>/dev/null
}
trap cleanup EXIT

for _ in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/nf-authcheck" 2>/dev/null || true)
  [[ "$code" == "204" || "$code" == "403" ]] && break
  sleep 1
done
TOKEN=$(python3 -c "import json;print(json.load(open('$HOME_DIR/auth.json')))" 2>/dev/null || true)
if [[ -z "$TOKEN" ]]; then
  echo "instance did not mint a token — see $HOME_DIR/server.log" >&2
  tail -20 "$HOME_DIR/server.log" >&2
  exit 2
fi

post() {
  local label="$1" body="$2" want_status="$3" want_reason="${4:-}"
  local hdr="/tmp/nf-ext-hdr.$$" out="/tmp/nf-ext-body.$$"
  local got
  got=$(curl -s -D "$hdr" -o "$out" -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$body" "http://127.0.0.1:$PORT/api/nf-external/open")
  local reason short ok
  reason=$(grep -i '^x-nf-reason:' "$hdr" | tr -d '\r' | awk '{print $2}' | head -1)
  short=$(head -c 300 "$out" | tr '\n' ' ')
  ok="yes"
  [[ "$got" == "$want_status" ]] || ok="no"
  if [[ -n "$want_reason" && "$reason" != "$want_reason" ]]; then ok="no"; fi
  printf '%-34s status=%s reason=%-22s %s\n' "$label" "$got" "${reason:-<none>}" "$short"
  python3 - "$JSONL" "$label" "$got" "${reason:-}" "$ok" "$short" <<'PY'
import json,sys
path,label,status,reason,ok,body = sys.argv[1:7]
with open(path,'a') as f:
    f.write(json.dumps({"case":label,"status":int(status),"reason":reason,"ok":ok=="yes","body":body},ensure_ascii=False)+"\n")
PY
  [[ "$ok" == "yes" ]] || FAILED=1
}

echo
echo "-- negative controls (must be red) ---------------------------------"
post "credential: <home>/auth.json" "{\"path\":\"$HOME_DIR/auth.json\"}" 403 credential-path
if [[ -f "$HOME/.ssh/id_rsa" ]]; then
  post "credential: ~/.ssh/id_rsa" "{\"path\":\"$HOME/.ssh/id_rsa\"}" 403 credential-path
elif [[ -f "$HOME/.ssh/known_hosts" ]]; then
  post "credential: ~/.ssh/known_hosts" "{\"path\":\"$HOME/.ssh/known_hosts\"}" 403 credential-path
else
  echo "skip  ~/.ssh entry (no file on this host)"
fi
post "url: loopback 127.0.0.1" "{\"url\":\"http://127.0.0.1:$PORT/api/nf-authcheck\"}" 403 url-private-address
post "url: metadata 169.254.169.254" '{"url":"http://169.254.169.254/latest/meta-data/"}' 403 url-private-address
post "url: private 10.0.0.5" '{"url":"http://10.0.0.5/a.png"}' 403 url-private-address
post "url: localhost name" "{\"url\":\"http://localhost:$PORT/\"}" 403 url-private-address
post "url: DNS -> 127.0.0.1 (localtest.me)" '{"url":"http://localtest.me/"}' 403 url-private-address
post "url: scheme file://" '{"url":"file:///etc/passwd"}' 400 url-scheme
post "url: 40MB body -> too-large" '{"url":"https://speed.cloudflare.com/__down?bytes=41943040"}' 413 too-large
post "path: nonexistent" '{"path":"/nonexistent/nope.pdf"}' 404 not-found

echo
echo "-- positive controls (must open) -----------------------------------"
PICK_PDF=$(find "$HOME/Downloads" "$HOME/Documents" "$HOME/Desktop" -maxdepth 2 -type f -iname '*.pdf' 2>/dev/null | head -1)
if [[ -z "${PICK_PDF:-}" ]]; then
  PICK_PDF="$HOME_DIR/outside-report.pdf"
  printf '%%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%%%EOF\n' > "$PICK_PDF"
fi
PICK_IMG=$(find "$HOME/Pictures" "$HOME/Downloads" "$HOME/Desktop" -maxdepth 2 -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' \) 2>/dev/null | head -1)
if [[ -z "${PICK_IMG:-}" ]]; then
  PICK_IMG="$HOME_DIR/outside-pic.png"
  python3 - "$PICK_IMG" <<'PY'
import base64,sys
data=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAGQAAABkCAYAAABw4pVUAAAAXklEQVR4nO3BAQ0AAADCoPdPbQ8HFAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAvBsYAAAB3l0sAAAAASUVORK5CYII=')
open(sys.argv[1],'wb').write(data)
PY
fi
echo "local picks: pdf=$PICK_PDF img=$PICK_IMG"
post "path: real PDF outside project" "{\"path\":\"$PICK_PDF\"}" 200 ""
post "path: real image outside project" "{\"path\":\"$PICK_IMG\"}" 200 ""

PDF_URL='https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf'
# wikimedia 在本机的 egress 上直接不可达（curl 000），换 python.org 的静态 PNG。
IMG_URL='https://www.python.org/static/img/python-logo.png'
post "url: public PDF (w3.org)" "{\"url\":\"$PDF_URL\"}" 200 ""
post "url: public PNG (python.org)" "{\"url\":\"$IMG_URL\"}" 200 ""

echo
echo "-- stored file + byte leg (canvas really renders) ------------------"
ABS=$(curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"url\":\"$PDF_URL\"}" "http://127.0.0.1:$PORT/api/nf-external/open" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('absPath',''))")
echo "stored absPath: $ABS"
if [[ -n "$ABS" && -f "$ABS" ]]; then
  echo "sha256(file) = $(shasum -a 256 "$ABS" | awk '{print $1}')"
  echo "magic        = $(head -c 5 "$ABS")"
  TICKET=$(curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"verify\",\"paths\":[\"$ABS\"]}" "http://127.0.0.1:$PORT/api/nf-ticket" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print(next(iter(d.get('tickets',{}).values()),{}).get('t',''))")
  ENC=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$ABS")
  CODE=$(curl -s -o /tmp/nf-ext-fetch.bin -w '%{http_code}' "http://127.0.0.1:$PORT/api/nf-file?path=$ENC&ticket=$TICKET")
  echo "GET /api/nf-file  status=$CODE bytes=$(wc -c < /tmp/nf-ext-fetch.bin) magic=$(head -c 5 /tmp/nf-ext-fetch.bin)"
  [[ "$CODE" == "200" ]] || FAILED=1
else
  echo "no stored file — the URL leg did not land (see readings above)"
  FAILED=1
fi
echo
echo "-- TTL surface: external-fetch tree --------------------------------"
find "$HOME_DIR/external-fetch" -maxdepth 2 2>/dev/null | sed "s#$HOME_DIR#<home>#" | head -10

echo
if [[ "$FAILED" == "0" ]]; then echo "RESULT: ALL EXPECTED (readings -> $JSONL)"; else echo "RESULT: RED (see cases above)"; fi
exit "$FAILED"
