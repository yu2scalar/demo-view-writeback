#!/bin/bash
# Spark Connect server 起動(.130 ホスト、:15002)
# 前提: ~/opt/spark-3.5.6-bin-hadoop3(アプリの spark-connect クライアント 3.5.6 に合わせる)
set -eu
SPARK_HOME="${SPARK_HOME:-$HOME/opt/spark-3.5.6-bin-hadoop3}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

"$SPARK_HOME/sbin/start-connect-server.sh" \
  --properties-file "$SCRIPT_DIR/spark-defaults.conf" \
  --packages org.apache.spark:spark-connect_2.12:3.5.6,com.scalar-labs:scalardb-analytics-spark-all-3.5_2.12:3.18.0

echo "spark-connect starting on sc://localhost:15002 (log: $SPARK_HOME/logs/)"
