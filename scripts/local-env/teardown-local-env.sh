#!/bin/bash
# .130 ローカル環境の teardown(docs/local-env-setup.md §8)
#
# 使い方:
#   scripts/local-env/teardown-local-env.sh          # ホストプロセス停止 + minikube 内リソース削除
#   scripts/local-env/teardown-local-env.sh --all    # 加えて minikube delete(完全に更地)
#
# 注意: 全 DB が非永続(persistence 無効 / emptyDir)のため、teardown で
#       カタログ・ScalarDB 管理テーブル・view 実体・RE キューは全て消える。
#       再構築は setup-local-env.sh(+ RE --create-schema / アプリ setup)で行う。
set -uo pipefail

step() { echo; echo "==== $* ===="; }

# ---- 1. ホストプロセス停止 ---------------------------------------------------
step "アプリ(:8082)停止"
APP_PID=$(pgrep -f '^java -jar.*demo-view-writeback.*\.jar' || true)
if [ -n "$APP_PID" ]; then kill $APP_PID && echo "killed: $APP_PID"; else echo "(起動していない)"; fi

step "ScalarRE(:8080)停止"
if docker rm -f scalar-re >/dev/null 2>&1; then echo "removed container: scalar-re"; fi
RE_PID=$(pgrep -f '^java -jar.*demo-scalar-re.*\.jar' || true)
if [ -n "$RE_PID" ]; then kill $RE_PID && echo "killed jar process: $RE_PID"; fi

step "Spark Connect server(:15002)停止"
SPARK_HOME="${SPARK_HOME:-$HOME/opt/spark-3.5.6-bin-hadoop3}"
if [ -x "$SPARK_HOME/sbin/stop-connect-server.sh" ]; then
  "$SPARK_HOME/sbin/stop-connect-server.sh" || true
else
  echo "(SPARK_HOME 不明 — 手動で停止してください)"
fi

# ---- 2. minikube 内リソース削除 ----------------------------------------------
if minikube status >/dev/null 2>&1; then
  step "Helm release / manifest / Secret 削除"
  helm uninstall scalardb-analytics-server 2>/dev/null || true
  helm uninstall scalardb-cluster 2>/dev/null || true
  helm uninstall postgresql-scalardb-analytics 2>/dev/null || true
  kubectl delete pod analytics-server-cli --ignore-not-found
  kubectl delete -f "$(dirname "$0")/mysql.yaml" --ignore-not-found
  kubectl delete secret scalardb-credentials analytics-credentials --ignore-not-found
else
  echo "(minikube 停止中 — k8s リソース削除はスキップ)"
fi

# ---- 3. --all: minikube ごと削除 ---------------------------------------------
if [ "${1:-}" = "--all" ]; then
  step "minikube delete(--all)"
  minikube delete
fi

echo
echo "==== teardown 完了 ===="
echo "Spark 配布物(~/opt/spark-3.5.6-bin-hadoop3)と ivy キャッシュ(~/.ivy2)は残しています。"
echo "不要なら手動で削除してください。"
