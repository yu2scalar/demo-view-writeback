# 再現手順書: 未解決 2 事象(2026-07-20、plan-009 E2E 中に発見)

- 対象環境: .130 ローカル(minikube 192.168.49.2 / Cluster 3.18.0 replicaCount=1 /
  MySQL 8 NodePort 30306 / アプリ jar 2026-07-20 11:31 ビルド)
- 発見経緯・一次分析: `plan-009-re-approval-roundtrip.md §7-2 / §7-3`
- 本書の目的: 次回調査セッションで**そのまま実行できる**再現コマンドと、
  今回できなかった監査(書込元特定)の仕込み方を残す

## 事前準備(共通)

```bash
# 1. 環境起動確認
kubectl get pods            # mysql / postgresql / cluster / analytics が Running
pgrep -f 'demo-view-writeback.*jar' && ss -tln | grep 8082
docker ps | grep scalar-re

# 2. データを既知の整合状態にする(佐藤=設計部1、headcount 2/2/2)
#    ズレていたら: DemoSeed 再実行 + erp-seed.sql + 全 view refresh(§6.8/6.9 手順)
mysql -h 192.168.49.2 -P 30306 -uroot -pmysql -e \
  "SELECT d.dept_id, d.headcount, COUNT(e.emp_id) actual
   FROM hr.department d LEFT JOIN hr.employee e ON e.dept_id=d.dept_id
   GROUP BY d.dept_id, d.headcount ORDER BY d.dept_id"
# 期待: headcount = actual = 2/2/2
```

## 事象 2: 連続モジュール Tx のステイル読み疑い

### 症状

異動(モジュールが department 2 行を keyed update)→ **即** 逆異動を
連続実行すると、2 本目のモジュールの read ノードが 1 本目のコミット済み
headcount ではなく**コミット前の値**を読んだ結果になる。

### 再現コマンド(2026-07-20 に 1/1 で発生。curl 間隔 〜100ms)

```bash
# 高橋(emp3、施工部2)を設計部1へ → 即、施工部2へ戻す
curl -s -X PUT -H 'Content-Type: application/json' \
  -d '{"emp_id":3,"pay_emp_id":3,"dept_id":1}' \
  localhost:8082/api/views/vw_employee_overview/rows
curl -s -X PUT -H 'Content-Type: application/json' \
  -d '{"emp_id":3,"pay_emp_id":3,"dept_id":2}' \
  localhost:8082/api/views/vw_employee_overview/rows
mysql -h 192.168.49.2 -P 30306 -uroot -pmysql \
  -e "SELECT dept_id, headcount FROM hr.department ORDER BY dept_id"
```

- **期待**: 1 本目後 3/1/2 → 2 本目後 **2/2/2**(1 本目の書込を読んで ±1)
- **観測(2026-07-20 11:40)**: **1/3/2** = 2 本目の read(nd/od)が
  1 本目コミット前の 2/2 を読み、2−1=1 / 2+1=3 を書いた計算に一致
- 参考 Tx: 1 本目 = emp3 v3(上書き済)、2 本目 = `243f33de`(02:40:20.556 UTC、
  emp3 v4 + dept1/dept2 を書いた)

### 調査ポイント

1. **間隔依存の確認**: 上記 2 curl の間に `sleep 1` を入れて 10 回、入れずに 10 回 →
   発生率の差を取る(人手デモ速度で安全かの裏取りにもなる)
2. **分離レベル**: アプリの `config/scalardb.properties` は
   `scalar.db.transaction_manager=cluster` のみで isolation 未指定。
   Cluster ノード側の consensus-commit isolation(既定 SNAPSHOT のはず)と、
   コミット直後の可視性(prepared → committed 遷移のタイミング)を確認する
3. app.log の `AbstractClusterNodeManager : Closed the cluster node` が編集直前に
   出ることがある(11:38:34 / 11:39:27 に観測)。コネクション再接続と可視性の
   相関も見る

## 事象 3: emp1(佐藤)の dept_id が意図しない Tx で 2 になる

### 症状

誰も emp1 の dept_id を編集していないのに、MySQL の `hr.employee` emp1 行の
dept_id が 2(施工部)になっている。**department の headcount は調整されない**
(= 更新モジュールを通らない書込)ため、在籍数と headcount が食い違う。

### 発生記録(2 回)

| 回 | 時刻(JST) | 証跡 | 当時の推定 |
|---|---|---|---|
| 1 | 2026-07-20 09:48:06 | emp1 v4 = tx `d4d6ebe5`(coordinator 00:48:06.973 committed)。直前 09:47:21 に `vw_employee_overview2`(モジュール無し複製)が GUI 作成されていた | 複製 view 経由のユーザー編集(**要再検証** — 2 回目が下記条件で再発したため) |
| 2 | 2026-07-20 10:28 の再シード後〜11:40:20 の間(emp1 v2。正確な時刻・tx 不明) | F フェーズ(11:40:19-20)の emp1 emp_name 編集 tx `5c0e4d8d` = v3 の行イメージに dept_id=2 が持ち越されていたことから逆算 | **ユーザー不在・view2 不存在**で発生。書込元未特定 |

### 今回の調査で除外できたもの(2 回目について)

- coordinator.state の 01:28〜02:40 UTC の全 Tx は既知の操作(A〜E フェーズの
  申請/承認/RE 配送/自動確定/refresh/RE・アプリ再起動)と時刻で対応が取れた
- アプリ log(11:38〜11:40)にも emp1 を書く操作の記録なし
- InboxApplyService は views/viewmgr のみ、ExternalSystemService は
  erpdb(PG)+ outbox/inbox のみを書く実装
- **限界**: ScalarDB の before イメージはコミット後にクリアされ、coordinator は
  write-set を持たないため、後からの書込元特定は不可能だった → 監査の仕込みが必須

### 次回の監査の仕込み(いずれか。調査セッション冒頭に設定)

```bash
# 案1: MySQL general_log(全 SQL を記録。短時間の再現運転向け)
mysql -h 192.168.49.2 -P 30306 -uroot -pmysql -e \
  "SET GLOBAL general_log_file='/tmp/mysql-general.log'; SET GLOBAL general_log='ON'"
# 再現操作 … 終了後:
mysql -h 192.168.49.2 -P 30306 -uroot -pmysql -e "SET GLOBAL general_log='OFF'"
kubectl exec mysql-scalardb-0 -- grep -n "hr.employee" /tmp/mysql-general.log

# 案2: 監査トリガー(dept_id の変更だけを別テーブルに記録。常設可)
mysql -h 192.168.49.2 -P 30306 -uroot -pmysql -e "
CREATE TABLE IF NOT EXISTS hr.audit_dept_change (
  at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3), emp_id INT, old_dept INT, new_dept INT,
  new_tx_id VARCHAR(64));
CREATE TRIGGER hr.trg_emp_dept_audit BEFORE UPDATE ON hr.employee FOR EACH ROW
  INSERT INTO hr.audit_dept_change (emp_id, old_dept, new_dept, new_tx_id)
  SELECT NEW.emp_id, OLD.dept_id, NEW.dept_id, NEW.tx_id
  FROM DUAL WHERE NOT (OLD.dept_id <=> NEW.dept_id)"
# 注意: ScalarDB 管理テーブルへのトリガーは読み取り専用の記録に留めること。
#       prepare 段階の書込も記録される(tx_state 遷移込みで複数行残る)点に留意
```

アプリ側の補助: `UpdateEngine.apply` に「viewName / changed / 各 repo.update の
ns.table + key + 書込列」を INFO で 1 行ログする改修を入れると、アプリ発の書込は
全て突合できるようになる(数行の変更。調査ブランチで可)。

### 再現の試み方

1. 監査(上記)を仕込む
2. 2 回とも「emp1 以外への編集・承認フローの操作が連続した後」に発生している。
   plan-009 E2E の A〜F フェーズ(申請→承認→却下→erp 承認→TX 編集→異動 2 連発)を
   スクリプト化して繰り返し、audit テーブル/general_log に emp1 dept_id 変更が
   emp1 宛でない操作の Tx で現れるかを見る
3. 発生したら new_tx_id で coordinator と突合し、同 Tx の全書込を general_log から抽出

## 現状(2026-07-20 12:30 時点)

- データは整合状態に修正済み(headcount = 実所属 = 2/2/2、佐藤 = 設計部)
- 監査は未設置(本書の仕込みは次回調査時に行う)
