# plan-010: 非 ScalarDB テーブルのキー自動補完(接続情報経由)

- 状態: **完了(実装 + gradle build + 実 DB E2E 全 7 項目 PASS、2026-07-22)**
- 起票: 2026-07-22 / 実装: 2026-07-22 / E2E: 2026-07-22
- E2E レポート: `docs/e2e-report-20260722-plan010.md`(証跡スクショ = `docs/results/plan010-20260722/`)

## 実装結果(2026-07-22)

追加:
- `catalog/KeyResolver`(interface)
- `catalog/JdbcKeyResolver`(JDBC 接続・PK 取得の共通実装。接続失敗/PK 無しは空リストで返す)
- `catalog/PostgresKeyResolver`(supports: postgresql/postgres、information_schema)
- `catalog/MysqlKeyResolver`(supports: mysql、information_schema TABLE_CONSTRAINTS×KEY_COLUMN_USAGE)
- `catalog/TableKeyService`(SDB→接続情報→手動 の 3 段解決。`List<KeyResolver>` 注入)

変更:
- `CatalogService#dataSource(name)` 追加(平文 payload を持つ内部モデルを返す)
- `CatalogController#tableKeys` に `dataSource`(任意)を追加し `TableKeyService` へ委譲。JSON 形状不変
- `view-builder.html` `addTable`: 非 SDB でも常に table-keys を呼び `dataSource=ref.ds` を付与。
  `known=true` で `keysKnown=true`(既存の 269/276 行がキーをロック)
- `build.gradle`: `runtimeOnly 'com.mysql:mysql-connector-j:9.1.0'` 追加

検証: `./gradlew build` PASS(既存 KeyConcatTest 含む)。fat jar に postgresql-42.7.4 +
mysql-connector-j-9.1.0 同梱を確認。**実 DB 接続を伴う PK 取得の動作確認は §7 の E2E で別セッション**。

- 前提メモ: `memory/catalog-key-resolution.md`(backlog 案)、`memory/design-principles.md`、
  `memory/project-status.md`
- 関連コード: `CatalogController#tableKeys`(`/api/catalog/table-keys`)、`CatalogService`、
  `AnalyticsCatalogClient`、`CatalogModel.DataSourceInfo`、`DynamicRepository#metadataOrNull`、
  `src/main/resources/static/view-builder.html`

## 1. 背景・課題

view builder のツリー(データソース→名前空間→テーブル→列)は Analytics カタログから**自動取得**
されるが、**カタログはキー(PK)情報を一切持たない**(`registry_columns` に主キー項目なし)。
現状のキー解決は次の 2 経路:

- **ScalarDB テーブル** → `getTableMetadata()` の partition/clustering key で**自動**
  (`/table-keys` の `known=true`)。
- **非 ScalarDB(raw PG 等)** → 手段が無く `known=false` を返し、**GUI でユーザーが手動指定**。
  view 定義 JSON の `keyColumns` に手書きされ、実 PK との一致は検証されない。

「一覧は自動なのにキーだけ手動」というミスマッチを、**データソースの接続情報**
(`registry_data_sources.provider_payload_json`。非 SDB は host/port/username/password/database/type が
平文で格納済み。アプリは `DataSourceInfo.providerPayload` として取り込み済み)を使って解消する。

## 2. ゴール(今セッション)

- `/table-keys` の非 SDB 分岐を、接続情報から JDBC 接続を張って PK を自動取得する形に改修。
- **PostgreSQL + MySQL** の 2 プロバイダに対応(`KeyResolver` インターフェースで種別切替。
  将来 Analytics サポート DB へ拡張可能な構造にする ← ユーザー方針: 本来は Analytics 対応 DB
  すべて自動化すべきだが現時点はデモ範囲で PG+MySQL)。
- 自動解決できた非 SDB キーは GUI で **SDB と同じくロック**(`known=true` → キートグル不可)。
- **フォールバック維持**: PK が取れない/接続失敗/未対応プロバイダは従来通り `known=false`
  → ユーザー手動指定。手動 `keyColumns` は「PK 無しテーブル用フォールバック」に格下げ。
- **実装 + `gradle build` まで**。完全 E2E(minikube + 全サービス再構築)は環境停止中のため別セッション。

## 3. 非ゴール(今回やらないこと)

- 登録時(`ViewDefinition` 検証)での PK 一致検証・自動補完。取得は builder 時の遅延解決に留める
  (メモ方針「取得は遅延(builder 時)が自然」)。既存の keyColumns 検証はそのまま。
- 複数列 PK の partition/clustering への意味的分割。非 SDB は Cluster 直書きしない(RE 経路)ため、
  PK 列の並び(ordinal 順)を **すべて partitionKeys** として返す(clusteringKeys は空)。
  builder は `keyColumns = pk + ck` で復元するので順序さえ保てれば十分。
- 接続情報のパスワード秘匿化。payload は平文のまま(デモ範囲、メモ留意事項)。
- Oracle 等 PG/MySQL 以外のプロバイダ実装。

## 4. 設計

### 4.1 エンドポイント

`GET /api/catalog/table-keys` に **`dataSource` パラメータを追加**(現状は namespace + table のみ)。
非 SDB 分岐で providerType / providerPayload を引くのに必要。builder の drop `ref` は `ds` を持つので
フロントから渡せる。`dataSource` は省略可(未指定なら従来通り SDB のみ判定)。

解決ロジック(新設 `TableKeyService` に集約):

1. `DynamicRepository#metadataOrNull(ns, table)` を試す。非 null(= ScalarDB 管理)なら
   `known=true` + partition/clustering key(既存挙動)。
2. null かつ `dataSource` 指定あり → `CatalogService` で `DataSourceInfo` を名前で引き、
   `providerType` に一致する `KeyResolver` を選び、`providerPayload` から JDBC 接続して PK 取得。
   - 取得成功(1 列以上)→ `known=true`, `partitionKeys = PK 列(ordinal 順)`, `clusteringKeys = []`。
   - 取得 0 件 / 接続例外 / 一致する resolver 無し → `known=false`(ログに理由を残す)。
3. それ以外(dataSource 未指定で SDB でもない)→ `known=false`。

例外は握りつぶして `known=false` にフォールバック(builder を止めない)。

### 4.2 新規クラス

- `catalog/KeyResolver`(interface):
  ```java
  boolean supports(String providerType);
  /** 主キー列を ordinal 順で返す。取得できなければ空リスト。*/
  List<String> primaryKeyColumns(JsonNode payload, String schema, String table);
  ```
- `catalog/PostgresKeyResolver`(`@Component`, supports "postgresql"):
  `jdbc:postgresql://{host}:{port}/{database}` に接続し
  `information_schema.table_constraints × key_column_usage` の PRIMARY KEY を
  `ordinal_position` 順で取得。schema = 名前空間 displayName。
- `catalog/MysqlKeyResolver`(`@Component`, supports "mysql"):
  `jdbc:mysql://{host}:{port}/{database}` に接続し
  `information_schema.TABLE_CONSTRAINTS × KEY_COLUMN_USAGE` の PRIMARY KEY を
  `ORDINAL_POSITION` 順で取得。TABLE_SCHEMA = database(= 名前空間)。
- `catalog/TableKeyService`(`@Service`): 上記解決ロジック。`List<KeyResolver>` を Spring 注入し、
  `supports` で選択。`DynamicRepository` と `CatalogService` に依存。
  戻り値は record `TableKeys(boolean known, List<String> partitionKeys, List<String> clusteringKeys)`。

### 4.3 既存クラスの変更

- `CatalogService`: `DataSourceInfo dataSource(String name)` を追加(内部キャッシュ = 平文 payload
  を保持。`tree()` のマスクは通さない)。見つからなければ null。
- `CatalogController#tableKeys`: `@RequestParam(required=false) String dataSource` を追加し、
  ロジックを `TableKeyService` に委譲。レスポンス形状(`known/partitionKeys/clusteringKeys`)は不変。
- `view-builder.html` `addTable`: `ref.scalardb` 条件を外し、**常に** `/table-keys` を呼ぶ
  (`dataSource=ref.ds` も付与)。`known=true` なら `keysKnown=true` でロック(既存の
  269/276 行がそのまま効く)。`known=false` は従来のフォールバック(preset 復元 or 先頭列既定)。

### 4.4 依存追加

- `build.gradle` に MySQL JDBC ドライバ `runtimeOnly 'com.mysql:mysql-connector-j:9.1.0'` を追加
  (PG ドライバ 42.7.4 は既存)。バージョンは既存 scalardb と衝突しないものを確認して確定。

## 5. 影響範囲・互換性

- レスポンス JSON 形状は不変。SDB テーブルの挙動は完全に不変(先に metadataOrNull を見る)。
- 非 SDB で自動取得できなかった場合は現行と同じ手動フロー。既存 view 定義(`keyColumns` 手書き)は
  影響なし。登録経路(`ViewDefinition` 検証)は変更なし。
- builder が全テーブルで table-keys を叩くようになる(呼び出し回数増)。遅延解決なので許容。

## 6. テスト・検証(今回の範囲)

- `gradle build`(コンパイル + 既存 `KeyConcatTest`)が通ること。
- 可能なら `TableKeyService` / resolver の軽い単体(payload → JDBC URL 組み立て、
  resolver 選択ロジック)。実 DB 接続を伴う PK 取得は環境依存のため単体では省略。
- **完全 E2E は別セッション**: minikube + Cluster + Analytics + カタログ PG + アプリを立て、
  builder で `ds_postgres`/`erp.project_order` を drop → キーが 🔑 でロック表示されることを確認
  (手順は本ドキュメント §7 に控える)。

## 7. E2E 手順控え(次セッション用)

1. `scripts/local-env/setup-local-env.sh` で環境再構築 → RE 起動(ops-lessons 参照)。
2. アプリ setup → `scripts/local-env/register-views.sh` で view/module 登録。
3. view-builder を開き、`ds_postgres` の `erp.project_order` をキャンバスに drop。
4. **期待**: `project_id` が 🔑 でロック表示(known=true・クリック不可)。手動指定 UI が出ない。
5. MySQL データソース(`ds_scalardb` 配下の raw ではなく、非 SDB として登録した MySQL がある場合)でも
   同様にロック表示されることを確認。無ければ PG のみで可。

## 8. 未確定・留意

- 複数列 PK テーブルはデモに無い見込みだが、ordinal 順を保持する実装にしておく。
- `providerType` の実値表記("postgresql"/"postgres"/"mysql")を実データで確認し、
  `supports` は小文字化 + 別名(postgres/postgresql)を吸収する。
- payload のキー名(host/port/username/password/database)が実カタログと一致するか、
  環境起動時に 1 度実測して resolver のパスを確定(メモの実測値: PG は上記名で確認済み)。
