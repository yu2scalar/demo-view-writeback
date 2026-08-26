# 設計ノート 2026-07-18: FC 結果を受けたユーザーフィードバックと決定事項

fc-report.md の報告に対するユーザー回答(2026-07-18)の記録。本設計(plan-003 想定)の入力。

## 1. キー情報の扱い

- カタログにキー情報が無い件: Analytics は「分析用にとりあえず全情報を持ち込む」姿勢と推測。
  ただしフィルタリング(必要な範囲だけ取り込む。例: 10年分でなく1年分)が無いと
  リソース影響が大きい、という問題意識をユーザーが表明(Analytics 側仕様の将来変更は覚悟する)
- scalardb 配下: `getTableMetadata()` での補完で確定(FC-3 実証済み)
- **非 scalardb 配下: 行特定をアプリ側で行わず、レコード全体の Before/After イメージを
  RE キューに投げる方式(ユーザー提案、方向性合意)**
  - Before イメージ = View キャッシュ上の現在値、After = 更新後の値。宛先(ERP 等)側が
    自分のスキーマ知識で行を特定し、Before と現在値の照合を楽観ロック的な検証にも使える
  - 留意: ペイロードサイズ(ワイド行・BLOB)、機微カラムがキューを通ること、
    イベントの順序性・冪等性(前身同様 UUIDv7 の event_id を踏襲予定)

## 2. デモ環境の構築方針

- 現在の .129 環境は**フィージビリティチェック専用**(Analytics 関連 DB はすべて .129 で稼働中)
- **デモ環境としては Analytics Server を含めて一気に(スクリプト等で)作成できる必要があり、
  localhost で実証したい**(ユーザー要件)
  → 本設計に「ローカル環境一括構築」(compose/スクリプト: Analytics Server + Spark +
  カタログ DB + バックエンド群)のタスクを含める。Analytics のライセンス要件は要確認

## 3. カタログアクセス経路のセキュリティ(調査項目・ペンディング)

- カタログ DB 直叩きは本来 NG のはず。また API 経由でも datasource 情報(平文接続情報)が
  簡単に取れてしまうのはセキュリティ上の問題がありうる
- **追って調査**(ユーザー)。それまでは FC の直読方式(`AnalyticsCatalogClient` に隔離)で進める
- 関連: カタログには auth_* / authz_* テーブル(ユーザー・トークン・ロール・ACL)が存在しており、
  Analytics 側に認証認可の仕組みがあることを示唆。API 経路調査時の手がかり

## 4. 型マッピング(ユーザー提供、過去サンプルからの実績表)

Application(W) ⇒ ScalarDB ⇒ MySQL ⇒ Analytics(Spark) ⇒ Application(R) の型対応。
「基本的には動作するが、型のマッピング等は気をつける」(ユーザー)。

| Java (Write) | ScalarDB Type | MySQL Type | Spark Type | Java (Read) | Row メソッド | ScalarDB 型への変換 |
|---|---|---|---|---|---|---|
| boolean | BOOLEAN | BOOLEAN | BooleanType | boolean | getBoolean(i) | — |
| int | INT | INT | IntegerType | int | getInt(i) | — |
| long | BIGINT | BIGINT | LongType | long | getLong(i) | — |
| float | FLOAT | REAL | FloatType | float | getFloat(i) | — |
| double | DOUBLE | DOUBLE | DoubleType | double | getDouble(i) | — |
| String | TEXT | LONGTEXT | StringType | String | getString(i) | — |
| ByteBuffer | BLOB | LONGBLOB | BinaryType | byte[] | get(i) | ByteBuffer.wrap() |
| LocalDate | DATE | DATE | DateType | java.sql.Date | getDate(i) | .toLocalDate() |
| LocalTime | TIME | TIME(6) | **TimestampNTZType** | LocalDateTime | get(i) | **.toLocalTime()** |
| LocalDateTime | TIMESTAMP | DATETIME(3) | TimestampNTZType | LocalDateTime | get(i) | — |
| Instant | TIMESTAMPTZ | DATETIME(3) | TimestampType | java.sql.Timestamp | getTimestamp(i) | .toInstant() |

要注意ポイント:

- **TIME は Spark 側で TimestampNTZType になる**(時刻のみの型が無い)→ 読み出しは
  LocalDateTime で受けて `.toLocalTime()` に落とす
- TIMESTAMP(NTZ)と TIMESTAMPTZ で Row の受け型が異なる(LocalDateTime vs java.sql.Timestamp)
- BLOB は Row から byte[] で来る → ScalarDB へは ByteBuffer.wrap()
- FC-4 の `DynamicRepository` は現状 INT/BIGINT/TEXT/BOOLEAN/FLOAT/DOUBLE(+BLOB 読み)のみ対応。
  本実装では上表の全型(日時系含む)をこの表に従って実装し、単体テストで往復を検証する
