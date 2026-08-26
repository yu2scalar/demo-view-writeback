# plan-008: 選択値(ルックアップ)列 — MS Access 風の参照表示

- 作成日: 2026-07-20
- ステータス: **完了(2026-07-20 実装 + E2E 全 6 項目 PASS。結果 = §6)**
  (2026-07-20 承認。既存 vw_employee_overview はそのまま生かし dept_id に
  lookup を追加するだけ、との確認あり)
- 発端(2026-07-20 ユーザー設計判断): 表示目的で外部キー先を JOIN すると
  JOIN キー保守(行再構成・伝播)で更新ロジックが複雑化しバグの温床になる。
  **更新可能な view は「1 行 = 各ソース 1 行(一対一)」で表現できる範囲に留める**のが
  設計ルール。外部キーの「名前を見せたい/選ばせたい」は結合ではなく
  **列ごとの選択値(ルックアップ)設定**で解決する(Access のルックアップフィールド相当)

## 1. 方式

### view 定義の拡張(クエリ・更新経路は無変更)

`ViewDefinition.ColumnDef` に任意フィールド `lookup` を追加:

```json
{"viewColumn": "dept_id", "source": "e.dept_id", "kind": "INT", "updatable": true,
 "via": "TX",
 "lookup": {"namespace": "hr", "table": "department",
            "keyColumn": "dept_id", "labelColumn": "dept_name"}}
```

- view の SELECT SQL・マテリアライズ・更新 Tx・モジュールには**一切影響しない**
  (lookup はクライアント表示と入力補助のためのメタデータ)
- 旧定義(lookup なし)はそのまま有効(フィールド欠落 = null)

### ルックアップソースの範囲(ユーザー確認済み 2026-07-20)

**Cluster 経由で都度読める範囲** = ScalarDB 管理テーブル + **view 実体(views.*)**。

- ds_postgres の raw テーブル(erp.project_order 等)は ScalarDB メタデータが無く
  Cluster で読めない(Spark 経由は都度数秒かかり選択肢用途に不向き)→ 直接は不可。
  **必要なら raw テーブルを view 化し、その view をソースに指定する**(view 実体は
  ScalarDB テーブルなので同一機構で読める)
- 参照は都度読み(キャッシュしない)。選択肢の上限は 1000 行(超過時は打ち切り + 警告)

### API

- `GET /api/views/{viewName}/lookups` — その view の lookup 付き列すべての選択肢を
  一括返却: `{dept_id: [{value: 1, label: "設計部"}, ...]}`。
  Cluster 経由 `scanAll` → keyColumn/labelColumn を射影
- 列存在チェック(ソーステーブルのメタデータに keyColumn / labelColumn があること)は
  view 登録時(validate)に実施

### GUI

- **data-edit**: lookup 付き列は `<select>` ドロップダウン(表示 = ラベル、値 = キー)。
  行ロード時に /lookups を取得し、現在値を selected に。保存は従来どおり PUT
  (送る値はキー値なので更新経路・モジュールは無変更で動く)
- **view-builder**: 列マッピング表に「選択値」設定欄を追加 —
  ソース選択(ds_scalardb 配下のテーブル + 登録済み view の一覧)→
  キー列・表示列のプルダウン。ds_postgres の raw テーブルは選択不可
  (ガイダンス「view 化して指定してください」を表示)

## 2. デモ適用

- `vw_employee_overview` の `dept_id` に lookup(hr.department / dept_id / dept_name)を設定
  → data-edit で「設計部/施工部/管理部」のドロップダウンになり、
  **選ぶだけで異動 → 既存モジュール(定員チェック・keyed update)がそのまま発火**
- 部署名の表示問題(plan-007 で department を view から外した代償)がこれで解消。
  capacity / headcount の俯瞰は引き続き vw_department
- 既存 vw_project_overview は無変更(material_id は JOIN キーのため updatable=false の
  まま。lookup は将来必要になったら別途)

## 3. 変更ファイル

1. `meta/ViewDefinition.java` — ColumnDef に `lookup`(record LookupDef)+ validate
2. `api/ViewController.java` + `view/ViewService.java`(または LookupService)—
   GET /{viewName}/lookups
3. `static/data-edit.html` — lookup 列の select 化
4. `static/view-builder.html` — 選択値設定 UI
5. `scripts/local-env/views/vw_employee_overview.json` — dept_id に lookup 追加
6. ドキュメント: 本計画・README・local-env-setup.md・memory

## 4. 検証(E2E)

1. jar 再ビルド → アプリ再起動(実行中 jar の上書き禁止ルール遵守)→
   vw_employee_overview を PUT で再登録(lookup 付き定義)
2. GET /lookups が 3 部署の value/label を返す
3. data-edit: dept_id がドロップダウン表示(現在値 selected)。
   ドロップダウンで異動(成功系 + 満員 abort 系)→ モジュール動作は plan-007 と同一
4. lookup なしの列・既存 view(vw_project_overview / vw_department)の表示回帰
5. builder: 選択値設定 UI で lookup を設定した定義 JSON が生成される(スポット)
6. バリデーション: 存在しない labelColumn を指定した登録が 400

## 5. スコープ外(backlog へ)

- 選択肢のキャッシュ・検索(インクリメンタルフィルタ)
- raw PG テーブルの直接ソース化(Spark 経由)— view 化で代替できるため不急
- 一対多更新(keyed update)の伝播設計は引き続き検討課題(2026-07-20 ユーザー発言:
  「一対多更新はもう少し検討が必要」)
- **複製 view のモジュール非引継ぎ(§6 発見 (a)、2026-07-20 backlog 入りユーザー承認)**:
  builder の複製作成でモジュール(業務ルール)が付いてこず、検証素通りの編集経路が
  できる。対策候補: 複製時にモジュールもコピー / モジュール無し view の updatable 編集に
  警告 / view 単位でなくソーステーブル単位のルール定義
- **view 間伝播(§6 発見 (b)、同上)**: 同一ソーステーブルを写す複数 view の実体間で
  更新が伝播しない(伝播は編集した view 内のみ)。IVM デモとして本質的な課題。
  対策候補: 伝播時に「同じ ns.table をソースに持つ全 view」を view_def から逆引きして
  各実体へ書く(scan 先行の 2 段構えは既存機構を流用)

## 6. 検証結果(2026-07-20 実施)

| # | 検証 | 結果 |
|---|---|---|
| 1 | jar 再ビルド(アプリ停止後)→ 再起動 → lookup 付き定義で PUT 再登録 | ✅ rows=6 |
| 2 | GET /lookups | ✅ dept_id: 設計部/施工部/管理部(value 1/2/3) |
| 3 | ドロップダウン相当の PUT で異動(高橋 設計部→施工部)+ 満員 abort(田中→管理部) | ✅ txWrites=3・headcount 同一 Tx ±1 / abort・不変 — モジュール連動は無変更で動作 |
| 4 | 表示回帰: vw_project_overview(6 行)/ vw_department(3 行)/ lookup なし view の /lookups | ✅ 影響なし({} を返却) |
| 5 | builder: 編集モードで選択値欄に「hr.department → dept_name」表示 + ダイアログで再設定可(スクショ) | ✅ |
| 6 | バリデーション: 存在しない labelColumn → 400「lookup ソース hr.department に列 'no_such_column' がありません」 | ✅ |

GUI スクショ: data-edit の dept_id が部署名ドロップダウン表示(全 6 行)、
builder の列マッピングに選択値列 + 設定ダイアログ。

### E2E 中の発見(lookup とは無関係、別途報告)

view 実体の dept_id 値調査から、**09:47-48 に GUI 操作(セッション外)で
`vw_employee_overview2` が複製作成され、モジュール無しで佐藤の dept_id が編集された**
ことを特定(coordinator / tx メタデータで裏付け)。
**【2026-07-20 同日追記・要再検証】plan-009 の E2E 中に、ユーザー不在・view2 不存在の
状況で「emp1 の dept_id=2 が意図しない Tx で書かれる」同じ署名の事象が再発した
(plan-009 §7-3)。09:48 の事象を複製 view 経由と断定した本推定も再検証が必要。**
view2 複製が実在した事実(09:47 作成)は tx 記録どおり。これにより
(a) 複製 view にモジュールが引き継がれない = 検証素通りの編集経路、
(b) view 間伝播が無い(同一ソースを写す複数 view の実体が食い違う)、
という 2 つの設計課題が顕在化。department の headcount と実所属の不整合が現存する。
