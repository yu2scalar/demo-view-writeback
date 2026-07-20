# plan-005: 一対多 View の同一 Tx 伝播(IVM)対応

- 日付: 2026-07-19
- 前提: `design-note-mv-maintenance.md` の決定欄(2026-07-19)に基づく実装計画
- ステータス: **T0〜T6 完了(2026-07-19 実装・E2E 済)** →
  結果は `e2e-report-20260719-plan005.md`(発見3件: DB-CORE-10106 の2段構え対処 /
  DDL 直後の metadata cache / tx_state index テーブルの Cluster scan 不可と JDBC 直読対処)
- 関連: plan-004 フェーズ2(.130 Minikube 構築)は本計画の後続

## 決定事項の要約(design-note より)

1. View 実体に参加テーブルごとの `<alias>_pk` カラム(非表示)を追加。
   値 = キー列値の正規文字列化 → 各成分 Base64URL → `.` 連結
2. View の PK = `<alias>_pk` 群の複合キー。本来のキーカラムは可視の通常列に降格
3. **クラスタリング側の `<alias>_pk` にのみ** SecondaryIndex(2026-07-19 実機検証済み。
   パーティションキー列に index を張ると主キー Get が DB-CORE-10003 で壊れるため —
   詳細は design-note 決定欄)。パーティション側の逆引きはパーティション scan
4. 更新伝播: view 実体側を「編集行のみ」→「touched alias ごとに逆引き
   (パーティション alias = partition scan / クラスタリング alias = indexKey scan)→
   ヒット全行を同一 Tx 更新」に拡張。ソース書き戻しは現行どおりテーブル単位
5. クライアントは更新成功後に view リロード
6. 実行形態は現行 UpdateEngine(インタプリタ)の拡張。codegen 転換なし
7. **接続は .129 Cluster 経由に切替**(T0。2026-07-19 ユーザー指示 + Cluster 側
   namespace mapping 修正済みで viewmgr/views/order/inventory とも Cluster から可視・移行不要)

## タスク(T0 を追加)

### T0. ScalarDB 接続の Cluster 切替(.129)

- build.gradle: `com.scalar-labs:scalardb-cluster-java-client-sdk:3.18.0` に切替
  (probe で .129 Cluster への接続・scan・insert・indexKey scan まで動作確認済み)
- 接続情報: **アプリはローカル properties(demo-cache-writeback と同形式:
  `scalar.db.transaction_manager=cluster` / `scalar.db.contact_points=indirect:192.168.214.129` /
  `scalar.db.cluster.auth.enabled=true` / admin/admin)で接続**。
  カタログ ds_scalardb(Spark 用ライブラリモード configs)は現状維持 —
  cluster 登録への変更は Analytics server 側の cluster 対応検証とセットで .130 フェーズ2で扱う
- `ScalarDbConfig` を catalog payload 由来 → properties ファイル由来に変更
  (`CatalogService.scalarDbProperties()` は不要化。カタログ読取り機能自体は SQL 生成用に残す)
- 起動 + 既存機能のスモーク(view 一覧・データ編集・リフレッシュ)

### T1. キーエンコーダ(core)

- `KeyConcat`(仮)ユーティリティ新設: `encode(List<Object> keyValues) -> String`
  - 正規文字列化: INT/BIGINT = 10進、TEXT = そのまま、他型は ValueCodec と整合させる
  - 各成分 UTF-8 → Base64URL(パディングなし)→ `.` join
- 単体で往復(encode の単射性)を確認するテスト or 検証コード

### T2. View 定義・実体スキーマ(ViewDefinition / ViewService)

- `ViewDefinition` に `<alias>_pk` 相当の内部キー列を導出する仕組みを追加
  (definition_json 互換に注意: 既存 view は再作成で移行)
- `entityMetadata()`: PK = `<alias>_pk` 群(テーブル配置順、先頭 = パーティションキー)、
  **クラスタリング側の `<alias>_pk` にのみ** SecondaryIndex、本来のキーカラムは通常列化
- SecondaryIndex 作成経路の確認: `TableMetadata.Builder.addSecondaryIndex` で
  クラスタリングキー列に張れるか(sct と同じ構造になるか)を実機検証 ★残リスク
  (Cluster 経由の既存 index の**読み書き**は検証済み。**作成**経路のみ未検証)
- マテリアライズ(`insertRows`): spark 行から `<alias>_pk` を計算して投入

### T3. 伝播更新(UpdateEngine / DynamicRepository)

- `DynamicRepository.scanByIndex(tx, ns, table, column, value)` 新設
  (`Scan.newBuilder().indexKey(...)`。where 併用は今回不要)
- `UpdateEngine.apply()`: touched alias ごとに `<alias>_pk` = encode(当該テーブルキーの
  before 値) で view 実体を逆引き(パーティション alias = `scanPartition` /
  クラスタリング alias = `scanByIndex`)し、ヒット全行へ当該 alias 由来の viewChanges を
  PK 指定 update(同一 Tx)
- ~~キー列への `indexKey()` スキャンの実機検証~~ → **2026-07-19 検証済み**:
  クラスタリング列 indexKey ✅ / パーティション列 indexKey ✅(ただし
  パーティション列に index があると主キー Get が DB-CORE-10003 ❌ → 設計側で回避済み)
- 非 ScalarDB 宛先(re_inbox 経由)は現行の最終的整合のまま(スコープ外と明記)

### T4. GUI / API

- ビルダー・データ編集画面で `<alias>_pk` を非表示(定義上 hidden 扱い)
- データ編集: 更新成功後、編集行差し替えではなく view 全体を再取得(リロード)
- 更新モジュール実行後のレスポンスに伝播行数を含めてトースト等で見せる(デモ効果)

### T5. E2E 検証

- vw_order2 シナリオ: 受注数変更 → 在庫更新 → **同じ商品の他 view 行の在庫も
  同一 Tx 後に反映されている**ことを確認(従来の stale が解消)
- 既存 E2E 12 項目のリグレッション(view 再作成を含む)
- 検証結果は `e2e-report-*.md` に追記

### T6. 記録

- 本計画のステータス更新、design-note との相互参照確認
- memory(project-status)更新

## リスク・確認事項

- ★ クラスタリングキー列への SecondaryIndex を **admin API(TableMetadata 経由)で
  作成できるか**は未確認(sct は既存実物だが作成経路が不明)。不可の場合は
  raw SQL で index を張る等の代替を検討し、結果を design-note に追記する
- ~~キー列 `indexKey()` スキャンの動作~~ → 2026-07-19 に .129 Cluster 実機で検証済み
  (結果と回避策は design-note 決定欄)
- 既存 view(vw_order 系)は PK 構造が変わるため**削除 → 再作成**が必要
- 式言語は INT 中心(evalExpr)のため、デモのキーは INT 前提で問題ないが
  KeyConcat 自体は TEXT キーにも対応させておく
- Cluster 切替後、AdminSetupService の DDL(createNamespace / createTable)が
  Cluster 経由でも同様に動くか(T0 スモークで確認)

## 2026-07-19 実機検証の記録(.129 Cluster)

- Cluster 到達性: `indirect:192.168.214.129`(auth 有効、admin/admin)で接続成功
- Cluster の mysql ストレージ = .129 MySQL 実体(ns_mysql.item / stock_item のデータ一致で確認)
- Cluster 側 `default_storage=postgres` が原因で viewmgr/views/order/inventory が不可視だったが、
  **ユーザーによる namespace mapping 修正 + Cluster 再起動後、4 namespace とも可視**
  (view_def / order / product の実データ読取り、views 配下の全 view 実体一覧まで確認)
- sct(pk/ck/string_value に index)での動作確認:
  Insert ✅ / Delete ✅ / indexKey(pk) 2hits ✅ / indexKey(ck) 1hit ✅ / partition scan ✅ /
  主キー Get(pk+ck) ❌ DB-CORE-10003(パーティションキー列 index の副作用。
  `ScalarDbUtils.isSecondaryIndexSpecified` の後方互換ロジック、5.0.0 削除予定)

## スコープ外

- 行の増減・外部起因変更への追従(B: 部分 refresh の自動化、C: 非同期伝播)
- plan-004 フェーズ2(.130 Minikube 構築、接続形態 (b) = ds_scalardb を Cluster 指しで
  カタログ登録)— 本計画完了後に着手
- 初回 git commit(ユーザー指示待ちのまま)

## 追加対応(2026-07-19、ユーザー要望)

- **View Builder に「実行結果」タブを追加**(SQL タブの隣)。「▶ 実行」で現在の定義から
  生成した SQL を spark-connect で実行し、結果をグリッド表示(LIMIT 100、保存はしない)
  - API: `POST /api/views/preview`(`ViewService.preview` — validate → selectSql → spark.query)
  - GUI: `view-builder.html` にタブ + `runPreview()`。playwright で実機確認済み
    (vw_order2 編集モードで 7 行表示)
- **View の複製 = ビルダーの「別名で保存」**(テスト用ベース View の微修正が目的)。
  編集モードの View 名ロックを外し、名前が変わっていたら PUT でなく POST(新規作成)に
  切替(クライアントのみの変更、新 API なし)。**定義のみ複製**(更新モジュールは
  コピーしない — ユーザー指定)。元 View は無変更。playwright で
  vw_order2 → vw_order2_copy の複製(7行マテリアライズ、hasModule=false)を確認後、削除済み

## backlog(実装完了時点の残課題)

- view 再作成直後のマテリアライズ失敗(Cluster metadata cache 約60秒)への
  リトライ内蔵(現状は refresh 再実行で回避)
- ~~DB-CORE-10281~~ → **解消済み(2026-07-19)**: ユーザーが .129 ノードを 3.18 に更新、
  プローブ検証 OK、overview の JDBC 直読を ScalarDB scan に復元済み
  (経緯は e2e-report-20260719-plan005.md 発見3)
- GUI E2E スクリプトの結果待機を「メッセージ変化」ベースに改善
  (今回 revert クリック後の待機が旧メッセージに誤マッチ)
