# plan-006: demo-cache-writeback 風サンプルへの置換(ERP = PostgreSQL)

- 作成日: 2026-07-19
- ステータス: **完了(2026-07-19 実装・ゼロから再構築 + E2E スモーク PASS)**。
  結果は §5
- 前提: plan-004 フェーズ2 完了(.130 一括環境)。決定事項(2026-07-19 ヒアリング):
  ERP PG は**カタログ PG に相乗り** / 投入は**環境スクリプト側**(アプリ変更なし)/
  既存サンプル(order / inventory / ns_mysql.item_stock、vw_order2 等)は**新サンプルに置換**

## 1. 題材(demo-cache-writeback から移植)

建設プロジェクト管理。ScalarDB 管理の基幹テーブル + ERP 所有の受発注ステータス。

### ScalarDB 側(Cluster 経由、MySQL バックエンド、namespace `project`)

| テーブル | カラム | シード |
|---|---|---|
| `project` | project_id INT PK / project_name TEXT / status TEXT / budget BIGINT / material_id INT / material_volume BIGINT | 6 件(Bridge Renovation 〜 Rail Yard Upgrade。cache-writeback と同値) |
| `material` | material_id INT PK / material_name TEXT / stock BIGINT / allocated BIGINT | Cement(1200/350)/ Steel(60/50 — 逼迫させて割当拒否デモ用)/ Timber(250/200) |

### ERP 側(PostgreSQL 実テーブル = raw。Analytics に postgres データソースとして取込)

- 置き場所: カタログ PG(`postgresql-scalardb-analytics`、NodePort 30432)に
  **DB `erpdb` + スキーマ `erp`** を追加(カタログ DB `scalardb_analytics` とは DB 単位で分離)
- `erp.project_order`: project_id INT PK / order_no TEXT / order_status TEXT。
  シード 6 件(CONFIRMED / RECEIVED / SHIPPED / RECEIVED / INVOICED / RECEIVED)

### Analytics カタログ登録(変更)

- `ds_scalardb`: 現行どおり(ライブラリモード、MySQL 直結)→ `project` namespace が見える
- `ds_postgres`: **新規**(type=postgresql、erpdb)→ `erp.project_order` が見える
- `ds_mysql`: **廃止**(ns_mysql ごと置換)

### View サンプル(`scripts/local-env/views/` を置換)

- `vw_project_overview`: p=project × m=material × e=erp.project_order の 3 テーブル JOIN
  (cache-writeback の cache テーブル相当を View で実現)
  - via=TX(updatable): project_name / status / budget / material_volume
  - via=RE(updatable): order_status(ERP 所有 → erp.re_inbox へ配送)
- 更新モジュール `vw_project_overview_module`(cache-writeback の割当チェックを移植):
  material_volume 変更時 → material 読込 → `delta = $input - before` →
  `newalloc = allocated + delta` → `newalloc <= stock` 分岐 →
  true: `update m set allocated = newalloc` / false: 中断「在庫不足: 割当超過」
  (Steel の project 2 で +20 すると拒否、Cement は成功、が台本どおり再現)

### ScalarRE 宛先の変更

- `config/scalar-re-config.yml`: dest storage を **postgres(erpdb)** に変更、
  宛先 namespace を `ns_mysql` → **`erp`** に置換(RE init が erp.re_inbox 等を PG に作成)
- アプリ overview のデフォルト `destNamespace=erp` と一致する(現状はパラメータ指定が必要だった)

## 2. 変更ファイル

1. `scripts/local-env/seed/DemoSeed.java` — project / material の作成 + シードに書き換え
   (order / inventory / ns_mysql.item_stock は廃止)
2. `scripts/local-env/erp-seed.sql` — **新規**: erpdb / erp スキーマ / project_order 作成 + シード
3. `scripts/local-env/cli/data_source_postgres.json` — 新規(erpdb)。
   `data_source_mysql.json` は登録対象から外す(ファイルは削除)
4. `scripts/local-env/setup-local-env.sh` — ns_mysql 作成の廃止 / erp-seed.sql 実行(psql)/
   ds_postgres 登録に変更
5. `config/scalar-re-config.yml` — dest を postgres erpdb / namespace erp に変更
6. `scripts/local-env/views/` — vw_project_overview.json + モジュール JSON に置換
   (旧 3 ファイルは削除)
7. ドキュメント: 本計画・`local-env-setup.md`・README のサンプル記述・memory

## 3. 検証(ゼロから再構築で実施)

1. `teardown-local-env.sh` → `setup-local-env.sh`(変更後スクリプトで完走すること自体が検証)
2. アプリ起動 → `POST /api/admin/setup` → view + モジュール登録
3. E2E スモーク:
   - TX: budget / status 編集 → project テーブル反映(MySQL 直読)+ view リロード一致
   - モジュール: material_volume +10(Cement)→ allocated 連動 / Steel +20 → 中断、全 view 行不変
   - 伝播: material 側の変更が同 material を使う全プロジェクト行に同一 Tx 反映
   - RE: order_status 変更 → reEvents=1 → **PG の erp.re_inbox** に配送(psql 直読)、
     overview(デフォルト destNamespace=erp)に表示
4. GUI スポット確認(builder に ds_postgres ツリー、data-edit の TX/RE バッジ)

## 3.5 実装中の設計追加(2026-07-19)

**Cluster の multi-storage 化が必要**: アプリの俯瞰パネル(/api/overview)は宛先 inbox を
**Cluster 経由の ScalarDB scan** で読む(plan-005 発見3 の解消で JDBC 直読を撤去済み)。
ERP(erp.re_inbox)を PG に移すと single-storage(MySQL)の Cluster からは見えなくなるため、
Cluster ノードを multi-storage 構成に変更する:
`storages=mysql,postgres` / `namespace_mapping=erp:postgres` / `default_storage=mysql`
(postgres = erpdb)。これは .129 の実構成と同じパターンで、
「書き込み・監視は Cluster 経由」の規律を保つための正攻法。
RE 側も dest storage(postgres)+ default-storage viewmgr(mysql)で coordinator を
Cluster と共有する — scalar-re-samples の PG 構成で実績あり。

## 4. 留意点

- postgres datasource も**登録時スナップショット** → スクリプトの順序は
  「ERP シード → 登録」を厳守(既存の教訓どおり)
- RE の dest が MySQL → PG に変わるため、RE init(コンテナ)の PG 接続を要確認
  (scalar-re-samples は PG 構成で実績あり)
- 置換後、旧サンプル前提の記述(E2E レポート等)は「当時の記録」としてそのまま残す
  (歴史改変はしない。README とセットアップ手順のみ現行化)

## 5. 検証結果(2026-07-19、teardown → setup のゼロから再構築で実施)

| 検証 | 結果 |
|---|---|
| setup 完走(erpdb シード → multi-storage Cluster → ds_postgres 登録 → RE) | ✅(RE init が erp.re_inbox 等を PG に作成) |
| view 作成(6行)+ モジュール登録 | ✅ シードは cache-writeback と同値 |
| モジュール成功系: Cement volume 100→110 | ✅ `txWrites: 2 / viewRowsUpdated: 5`(volume 1行 + Cement 4行の allocated=360)、MySQL 反映・往復 |
| モジュール中断系: Steel volume 50→70(newalloc 70 > stock 60) | ✅ `aborted: 在庫不足`、volume / allocated とも不変 |
| RE 経路: order_status SHIPPED→DELIVERED→SHIPPED | ✅ reEvents=1×2、**PG の erp.re_inbox に配送(tx_state=3)**、outbox 滞留 0 |
| overview | ✅ **デフォルト destNamespace=erp のまま**表示(パラメータ不要になった) |
| GUI | ✅ 6行 + TX/RE バッジ + 宛先キュー(erp.re_inbox)パネルに配送 2 件(スクショ確認) |

### 実装中の発見・対処

1. **カタログ PG の接続枠枯渇**: erpdb 相乗りで Analytics server + アプリ + RE(pool 50)+
   Spark 並列 JDBC が集中し `remaining connection slots are reserved for SUPERUSER`。
   → PG `max_connections=300`(helm `primary.extendedConfiguration`)+
   RE の `connection-pool-max-total` を 50→10 に削減
   - **追記(同日、ユーザー操作で発覚)**: max_connections 拡大だけでは不足。bitnami の
     デフォルト resourcesPreset はメモリ上限 **192Mi** しかなく、GUI 操作の負荷で
     **PG が OOMKilled**(3 回)→ Analytics server の接続全滅 →
     `Failed to describe table`。→ helm で `primary.resources` を明示
     (requests 256Mi/250m、**limits 1Gi/1cpu**)。再構築後、refresh 連続 3 回 PASS・
     PG restartCount 0 を確認
   - **実測(「300 も要らないのでは」への回答)**: アイドル 77 接続
     (**うち 65 = multi-storage 化した Cluster ノードの postgres プール**、RE 10)、
     refresh 1 回実行中のピーク **147**。デフォルト 100 では refresh 一発で枯渇するため
     300 は妥当。削るなら Cluster 側の `scalar.db.jdbc.connection_pool.*` だが
     デモ用途では不要と判断
2. モジュールは全編集で走るため、material_volume 以外の編集でも delta=0 の
   allocated 同値書きが入る(txWrites / viewRowsUpdated が +1 される)。
   デモ上は無害なので簡潔さを優先(cache-writeback の元モジュールと同じ性質)
