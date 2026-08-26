# plan-009: RE 承認往復 — 非同期 write-back の確定フロー

- 作成日: 2026-07-20
- ステータス: **完了(2026-07-20 実装 + E2E PASS。結果 = §6、発見 = §7)**
  (2026-07-20 承認。既許可種のオペレーションは自律進行して良い、との指示あり)
- 発端(2026-07-20 ユーザー指示、優先実装): 現状の RE 経路は「受け付けた値で view を
  即確定」する楽観的一方通行で問題がある。外部システムの承認(許可/却下)往復で
  view を確定させる本来の非同期パターンに改める
- 決定事項(同日ヒアリング): 外部システムコンソールは**別ページ** /
  view 側の確定反映は**自動ポーリング** / UPDATE REQUESTED 中の再編集は**ブロック**

## 1. フロー全体

```
[data-edit] RE 列編集(例 payroll_status → PAID)
   │ 同一 Tx: view 実体の RE 列 = "UPDATE REQUESTED" + viewmgr.re_outbox へリクエスト
   ▼
[ScalarRE] viewmgr.re_outbox → payroll.re_inbox(既存の往路)
   ▼
[外部システムコンソール(新ページ)] Namespace 選択 → inbox 一覧
   ├ 許可: 生テーブル(emp_payroll)を直接 JDBC で UPDATE
   │        + payroll.re_outbox へ ViewWritebackResolved(SUCCEEDED)
   └ 却下: payroll.re_outbox へ ViewWritebackResolved(REJECTED)のみ
   ▼
[ScalarRE] payroll.re_outbox → viewmgr.re_inbox(新設の復路。config 変更)
   ▼
[アプリ内コンシューマ(@Scheduled 2秒)] viewmgr.re_inbox を処理
   ├ SUCCEEDED → view 行の RE 列 = After(requested_changes)
   └ REJECTED  → view 行の RE 列 = Before
   (data-edit は既存ポーリングで自動的に画面反映)
```

## 2. 設計詳細

### 2.1 往路の変更(UpdateEngine)

- RE 列(via=RE)の編集時、view 実体へ書く値を受付値でなく **`"UPDATE REQUESTED"`** に
  する(propagation も同値)。外部生テーブルは従来どおり未変更
- **制約: この方式は RE 列が TEXT の場合のみ**(現サンプルは order_status /
  payroll_status とも TEXT)。非 TEXT の RE 列編集は 400 で拒否(文書化)
- **二重編集ブロック**: 対象 RE 列の before 値が "UPDATE REQUESTED" のとき 400
  「承認待ちのため編集できません」
- **payload 拡張(不足情報の追加)**: 既存の source / view_key / requested_changes /
  before に加え、外部側が生テーブルを更新するための
  - `dest_table`: "payroll.emp_payroll"(view 定義の RE alias から導出)
  - `dest_key`: {emp_id: 1}(ソース列名ベース)
  - `dest_changes`: {payroll_status: "PAID"}(ソース列名ベース)

### 2.2 復路イベント

- event_type **`ViewWritebackResolved`**、routing destination = **`viewmgr`**
- payload: `result`(SUCCEEDED / REJECTED)+ 往路 payload のエコー
  (source / view_key / requested_changes / before)
- 外部側 outbox(erp.re_outbox / payroll.re_outbox — RE init 作成済みの ScalarDB
  テーブル)へ Cluster 経由 ScalarDB Tx で INSERT(event_id は UUIDv7)

### 2.3 外部システムコンソール(新ページ + API)

- `static/external-system.html`(ホームからボタンで別タブ)。Namespace 選択
  (erp / payroll)→ inbox 一覧(未処理/処理済み切替)→ 行ごとに 許可 / 却下
- API(ExternalSystemController):
  - `GET  /api/external/{ns}/inbox` — Cluster scan で一覧
  - `POST /api/external/{ns}/inbox/{eventId}/approve` —
    (1) 生テーブルを**直接 JDBC** で UPDATE(dest_table / dest_key / dest_changes)。
    外部システムは自分の DB を直接書く、の模擬として意味的に正しい経路。
    (2) 同 ns の re_outbox へ Resolved(SUCCEEDED)INSERT + inbox 行 status=1 を
    **同一 Cluster Tx** で実行。(1) と (2) は非原子(模擬のため許容、文書化)
  - `POST /api/external/{ns}/inbox/{eventId}/reject` — Resolved(REJECTED)+ status=2
- 直接 JDBC 用に erpdb への DataSource を application.properties に追加

### 2.4 view 側コンシューマ(新 InboxApplyService)

- `@Scheduled(fixedDelay=2000)` + `@EnableScheduling`
- viewmgr.re_inbox の未処理(status=0)ViewWritebackResolved を Cluster Tx で処理:
  - SUCCEEDED → view 実体行(view_key)の RE 列 = requested_changes 値
  - REJECTED → before 値
  - 同ソース行を写す他 view 行へは既存の pk 逆引き伝播を流用
  - inbox 行 status=1 を同 Tx で更新(exactly-once 適用)

### 2.5 RE config(復路の有効化)

- `config/scalar-re-config.yml`: erp / payroll namespaces に polling
  (viewmgr と同値)+ event-types `ViewWritebackResolved`(delivery-type: atomic)を追加
- RE コンテナ再起動で反映(--create-schema 不要。キューは保持)

### 2.6 GUI

- data-edit: 値が "UPDATE REQUESTED" の RE セルを承認待ちスタイル(斜体・琥珀色)で表示。
  編集ブロックは API の 400 メッセージ表示で対応
- index.html に「外部システムコンソール」ボタン

## 3. 変更ファイル

1. `engine/UpdateEngine.java` — RE 分岐(REQUESTED 化・二重編集ブロック)
2. `service/ReEventService.java` — payload 拡張 + Resolved イベント発行の汎用化
3. `service/InboxApplyService.java` — **新規**(自動ポーリング適用)
4. `api/ExternalSystemController.java` + `service/ExternalSystemService.java` — **新規**
5. `config/`(Java)— erpdb 直接 JDBC の DataSource / `DemoViewWritebackApplication` に
   `@EnableScheduling`
6. `static/external-system.html` — **新規** / `index.html` / `data-edit.html`
7. `config/scalar-re-config.yml` — erp / payroll の polling + event-types
8. `application.properties`(jar 内デフォルト + 必要なら env)— erpdb JDBC URL
9. ドキュメント: 本計画・README・local-env-setup.md・memory

## 4. E2E(実装後)

1. RE コンテナ再起動(config 反映)+ アプリ再ビルド・再起動
2. 申請: payroll_status → PAID 編集 → view 行 = UPDATE REQUESTED /
   **emp_payroll 未変更(PG 直読)** / payroll.re_inbox にリクエスト到着
3. 二重編集: 同列を再編集 → 400「承認待ち」
4. 承認: コンソールで許可 → emp_payroll = PAID(PG 直読)→ 復路配送 →
   **view 行が自動で PAID に確定**(data-edit ポーリングで無操作反映)
5. 却下: 別行で申請 → 却下 → view 行が **Before 値に自動復帰**、生テーブル不変
6. erp 側(order_status)でも承認 1 本(2 宛先の両方で復路が動くこと)
7. 回帰: TX 編集・部署異動(keyed update)・残業多段分岐・lookup ドロップダウン

## 5. 留意点

- 生テーブル更新(直接 JDBC)と Resolved イベント(ScalarDB Tx)の非原子性は
  模擬の割り切り(実システムでは外部側の Tx/outbox で担保する想定)を README に明記
- 復路の適用は view 実体のみ(元テーブルは外部所有なので触らない)
- インボックスの「未処理」判定に status 列を流用(0=未処理 / 1=許可 / 2=却下)。
  RE の InboxRecoveryScanner が status を触らないことは E2E で確認

## 6. E2E 結果(2026-07-20 実施)

| # | 検証 | 結果 |
|---|---|---|
| 1 | 申請: payroll_status 編集 → view = UPDATE REQUESTED / 生テーブル未変更 / payload に dest_table・dest_key・requested_changes・before | ✅ |
| 2 | 二重編集 → 400「承認待ちのため編集できません」 | ✅ |
| 3 | 承認サイクル(伊藤 PENDING→PAID): コンソール許可 → 生テーブル PAID(直接 JDBC)→ 復路配送 → **view 自動確定 PAID**(無操作) | ✅ 許可〜確定 約 2〜4 秒 |
| 4 | 却下サイクル(高橋 PENDING→PAID 申請 → 却下): view が **Before(PENDING)に自動復帰**、生テーブル不変 | ✅ |
| 5 | erp 側(order_status CONFIRMED→SHIPPED 承認)— 2 宛先両方で復路動作 | ✅ |
| 6 | GUI: data-edit の承認待ちセル(琥珀色 ⏳)/ 外部コンソール(namespace 選択・変更内容・許可/却下)/ **コンソールのボタン操作でも往復完走** | ✅ スクショ確認 |
| 7 | 回帰: TX 編集 / 部署異動(keyed update)/ 残業 abort / lookup | ✅(ただし §7-2 の事象を発見) |

## 7. 実装中・E2E 中の発見

1. **【対処済み】RE 0.9.1: event-type 名は全 namespace で一意にする必要がある**。
   `ViewWritebackResolved` を erp / payroll 両 namespace に定義したところ、
   ライフサイクル管理(名前キー)が衝突し **2 つとも paused のまま resume されず**、
   outbox をスキャンはするが配送されない(エラーも出ない)。
   → event-type を `ViewWritebackResolved_<namespace>` に分離して解消
   (config とコード両方。起動ログの「EventType resumed」で 3 種を確認)
2. **【未解決・要調査】連続実行した更新モジュール Tx のステイル読み疑い**:
   回帰テストで異動 → 即逆異動(間隔 〜100ms)を行うと、2 本目の read ノードが
   1 本目のコミット済み headcount ではなく**コミット前の値を読んだ**結果になった
   (期待 2/2/2 → 実際 1/3/2。coordinator・tx メタデータから逆算)。
   人手のデモ速度では発生しない見込みだが、Cluster の可視性/分離レベルの調査が必要
3. **【未解決・要調査】emp1(佐藤)の dept_id が意図しない Tx で 2 になる事象が再発**:
   09:48 の事象(当時は複製 view 経由のユーザー編集と推定)と同じ署名で、
   ユーザー不在・view2 不存在の状況で再発した(v2 の書き込み元は before イメージ消失で
   特定不能)。**09:48 の複製 view 起因という推定も再検証が必要**(plan-008 §6 に注記)。
   次回、監査手段(binlog or トリガー or 全 Tx ログ)を仕込んで再現調査を推奨。
   **§7-2 / §7-3 の再現コマンド・監査の仕込み方 = `repro-20260720-open-issues.md`
   (2026-07-20 ユーザー指示で作成)**
