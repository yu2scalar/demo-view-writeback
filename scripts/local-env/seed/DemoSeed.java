import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.TransactionFactory;

import java.util.Properties;

/**
 * plan-006 デモシード(demo-cache-writeback から移植した建設プロジェクト題材)
 * + plan-007 人事・勤怠題材。
 * ScalarDB 側 = namespace `project` の project / material、namespace `hr` の
 * employee / department を Cluster 経由で作成・投入。
 * 外部システム側(PG の erp.project_order / payroll.emp_payroll)は erp-seed.sql(psql)が担当。
 * 実行: javac --release 17 -proc:none -cp "<BOOT-INF/lib>/*" DemoSeed.java
 *       java -cp ".:<BOOT-INF/lib>/*" DemoSeed <contact_point> <contact_port>
 * 既存テーブルは drop → create で作り直す(冪等)。
 */
public class DemoSeed {
  public static void main(String[] args) throws Exception {
    String host = args.length > 0 ? args[0] : "indirect:192.168.49.2";
    String port = args.length > 1 ? args[1] : "30053";
    Properties p = new Properties();
    p.setProperty("scalar.db.transaction_manager", "cluster");
    p.setProperty("scalar.db.contact_points", host);
    p.setProperty("scalar.db.contact_port", port);
    p.setProperty("scalar.db.cluster.auth.enabled", "true");
    p.setProperty("scalar.db.username", "admin");
    p.setProperty("scalar.db.password", "admin");
    TransactionFactory f = TransactionFactory.create(p);

    try (DistributedTransactionAdmin admin = f.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("project", true);
      admin.dropTable("project", "project", true);
      admin.dropTable("project", "material", true);
      admin.createTable("project", "project", TableMetadata.newBuilder()
          .addColumn("project_id", DataType.INT)
          .addColumn("project_name", DataType.TEXT)
          .addColumn("status", DataType.TEXT)
          .addColumn("budget", DataType.BIGINT)
          .addColumn("material_id", DataType.INT)
          .addColumn("material_volume", DataType.BIGINT)
          .addPartitionKey("project_id")
          .build());
      admin.createTable("project", "material", TableMetadata.newBuilder()
          .addColumn("material_id", DataType.INT)
          .addColumn("material_name", DataType.TEXT)
          .addColumn("stock", DataType.BIGINT)
          .addColumn("allocated", DataType.BIGINT)
          .addPartitionKey("material_id")
          .build());
      admin.createNamespace("hr", true);
      admin.dropTable("hr", "employee", true);
      admin.dropTable("hr", "department", true);
      admin.createTable("hr", "employee", TableMetadata.newBuilder()
          .addColumn("emp_id", DataType.INT)
          .addColumn("emp_name", DataType.TEXT)
          .addColumn("dept_id", DataType.INT)
          .addColumn("overtime_hours", DataType.BIGINT)
          .addColumn("alert_level", DataType.BIGINT)
          .addPartitionKey("emp_id")
          .build());
      admin.createTable("hr", "department", TableMetadata.newBuilder()
          .addColumn("dept_id", DataType.INT)
          .addColumn("dept_name", DataType.TEXT)
          .addColumn("capacity", DataType.BIGINT)
          .addColumn("headcount", DataType.BIGINT)
          .addPartitionKey("dept_id")
          .build());
      System.out.println("schema created: project.project / project.material"
          + " / hr.employee / hr.department");
    }

    DistributedTransactionManager tm = f.getTransactionManager();
    DistributedTransaction tx = tm.start();
    try {
      // material: Steel は在庫逼迫(割当拒否デモ用)。値は demo-cache-writeback と同一
      tx.insert(material(1, "Cement", 1200, 350));
      tx.insert(material(2, "Steel", 60, 50));
      tx.insert(material(3, "Timber", 250, 200));

      tx.insert(project(1, "Bridge Renovation", "ACTIVE", 1_000_000L, 1, 100));
      tx.insert(project(2, "Harbor Extension", "PLANNING", 2_500_000L, 2, 50));
      tx.insert(project(3, "Depot Build", "ACTIVE", 800_000L, 3, 200));
      tx.insert(project(4, "Tunnel Survey", "PLANNING", 300_000L, 1, 50));
      tx.insert(project(5, "Coastal Road Repair", "ACTIVE", 1_200_000L, 1, 120));
      tx.insert(project(6, "Rail Yard Upgrade", "PLANNING", 600_000L, 1, 80));

      // hr(plan-007): 管理部は満員(定員超過 abort デモ用)。headcount は実在籍と一致
      tx.insert(department(1, "設計部", 5, 2));
      tx.insert(department(2, "施工部", 4, 2));
      tx.insert(department(3, "管理部", 2, 2));

      tx.insert(employee(1, "佐藤", 1, 12));
      tx.insert(employee(2, "鈴木", 1, 30));
      tx.insert(employee(3, "高橋", 2, 44));
      tx.insert(employee(4, "田中", 2, 20));
      tx.insert(employee(5, "伊藤", 3, 38));
      tx.insert(employee(6, "渡辺", 3, 8));
      tx.commit();
      System.out.println("seed committed: 3 materials, 6 projects,"
          + " 3 departments, 6 employees");
    } catch (Exception e) {
      tx.abort();
      throw e;
    } finally {
      tm.close();
    }
    System.out.println("SEED DONE");
  }

  private static Insert material(int id, String name, long stock, long allocated) {
    return Insert.newBuilder()
        .namespace("project").table("material")
        .partitionKey(Key.ofInt("material_id", id))
        .textValue("material_name", name)
        .bigIntValue("stock", stock)
        .bigIntValue("allocated", allocated)
        .build();
  }

  private static Insert project(int id, String name, String status, long budget,
                                int materialId, long volume) {
    return Insert.newBuilder()
        .namespace("project").table("project")
        .partitionKey(Key.ofInt("project_id", id))
        .textValue("project_name", name)
        .textValue("status", status)
        .bigIntValue("budget", budget)
        .intValue("material_id", materialId)
        .bigIntValue("material_volume", volume)
        .build();
  }

  private static Insert department(int id, String name, long capacity, long headcount) {
    return Insert.newBuilder()
        .namespace("hr").table("department")
        .partitionKey(Key.ofInt("dept_id", id))
        .textValue("dept_name", name)
        .bigIntValue("capacity", capacity)
        .bigIntValue("headcount", headcount)
        .build();
  }

  private static Insert employee(int id, String name, int deptId, long overtime) {
    return Insert.newBuilder()
        .namespace("hr").table("employee")
        .partitionKey(Key.ofInt("emp_id", id))
        .textValue("emp_name", name)
        .intValue("dept_id", deptId)
        .bigIntValue("overtime_hours", overtime)
        .bigIntValue("alert_level", 0)
        .build();
  }
}
