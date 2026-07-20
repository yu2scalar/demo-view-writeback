# plan-007: 人事・勤怠サンプルの追加(2 つ目の view、既存サンプルと共存)

- 作成日: 2026-07-20
- ステータス: **完了(2026-07-20 実装・ゼロから再構築 + E2E 全 12 項目 PASS)**。
  結果 = `e2e-report-20260720-plan007.md`(V5 検証を受けた設計変更
  = department を view から外す + keyed update 拡張、はユーザー承認済み)
- 前提: plan-006 完了(建設プロジェクト管理サンプル、ERP=PG raw、multi-storage Cluster)
- 決定事項(2026-07-20 ヒアリング):
  - 題材 = **人事・勤怠**(従業員 + 部署 = ScalarDB / 給与ステータス = 外部給与システム所有)
  - 既存の建設プロジェクトサンプルと**共存**(置換しない。setup・シードは追加式)
  - 差別化要素 = **①複数テーブル更新モジュール ②多段分岐モジュール ③RE 宛先の追加(2 つ目)**
  - 集計 view / view-on-view / レコード追加(INSERT)は今回スコープ外 → §6 backlog

## 1. 題材設計

### ScalarDB 側(Cluster 経由、MySQL バックエンド、namespace `hr` を新設)

| テーブル | カラム | シード |
|---|---|---|
| `employee` | emp_id INT PK / emp_name TEXT / dept_id INT / overtime_hours BIGINT / alert_level BIGINT | 6 件(残業時間はデモ用に 10〜44h で分散、alert_level=0) |
| `department` | dept_id INT PK / dept_name TEXT / capacity BIGINT / headcount BIGINT | 設計部(5/3)/ 施工部(4/2)/ 管理部(**2/2 — 満員。異動拒否デモ用**) |

### 外部システム側(PostgreSQL raw。erpdb に**スキーマ `payroll` を新設**)

- 置き場所: カタログ PG の既存 DB `erpdb` に相乗り(スキーマ単位で erp と分離)
- `payroll.emp_payroll`: emp_id INT PK / payroll_no TEXT / payroll_status TEXT。
  シード 6 件(CALCULATED / PENDING の混在)

### Analytics カタログ登録(変更)

- `ds_scalardb`: `hr` namespace を見せるため **delete --cascade → 再 register**
  (登録時スナップショットのため。順序は「hr シード → 再登録」厳守)
- `ds_postgres`: `payroll` スキーマを見せるため同じく **delete --cascade → 再 register**
  (順序は「payroll シード → 再登録」)

### View `vw_employee_overview`(scripts/local-env/views/ に追加)

**2026-07-20 V5 検証(§4)を受けて 2 テーブル構成に変更(ユーザー承認済み)**:
department は view に**参加させない**(JOIN キー変更問題の回避 + 同一テーブル 2 行更新は
エンジン拡張の keyed update で行う)。

- e=hr.employee × pay=payroll.emp_payroll の 2 テーブル JOIN(e.emp_id=pay.emp_id、INNER)
- via=TX(updatable): emp_name / **dept_id(JOIN キーではないため普通の更新可列)** /
  overtime_hours
- via=RE(updatable): payroll_status(給与システム所有 → **payroll.re_inbox** へ配送)
- 表示専用: alert_level / payroll_no
- 部署の headcount 変化を GUI で見せるため、単一テーブルの参照 view
  **`vw_department`**(d=hr.department のみ、全列 updatable=false)も追加する。
  異動後に refresh して ±1 を見せる(単一テーブル view のサンプルにもなる)

## 2. 差別化要素の実現方法

### ①' エンジン小拡張: update ノードの key 指定(2026-07-20 V5 対応、ユーザー承認済み)

現行の update ノードは view 参加 alias の AfterModel(1 alias = 1 行、キー = before 値
固定)にしか書けず、「同一テーブルの別の 2 行(旧部署/新部署)」を更新できない。
以下を `UpdateEngine` に追加する:

- update ノードに**任意パラメータ `key`**(read ノードと同じ `col=式, ...` 形式)を追加
- key 指定ありの update は AfterModel を経由せず、**keyed update** としてコンテキストに
  蓄積し、commit 時の一括適用で `repo.update(ns, table, key, set)` を直接実行
  (同一 Tx 内。対象は `ns.table` 形式で **view 非参加テーブルも可**)
- タイポ防止として対象テーブルの ScalarDB メタデータで列存在チェック
- **view 参加テーブルへの keyed update は伝播(propagation)対象外**のため、
  本デモでは view 非参加テーブル(hr.department)にのみ使う(制約として文書化)
- GUI(update-module.html)の update ノードに key 入力欄を追加

### ① 複数テーブル更新(部署異動)+ ② 多段分岐 — 1 つのモジュールに直列で実装

モジュールは view につき 1 つ(`update_module` は viewName キー)のため、
`vw_employee_overview_module` に 2 つのチェック連鎖を直列に持たせる。
employee.dept_id 自体の更新は入力差分としてエンジンが自動適用(ノード不要):

```
start
 → var deptDelta = $input.dept_id - before.dept_id
 → compare deptDelta != 0 → branch
    ├ true:  read nd (hr.department, dept_id=$input.dept_id)
    │        read od (hr.department, dept_id=before.dept_id)
    │        var newHead = nd.headcount + 1
    │        compare newHead <= nd.capacity → branch
    │           ├ true:  update hr.department key dept_id=$input.dept_id
    │           │            set headcount = newHead              ← keyed update(新部署)
    │           │        update hr.department key dept_id=before.dept_id
    │           │            set headcount = od.headcount - 1     ← keyed update(旧部署)
    │           │        → (残業チェックへ合流)
    │           └ false: abort「定員超過: 異動先に空きがありません」
    └ false: (残業チェックへ合流)
 → compare $input.overtime_hours > 80 → branch                     ← 多段分岐 1 段目
    ├ true:  abort「残業上限(80h)超過: 申請が必要です」
    └ false: compare $input.overtime_hours > 45 → branch           ← 多段分岐 2 段目
       ├ true:  update e set alert_level = 1 → commit
       └ false: update e set alert_level = 0 → commit
```

- 台本: 施工部→管理部(満員)への異動 = **abort、両部署の headcount 不変**。
  設計部への異動 = 成功、**旧部署 -1 / 新部署 +1 が同一 Tx**(cache-writeback の
  「資材の付け替え」相当を人事題材で)。headcount の確認は vw_department の
  refresh(GUI)+ MySQL 直読(E2E)
- 残業時間 46 → alert_level=1 に自動更新 / 81 → abort(2 段の閾値分岐)
- alert_level を BIGINT にしたのは現行エンジンの制約のため(var/update の式は
  数値演算のみ。TEXT リテラル代入は未対応 — §4 検証 V3)

### ③ RE 宛先の追加(2 つ目)

- `config/scalar-re-config.yml`: `namespaces:` に **`payroll`**(storage: dest =
  既存の erpdb PG)を追加。HMAC は dest 用キーを流用
- RE init(`--create-schema`)再実行で `payroll.re_inbox` 等を PG に作成
  (`--recreate-schema` は使わない — キュー消去)
- **Cluster の namespace_mapping に `payroll:postgres` を追加**(setup スクリプトの
  helm values)。俯瞰パネルが Cluster 経由 scan で payroll.re_inbox を読むために必須
- 結果: erp(既存サンプル)と payroll(新サンプル)の **2 宛先へ view ごとに配送し分け**

## 3. 変更ファイル

1. `src/main/java/com/example/viewwb/engine/UpdateEngine.java` +
   `ExecutionContext.java` — keyed update 対応(§2 ①')
2. `src/main/resources/static/update-module.html` — update ノードに key 入力欄
3. `scripts/local-env/seed/DemoSeed.java` — hr.employee / hr.department の作成 + シードを
   **追加**(project / material は現状維持)
4. `scripts/local-env/erp-seed.sql` — payroll スキーマ + emp_payroll 作成 + シードを**追加**
5. `scripts/local-env/setup-local-env.sh` — Cluster values に payroll:postgres 追加 /
   ds_scalardb・ds_postgres の再登録順序を維持(シード → 登録)
6. `config/scalar-re-config.yml` — namespaces に payroll を追加
7. `scripts/local-env/views/` — `vw_employee_overview.json` +
   `vw_employee_overview_module.json` + `vw_department.json` を新規追加
8. ドキュメント: 本計画・`local-env-setup.md`・README のサンプル節・memory

## 4. 実装前検証の結果(2026-07-20 実施。コード根拠付き)

- V1 ✅ read の key 式は `evalExpr → ExecutionContext.resolve` を通るため
  `before.dept_id` を解決できる(UpdateEngine.execRead / ExecutionContext.resolve)
- V2 ✅ ノードは id 参照でつながるため branch の true/false 両出力を同一ノードに
  合流させられる(UpdateEngine.runFlow の next 配列)
- V3 ✅ SET 式の整数リテラル可(resolve が `-?\d+` を Long にパース)。
  **TEXT リテラルは不可** → alert_level は BIGINT で設計(計画どおり)
- V4 ✅ data-edit は「選択中 view の非 scalardb テーブルの namespace」を宛先に使う
  (data-edit.html destNamespace())。API も `?destNamespace=` 対応済み → 無改修で OK
- **V5 ❌(新発見)**: update ノードは view 参加 alias の AfterModel のみ・キーは
  before 値固定(ExecutionContext.AfterModel / UpdateEngine.execUpdate)→ 同一テーブル
  2 行更新は不可。さらに dept_id を JOIN キーにすると変更時に view 実体行の部署側列が
  再結合されない(既存サンプルも material_id を updatable=false で回避)。
  **対応(ユーザー承認 2026-07-20): department を view から外す + keyed update 拡張(§2 ①')**

## 5. 検証(E2E)

1. `teardown-local-env.sh` → `setup-local-env.sh`(ゼロから再構築で完走)
2. アプリ起動 → `POST /api/admin/setup` → 両 view + 両モジュール登録
3. 新サンプル E2E:
   - TX: emp_name 編集 → hr.employee 反映(MySQL 直読)+ view リロード一致
   - 複数テーブル更新: 施工部→設計部異動 → 両部署 headcount が同一 Tx で ±1
     (MySQL 直読 + vw_department refresh)/ →管理部(満員)は abort、全テーブル不変
   - 多段分岐: overtime 44→46 → alert_level=1 / →81 → abort
   - RE: payroll_status 変更 → reEvents=1 → **PG の payroll.re_inbox** に配送(psql 直読)
   - keyed update 拡張の回帰: 既存モジュール(key 無し update)が従来どおり動くこと
4. 既存サンプル回帰: vw_project_overview の TX 編集 / Cement +10 / Steel +20 abort /
   order_status → erp.re_inbox 配送が引き続き動くこと
5. GUI スポット確認(builder に hr ツリー + payroll ツリー、2 view の一覧表示、
   data-edit の TX/RE バッジと宛先キューパネル)

## 6. スコープ外 → backlog(2026-07-20 のユーザー発言より。本サンプル追加後に着手)

ユーザー承認済みの方針(2026-07-20)を含めて記録する:

- **レコード追加(INSERT)**: 部品は存在(`DynamicRepository.insert` は
  マテリアライズで使用中)だが、API(POST rows)・GUI(行追加)・モジュールの
  insert ノードが未実装。JOIN view への行追加は「どのソーステーブルに何を挿入するか」
  (RE 所有テーブルへの挿入イベント化を含む)の設計が必要
  - **UI 方針(ユーザー指定)**: View エディター上部に「追加 VIEW」チェックボックスを
    配置し、この情報がある view のみ data-edit に入力行(行追加 UI)を出す
- **view-on-view(View を ViewEditor のソースに使う)**: view 実体は ScalarDB の
  `views` namespace にマテリアライズされるため、ds_scalardb に載せれば参照自体は可能。
  ただし書き戻しのリネージが 2 段になる(view2 の編集が view1 実体に書かれ、
  真のソースに届かない)ため、更新可能にするには設計が必要
  - **制約方針(ユーザー指定)**: View をエディターのソースに選択した場合は、
    行追加(前項)および view への追加、ならびに集計機能(次項を実装した場合)は
    **NG(併用不可)**とする
- **集計 view(GROUP BY / SUM)**: 「クエリの作成時に必要になりそう」— エンジン拡張
  (SqlGenerator + 更新可能 view との整合設計)が必要。
  **ユーザー評価: 集計は重要な機能 → backlog 筆頭**
