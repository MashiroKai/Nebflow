#!/usr/bin/env bash
# nebflow-inject — Send a message to a nebflow agent via the callback API.
#
# Usage:
#   nebflow-inject --agent Nebula --message "lint found 3 errors"
#   nebflow-inject --agent Nebula --session abc123 --message "build failed"
#   nebflow-inject --agent Nebula --message ./prompt.md
#   nebflow-inject --agent CodeReview --message @issues/todo.md
#
# Environment:
#   NEBFLOW_URL  — gateway URL (default: http://localhost:8080)
#   NEBFLOW_TOKEN — gateway token (same token used for web UI / WebSocket)

set -euo pipefail

URL="${NEBFLOW_URL:-http://localhost:8080}"
TOKEN="${NEBFLOW_TOKEN:-}"
AGENT=""
SESSION=""
MSG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --agent|-a)    AGENT="$2"; shift 2 ;;
    --session|-s)  SESSION="$2"; shift 2 ;;
    --message|-m)  MSG="$2"; shift 2 ;;
    --url)         URL="$2"; shift 2 ;;
    --token|-t)    TOKEN="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: nebflow-inject --agent NAME --message TEXT"
      echo ""
      echo "Required:"
      echo "  --agent,    -a NAME   Agent name (e.g. Nebula, CodeReview)"
      echo "  --message,  -m TEXT   Message text, or path to a file"
      echo ""
      echo "Optional:"
      echo "  --session,  -s ID     Session ID (new session created if omitted)"
      echo "  --url       URL       Gateway URL (default: \$NEBFLOW_URL or http://localhost:8080)"
      echo "  --token,   -t TOKEN  Gateway token (default: \$NEBFLOW_TOKEN)"
      echo ""
      echo "Message can be inline text or a file path."
      echo "Use @ prefix to force file read: --message @prompt.md"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# --- Validate ---
if [[ -z "$AGENT" ]]; then
  echo "Error: --agent is required" >&2; exit 1
fi
if [[ -z "$MSG" ]]; then
  echo "Error: --message is required" >&2; exit 1
fi
if [[ -z "$TOKEN" ]]; then
  echo "Error: no token. Set NEBFLOW_TOKEN or use --token" >&2; exit 1
fi

# --- Resolve message: file or text ---
resolve_message() {
  local input="$1"
  if [[ "$input" == @* ]]; then
    local filepath="${input:1}"
    if [[ ! -f "$filepath" ]]; then
      echo "Error: file not found: $filepath" >&2; exit 1
    fi
    cat "$filepath"
  elif [[ -f "$input" ]]; then
    cat "$input"
  else
    echo "$input"
  fi
}

RESOLVED_MSG=$(resolve_message "$MSG")

# --- Build JSON body ---
if command -v python3 &>/dev/null; then
  ESCAPED_MSG=$(printf '%s' "$RESOLVED_MSG" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
else
  ESCAPED_MSG=$(printf '%s' "$RESOLVED_MSG" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\n/\\n/g' | while IFS= read -r line; do printf '"%s"' "$line"; done)
fi

BODY="{\"agent\":\"$AGENT\",\"message\":$ESCAPED_MSG"
if [[ -n "$SESSION" ]]; then
  BODY="$BODY,\"session\":\"$SESSION\""
fi
BODY="$BODY}"

# --- Send ---
ENDPOINT="${URL}/api/callbacks/inject"

HTTP_CODE=$(curl -s -o /tmp/nebflow-inject-resp.json -w "%{http_code}" \
  -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "$BODY" 2>/dev/null || echo "000")

if [[ "$HTTP_CODE" == "200" ]]; then
  SID=$(python3 -c "import json; print(json.load(open('/tmp/nebflow-inject-resp.json')).get('sessionId','?'))" 2>/dev/null || echo "?")
  echo "OK → agent=$AGENT session=$SID"
else
  BODY_PREVIEW=$(cat /tmp/nebflow-inject-resp.json 2>/dev/null | head -c 200)
  echo "Error: HTTP $HTTP_CODE $BODY_PREVIEW" >&2
  exit 1
fi

rm -f /tmp/nebflow-inject-resp.json
