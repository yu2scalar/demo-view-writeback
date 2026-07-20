-- plan-006: ERP 側サンプル(erpdb に対して実行する。DB 作成は setup スクリプト側)
-- erp.project_order = ERP が所有する受発注ステータス(raw テーブル、ScalarDB 管理外)。
-- Analytics には ds_postgres として登録し、order_status の編集は via=RE で
-- erp.re_inbox(RE init が作成)へ配送される。
CREATE SCHEMA IF NOT EXISTS erp;

DROP TABLE IF EXISTS erp.project_order;
CREATE TABLE erp.project_order (
    project_id   INT PRIMARY KEY,
    order_no     TEXT NOT NULL,
    order_status TEXT NOT NULL
);

INSERT INTO erp.project_order (project_id, order_no, order_status) VALUES
    (1, 'PO-0001', 'CONFIRMED'),
    (2, 'PO-0002', 'RECEIVED'),
    (3, 'PO-0003', 'SHIPPED'),
    (4, 'PO-0004', 'RECEIVED'),
    (5, 'PO-0005', 'INVOICED'),
    (6, 'PO-0006', 'RECEIVED');

-- plan-007: 給与システム側サンプル(2 つ目の RE 宛先)。
-- payroll.emp_payroll = 給与システムが所有する給与計算ステータス(raw テーブル)。
-- payroll_status の編集は via=RE で payroll.re_inbox(RE init が作成)へ配送される。
CREATE SCHEMA IF NOT EXISTS payroll;

DROP TABLE IF EXISTS payroll.emp_payroll;
CREATE TABLE payroll.emp_payroll (
    emp_id         INT PRIMARY KEY,
    payroll_no     TEXT NOT NULL,
    payroll_status TEXT NOT NULL
);

INSERT INTO payroll.emp_payroll (emp_id, payroll_no, payroll_status) VALUES
    (1, 'PR-2607-001', 'CALCULATED'),
    (2, 'PR-2607-002', 'PENDING'),
    (3, 'PR-2607-003', 'PENDING'),
    (4, 'PR-2607-004', 'CALCULATED'),
    (5, 'PR-2607-005', 'PENDING'),
    (6, 'PR-2607-006', 'CALCULATED');
