# Plan 004: 導線見直し + .130 ローカル環境構築

- 作成日: 2026-07-18
- ステータス: フェーズ1 **完了**(2026-07-18。レビュー→採否→実装→検証まで。
  結果は `ux-review-20260718.md` 実施結果欄)。フェーズ2 **完了**(2026-07-19。
  詳細計画・構成・結果 = `local-env-setup.md`、E2E = `e2e-report-20260719-local-env.md`。
  未決事項は下記のとおり決着: 接続形態 (b) は Analytics Server 側の cluster SDK
  未同梱により**不可と実証** → ライブラリモード維持 + 運用規律で対処)
- 前提: plan-003 完了(本実装 + E2E 全12項目 PASS)。§5.5 の残作業のうち
  「機能の導線見直し」「.130 環境構築」の2件を本計画で扱う。
  初回 git commit は**保留のまま**(ユーザー選択 2026-07-18)。

## 決定事項(2026-07-18 ヒアリング)

| 論点 | 決定 |
|---|---|
| 順序 | **導線見直し → 環境構築**。機能を完成させてから環境を固める。デモシード設計も完成後の機能に合わせる |
| 導線見直しの進め方 | **レビュー→提案方式**: 4画面を実際に一巡操作して問題点を洗い出し、改善提案一覧を提示 → ユーザーが採否を決めてから実装 |
| 環境構築の形態 | **Minikube 上**(ScalarDB Cluster との関係)。公式資料: <https://scalardb.scalar-labs.com/docs/latest/scalardb-analytics/deployment-local> |
| Analytics ライセンス | ユーザー手元に情報あり(構築フェーズ開始時に共有してもらう) |

## フェーズ1: 導線見直し

plan-003 §5.5-1 の候補(View 一覧管理画面 / 作成→モジュール→編集の誘導 /
エラーメッセージ整理)は**未確定** — レビュー結果を見て採否を決める。

1. **レビュー**: アプリを .129 向けに起動し、4画面(index / view-builder /
   update-module / data-edit)を実ユーザー動線で一巡操作
   - 観点: 画面間遷移の欠落・行き止まり、初見で迷う操作、エラー時の文言と回復手段、
     状態表示(refreshed_at、モジュール有無等)の過不足、View のライフサイクル管理
     (一覧・削除・編集の入口)
2. **提案**: `docs/ux-review-20260718.md` に問題点と改善提案の一覧
   (優先度・工数感つき)を作成しユーザーに提示
3. **承認された項目のみ実装** → 動作確認(該当画面の再操作)

### フェーズ1 確定スコープ(2026-07-18 ユーザー決定。詳細は ux-review-20260718.md 採否欄)

実装順: **E → A → B → C → D** → ブラウザ再巡回 + 対象機能の E2E で検証。

- **E. バグ修正(最優先)**: リフレッシュ失敗でデータ消失(truncate 先行)/
  Error 系が ApiResponse にならず GUI 無反応 / `java -jar` に --add-opens が付かない
- **A. Home メニュー化(ユーザー提示の階層)**: Admin(新規 View、View 一覧=
  編集・更新モジュール・削除)/ User(View 一覧 → データ編集)。
  ビルダーに保存 + リフレッシュを配置、データ編集からリフレッシュ撤去
- **B. 行き止まり解消**: 未選択時 disabled、view 未指定/不在時のガード + Home リンク
- **C. エラー文言**: 頻出サーバーエラーの日本語化、builder の alert をインライン化
- **D. 磨き**: favicon / ja-JP 日時 / モジュール有無表示 / 確認ダイアログ文言

## フェーズ2: .130(localhost)一括構築

導線見直し完了後に着手。着手時に詳細タスクを詰める(本計画では枠のみ)。

1. 公式 deployment-local 資料の精読 + ユーザー保有のライセンス情報の受領
2. 構成設計: Minikube 上の Analytics Server + ScalarDB Cluster との関係、
   Spark(spark-connect)、カタログ DB、バックエンド DB(MySQL/PG)、
   ScalarRE、本アプリ(:8082)の配置と接続
3. デモ用データソース設計(ERP らしいテーブル + シード。plan-003 タスク7 の
   デモシナリオ文書と整合させる)
4. 一括構築手順の自動化(スクリプト/manifest)と、ゼロから構築→E2E 再実行での検証

### 未決事項(2026-07-19 に調整予定。2026-07-18 の議論より)

- **ScalarDB 接続形態**: 現状はカタログ ds_scalardb の configs 流用による
  **ライブラリモード直結**(viewmgr/views は .129 MySQL 上の ScalarDB 管理
  namespace として存在。**Cluster からは見えない** — 2026-07-18 に実機確認、
  scalardb.metadata 登録・tx 列付きで設計 plan-003 案A どおり)。
  論点: (a) ライブラリモード維持 / (b) .130 では ds_scalardb を Cluster 指しで
  カタログ登録し cluster client 接続に切替(Claude 推奨 = (b) を .130 標準、
  .129 は FC 環境として現状維持)。根っこは**カタログへの datasource 登録内容**
- **一対多 View の stale 伝播(IVM)**: 選択肢 A〜E を
  `design-note-mv-maintenance.md` に整理済み・未決
  (デモ向け推奨 = A: パーティション内限定の同期伝播 + D: stale 注記)

### 設定ファイル方針(2026-07-19 決定、フェーズ2で適用)

現状の接続設定は 3 系統(いずれも properties 指定):

| 接続先 | 現在の場所 | キー |
|---|---|---|
| Analytics カタログ DB(PG 直読) | `src/main/resources/application.properties`(jar 内) | `app.catalog.jdbc-url / username / password` |
| Spark(spark-connect) | 同上(jar 内) | `app.spark.remote` |
| ScalarDB Cluster | `config/scalardb.properties`(jar 外) | `scalar.db.*`(plan-005 T0 で導入) |

**方針**: 環境依存の接続設定は**すべて実行ディレクトリの `config/` 配下に外出し**して統一する。
Spring Boot は `config/application.properties` を自動で読み jar 内の値を上書きするため、
コード変更は不要 — フェーズ2(.130)では `config/` に .130 向けの
`application.properties`(カタログ DB / Spark)と `scalardb.properties`(Cluster)を置くだけで
切り替わる。jar 内の application.properties の値は「.129 向けデフォルト」として残す。
一括構築手順(`local-env-setup.md` 予定)にはこの方式で記載する。

### アーキテクチャ上の考慮(2026-07-19 追記): Analytics と Cluster の二重接続経路

.129 の Cluster 切替検証(plan-005 T0)で顕在化した、フェーズ2以降も引きずる構造問題:

1. **同一データへの公式な二重経路**: Cluster 経由(認証・認可・メタデータ解決の門番あり)と
   Analytics のバックエンド直結(門番なし)が並立。経路によってセキュリティの意味論が変わる
   (スコープ外にしている「カタログアクセス経路のセキュリティ」の正体)
2. **topology の二重管理**: Cluster の `namespace_mapping` とカタログ ds_scalardb payload が
   同じ物理配置を別々に記述。乖離しても検出手段がない(2026-07-19 の
   「default_storage=postgres で viewmgr 不可視」が実例)
3. **datasource と Cluster の対応関係が無名**: カタログには複数 Cluster 配下の DB を
   datasource として登録できるが、管轄 Cluster はどこにも表現されない。
   別 Cluster 管轄テーブルを JOIN した View も定義でき、その書き戻しの Tx 境界・権限は未定義

デモとしての規律: **書き込みは必ず「View の参加テーブルを管轄する単一 Cluster」経由。
Analytics 直結は read-only 専用。** フェーズ2で「ds_scalardb を cluster configs で
カタログ登録できるか(Analytics server の cluster client 対応)」を検証し、
可能なら 2. の二重管理を Cluster 一元に畳む。

**検証結果(2026-07-19、フェーズ2 T4)**: **不可**。Analytics Server 3.18.0 は
datasource 登録時に自前で schema 解決を行い、公式イメージに
scalardb-cluster-java-client-sdk が同梱されていないため
`DB-CORE-10066: Transaction manager 'cluster' is not found` で拒否される。
→ ライブラリモード登録を継続(.130 は single-storage なので 2. の namespace_mapping
二重管理は消えたが、「Cluster topology とカタログ登録が別管理」という構造自体は残る)。
上記の規律で運用し、製品側が cluster 対応した時点で再検証する。
詳細 = `local-env-setup.md` §4.5。

## スコープ外(plan-003 から継続)

- 初回 git commit(ユーザー指示待ちのまま)
- カタログアクセス経路のセキュリティ(ユーザー別途調査中)
- デモシナリオ文書はフェーズ2-3 と合わせて作成

## 記録方針

- フェーズ1 のレビュー結果・提案・採否は `docs/ux-review-20260718.md` に記録
- フェーズ2 の構成設計・手順は `docs/local-env-setup.md`(仮)に記録
- 各フェーズ完了時に本計画のステータスを更新
