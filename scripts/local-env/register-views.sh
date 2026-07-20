#!/bin/bash
# scripts/local-env/views/ の定義 JSON をアプリ(:8082)へ登録する。
#   - vw_*.json          → POST /api/views(view 作成 + マテリアライズ)
#   - vw_*_module.json   → PUT  /api/views/<view>/module(更新モジュール)
# 前提: アプリ起動済み + POST /api/admin/setup 済み。冪等(既存 view は置換)。
set -euo pipefail

ENV_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8082}"

for v in "$ENV_DIR"/views/*.json; do
  [[ "$v" == *_module.json ]] && continue
  name=$(basename "$v" .json)
  # 既存があれば置換(PUT)、無ければ新規(POST)
  if curl -sf "$BASE_URL/api/views/$name" >/dev/null 2>&1; then
    method=PUT; url="$BASE_URL/api/views/$name"
  else
    method=POST; url="$BASE_URL/api/views"
  fi
  echo "== $method $name"
  curl -s -X "$method" -H 'Content-Type: application/json' -d @"$v" "$url"; echo
done

for m in "$ENV_DIR"/views/*_module.json; do
  [ -e "$m" ] || continue
  view=$(basename "$m" _module.json)
  echo "== PUT module for $view"
  curl -s -X PUT -H 'Content-Type: application/json' -d @"$m" \
    "$BASE_URL/api/views/$view/module"; echo
done

echo "VIEWS REGISTERED"
