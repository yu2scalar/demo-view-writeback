# demo-view-writeback

ScalarDB Analytics のカタログを「バックエンド定義」として活用し、GUI で定義した View
(マテリアライズドビュー)への編集を ScalarDB Tx / ScalarRE で元テーブルへ write-back するデモ。

- カタログ(catalog → data_source → namespace → table → column)を **DB 直読**して
  テーブル定義の登録作業をなくす(SDK 未公開のため。`AnalyticsCatalogClient` に隔離)
- JOIN を含む View のマテリアライズは **spark-connect** で実行
- 編集は **provider_type=scalardb → 同一 Tx write-back / それ以外 → RE キュー
  (レコード全体の Before/After イベント)** に分岐
- 更新時の検証は **更新モジュール**(RPA 風フローチャートで定義、エンジンが 1 Tx 内で解釈実行)

## 環境(2026-07-19 時点: .130 ローカル一括環境が標準)

すべて localhost(.130)で完結する。**構築手順の正本は `docs/local-env-setup.md`**、
一括構築スクリプトは `scripts/local-env/setup-local-env.sh`。

| 要素 | 場所 |
|---|---|
| ScalarDB Cluster 3.18 | minikube(envoy NodePort `indirect:192.168.49.2:30053`、auth admin/admin) |
| Analytics Server 3.18 | minikube(NodePort 31051/31052) |
| Analytics カタログ DB | minikube PG(NodePort 30432、`scalardb_analytics`) |
| バックエンド DB | minikube MySQL 8(NodePort 30306。ScalarDB 管理 namespace: project / hr / viewmgr / views) |
| 外部システム DB(raw) | カタログ PG 相乗りの `erpdb`(erp.project_order / payroll.emp_payroll + RE の erp.re_inbox / payroll.re_inbox)。Cluster は multi-storage(erp:postgres, payroll:postgres)で両方を管轄 |
| spark-connect | ホスト `sc://localhost:15002`(Spark 3.5.6、`scripts/local-env/spark-connect/`) |
| 本アプリ | ホスト :8082(接続設定は `config/` の 3 ファイル) |
| ScalarRE | ホスト :8080、`config/scalar-re-config.yml` |

旧 .129 フィージビリティ環境の値は jar 内 `application.properties` のデフォルトと
各ドキュメントに残っている(FC 環境として現状維持)。

## 構築(ゼロから)

```bash
# 前提: minikube / kubectl / helm / docker / java 17+ / mysql / psql クライアント、
#       config/set_license.sh(ライセンス。リポジトリ外管理)
scripts/local-env/setup-local-env.sh          # 既存 minikube に構築(--fresh で更地から)
# → minikube 上の PG/MySQL/Cluster/Analytics + カタログ登録 + デモシード + Spark Connect まで自動

# 撤去(全 DB 非永続 = データも全消去。--all で minikube ごと削除):
scripts/local-env/teardown-local-env.sh [--all]
```

サンプルは 2 題材が共存する(アプリ setup 後に `scripts/local-env/register-views.sh` で
view + 更新モジュールを一括投入):

1. **建設プロジェクト管理**(plan-006、demo-cache-writeback から移植):
   ScalarDB 側 `project.project` / `project.material`、ERP 側 `erp.project_order`(PG raw)。
   view = `vw_project_overview`(3 テーブル JOIN、TX/RE 混在)+
   更新モジュール(material_volume 変更 → 割当連動、在庫超過なら abort。
   Steel の行で +20 すると拒否されるのが台本)
2. **人事・勤怠**(plan-007): ScalarDB 側 `hr.employee` / `hr.department`、
   給与システム側 `payroll.emp_payroll`(PG raw = **2 つ目の RE 宛先**)。
   view = `vw_employee_overview`(TX/RE 混在)+ `vw_department`(参照用)+
   更新モジュール(部署異動 = 旧部署 −1 / 新部署 +1 を update ノードの **key 指定**で
   同一 Tx、満員の管理部への異動は abort。残業は多段分岐 = >80h abort / >45h alert_level=1)。
   dept_id には**選択値(ルックアップ)**(plan-008、Access 風)を設定済み —
   data-edit では部署名のドロップダウンになり、列ごとに builder の「選択値」欄で
   ソース(ScalarDB テーブル or view)+ キー列 + 表示列を指定できる

RE 経由(via=RE)の編集は**承認往復**で確定する(plan-009): 編集すると view は
`UPDATE REQUESTED`(承認待ち、再編集ブロック)になり、変更リクエストが外部 inbox へ
配送される。**外部システムコンソール(模擬、ホームの「外部システム」グループのボタン)**で
許可 = 外部の生テーブルを直接 JDBC 更新 + SUCCEEDED 返送 / 却下 = REJECTED 返送。
復路イベントをアプリの自動ポーリング(2 秒)が受けて view を確定する
(SUCCEEDED → 依頼値 / REJECTED → 元の値に復帰)。
注意: コンソールの生テーブル更新(JDBC)と返送イベント(ScalarDB Tx)は非原子
(外部システム模擬の割り切り。実システムでは外部側の Tx/outbox で担保する想定)。

注意(詳細は `docs/local-env-setup.md`):
- Analytics の scalardb データソースは**登録時スナップショット**。スキーマ変更後は
  `data-source delete --cascade` → `register` のやり直し(スクリプトは「シード → 登録」の順)
- Analytics Server は cluster client configs での scalardb データソース登録に**未対応**
  (DB-CORE-10066。§4.5)→ ライブラリモード登録。書き込みは必ず Cluster 経由の規律で運用

## 起動

ScalarRE は公開コンテナ(`ghcr.io/yu2scalar/scalar-re{,-init}`)を使う。
setup スクリプトが初期化・起動まで行うが、単体では:

```bash
# 1. ScalarRE(host network、config をマウント。init はワンショットで exit)
docker run --rm --network host \
  -v $PWD/config/scalar-re-config.yml:/app/scalar-re-config.yml:ro \
  ghcr.io/yu2scalar/scalar-re-init:0.9.1 --create-schema
docker run -d --name scalar-re --network host --restart unless-stopped \
  -v $PWD/config/scalar-re-config.yml:/app/scalar-re-config.yml:ro \
  ghcr.io/yu2scalar/scalar-re:0.9.1                       # :8080
# 2. 本アプリ(repo root から。config/ が .130 向け設定を上書き)
java -jar build/libs/demo-view-writeback-0.0.1-SNAPSHOT.jar   # :8082
# ./gradlew bootRun でも可(--add-opens は設定済み)
# 3. 初回のみ: 画面の「初期セットアップ」または POST /api/admin/setup
```

## 画面(2026-07-18 導線見直し後)

0. **Home** `/` — Admin(View 一覧: 編集・更新モジュール・削除 / 新規 View / 初期セットアップ)と
   User(View 一覧 → データ編集)の役割別メニュー
1. **View ビルダー** `/view-builder.html` — カタログツリーからテーブルを D&D、カラム●同士の
   ドラッグで JOIN(線クリックで INNER/LEFT/RIGHT/FULL 変更)、キー列自動包含、
   ソート順指定、SQL プレビュー、保存=実体生成+マテリアライズ。
   **`?view=<名>` で既存 View の定義編集**(保存=実体再作成+再マテリアライズ、モジュール維持)。
   **リフレッシュ(再マテリアライズ)は編集モードのここから**(Admin 操作)
2. **更新モジュールエディタ** `/update-module.html?view=<名>` — 読込/変数/INT比較/分岐/更新/
   中断/完了のフローチャート。開始(Begin)=Tx begin。定義は view 単位に保存
3. **データ編集** `/data-edit.html?view=<名>` — グリッド編集(TX/RE バッジ)、
   outbox・宛先キューの俯瞰(**デフォルト非表示**。ヘッダーの「RE キューを表示」で切替 —
   demo-cache-writeback と同じ方式。User 操作。リフレッシュは持たない)

## フィージビリティチェック(実施済み)

`./gradlew fc1`(カタログ直読)/ `fc2`(spark-connect)/ `fc3`(Tx 書き戻し)。
結果と制約は `docs/fc-report.md`。

## ドキュメント

- `docs/plan-001-feasibility-check.md` / `docs/fc-report.md` — FC 計画と結果
- `docs/plan-002-gui-feasibility.md` — GUI 検証(`fc/gui-proto/` にプロトタイプ)
- `docs/plan-003-main-implementation.md` — 本実装計画(設計合意含む)
- `docs/design-notes-20260718.md` — Before/After キュー方式・型マッピング表
- `docs/e2e-report-20260718.md` — E2E 結果(RE 配送のブロッカー含む)
- `docs/plan-004-ux-review-and-local-env.md` — 導線見直し(フェーズ1)+ .130 構築(フェーズ2)。
  Analytics×Cluster の二重接続経路の考察と (b) 検証の決着はここ
- `docs/ux-review-20260718.md` — 4 画面レビューと採否・実施結果
- `docs/design-note-mv-maintenance.md` / `docs/plan-005-ivm-propagation.md` /
  `docs/e2e-report-20260719-plan005.md` — 一対多 View の同一 Tx 伝播(IVM)設計・実装・E2E
- `docs/local-env-setup.md` / `docs/e2e-report-20260719-local-env.md` —
  **.130 ローカル一括環境の構築手順(正本)**と E2E 結果
