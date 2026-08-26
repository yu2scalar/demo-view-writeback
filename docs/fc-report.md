# FC レポート: Analytics カタログ前提アーキテクチャのフィージビリティチェック結果

- 実施日: 2026-07-18
- 対象計画: `plan-001-feasibility-check.md`
- 結論: **全4項目 PASS — go**。本設計(plan-003 想定)に進める

## 結果サマリ

| 項目 | 結果 | 所要 | 備考 |
|---|---|---|---|
| FC-1 カタログ DB 直読 | **PASS** | 3,503 カラムを約1.2秒 | ただしキー情報はカタログに無い(下記) |
| FC-2 spark-connect(Java) | **PASS** | JOIN 1.5秒 | 調整2点のみで Spring Boot と同居可 |
| FC-3 ScalarDB Tx 書き戻し | **PASS** | — | カタログ由来の configs だけで接続・更新成功 |
| FC-4 動的 Model/汎用 Repository | **PASS** | — | DTO レス get/scan/update 成立 |

実行方法: `./gradlew fc1` / `fc2` / `fc3` / `fc4`(コード: `src/main/java/com/example/viewwb/fc/`)

## 環境の訂正(plan-001 §2 からの変更)

- **カタログ DB は 192.168.214.129:5432 にある**(計画時の想定 .130 は誤り。
  .130 のローカル postgres コンテナ4つを起動して確認したが該当 DB なし → 元どおり停止済み)。
  接続: `jdbc:postgresql://192.168.214.129:5432/scalardb_analytics`、scalaradmin/scalaradmin
- Spark は **3.5.6 standalone** @ .129(master :7077、Master UI :8080、worker UI :8081、
  spark-connect :15002、実行中 app の UI :4040)
- 新環境はカタログ・Spark・バックエンド実データすべて **.129 に集約**されている

## FC-1: カタログ DB 直読

`scalardb_analytics.scalardb_analytics` スキーマの5テーブルを JDBC で直読し、
catalog → data_source → namespace → table → column の階層を Java オブジェクトに再構成できた
(1 catalog `scalardb_catalog` / 2 datasource / 47 namespace / 307 table / 3,503 column、約1.2秒)。

判明した構造:

- `registry_columns.type` は `{"kind":"INT"}` 形式の JSON。kind は ScalarDB 型
  (INT/BIGINT/TEXT/BOOLEAN/DOUBLE/FLOAT/BLOB/DATE/TIME/TIMESTAMP/TIMESTAMPTZ の11種を確認)
- `registry_namespaces.names` は JSON 配列(多段 namespace 対応、例 `["ns_mysql"]`)
- 同名 namespace が複数 datasource に存在し得る(ns_mysql は ds_scalardb 配下=論理3列と
  ds_mysql 配下=生14列(tx_* 込み)の両方に登録)。**(namespace_id, name) はデータソース内で一意**
- カタログテーブル自体が Consensus Commit 管理(tx_state / before_* 列)。実装では
  tx_state=3(COMMITTED)以外の行は before イメージへフォールバック(`AnalyticsCatalogClient`)
- `provider_type`(scalardb / mysql)と `provider_payload_json`(接続情報一式)が取得でき、
  **scalardb か否かの分岐(Tx 更新 vs RE キュー)に十分**

**制約(設計へ引き継ぎ)**: カタログには**パーティションキー/クラスタリングキー情報が存在しない**。
→ provider_type=scalardb は `getTableMetadata()` で補完(FC-3 で実証)。
非 scalardb バックエンドは別途手当てが必要(バックエンド DB の information_schema 参照、
または RE 経路のみに限定する等 — 本設計の論点)。

## FC-2: spark-connect クエリ実行(Java)

`spark-connect-client-jvm_2.12:3.5.6`(サーバーと同版)で接続し、単表 SELECT と
**ストレージ横断 JOIN**(ds_scalardb 経由の ns_mysql × ns_postgres)が `collect()` で取得できた。

必要だった調整(いずれも既知の定番、本実装にも適用する):

1. **log4j binding の除外**: Spring Boot の `log4j-to-slf4j` と Spark の `log4j-slf4j2-impl` が
   共存不可 → spark 依存から `log4j-slf4j2-impl` を exclude(logback に一本化)
2. **JVM オプション**: Arrow が JDK17 のモジュール制限に掛かる →
   `--add-opens=java.base/java.nio=ALL-UNNAMED`(bootRun / 本番起動スクリプトにも必要)
3. (`commons-lang3` の明示追加 — scalardb の推移依存が解決されない問題への対処)

計測: 単表 1.4秒、2表 JOIN 1.5秒(コールドでこの程度。デモのマテリアライズ用途に十分)

## FC-3: ScalarDB Tx 書き戻し疎通

- `ds_scalardb` の `provider_payload_json.configs`(18 エントリの multi-storage 設定)を
  **そのまま Properties にして `TransactionFactory.create()` に渡すだけ**で接続できた。
  → **接続情報の二重管理は不要**(カタログが接続情報のマスター)という見立てを実証
- `getTableMetadata("ns_mysql","item_stock")` で partitionKey=[id]、clusteringKey=[item_group_id] を取得。
  **カタログに無いキー情報はこの経路で補完できる**
- Tx 更新(qty 123→124)→ **spark-connect 再クエリで更新値が見える**ことを確認 → 123 に復元済み
- 注意: Get/Put にはクラスタリングキーの指定が必須(パーティションキーだけでは DB-CORE-10021)

## FC-4: 動的 Model / 汎用 Repository

`core/DynamicRecord`(列名→値)+ `core/DynamicRepository`(TableMetadata 駆動で
get / scanAll / update)により、**テーブル固有の DTO・Repository 実装なし**で
ns_mysql.item_stock の読み書きが成立(更新→復元まで確認)。
キー構築・型変換はすべて `TableMetadata.getColumnDataType()` ベースの switch で動的に処理。
M2/M3(更新モジュール実行エンジン)もこの層の上に載せられる感触。

## 本設計への論点(持ち越し)

1. **非 scalardb バックエンドのキー情報**: カタログに無い。候補: (a) バックエンド DB の
   information_schema/SHOW KEYS を payload の接続情報で直接引く、(b) View 定義時にユーザーが指定、
   (c) 非 scalardb は RE キュー経路のみ(行特定は RE 側の責務)とする
2. **View 実体テーブルの置き場所**: 新環境に「アプリ管理用の ScalarDB namespace」をどこに作るか
   (view_definition テーブル、View 実体、re_outbox。ds_scalardb の multi-storage 定義に載せる形)
3. **RE キュー経路**: 新環境(.129 系)に ScalarRE をどう構成するか(FC スコープ外として未検証)
4. **カタログ直読の隔離**: 内部スキーマ依存は `AnalyticsCatalogClient` 1クラスに閉じた。
   SDK 公開時はここだけ差し替え
5. **spark-connect の同時実行**: FC では都度 SparkSession 作成。本実装では共有/プールを設計
6. GUI 検証は別枠 plan-002(未着手)

## 残課題・環境メモ

- ds_scalardb の configs には dynamo(fake endpoint)も含まれるが、ns_mysql/ns_postgres への
  操作には影響しなかった。dynamo 配下のテーブルを触る場合は要注意
- provider_payload_json は平文パスワードを含む。GUI 表示時はマスク必須
- Spark UI(:8080)は Master UI。ローカル .130 の ScalarRE(:8080)とはホストが違うだけで
  ポート番号が同じなので混同注意
