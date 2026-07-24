#!/bin/bash
# .130(localhost)一括環境構築スクリプト(docs/local-env-setup.md の自動化)
#
# 前提:
#   - minikube / kubectl / helm / docker / java 17+ / mysql / psql クライアント
#   - config/set_license.sh(CLUSTER_LICENSE_KEY / ANALYTICS_LICENSE_KEY / LICENSE_CHECK_CERT)
#   - ~/opt/spark-3.5.6-bin-hadoop3(無ければ archive.apache.org から取得)
#   - アプリの fat jar(build/libs/demo-view-writeback-0.0.1-SNAPSHOT.jar)※シード用の依存に使用
#
# 使い方: repo root から  scripts/local-env/setup-local-env.sh [--fresh]
#   --fresh: minikube delete から始める(完全に更地)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_DIR="$REPO_ROOT/scripts/local-env"
MINIKUBE_IP_EXPECTED="192.168.49.2"
cd "$REPO_ROOT"

step() { echo; echo "==== $* ===="; }

# ---- 0. minikube -----------------------------------------------------------
if [ "${1:-}" = "--fresh" ]; then
  step "minikube delete(--fresh)"
  minikube delete || true
fi
if ! minikube status >/dev/null 2>&1; then
  step "minikube start(6cpu / 6Gi)"
  minikube start --driver=docker --cpus=6 --memory=6144
fi
MINIKUBE_IP=$(minikube ip)
if [ "$MINIKUBE_IP" != "$MINIKUBE_IP_EXPECTED" ]; then
  echo "WARN: minikube ip = $MINIKUBE_IP(期待値 $MINIKUBE_IP_EXPECTED)。"
  echo "      config/ と cli/*.json 内のアドレスを合わせて修正してください。"
fi

# ---- 1. ライセンス Secret --------------------------------------------------
step "ライセンス Secret 作成"
source "$REPO_ROOT/config/set_license.sh" >/dev/null
kubectl create secret generic scalardb-credentials \
  --from-literal=SCALAR_DB_CLUSTER_NODE_LICENSING_LICENSE_KEY="$CLUSTER_LICENSE_KEY" \
  --from-literal=SCALAR_DB_CLUSTER_NODE_LICENSING_LICENSE_CHECK_CERT_PEM="$LICENSE_CHECK_CERT" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic analytics-credentials \
  --from-literal=SCALAR_DB_ANALYTICS_SERVER_LICENSING_LICENSE_KEY="$ANALYTICS_LICENSE_KEY" \
  --from-literal=SCALAR_DB_ANALYTICS_SERVER_LICENSING_LICENSE_CHECK_CERT_PEM="$LICENSE_CHECK_CERT" \
  --dry-run=client -o yaml | kubectl apply -f -

# ---- 2. DB(カタログ PG + バックエンド MySQL)------------------------------
step "カタログ PG(bitnami、NodePort 30432)"
helm repo add bitnami https://charts.bitnami.com/bitnami >/dev/null 2>&1 || true
helm repo add scalar-labs https://scalar-labs.github.io/helm-charts >/dev/null 2>&1 || true
# max_connections: カタログ + erpdb 相乗りで Analytics/アプリ/RE/Spark が同時接続するため拡大
helm upgrade --install postgresql-scalardb-analytics bitnami/postgresql \
  --set auth.postgresPassword=postgres \
  --set auth.username=scalaradmin --set auth.password=scalaradmin \
  --set auth.database=scalardb_analytics \
  --set primary.persistence.enabled=false \
  --set primary.service.type=NodePort \
  --set primary.service.nodePorts.postgresql=30432 \
  --set-string 'primary.extendedConfiguration=max_connections = 300' \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.requests.cpu=250m \
  --set primary.resources.limits.memory=1Gi \
  --set primary.resources.limits.cpu=1

step "MySQL 8(公式イメージ、NodePort 30306)"
kubectl apply -f "$ENV_DIR/mysql.yaml"
kubectl rollout status statefulset/mysql-scalardb --timeout=300s
kubectl wait --for=condition=ready pod/postgresql-scalardb-analytics-0 --timeout=300s
# RE(viewmgr storage)の JDBC 接続先 DB を先に作っておく(namespace と同名)
mysql -h "$MINIKUBE_IP" -P 30306 -uroot -pmysql -e 'CREATE DATABASE IF NOT EXISTS viewmgr' 2>/dev/null

step "ERP DB(erpdb)作成 + シード(Cluster の multi-storage 対象なので Cluster より先)"
export PGPASSWORD=postgres
psql -h "$MINIKUBE_IP" -p 30432 -U postgres -d postgres -tc \
  "SELECT 1 FROM pg_database WHERE datname='erpdb'" | grep -q 1 || \
  psql -h "$MINIKUBE_IP" -p 30432 -U postgres -d postgres -c "CREATE DATABASE erpdb OWNER scalaradmin"
PGPASSWORD=scalaradmin psql -h "$MINIKUBE_IP" -p 30432 -U scalaradmin -d erpdb \
  -f "$ENV_DIR/erp-seed.sql"
unset PGPASSWORD

# ---- 3. ScalarDB Cluster ----------------------------------------------------
step "ScalarDB Cluster 3.18(envoy LoadBalancer 60053 / nodePort 30053)"
helm upgrade --install scalardb-cluster scalar-labs/scalardb-cluster \
  -f "$ENV_DIR/scalardb-cluster-custom-values.yaml"
kubectl rollout status deployment/scalardb-cluster-node --timeout=300s

# ---- 4. Analytics Server ----------------------------------------------------
step "Analytics Server 3.18(NodePort 31051/31052)"
helm upgrade --install scalardb-analytics-server scalar-labs/scalardb-analytics-server \
  -f "$ENV_DIR/analytics-server-custom-values.yaml"
kubectl rollout status deployment/scalardb-analytics-server --timeout=300s

# ---- 5. デモシード(Cluster 経由。ds 登録より先に!)-------------------------
step "デモシード(project.project / project.material / hr.employee / hr.department)"
SEED_LIB="$(mktemp -d)"
unzip -q -o "$REPO_ROOT/build/libs/demo-view-writeback-0.0.1-SNAPSHOT.jar" "BOOT-INF/lib/*" -d "$SEED_LIB"
( cd "$ENV_DIR/seed" \
  && javac --release 17 -proc:none -cp "$SEED_LIB/BOOT-INF/lib/*" DemoSeed.java \
  && java -cp ".:$SEED_LIB/BOOT-INF/lib/*" DemoSeed "indirect:$MINIKUBE_IP" 30053 )
rm -rf "$SEED_LIB"

# ---- 6. カタログ・データソース登録 ------------------------------------------
# 注意: scalardb datasource は「登録時スナップショット」。スキーマ変更後は
#       data-source delete --cascade → register のやり直しが必要(CLI 3.18 に refresh 無し)
step "カタログ作成 + データソース登録"
kubectl apply -f "$ENV_DIR/analytics-server-cli.yaml"
kubectl wait --for=condition=ready pod/analytics-server-cli --timeout=180s
kubectl cp "$ENV_DIR/cli" analytics-server-cli:/tmp/cli
CLI="kubectl exec analytics-server-cli -- java -jar /scalardb-analytics-cli/scalardb-analytics-cli.jar -c /tmp/cli/client.properties"
$CLI catalog create --catalog scalardb_catalog 2>/dev/null || echo "(catalog は既存)"
$CLI data-source delete --cascade --catalog=scalardb_catalog --data-source=ds_scalardb 2>/dev/null || true
$CLI data-source delete --cascade --catalog=scalardb_catalog --data-source=ds_postgres 2>/dev/null || true
$CLI data-source register --catalog=scalardb_catalog --data-source=ds_scalardb --provider-file=/tmp/cli/data_source_scalardb.json
$CLI data-source register --catalog=scalardb_catalog --data-source=ds_postgres --provider-file=/tmp/cli/data_source_postgres.json

# ---- 7. Spark Connect server(ホスト :15002)--------------------------------
step "Spark Connect server"
if [ ! -d "$HOME/opt/spark-3.5.6-bin-hadoop3" ]; then
  mkdir -p "$HOME/opt" && cd "$HOME/opt"
  curl -sfLO https://archive.apache.org/dist/spark/spark-3.5.6/spark-3.5.6-bin-hadoop3.tgz
  tar xzf spark-3.5.6-bin-hadoop3.tgz
  cd "$REPO_ROOT"
fi
if ! ss -tln | grep -q ':15002 '; then
  "$ENV_DIR/spark-connect/start-spark-connect.sh"
else
  echo "(15002 は既に listen 中 — 起動スキップ)"
fi

# ---- 8. ScalarRE(公開コンテナ ghcr.io/yu2scalar、host network)---------------
step "ScalarRE 初期化 + 起動(:8080)"
RE_VERSION="${RE_VERSION:-0.9.1}"
docker run --rm --network host \
  -v "$REPO_ROOT/config/scalar-re-config.yml:/app/scalar-re-config.yml:ro" \
  "ghcr.io/yu2scalar/scalar-re-init:$RE_VERSION" --create-schema
docker rm -f scalar-re >/dev/null 2>&1 || true
docker run -d --name scalar-re --network host --restart unless-stopped \
  -v "$REPO_ROOT/config/scalar-re-config.yml:/app/scalar-re-config.yml:ro" \
  "ghcr.io/yu2scalar/scalar-re:$RE_VERSION"

echo
echo "==== 環境構築完了 ===="
echo "残りの手動ステップ(docs/local-env-setup.md 参照):"
echo "  1. アプリ起動(repo root から): java -jar build/libs/demo-view-writeback-0.0.1-SNAPSHOT.jar"
echo "  2. セットアップ:               curl -X POST localhost:8082/api/admin/setup"
echo "  3. view + 更新モジュール登録(定義はデータと同様に毎回消える):"
echo "     scripts/local-env/register-views.sh"
