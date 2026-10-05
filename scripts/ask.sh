#!/usr/bin/env bash
# Ask the hub directly, as a given user. Usage: scripts/ask.sh "What is my EUR exposure?" [email]
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
curl -sS http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $HUB_API_KEY" \
  -H "X-OpenWebUI-User-Email: ${2:-dana@acme.example}" \
  -H "Content-Type: application/json" \
  -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$1\"}]}"
echo
