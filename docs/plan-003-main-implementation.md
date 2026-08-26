# Plan 003: demo-view-writeback 本実装(フルセット)

- 作成日: 2026-07-18
- ステータス: **実装完了・E2E 全12項目 PASS(2026-07-18)**。
  RE 配送は .129 の RE インフラテーブルを `--recreate-schema` で再作成して解消
  (ユーザー承認済み)。詳細 `e2e-report-20260718.md`。残: デモシナリオ文書、
  localhost 一括構築(タスク9)、初回 commit(ユーザー指示待ち)
- 補足決定: ~~リフレッシュ入口はデータ編集画面のみ~~ → **plan-004 で上書き(2026-07-18 夜)**:
  リフレッシュは Admin の View ビルダー(編集モード)のみに移動し、データ編集(User)からは撤去
- 前提: plan-001(データパス FC、全項目 PASS → `fc-report.md`)、plan-002(GUI プロトタイプ、
  フィードバック反映済み第4版)、`design-notes-20260718.md`(Before/After キュー方式・型マッピング)

## 1. 決定事項(2026-07-18 ヒアリング)

| 論点 | 決定 |
|---|---|
| スコープ | **フルセット** — View 定義 GUI + マテリアライズ + 表示/編集 + 更新モジュール実行エンジン + RE 経路を一括実装 |
| 配置(案A) | メタ=`viewmgr`、View 実体=`views` を **ScalarDB 配下に新設**(ds_scalardb の default storage=MySQL に乗る。namespace_mapping 変更不要)。View 実体更新 + write-back + outbox を**同一 Tx** にするための必須構成 |
| Analytics 登録 | **views/viewmgr は Analytics に登録しない**(クエリが分かればいつでも実行できるため不要 — ユーザー判断) |
| RE 経路 | **前身踏襲**: `viewmgr.re_outbox` → ローカル ScalarRE(:8080)ポーリング → 宛先 inbox。UUIDv7 イベント、GUI に俯瞰表示。イベントは**レコード全体の Before/After イメージ** |
| 環境 | **.129 環境で開発・検証** → localhost 一括構築(Analytics Server 込み)は後段の別タスク(ライセンス確認含む) |
| GUI スタック | plan-002 の決定どおり(View ビルダー=自前 HTML+SVG、更新モジュール=Drawflow)。プロトタイプを本実装に接続する |

## 2. 実行エンジンの設計合意(詳細は実装後に再確認)

2026-07-18 の議論で合意したモデル。**「実際に出来上がったときに詳細を詰める」(ユーザー)**:

1. **フローは生 JSON を受け取らない**。エンジンが入口で view_definition に基づき検証・型変換し、
   実行コンテキスト(すべて型付きモデル)を構築する:
   - `$input`(受信 view 行)/ `before`(View キャッシュ現在行)/
     `after.<alias>`(updatable 列を持つ参加テーブルごとの After モデル。キー束縛・初期値セット済み)
2. **差分駆動**: `$input` vs `before` で変更列→変更テーブルを確定。
   **変更があるテーブルの After モデルだけが Tx に参加**(put / outbox とも)。変更ゼロなら no-op。
   フローには `changed.<alias>` / `changed.<alias>.<col>` フラグを公開(条件付き検証を書くため)
3. **レコード読込ノードは ScalarDB 配下の任意テーブルを読める**(View 非参加テーブルも可。
   キー式は $input/before/変数を参照可)。読みは同一 Tx 内 = Consensus Commit の楽観検証により
   「在庫を読んで判定して更新」がレース安全。RE 宛先(外部)は読めない(before.* で代用)
4. **書き込みはモデルへの値設定のみ**。commit ノード到達時にエンジンが一括適用:
   dirty な After → TX 宛先は tx.put(is_source_pk で行特定)/ RE 宛先は Before/After イベントを
   re_outbox へ INSERT / View 実体行の UPDATE(エンジン自動)→ tx.commit()。
   中断ノードで tx.abort() + 理由返却
5. 更新モジュールは View 単位の事前定義コントラクト。モジュール未定義の View は
   「読込→更新→完了」の暗黙デフォルトフロー(素通し)
6. **セマンティクス改訂(2026-07-18 夜、ユーザー合意)**: 「更新可」フラグは
   **GUI 直接編集の可否**であり、フロー(管理者定義)は **View 参加テーブルの
   非キー参加列すべて**に書き込める(例: ユーザー編集は受注数のみ・在庫はフローが
   差分反映で更新)。View に現れない列はタイポ防止のため従来どおり不可。
   実例モジュール = vw_order2(read prod → var delta/newstock → compare → 更新/中断)

## 3. データモデル(viewmgr、ScalarDB 配下)

| テーブル | キー | 主な列 | 備考 |
|---|---|---|---|
| `view_def` | view_name (PK) | definition_json (GUI 出力の view_definition 丸ごと), sql_text, materialized_table, status, created_at, refreshed_at | 正規化せず JSON 保存(GUI との相互変換が自明、M3 拡張に強い) |
| `update_module` | view_name (PK) | flow_json (GUI 出力のフロー定義), updated_at | M1 は View:モジュール = 1:1 |
| `re_outbox` | event_id (PK, UUIDv7) | occurred_at, dest_namespace, dest_table, key_json, before_json, after_json, status | 前身の outbox 作法 + Before/After 全レコードイメージ |

- View 実体: `views.<view_name>` テーブルを動的 CREATE(ScalarDB admin)。
  列 = view_definition の全列(型マッピング表準拠)。キー構成(パーティション/クラスタリングの割付)は
  参加テーブルのキー列から自動導出(詳細は実装時に確定)
- 宛先 inbox(デモ用 ERP 相当): `erp` namespace の inbox テーブル(ScalarRE の設定に合わせる)

## 4. コンポーネントとデータパス

```
[Analytics カタログ(.129 PG)] --JDBC 直読--> CatalogService(AnalyticsCatalogClient=FC-1)
[spark-connect .129:15002] <--JOIN SQL-- MaterializeService(FC-2) --結果--> views.<view>(FC-3/4 の DynamicRepository)
GUI(3画面) <--REST--> App(:8082)
  1. View ビルダー: カタログツリー表示 → view_definition JSON → POST /api/views(実体 CREATE + 初期マテリアライズ)
  2. 更新モジュールエディタ: flow_json → PUT /api/views/{v}/module
  3. データ編集画面: GET /api/views/{v}(定義+行) → 動的 TableView → PUT /api/views/{v}/rows → エンジン実行
更新 Tx(1つの ScalarDB Tx): 読込ノードの get → dirty put → re_outbox INSERT → views.<view> UPDATE → commit
RE: re_outbox → ScalarRE(:8080, config/scalar-re-config.yml) → erp inbox
```

### REST API(主要)

| Method / Path | 役割 |
|---|---|
| GET `/api/catalog` | カタログツリー(GUI 左ペイン用。パスワードはマスク) |
| GET/POST/DELETE `/api/views`, GET `/api/views/{v}` | View 定義 CRUD(POST=実体作成+初期マテリアライズ) |
| PUT `/api/views/{v}` | **定義の置換(編集)**: 実体 drop→再作成→再マテリアライズ。モジュールは維持+警告(2026-07-18 追加) |
| POST `/api/views/{v}/refresh` | 全件再マテリアライズ(truncate → spark 再クエリ) |
| GET `/api/views/{v}/rows` | View 行(定義 JSON + 行 JSON の2点セット → クライアントが動的 TableView 構成) |
| PUT `/api/views/{v}/rows` | 行更新(エンジン実行。結果 = committed / aborted+理由) |
| GET/PUT `/api/views/{v}/module` | 更新モジュール定義の取得/保存 |
| GET `/api/overview` | 俯瞰(選択 View + outbox + erp inbox。GUI ポーリング用) |
| POST `/api/admin/setup` | viewmgr 作成 + デモシード |

## 5. タスク分解

1. **基盤**: viewmgr スキーマ作成(setup)+ FC コードの本実装化(CatalogService / SparkService /
   DynamicRepository の型拡張=型マッピング表の全11型 + 単体テスト)
2. **View ライフサイクル**: view_definition 保存 → views.<view> 動的 CREATE → spark-connect
   マテリアライズ → refresh / 削除
3. **表示パス**: GET rows(定義+行)、GUI データ編集画面(動的 TableView、前身の編集 UX 踏襲、
   俯瞰表示 + refresh ボタン)。**リフレッシュの入口 = データ編集画面ヘッダ(View 選択の隣)+
   REST 単体実行**(バックエンド直接更新後の最新化用。View 経由編集は同一 Tx で実体も更新される
   のでリフレッシュ不要)。refreshed_at を画面に表示
4. **更新エンジン**: 実行コンテキスト構築(パース/検証/差分)→ フロー解釈実行(読込/変数/比較/分岐/
   更新/キュー送付/完了/中断)→ commit 時一括適用。素通しデフォルトフロー
5. **RE 経路**: re_outbox スキーマ + ScalarRE 設定(config/scalar-re-config.yml、宛先 erp inbox)+ 配送確認
6. **GUI 本実装**: プロトタイプ2画面を REST 接続(カタログ実データ、保存、モジュール編集)+
   データ編集画面。ポート 8082
7. **デモシード + シナリオ文書**(`docs/demo-scenario.md`): 在庫チェック付き更新モジュールの
   デモ(前身の題材を Analytics 環境の item_stock/item_master/orders 系に置き換え)
8. **E2E**: View 作成 → 表示 → 編集(検証 OK/NG 両方)→ TX write-back 確認(spark 再クエリ)→
   RE 配送確認 → refresh。**E2E 完了までは commit しない**(E2E-before-PR フック)
9. **(後段・別タスク)** localhost 一括構築: Analytics Server + Spark + カタログ + バックエンド +
   ScalarRE の compose/スクリプト化。**Analytics のライセンス要確認**

## 5.4 実装後の同日追加(2026-07-18、ユーザーフィードバック起点)

- **View 定義の編集**: PUT `/api/views/{v}` + ビルダー `?view=` 編集モード(定義復元、
  実体再作成+再マテリアライズ、モジュール維持+警告)。データ編集画面に「定義を編集」リンク
- **TIMESTAMP ラウンドトリップ修正**: RFC3339(offset 付き)応答値の送り返しを
  ValueCodec が受けられず必ずエラーになるバグ → APP_ZONE 変換フォールバック追加
- **テーブル別名の変更**: カード見出しの別名クリックで変更。JOIN・列マッピング・SQL・
  自動生成 View 列名が追従(ユーザー動作確認済み)
- 俯瞰の宛先キュー表示を選択中 View の RE namespace に動的追従

## 5.5 次回作業(2026-07-20 の週、ユーザー方針 2026-07-18)

1. **機能の導線見直し → 完成**: 画面間の遷移・操作フローを一巡見直す
   (候補: View 一覧管理画面、作成→モジュール→編集の誘導、エラーメッセージの整理)
2. **.130(localhost)だけで完結する環境構築**: Analytics Server + Spark +
   カタログ DB + バックエンド + 初期データ(デモシード)を一括作成できるようにする
   (= タスク9。Analytics のライセンス・デプロイ方法の確認込み。
   ERP らしいデモ用テーブル/データソース登録もここで設計)
3. 初回 git commit(E2E 済み。ユーザー指示があれば即実行できる状態 — 全ファイルステージ済み)

## 6. スコープ外・ペンディング

- views/viewmgr の Analytics 登録(不要と決定。将来「View の上の View」をやる場合のみ)
- カタログアクセス経路のセキュリティ(API 化・認可)— ユーザーが別途調査中
- スキーマドリフト検知(旧計画バックログから継承)
- 非 equi-join・集約 View、複数更新モジュール/View、M2 相当の式言語の拡張

## 7. リスク・実装時に詰める事項

- 実行エンジンのセマンティクス詳細(§2)は**動くものを見ながらユーザーと再確認**する
- View 実体キーの自動導出規則(複合キーの割付、`rows/{key}` の表現)
- spark-connect セッションの共有/プール(FC は都度生成)
- Drawflow ノードとエンジン命令セットの対応(プロトタイプの JSON を正とする)
- .129 環境は共用の可能性 — デモシード投入時に既存データと衝突しない namespace/テーブル名を使う
