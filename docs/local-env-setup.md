# .130 ローカル一括環境構築(plan-004 フェーズ2 詳細計画)

- 作成日: 2026-07-19
- ステータス: **T1〜T8 完了(2026-07-19)。E2E 全 10 項目 PASS** →
  結果は `e2e-report-20260719-local-env.md`。
  一括構築 = `scripts/local-env/setup-local-env.sh`(+ 手動 3 ステップ: RE 初期化/起動・アプリ起動)
- ポート早見(すべて minikube IP 192.168.49.2 の NodePort、ホストは localhost):
  Cluster=30053(envoy)/ カタログ PG=30432 / MySQL=30306 /
  Analytics=31051・31052 / Spark Connect=ホスト15002 / アプリ=8082 / RE=8080
- 前提: plan-005 完了(Cluster 切替 + IVM 伝播、E2E PASS)。
  接続形態は **(b) Cluster 一元化** で決定(2026-07-19 ユーザー承認)。
- 参照: 公式 <https://scalardb.scalar-labs.com/docs/latest/scalardb-analytics/deployment-local>
  (2026-07-19 精読済み。要点は本文に反映)

## 1. ゴール

.130(= このマシン、localhost)単体で、デモ一式をゼロから構築できる状態にする:

- ScalarDB Cluster / Analytics Server / カタログ DB / バックエンド DB /
  Spark(spark-connect)/ ScalarRE / 本アプリ(:8082)がすべて .130 で完結
- **(b) 検証**: カタログへの ds_scalardb 登録を **cluster client configs**
  (`scalar.db.transaction_manager=cluster`)で行い、Analytics server(Spark 読取り)が
  Cluster 経由で動くかを確認。可能なら topology 二重管理(plan-004 の考慮 2.)を Cluster に一元化
- デモ用データソース(ERP らしいテーブル + シード)を設計し、plan-003 のデモシナリオと整合
- 一括構築手順を自動化(スクリプト + 本ドキュメント)し、ゼロから構築 → E2E 再実行で検証

## 2. 現状(.129 依存)→ 目標(.130)対応表

| コンポーネント | 現在(.129) | 目標(.130) |
|---|---|---|
| ScalarDB Cluster | .129 minikube(3.18、`indirect:192.168.214.129`) | .130 minikube に Helm `scalar-labs/scalardb-cluster` 3.18 で新設(**Cluster ライセンス要**) |
| Cluster バックエンド DB | .129 MySQL | minikube 内に新設(§4 未決-2: MySQL 推奨) |
| Analytics Server | .129(カタログ 11051 / メータリング 11052) | minikube に Helm `scalar-labs/scalardb-analytics-server` 3.18 で新設(**Analytics ライセンス要**) |
| カタログ DB(PG) | .129:5432 `scalardb_analytics`(アプリが JDBC 直読) | minikube 内 bitnami/postgresql 新設。**ホストへ公開必須**(アプリ直読のため) |
| Spark(spark-connect) | `sc://192.168.214.129:15002` | **ホスト上に Spark Connect server** を立てる(§3 設計判断 D2)|
| ScalarRE | ホスト(:8080、手動起動) | **公開コンテナ** `ghcr.io/yu2scalar/scalar-re{,-init}:0.9.1`(host network、:8080。2026-07-19 に jar 起動から変更) |
| 本アプリ | ホスト :8082、`config/` は .129 向け | 同じくホスト。`config/` に .130 向け一式を配置(plan-004 設定ファイル方針どおり、コード変更なし) |

## 3. 設計判断

- **D1: すべて既存 minikube プロファイル上に構築**(docker driver、停止中)。
  起動して中身を確認し、再利用か `minikube delete` で作り直すかを決める(§4 未決-4)
- **D2: Spark Connect server はホスト側**。公式手順は spark-sql/spark-submit を
  Pod 内から実行する形だが、本アプリは `sc://` (spark-connect) 接続のため
  Connect server が必要。ホストに Spark 3.5.x を展開し
  `scalardb-analytics-spark-all` パッケージ + カタログ設定
  (`spark.sql.catalog.<name>.server.host` → Analytics server)で起動するのが最小構成。
  アプリからは `sc://localhost:15002`
- **D3: ホストへのサービス公開**。アプリ/RE/Spark(いずれもホスト)から
  minikube 内へ届く必要があるもの: Cluster(indirect 接続)、カタログ DB(5432)、
  Analytics server(11051/11052)。minikube docker driver のため
  NodePort(`minikube ip` 経由)か `minikube tunnel` を T1 で選定・固定する
- **D4: (b) 検証の進め方**。ds_scalardb の provider configs を
  cluster client properties(`transaction_manager=cluster` / `contact_points=indirect:<svc>`)にして
  CLI から登録 → Spark で読めるか確認。**不可の場合のフォールバック** =
  ライブラリモード configs(バックエンド DB 直結)で登録し、二重管理が残る旨を
  plan-004 の考慮欄に追記(デモ規律「書込みは Cluster 経由・Analytics は read-only」は維持)
- **D5: バージョン**: Cluster/Analytics/CLI とも 3.18.0(アプリ SDK 3.18.0 と一致)。
  Spark 3.5.x + `scalardb-analytics-spark-all-3.5_2.12:3.18.0`
- **D6: リソース**: メモリ 11Gi / 12 コア。minikube に 6Gi 目安で割当て、
  Spark Connect(1〜2Gi)・アプリ・RE はホスト側。動かなければ割当てを調整。
  **ディスク残 16G(88% 使用)が最大の制約** → §4 未決-3

## 4. 未決事項 → 決定(2026-07-19 ユーザー回答)

1. **ライセンス情報の受領**: T3/T4 着手までに共有いただく(Analytics Server 用
   `license_key` + `license_check_cert_pem`、ScalarDB Cluster 用ライセンス)
2. **Cluster バックエンド DB** = **MySQL 8**(.129 と同じ構成、検証済み経路を踏襲)
3. **ディスク確保** = **全部承認**: build cache / 未使用イメージ / 未使用ボリューム
   (sagabench データ消失了承)/ 停止中コンテナ削除。稼働中コンテナ(alloy / loki 等)は触らない
4. **minikube** = **`minikube delete` で更地にして新規作成**

## 4.5 (b) 検証結果(2026-07-19 実施 → **不可、フォールバック採用**)

- 手順: Analytics Server 3.18.0(公式イメージ)+ CLI 3.18.0 で、ds_scalardb を
  cluster client configs(`scalar.db.transaction_manager=cluster` /
  `indirect:192.168.49.2` / `contact_port=30053` / auth 有効)で登録
- 結果: 登録時にサーバー側の schema 解決(`ScalarDbSchemaResolver.resolveSchema`)が
  **`DB-CORE-10066: Transaction manager 'cluster' is not found`** で失敗
  (gRPC INVALID_ARGUMENT)。= **公式 Analytics Server イメージには
  scalardb-cluster-java-client-sdk が同梱されておらず、cluster configs での
  scalardb datasource 登録は 3.18.0 時点で不可**
- 使用した configs は `scripts/local-env/cli/data_source_scalardb_cluster_NG.json` に保存
- フォールバック(D4): ライブラリモード configs(`scalar.db.storage=jdbc` で
  バックエンド MySQL 直結)で ds_scalardb を登録 → 成功。
  .129 と異なり single-storage なので multi-storage の namespace_mapping 二重管理は無いが、
  「Cluster の topology とカタログ登録が別管理」という構造(plan-004 考慮 2.)は残る。
  **デモ規律は維持: 書き込みは必ず Cluster 経由、Analytics 直結は read-only 専用**
- 補足: サーバーイメージに cluster SDK jar を足したカスタムイメージなら通る可能性が
  あるが、Spark 側 connector にも同様の同梱が必要で非標準構成になるため見送り

## 5. タスク分解

- **T1 環境準備**: 承認範囲の docker prune → minikube 起動・中身確認(未決-4 の判断)→
  リソース割当て確定 → ホスト公開方式(D3)決定
- **T2 DB 構築**: カタログ用 PG(bitnami)+ Cluster バックエンド DB(未決-2)を minikube に。
  カタログ PG をホストに公開
- **T3 ScalarDB Cluster 構築**: Helm チャート + Cluster ライセンス。auth 有効(admin/admin、
  .129 と同一)。schema loader で `viewmgr` / `views` / デモ ERP namespace を作成。
  ホストから `indirect:` 接続をプローブ(既存 probe コード再利用)
- **T4 Analytics Server 構築 + (b) 検証 ★**: Helm チャート + Analytics ライセンス →
  CLI Pod からカタログ作成 → **ds_scalardb を cluster configs で登録**(D4)。
  併せて PG datasource(デモ用)も登録
- **T5 Spark Connect server(ホスト)**: Spark 3.5.x 展開 + analytics パッケージ +
  カタログ設定 → :15002 起動 → spark-sql で ds_scalardb / ds_postgres が見えることを確認
- **T6 デモ用データソース設計 + シード**: ERP らしいテーブル(order / inventory 系は
  既存 E2E と整合させつつ拡充)+ シードスクリプト。plan-003 タスク7 のデモシナリオ文書と整合
- **T7 アプリ / RE の .130 切替**: `config/application.properties`(カタログ DB / Spark)+
  `config/scalardb.properties`(Cluster)+ `scalar-re-config.yml` を .130 向けに作成 → 起動
- **T8 検証 + 自動化**: E2E 再実行(plan-005 の項目一式)→ 一括構築スクリプト
  (`scripts/` 予定)+ 本ドキュメントを再現手順として完成 → ゼロから再構築テストは
  時間次第でスコープ調整
- **T9 記録**: plan-004 ステータス更新、E2E レポート、memory 更新

## 6. 検証基準

- ホストのアプリ(:8082)から: view 作成 → マテリアライズ → データ編集(Cluster 経由書込み)→
  IVM 伝播 → リフレッシュ、が .130 のみで成立
- (b) 検証の結果(可否とエビデンス)が本ドキュメントに記録されている
- ScalarRE 経路(outbox → inbox)が .130 で動作

## 6.5 実施結果メモ(2026-07-19)

- T1: prune で約 27GB 回収(ディスク 88%→65%)、minikube 更地から再作成(k8s 1.34)
- T2: bitnami/mysql は取得不可(2025 カタログ変更)+9系 → 公式 `mysql:8.0` manifest に変更
- T3: byol-premium:3.18.0 + trial ライセンス(Secret 注入)で起動。ホストから
  `indirect:192.168.49.2:30053` プローブ PASS(auth・DDL 込み)
- T4: §4.5 のとおり (b) 不可 → ライブラリモード登録。**scalardb datasource は
  登録時スナップショット**のため「シード → 登録」の順が必須(CLI に refresh 無し、
  delete は `--cascade` 必須)
- T5: Spark 3.5.6(クライアント 3.5.6 に一致)+ `scalardb-analytics-spark-all-3.5_2.12:3.18.0`。
  JOIN クエリまで PASS
- T6: order.order / inventory.product / ns_mysql.item_stock(RE 経路用)を
  `scripts/local-env/seed/DemoSeed.java` で Cluster 経由シード(冪等)
- T7: `config/application.properties`(新規)+ `config/scalardb.properties` +
  `config/scalar-re-config.yml` を .130 向けに更新。RE は `--create-schema` 初期化後に常駐起動
- T8: E2E 全 10 項目 PASS(`e2e-report-20260719-local-env.md`)。
  ゼロから再構築テスト(`--fresh`)は未実施(スコープ調整、backlog)
- 追記(2026-07-19 同日): **ScalarRE を公開コンテナに切替**
  (`ghcr.io/yu2scalar/scalar-re-init:0.9.1` でワンショット `--create-schema` →
  `ghcr.io/yu2scalar/scalar-re:0.9.1` 常駐。host network +
  `config/scalar-re-config.yml` を `/app/scalar-re-config.yml` に ro マウント。
  使い方の典拠 = `~/IdeaProjects/scalar-re-samples/docker-compose.yml`)。
  setup スクリプトのステップ 8 に組込み、コンテナ RE で RE 経路 E2E 再 PASS。
  また同日、setup スクリプトによる**再構築の実地テストが完了**
  (k8s リソース再作成 → シード → 登録まで自動で成功。
  非永続ゆえ view 定義は消える → アプリ setup + view 再作成で復旧、§6.6 のとおり)

## 6.6 Teardown(2026-07-19 追加)

`scripts/local-env/teardown-local-env.sh` で撤去する:

- 引数なし: ホスト側停止(アプリ kill / ScalarRE は `docker rm -f scalar-re` /
  Spark Connect stop)+ minikube 内リソース削除
  (helm release 3 つ・mysql manifest・CLI Pod・ライセンス Secret)
- `--all`: 加えて `minikube delete`(完全に更地)

**注意: 全 DB が非永続**(カタログ PG / MySQL とも persistence 無効・emptyDir)。
teardown どころか **Pod 再作成だけでもデータは消える**(カタログ登録・ScalarDB 管理
テーブル・view 実体・RE キューすべて)。消えた後の再構築は
`setup-local-env.sh`(RE コンテナ初期化・起動込み)→ アプリ起動 →
`POST /api/admin/setup` → view + 更新モジュール再登録
(`scripts/local-env/register-views.sh` — 2026-07-20 追加。view JSON は POST /api/views、
`*_module.json` は PUT /api/views/{view}/module)、の順。
Spark 配布物(~/opt)と ivy キャッシュは teardown では残す。

**フルサイクル実地検証(2026-07-19)**: teardown → setup → アプリ復旧 → E2E スモーク
(TX 書き戻し往復 + 一対多 view 反映 + RE 経路往復 + overview)まで一気通貫で PASS。
所要は setup 完走まで約 2 分(イメージキャッシュ済みの場合)+ アプリ復旧 1 分弱。

## 6.7 サンプル置換(2026-07-19、plan-006)

デモサンプルを demo-cache-writeback 風(建設プロジェクト題材)に置換した。
**本ドキュメントの §2 / §6.5 に出てくる order / inventory / ns_mysql は当時の記録**であり、
現行サンプルは `project.project` / `project.material`(ScalarDB)+
`erp.project_order`(PG raw、erpdb)+ `vw_project_overview`(view + 更新モジュール)。
併せて Cluster を multi-storage 化(erp:postgres)、RE 宛先を PG(erp)に変更、
カタログ PG は `max_connections=300`。詳細・検証結果 = `plan-006-erp-sample.md`。

## 6.8 人事・勤怠サンプル追加(2026-07-20、plan-007)

建設プロジェクト題材と**共存**する 2 つ目のサンプルを追加した:

- ScalarDB 側: namespace `hr` の `employee` / `department`(DemoSeed.java が作成)
- 給与システム側: erpdb の**スキーマ `payroll`** の `emp_payroll`(erp-seed.sql が作成)
  = **2 つ目の RE 宛先**(scalar-re-config.yml に payroll namespace、
  Cluster の namespace_mapping に `payroll:postgres` を追加)
- view: `vw_employee_overview`(e×pay の 2 テーブル。dept_id / overtime_hours 編集で
  更新モジュールが発火)+ `vw_department`(単一テーブル参照 view、異動後の
  headcount 確認用)
- 更新モジュール: 部署異動(旧部署 headcount −1 / 新部署 +1 を **update ノードの
  key 指定**で同一 Tx、定員超過 abort)+ 残業の多段分岐(>80h abort / >45h alert_level=1)
- 詳細・検証結果 = `plan-007-hr-sample.md`

## 6.9 RE 承認往復(2026-07-20、plan-009)

RE 経由編集を承認往復型に変更した。view は `UPDATE REQUESTED` で保留 →
外部システムコンソール(`external-system.html`)で許可/却下 → 復路イベント
(`ViewWritebackResolved_<ns>`)が viewmgr.re_inbox へ配送 → アプリの
@Scheduled(2 秒)が view を確定。`config/scalar-re-config.yml` に erp / payroll の
polling + event-types を追加(**event-type 名は namespace ごとに一意必須** —
同名だと RE 0.9.1 で paused のまま配送されない)。アプリは erpdb への直接 JDBC
(`app.external.*`)を使う。詳細・E2E = `plan-009-re-approval-roundtrip.md`。

## 7. リスク

- **ディスク枯渇**(16G 残): minikube イメージ + Spark 配布物(~400MB)+ 各種イメージで
  数 GB 消費。prune 承認が得られないと構築途中で詰まる可能性
- **(b) 不可の可能性**: Analytics server が cluster client configs を受け付けない場合は
  D4 フォールバック(検証自体がフェーズ2の成果物)
- **DDL 直後の metadata cache 60秒問題**は .130 でも同様に発生する前提で手順に織り込む
