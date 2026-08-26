# Plan 001 (v2): demo-view-writeback — Analytics カタログ前提のフィージビリティチェック

- 作成日: 2026-07-18
- ステータス: **完了(2026-07-18)— 全4項目 PASS。結果は `fc-report.md`**
- 訂正(2026-07-18 FC-1 実施時): カタログ DB の所在は §2 の記載(.130)と異なり
  **192.168.214.129:5432** だった。Spark は 3.5.6 standalone @ .129。詳細は fc-report.md
- 前史: 初代 demo-view-writeback(2026-07-17、Analytics ブロッカーで方針転換)は
  `demo-view-writeback-old` にリネーム退避済み(参考実装。旧計画書 plan-001-view-writeback-foundation.md を含む)。
  本リポジトリは**ゼロから作り直し**。

## 1. 方針転換の背景(2026-07-18 ヒアリング)

旧計画は「バックエンドテーブル定義を GUI で登録(getTableMetadata インポート)」だったが、
ScalarDB 上での JOIN の限界と Analytics 連携の不確実性が課題だった。新方針:

1. **ScalarDB Analytics を前提**に構築する
2. **Analytics のカタログ情報をテーブル定義の代わりに応用**(TableDef 作成・登録画面をパス)
3. カタログの SDK は未公開(Analytics コード内)のため、**カタログの入った DB を直接読む**
4. 旧 demo-cache-writeback 同様、**provider_type が `scalardb` ならトランザクショナル更新、
   それ以外(ERP 相当)は RE キュー送付**で分岐
5. Cache(View 実体)テーブルは**一旦作らずに進め、まずフィージビリティチェック(FC)を行う**

## 2. テスト環境(新・2026-07-18 時点)

| 要素 | 場所 | 備考 |
|---|---|---|
| Analytics カタログ DB | PostgreSQL @ **192.168.214.130**、DB `scalardb_analytics`、スキーマ `scalardb_analytics` | ID/PW: `scalaradmin`/`scalaradmin`。ポートは 5432 想定(FC-1 で確認) |
| カタログ構造テーブル | `catalogs`, `registry_data_sources`, `registry_namespaces`, `registry_tables`, `registry_columns` | 階層: catalog → data_source → namespace → table → column。他に auth_* / authz_* があるが対象外 |
| spark-connect | **sc://192.168.214.129:15002** | 動作確認済みサンプル: `SELECT id, item_group_id, item_stock_qty FROM scalardb_catalog.ds_scalardb.ns_mysql.item_stock;`(4部名 catalog.datasource.namespace.table) |
| バックエンド実データ | **192.168.214.129**: MySQL :3306 (`ns_mysql`), PostgreSQL :5432 (`ns_postgres`), DynamoDB local :8000 (`ns_dynamo`) | 接続情報はすべて `registry_data_sources.provider_payload_json` に入っており、**別途接続情報管理は不要**という見立て |
| データソース例 | `ds_scalardb`(provider_type=`scalardb`、multi-storage 構成の configs 一式)/ `ds_mysql`(provider_type=`mysql`、host/port/user/pass/database) | provider_type による分岐の根拠 |

- 注意: 従来環境(.130 の minikube + バッキング postgres5432〜5435)とは**別系統**。
- カタログテーブル自体が ScalarDB(Consensus Commit)管理らしき列(`tx_id`, `tx_state`, `before_*`)を持つ。
  直読は未コミット中間状態を見るリスクがあるが、カタログは更新頻度が低くデモ用途では許容と整理
  (FC-1 で `tx_state` の実値を確認し、フィルタ要否を判断する)。

## 3. 目標アーキテクチャ概要(FC 後の設計 plan-002 への入力)

ユーザー提示のフロー(2026-07-18)を記録する:

### 表示までの流れ
1. `scalardb_analytics` のカタログ情報を読み込み
2. ScalarDB 配下のテーブルは**ダイナミックに Model を生成**。Repository はリフレクションを使った汎用実装
3. **View 定義 Editor(GUI)= 第一の課題**: SQL Server Management Studio 風。
   - テーブル選択は左ペインからドラッグ&ドロップ
   - JOIN 定義も GUI で行う
   - 利用テーブルの各キーは自動登録(除外・変更不可)。その他カラムは選択式で、表示/非表示・更新可否のチェック付き
   - 生成 SQL と Preview の表示(SSMS 類似構成)
4. 更新が必要な場合は**更新モジュール**を更新エディタで作成:
   - 生成された Model を介して対象レコードを更新する際の、簡易な確認を含めた**コントラクトのようなもの**
   - RPA 風フローチャート GUI で構成。最低限のコントロール: 既存レコード読み込み&変数格納、
     値の比較検証(一旦 INT のみ)、分岐、更新、フローチャート上の変数管理
   - デフォルトで、更新対象カラムのあるテーブルの Model 変数と更新コントロールを表示
5. View 定義を `view_definition` テーブル(ScalarDB 配下)に保存。更新モジュールも動的に保存し対応付け
6. 保存後、該当 View のレコードを保存するテーブル(View 実体)を作成し、
   **spark-connect 経由でクエリを実行**、結果を保存
7. REST API: view 名指定 → view_definition から定義抽出 + レコード抽出 → クライアントへ送信
8. クライアントは **View 定義とレコードの2つの JSON** を受け取り、動的に TableView を構成

### 更新時の流れ
1. View 名 + 更新後レコードを JSON で送付
2. view_definition と JSON をマッピングし対象レコードを特定、更新有無を確認
3. **provider_type=`scalardb` → ScalarDB Tx で更新 / それ以外 → RE キュー送付**
4. 受信情報と更新モジュールで更新を実施し、結果をクライアントへ返却

## 4. フィージビリティチェック項目(本計画のスコープ)

ヒアリングで選択された5項目のうち、データパス系の4項目(FC-1〜4)を本計画で扱う。
GUI 生成可能性(旧 FC-5)は別枠 `plan-002-gui-feasibility.md` に分離(2026-07-18)。
各項目は「確認内容 / 手段 / 合格基準」で定義する。

### FC-1: カタログ DB 直読
- 確認: catalogs → registry_data_sources → registry_namespaces → registry_tables → registry_columns の
  階層を辿り、テーブル定義(カラム名・型・PK 相当情報)と provider_type / provider_payload_json が
  設計に十分な粒度で取得できるか
- 手段: psql での構造確認 → Java(JDBC)での読み出しコード
- 合格: 4部名(catalog.datasource.namespace.table)+ カラム一覧 + 型 + キー情報を Java オブジェクトに
  再構成できる。ScalarDB の PK/クラスタリングキーに相当する情報がカタログにあるかも確認
  (**無い場合は write-back の行特定方法を設計で手当てする必要あり — FC の重要確認点**)

### FC-2: spark-connect クエリ実行(Java)
- 確認: Spring Boot(Java 17+)から spark-connect クライアントで sc://192.168.214.129:15002 に接続し、
  単表 SELECT とクロスデータソース JOIN(例: ns_mysql × ns_postgres)を実行、結果セットを取得できるか
- 懸念: `spark-connect-client-jvm` の依存衝突(grpc/guava)と Spark バージョン整合。
  NG 時の代替(別プロセス化、PySpark ブリッジ等)の当たりも付ける
- 合格: JOIN 結果を List<Row> 相当で Java 側に取り込める

### FC-3: ScalarDB Tx 書き戻し疎通
- 確認: `ds_scalardb` の provider_payload_json 内 configs(multi-storage 設定一式)を**そのまま**
  ScalarDB properties として使い、DistributedTransactionManager で対象レコードを更新できるか
  (= 接続情報の二重管理が本当に不要かの実証)
- 手段: configs → Properties 変換 → ScalarDB ライブラリモードで item_stock 等の1レコードを更新し戻す
- 合格: Tx コミット成功 + spark-connect 再クエリで更新値が見える

### FC-4: 動的 Model / 汎用 Repository 試作
- 確認: FC-1 のカタログ情報から Model(列名→型付き値)を動的生成し、リフレクション/メタデータ駆動の
  汎用 Repository で get / scan / put ができるか
- 合格: ハードコード DTO なしで FC-3 と同じ更新が通る

### スコープ外(FC では行わない)
- **GUI の生成可能性検証 — 別枠に分離(2026-07-18 ユーザー指示)**: SSMS 風 View ビルダーと
  RPA 風フローチャートエディタのプロトタイプ検証は `plan-002-gui-feasibility.md` で独立に扱う。
  モックデータのみで進められ、本計画(データパス FC)と依存関係がないため
- RE キュー経路の疎通(新環境での ScalarRE 構成は設計フェーズで扱う)
- View 実体(cache)テーブルの作成・リフレッシュ
- 本実装(GUI 本体、REST API、更新モジュール実行エンジン)

## 5. 進め方・成果物

1. **タスク1**: 旧リポジトリ退避 + 新リポジトリ初期化 — ✅ 完了(2026-07-18)
2. **タスク2**: FC-1 カタログ直読(psql 確認 → Java 読み出し)
3. **タスク3**: FC-2 spark-connect(依存関係の成立確認 → 単表 → JOIN)
4. **タスク4**: FC-3 Tx 書き戻し(payload → properties → 更新)
5. **タスク5**: FC-4 動的 Model/Repository 試作(FC-1/FC-3 の上に構築)
6. **タスク6**: FC レポート `docs/fc-report.md`(項目ごとの結果・制約・設計への影響)を作成し、
   go/no-go と本設計計画の論点を提示

- FC コードは本リポジトリ内に Spring Boot プロジェクトとして作る(本実装の土台に育てる前提。
  Gradle 構成は旧リポジトリから流用)
- ポートは 8082 を踏襲(旧計画と同じ、8080=RE / 8081=demo-cache-writeback と併存)

## 6. リスク・留意点

- **カタログスキーマは内部実装**(SDK 未公開)であり、Analytics のバージョンアップで変わり得る。
  デモとしては許容し、直読部分は1クラスに隔離して将来 SDK に差し替え可能にする
- カタログに ScalarDB の PK/クラスタリングキー情報が無い場合、write-back の行特定に
  別途手当てが必要(FC-1 で判明させる)
- spark-connect の Java クライアントは依存衝突の前例が多い(FC-2 で切り分け)
- provider_payload_json に平文パスワードが入っている。デモ用途と割り切るが、画面表示時はマスクする
