# plan-012: envoy Service を LoadBalancer 化(外部 SQL クライアント接続用)

- 日付: 2026-07-24
- 目的: テーブル状態を外から確認するため、ScalarDB Cluster の envoy に
  同ホスト+LAN 上の別マシンの SQL クライアントから接続できるようにする。
- 対象: この .130 minikube ローカル環境のみ(本番系構成には波及させない)。

## 背景 / 現状

- `scripts/local-env/scalardb-cluster-custom-values.yaml` の envoy は
  `type: NodePort`(30053)。ホストからは `indirect:192.168.49.2` + 30053 で接続可。
- `minikube tunnel --bind-address=0.0.0.0` は **既に常駐**(PID 23301)。
- ただし Linux + docker driver の tunnel は「ホストのルーティングテーブルに
  service CIDR への経路を足す」方式のため、External IP(10.x)へ到達できるのは
  **tunnel を実行しているホスト内のみ**。LAN 上の別マシンからは LoadBalancer 化
  だけでは届かない → ホスト側での 1 段転送が必要(手順 3)。

## 手順

1. **values 変更**: `scalardb-cluster-custom-values.yaml` の
   `envoy.service.type: NodePort` → `LoadBalancer`。`nodePort: 30053` は残す。
   - helm template(chart 1.11.1)で検証済み: LoadBalancer でも
     `nodePort: 30053` が Service に保持される → 既存スクリプト
     (setup-local-env.sh のシード、config/scalardb.properties 等)は無変更で動作。
   - setup-local-env.sh の step 表示文言(「envoy NodePort 30053」)を実態に合わせ微修正。
2. **反映**: `helm upgrade scalardb-cluster scalar-labs/scalardb-cluster -f <values>`
   → EXTERNAL-IP 付与を確認(tunnel は Service を watch しているので再起動不要の想定)。
3. **LAN 露出(必要時)**: ホストから EXTERNAL-IP:60053 到達を確認後、
   LAN 向けにホスト 0.0.0.0:60053 → 192.168.49.2:30053 の socat 転送を常駐
   (`setsid nohup socat TCP-LISTEN:60053,fork,reuseaddr TCP:192.168.49.2:30053`)。
   - tunnel の `--bind-address=0.0.0.0` が Linux route 方式では効かない想定のため。
     実測で LAN から届くならこの手順はスキップ。
4. **検証**:
   - `kubectl get svc scalardb-cluster-envoy` で EXTERNAL-IP 確認
   - ホストから EXTERNAL-IP:60053 に TCP 接続確認
   - ホスト実 IP:60053(socat 経由)に TCP 接続確認(= 別マシンからの接続経路)
   - 既存経路 192.168.49.2:30053 が引き続き生きていることを確認
   - アプリ/RE が動作中なら軽い疎通(view 一覧等)で無影響を確認

## 接続情報(完了後)

- 同ホスト: `indirect:<EXTERNAL-IP>` + 60053、または従来どおり `indirect:192.168.49.2` + 30053
- 別マシン: `indirect:<.130 の実 IP>` + 60053(socat 経由)
- 注意: `scalar.db.cluster.auth.enabled=true` のため SQL クライアントは認証情報が必要

## 実施結果(2026-07-24 同日実施・完了)

- values 変更 + `helm upgrade`(revision 2)実施。Service は
  `LoadBalancer 10.100.70.197 / EXTERNAL-IP 127.0.0.1 / 60053:30053` に。
- **想定と異なり socat は不要だった**: 常駐中の `minikube tunnel --bind-address=0.0.0.0`
  は(route 方式でなく)LB service ごとに SSH ポート転送を張る方式で動作しており、
  ホストの `0.0.0.0:60053` を直接 bind(`ssh ... -L 0.0.0.0:60053:10.100.70.197:60053`)。
  EXTERNAL-IP の 127.0.0.1 表示はこのモードの仕様。
- 検証(すべて PASS):
  - TCP: 127.0.0.1:60053 / 192.168.214.130:60053(ホスト実 IP)/ 192.168.49.2:30053 ✓
  - ScalarDB Tx レベル(読み取り専用 scan、hr.department 3 行):
    `indirect:192.168.214.130` + 60053 ✓、既存経路 `indirect:192.168.49.2` + 30053 ✓
- 接続情報(別マシンから): `indirect:192.168.214.130` + contact_port 60053、
  auth 有効(username/password 必要)
- 残注意:
  - **tunnel を `--bind-address=0.0.0.0` 無しで再起動すると 127.0.0.1 bind に落ち、
    LAN から届かなくなる**(転送は tunnel プロセスの寿命に従属)
  - **ufw が active**(ルールは sudo 権限が無く未確認)。別マシンから初回接続できない
    場合は `sudo ufw status` で 60053/tcp の許可を確認、必要なら
    `sudo ufw allow 60053/tcp`

## 追補: SQL インターフェース有効化(2026-07-24 同日)

- 外部 SQL クライアント(scalardbexplorer)から
  `UNIMPLEMENTED: Method not found: scalardb.cluster.rpc.v1.sql.SqlTransaction/Execute`。
- 原因: ノードプロパティに `scalar.db.sql.enabled=true` が無く、SQL 用 gRPC サービスが
  未登録だった(経路・認証は正常。デモアプリは CRUD API 利用のため無症状だった)。
- 対処: values の `scalardbClusterNodeProperties` に `scalar.db.sql.enabled=true` を追加し
  `helm upgrade`(revision 3)+ ノードロールアウト。
- 検証: SqlSession(`connection_mode=cluster` + `cluster_mode.username/password=admin`)で
  `SELECT * FROM hr.department` → 3 行 PASS(LAN 経路 192.168.214.130:60053)。
  既存 CRUD 経路(192.168.49.2:30053)も再確認 PASS。

## 影響範囲

- 変更ファイル: `scripts/local-env/scalardb-cluster-custom-values.yaml`(+ setup-local-env.sh の文言)
- 既存の 30053 経路は温存 → シード・アプリ・RE・Analytics への影響なし
- socat はホスト常駐プロセスが 1 つ増える(ops-lessons: setsid で切り離す)
