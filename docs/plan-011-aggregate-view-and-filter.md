# plan-011: 集計 view(read-only)+ カラムフィルタ(静的 WHERE)

- 状態: **完了(実装 + gradle build + 実 DB E2E 全 9 項目 PASS、2026-07-22)**
- 起票: 2026-07-22 / 実装・E2E: 2026-07-22
- E2E レポート: `docs/e2e-report-20260722-plan011.md`(証跡 `docs/results/plan011-20260722/`)
- 実装ファイル: `meta/ViewDefinition`(filters / ColumnDef.aggregate / isAggregate / validate 分岐)、
  `view/SqlGenerator`(WHERE / GROUP BY / 集計関数・演算子許可リスト・型別リテラル化)、
  `view/ViewService`(`aggregateEntityMetadata` / insertRows `_group_pk` / sortRows 修正)、
  `engine/UpdateEngine`(集計 400)、`api/ViewController`(module 400)、
  `static/view-builder.html`(集計ドロップダウン + フィルタタブ)、`static/data-edit.html`(read-only)
- 前提メモ: `memory/agg-view-propagation-design.md`(集計=参照専用/complete refresh の位置づけ)、
  `memory/design-principles.md`(view の責務分離)、`memory/project-status.md`
- 関連コード: `SqlGenerator`、`ViewService`(entityMetadata / insertRows / create / refresh /
  validate 経路)、`meta/ViewDefinition`、`engine/UpdateEngine`、`static/view-builder.html`、
  `static/data-edit.html`

## 1. 目的

backlog 筆頭の **集計 view(GROUP BY + 集計関数)** を追加し、あわせて **カラムのフィルタリング
条件(静的 WHERE)** を全 view で指定できるようにする。

## 2. ユーザー確定事項(2026-07-22 Q&A)

- **集計 view = 更新対象外(read-only)**。Oracle の集計 MV と同様、write-back の対象にしない。
- **集計関数は Spark で使えるものを広く並べる**(SUM/COUNT/AVG/MIN/MAX 等)。
  → 任意関数を許すため増分維持は不可能 = **Spark で全件計算する完全再構築**が必然。read-only と整合。
- **リフレッシュ契機は今回オンデマンドのみ**(作成時 + refresh ボタン)。ベース編集で自動再計算はしない。
  - ユーザー将来案(今回スコープ外・別途検討): 「類似 view 複数時の伝播は、各テーブルに更新フラグを
    持たせ定期的に該当 view が更新する軽量キュー方式(RE 不要)」→ [[agg-view-propagation-design]] に記録。
- **WHERE = 定義時の静的フィルタ、全 view 共通**(集計・非集計どちらでも)。集計では GROUP BY 前の絞り込み。
- **WHERE 入力 = 列ごとの条件行(列 / 演算子 / 値)**、AND 結合。
- **集計列と GROUP BY 列の指定 = 列ごとに役割を選ぶ**(各出力列に「集計なし(=GROUP BY キー)/ SUM /
  COUNT / AVG / MIN / MAX / ...」のドロップダウン)。

## 3. スコープ

### 3.1 カラムフィルタ(静的 WHERE)— 全 view 共通

- `ViewDefinition` に `filters: List<FilterCond>` を追加。`FilterCond{ source, operator, value, values }`。
  - `source` = "alias.column"(集計前のソース列を指す)。
  - `operator` = 許可リスト: `=, <>, >, >=, <, <=, LIKE, IN, IS NULL, IS NOT NULL`。
  - `value`(単値)/ `values`(IN 用リスト)。IS NULL / IS NOT NULL は値なし。
- `SqlGenerator`: JOIN 句の後・GROUP BY / ORDER BY の前に `WHERE <cond AND cond ...>` を挿入。
  - 値のリテラル化はサーバ側で型に応じて実施(数値は無引用、文字列は単一引用符エスケープ)。
    **operator は許可リスト検証、source は定義内のテーブル/列に実在検証**(SQL インジェクション対策。
    GUI が送る sql 文字列は従来通り信用せずサーバ再生成)。
- builder UI: 新規「フィルタ」タブ(既存の 列マッピング / JOIN / SQL / 実行結果 の並びに追加)。
  条件行(列ドロップダウン=ソース列 / 演算子ドロップダウン / 値入力)を追加・削除。
- 非集計の更新可能 view でも WHERE は使える(部分集合を編集対象にするだけで一対一は保たれる)。
  フィルタ条件を外れる編集をした行は次 refresh で実体から消える(read の写像なので仕様。UI に注記)。

### 3.2 集計 view(read-only)

- `ViewDefinition.ColumnDef` に `aggregate: String` を追加。
  - null/空 = **GROUP BY キー列**(グループ軸)。非空 = 集計関数名(`SUM`/`COUNT`/`AVG`/`MIN`/`MAX`/...)。
  - `COUNT` は `COUNT(*)` 相当も許す(source 空/`*`)。他は `FUNC(source)`。
- 「集計 view」判定 = いずれかの列が非空 `aggregate` を持つ。
- `SqlGenerator`(集計分岐): 
  - SELECT: 役割=キー列は `source AS viewcol`、集計列は `FUNC(source) AS viewcol`。
  - `WHERE`(3.1)→ `GROUP BY <キー列の source ...>` → `ORDER BY`(既存)。
  - 関数名も許可リストで検証(Spark 集計関数の別名表を持つ。最低 SUM/COUNT/AVG/MIN/MAX、拡張容易に)。
- **read-only 化**: 集計 view は全列 `updatable=false` 強制。lookup 不可。更新モジュール登録不可。
  `UpdateEngine.update` は集計 view に対し 400。data-edit は全セル grey。
- **実体キー(既存と別構造)**: 集計 view の 1 行 = 1 グループ。既存の `<alias>_pk`(ソース行 1:1)は
  使わず、内部 PK 列 `_group_pk`(TEXT)= GROUP BY キー列値の KeyConcat を単一パーティションキーにする。
  secondary index は張らない(IVM 逆引き不要 = write-back しないため)。
  - GROUP BY 列ゼロ(総計 1 行)も許可し `_group_pk="*"` 固定。
- `ViewService`:
  - `entityMetadata`: 集計 view 分岐 → 出力列 + `_group_pk`(PK)。
  - `insertRows`: 集計 view 分岐 → `_group_pk` を GROUP BY 列で組む(`<alias>_pk` は作らない)。
  - `validate`(ViewDefinition): 集計 view はテーブル別 `keyColumns` 必須・`isKey` 必須の検査を
    スキップし、代わりに「集計列が 1 つ以上」「キー列(=GROUP BY)と集計列以外が無い」を検査。
    非集計 view の検証は現状維持。
  - create / replace / refresh の Spark 実行〜投入経路は既存を再利用(sql_text を回す)。

## 4. 非ゴール(今回やらないこと)

- 集計 view の増分維持・ベース更新時の自動再計算(オンデマンド refresh のみ)。
- HAVING(集計後フィルタ)。今回は WHERE(集計前)のみ。
- 更新フラグ + 定期リフレッシュによる view 間伝播(ユーザー将来案。別 plan)。
- 集計 view 上の view-on-view、集計 view への lookup、集計 view の write-back。

## 5. 影響・互換性

- `ViewDefinition` に任意フィールド追加(`filters` / `ColumnDef.aggregate`)。`@JsonIgnoreProperties`
  で既存 JSON は影響なし。既存の非集計 view は分岐に入らず挙動不変。
- 既存 view 定義 JSON(register-views.sh)はそのまま動く。
- SqlGenerator の WHERE/GROUP BY は集計/フィルタ指定時のみ付加。

## 6. 実装順(1 plan・2 部)

1. **カラムフィルタ(WHERE)** — SqlGenerator + ViewDefinition.filters + builder フィルタタブ。
   非集計 view で先に動作確認(部分集合マテリアライズ)。
2. **集計 view** — ColumnDef.aggregate + SqlGenerator 集計分岐 + ViewService 実体キー分岐 +
   validate 分岐 + read-only(UpdateEngine/ data-edit)+ builder 役割ドロップダウン。
3. `gradle build` → 実 DB E2E(§7)。

## 7. E2E(実 DB)

- 環境: 起動中の .130(minikube + アプリ :8082 + Spark :15002)。
- フィルタ:
  1. 既存の非集計 view に WHERE(例: status = 'ACTIVE' 相当)を付けて refresh → 絞り込み行のみ実体化。
  2. 更新可能 view + フィルタで、対象行の編集が write-back される(一対一維持)。
- 集計 view(新規サンプル):
  3. project を material_id で GROUP BY、SUM(material_volume) + COUNT(*) の集計 view を作成 →
     グループごとの合計/件数が出る。
  4. WHERE + GROUP BY 併用(例: budget > N のプロジェクトだけ集計)。
  5. read-only 確認: data-edit で全セル grey、更新 API が 400、更新モジュール登録不可。
  6. ベース編集 → 集計 view は自動では変わらず、refresh で再計算されることを確認(仕様通り)。
- 証跡: `docs/e2e-report-20260722-plan011.md` + `docs/results/plan011-.../`(builder スクショ含む)。

## 8. 確定した細部(2026-07-22)

- 集計関数の初期ラインナップ = **SUM/COUNT/AVG/MIN/MAX**(拡張は SqlGenerator.AGG_FUNCTIONS に追加)。
- WHERE 値は列 kind(INT/TEXT/...)から型自動判定 + IN はカンマ区切り。
- **集計/フィルタのデモ view を常設化(ユーザー決定)**: `scripts/local-env/views/vw_material_summary.json`
  (集計: material_id GROUP BY, SUM(material_volume), COUNT)+ `vw_project_m1.json`
  (フィルタ+更新可: material_id=1 で絞った project)。register-views.sh が自動収集(追加コード不要)。
- data-edit の read-only 表示は保存ボタン非表示 + バナー。
