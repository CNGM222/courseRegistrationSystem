# 课程注册系统后端

后端使用 Spring Boot 4.1、Java 21、Spring Data JPA、Spring Security Session JDBC 和 MySQL 9.6。API 前缀为 `/api/v1`，实体不会直接作为 HTTP 响应，写接口使用 Cookie + CSRF，资源编辑使用 `If-Match: "version"`。

## 本地验证

```powershell
./mvnw.cmd test
./mvnw.cmd package
```

测试默认使用 H2，包含实体映射、策略、认证/CSRF、DTO 日志脱敏、草稿和正式选课、并发容量、教师授课、成绩版本、结课补位、账单 Outbox、目录同步和人员敏感字段测试。MySQL 专用的 NOWAIT 测试在 H2 下跳过。对已初始化的**独立测试数据库**运行 MySQL 集成测试时，在新的 PowerShell 窗口设置：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:13316/course_registration_system?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = ''
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'com.mysql.cj.jdbc.Driver'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
$env:SPRING_SESSION_JDBC_INITIALIZE_SCHEMA = 'never'
./mvnw.cmd test
```

测试会写入合成数据，不要使用业务数据库。`DB_*` 是正式运行配置；测试 profile 的数据源需用上述 `SPRING_*` 变量覆盖。

## 数据库与生产配置

先执行 `../课程设计文档/04-MySQL9.6初始化.sql`，然后用拥有建表权限的 MySQL 账号，在项目默认库中执行 Session 表脚本。该脚本会显式切换到 `course_registration_system`：

```sql
SOURCE D:/AAAMyProjects/courseRegistrationSystem/backend/session-schema-mysql.sql;
SHOW TABLES LIKE 'SPRING_SESSION%';
```

确认结果同时包含 `SPRING_SESSION` 和 `SPRING_SESSION_ATTRIBUTES`。MySQL Workbench 中也可以打开并执行 `session-schema-mysql.sql`，执行前确认当前连接指向应用所用实例。生产环境若 `DB_URL` 使用了其他数据库名，应先将脚本中的 `USE` 改为对应库。脚本是当前 Spring Session 依赖版本官方 MySQL DDL 的副本。正式环境必须设置：

- `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`
- `SSN_KEY`：用于社会安全号码 AES-GCM 加密和 HMAC 指纹的高强度密钥；保持稳定并安全备份，否则已有号码无法解密。缺失时涉及非空号码的操作返回 503
- `SESSION_COOKIE_SECURE=true`（HTTPS）
- `BILLING_URL`、`BILLING_TOKEN`（财务端不可用时 Outbox 会保留并重试）

正式环境默认不自动同步目录，使用 `app.operation=sync-catalog` 的无 Web 管理命令执行同步。远程目录调用在事务外完成，发布和导入在短事务内完成。

## 合成演示环境

演示环境只使用合成数据，不连接学校数据库：

```powershell
$env:DEMO_PASSWORD = 'demo-only-2026'
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

默认端口 8080，与前端 Vite 代理一致。演示账号为 `student.demo`、`professor.demo`、`registrar.demo`，密码由 `DEMO_PASSWORD` 提供。演示目录来自 `src/main/resources/demo-catalog.json`；H2 数据仅保存在进程内，停止服务后清空。

HTTP 冒烟脚本固定访问 18080。先在一个终端启动服务，再在另一个终端运行脚本：

```powershell
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo --server.port=18080
```

```powershell
python scripts/smoke_demo.py
```

脚本使用默认演示密码 `demo-only-2026`，会修改演示数据。`scripts/mock_billing.py` 是独立的本地财务接收端，使用 SQLite 保存事件并按 event ID 与学生-学期业务键去重；通过 `python scripts/mock_billing.py` 启动，接收地址为 `http://127.0.0.1:18081/integration/invoices`。演示 profile 默认禁用投递；需要联调时给后端传入 `--app.billing.dispatch-enabled=true --app.billing.url=http://127.0.0.1:18081/integration/invoices`。

## 受控管理命令

命令必须禁用 Web 应用，不会输出明文密码：

```powershell
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
$env:APP_OPERATION = 'provision-account'
$env:APP_PROVISION_USERNAME = 'registrar'
$env:APP_PROVISION_PASSWORD = 'change-this-password'
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

该命令为已有账号设置密码，或创建新的教务员账号；不会创建新的学生/教师资料。教务员可在新增师生资料时设置初始密码，并通过师生维护接口重置密码。密码使用 BCrypt 摘要保存；重置会清除登录失败计数和锁定状态，并撤销该账号现有会话。执行后关闭终端，避免管理变量影响后续 Web 启动。

同步目录时使用 `APP_OPERATION=sync-catalog` 和 `APP_SYNC_SEMESTER_ID`，同样禁用 Web。目录适配器支持 `CATALOG_SOURCE=legacy` 及默认的 `mock`；mock 需配置 `CATALOG_MOCK_FILE` 为 JSON 文件路径。没有可用发布快照、或开放学期快照验证时间超过 24 小时时 API 返回 `503 CATALOG_UNAVAILABLE`。准备期同步可发布目录；开放后数据变化只暂存，不覆盖选课引用的快照。

旧目录 SQL 适配器需提供 `APP_CATALOG_LEGACY_URL`、`APP_CATALOG_LEGACY_USER`、`APP_CATALOG_LEGACY_PASSWORD` 和对应 JDBC 驱动。当前依赖含 MySQL 驱动；对接 Ingres 时需自行添加其驱动，并提供以下只读契约视图（均含 `semester_code`）：

| 视图 | 其余列 |
| --- | --- |
| `crs_courses` | `legacy_id, code, name, department, credits` |
| `crs_prerequisites` | `course_id, prerequisite_id` |
| `crs_offerings` | `legacy_id, course_id, section_code, tuition, currency` |
| `crs_meetings` | `offering_id, week_no, weekday, start_period, end_period_exclusive, room` |
| `crs_qualifications` | `professor_id, course_id` |

课程、教学班引用使用旧目录 ID；`professor_id` 对应本系统教师 ID。SQL 账号应仅授予这些视图的 SELECT 权限。

## 验证边界

本地已执行 H2/MySQL 集成测试和真实 HTTP 冒烟测试。真实学校旧目录、真实财务接收端、500/2000 用户负载、长期可用性及备份恢复演练尚未验证；合成演示不能证明这些指标通过。正式部署应使用 HTTPS，并保持 Web 调试、SQL 参数及结果提取日志关闭。
