# E2E レポート: plan-004 フェーズ2(.130 ローカル一括環境)2026-07-19

- 対象: `local-env-setup.md` T1〜T8(.130 = 本マシン単体でのフルスタック構築 + plan-005 E2E 項目の再実行)
- 環境: **すべて .130**。minikube(6cpu/6Gi、IP 192.168.49.2)上に
  ScalarDB Cluster 3.18.0(byol-premium、envoy NodePort 30053)/
  Analytics Server 3.18.0(NodePort 31051/31052)/ カタログ PG(NodePort 30432)/
  MySQL 8.0.46(NodePort 30306)。ホスト側に Spark Connect server 3.5.6(:15002)、
  ScalarRE(:8080)、本アプリ(:8082)

## 結果サマリ: 全項目 PASS

| # | 項目(plan-005 E2E 対応) | 結果 | 備考 |
|---|---|---|---|
| 1 | Cluster 接続で起動、view 一覧/行取得 | ✅ | `indirect:192.168.49.2` + `contact_port=30053`(envoy NodePort) |
| 2 | ソース書き戻し + 実 DB 反映 | ✅ | vw_order2 qty 往復 1000→1001→1000、MySQL 直読で確認 |
| 3 | refresh / replace(再作成) | ✅ | ともに 8 行。metadata cache 60秒問題は今回発生せず |
| 4 | クラスタリング列への addSecondaryIndex | ✅ | vw_order2: t1_pk PARTITION(indexed=0)/ t2_pk CLUSTERING(indexed=1)。**新規 Cluster での作成経路も確認** |
| 5 | 伝播本体(一対多、同一 Tx) | ✅ | vw_order t6_stock 1 編集 → `viewRowsUpdated: 7`(product 1 の全行)+ ソース反映、往復とも |
| 6 | 新規 create / delete | ✅ | vw_order 作成(8行)→ 削除(views.vw_order 実体 drop 確認)。stock_re_demo は id+item_group_id の 2 成分連結 |
| 7 | RE 経路(stock_re_demo erp_qty) | ✅ | 123→124→123 の 2 イベント。reEvents=1×2 + viewRowsUpdated=2×2、outbox 滞留 0、**ns_mysql.re_inbox に 2 件配送済み(tx_state=3)** |
| 8 | キュー俯瞰(/api/overview) | ✅ | `?destNamespace=ns_mysql` で destInbox=1 を確認。tx_state index 付きテーブルの **ScalarDB scan 経由**(3.18 ノードなので DB-CORE-10281 非発生) |
| 9 | GUI(playwright + Chrome) | ✅ | home に view 一覧、data-edit で 8 行描画・`<alias>_pk` 非表示・TX バッジ・保存ボタン、builder にカタログ表示。スクショ確認済み |
| 10 | 単体テスト(KeyConcat 等) | ✅ | `./gradlew test` PASS |

## .129 との差分(構成上の判断)

- **(b) Cluster 一元化の検証 → 不可(重要な検証成果)**: ds_scalardb を cluster client
  configs で登録すると Analytics Server の schema 解決が
  `DB-CORE-10066: Transaction manager 'cluster' is not found` で失敗。
  **公式 Analytics Server イメージ 3.18.0 に cluster SDK は同梱されていない**。
  フォールバックでライブラリモード(`scalar.db.storage=jdbc` の MySQL 直結)登録。
  詳細 = `local-env-setup.md` §4.5
- ds_scalardb は .129 の multi-storage と違い **single-storage**(namespace_mapping 二重管理なし)
- bitnami/mysql chart はイメージ取得不可(2025 カタログ変更)+ 9系のため、
  公式 `mysql:8.0` の素の manifest(`scripts/local-env/mysql.yaml`)を使用

## 運用上の発見(.130 固有)

1. **scalardb datasource は登録時スナップショット**: 登録後に作った
   テーブル(シード)は Spark から見えない。CLI 3.18 に refresh は無く、
   `data-source delete --cascade` → `register` のやり直しが必要。
   → 一括構築スクリプトは「シード → 登録」の順に固定
2. `data-source delete` は namespace を持つと `FAILED_PRECONDITION` — `--cascade` 必須
3. MySQL 公式イメージは readiness probe を TCP(`mysqladmin ping -h127.0.0.1`)にする
   (socket パスがイメージ既定と異なるため)
4. overview の destNamespace デフォルトは `erp` — .130 デモは `?destNamespace=ns_mysql` で参照

## テスト後のデータ状態

- vw_order2 / order / inventory: 原状(qty=1000、stock=10000、view と source 一致)
- stock_re_demo: erp_qty 123(往復済み)。ns_mysql.re_inbox に配送済みイベント 2 件残置
  (consumer 適用はスコープ外、.129 と同じ性質)
- vw_order: 削除済み(E2E 用に一時作成)

## 稼働プロセス(レポート時点)

- アプリ :8082(`java -jar build/libs/demo-view-writeback-0.0.1-SNAPSHOT.jar`、repo root から)
- ScalarRE :8080(`--scalar-re.config.file=config/scalar-re-config.yml`)
- Spark Connect :15002(`scripts/local-env/spark-connect/start-spark-connect.sh`)
- minikube: PG / MySQL / Cluster(node+envoy)/ Analytics Server / CLI Pod

## 追記(2026-07-19 同日): ScalarRE 公開コンテナへの切替 + 再構築の実地確認

- ScalarRE を jar 起動 → **公開コンテナ `ghcr.io/yu2scalar/scalar-re{,-init}:0.9.1`**
  (host network、config ro マウント)に切替え、**項目 7(RE 経路)をコンテナで再 PASS**
  (往復 2 イベント配送、outbox 滞留 0、RE ログにエラーなし)
- 併せて setup スクリプトによる**再構築を実地確認**: k8s リソース再作成 → シード →
  データソース登録まで自動成功。全 DB 非永続のため view 定義・実体は消える →
  アプリ setup + view 再作成(定義 JSON 再 POST)で復旧し、vw_order2=8行 /
  stock_re_demo=12行 を再現。inbox の残置イベントは RE 起動時の
  InboxRecoveryScanner burst で自動回収される(件数アサート時の注意点)
