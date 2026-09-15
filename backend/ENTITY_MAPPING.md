# 后端实体映射

依据 `课程设计文档/03-设计文档.md` 第 4 章及 `04-MySQL9.6初始化.sql`，映射全部 19 张业务表。设计文档建议 MyBatis，当前项目已经采用 Spring Data JPA，因此实体继续使用 JPA/Hibernate，表名与现有初始化 SQL 一致。

| 范围 | 实体 |
| --- | --- |
| 身份与人员 | Account、Student、Professor |
| 学期与目录 | Semester、CatalogSnapshot、Course、CoursePrerequisite、CourseOffering、OfferingMeeting、ProfessorQualification |
| 课表与成绩 | Schedule、ScheduleChoice、Enrollment、Grade |
| 关闭与计费 | RegistrationClose、BillingInvoice、BillingInvoiceItem、OutboxEvent、AuditLog |

## 映射约定

- `Account`、`Student`、`Professor`、`Course` 分别对应 `accounts`、`students`、`professors`、`courses`；其余使用 SQL 中的单数下划线表名。
- 自增主键使用 `Long` 与 `IDENTITY`。先修和教师资格使用独立、可序列化的 `IdClass`；新增记录时填写两个 ID 字段，关联对象用于读取，不负责写入外键。
- `Grade.enrollmentId` 通过 `@MapsId` 与注册记录共享主键，创建成绩时设置 `enrollment`，没有成绩行表示未录入。
- 关联采用单向懒加载，不配置级联删除。师生的 `deletedAt` 映射删除时间；后续查询需显式决定是否过滤已删除人员，实体不自动拦截物理删除。
- 校历及出生日期使用 `LocalDate`。业务时刻使用表示 UTC 的 `LocalDateTime`，对应 `DATETIME(3)`；`JpaConfig` 按 UTC 毫秒精度填充审计时间。服务手动设置的业务时刻也必须转换为 UTC。
- 币种、UUID 和摘要使用 `String`；金额及学分使用 `BigDecimal`；SSN 密文及指纹使用 `byte[]`。JSON 对象使用 `Map<String, Object>`，上课时间快照使用 `List<Map<String, Object>>`，通过 Hibernate JSON 类型读写。
- 可并发编辑的实体使用 `@Version`，业务更新仍需事务。课表草稿和提交版本指针是逻辑版本数值，可以对应零条意向，不是明细外键。
- 意向版本、关闭结果、账单、账单明细与审计记录使用 `@Immutable`。ORM 不写回这些实体的后续字段修改；Outbox 允许更新投递状态，但消息 ID、归属和负载冻结。
- 状态沿用现有代码的字符串表示。数据库 CHECK、外键 RESTRICT 及完整索引由初始化 SQL 管理；应用使用 `ddl-auto=validate`，不通过 Hibernate 自动创建或更新正式表结构。
- 容量、先修、角色与归属、跨表一致性、关闭流程及状态转换需由后续业务服务实现。API 应使用 DTO，并将 ID 输出为字符串，不直接序列化实体。

## 运行配置

先执行初始化 SQL，再设置 `DB_USERNAME`、`DB_PASSWORD`；可通过 `DB_URL` 覆盖默认连接。默认库为 `localhost:3306/course_registration_system`，连接会话使用 UTC。项目支持 Java 21 及以上，Maven 已显式配置 Lombok 注解处理器。

## 验证

在 `backend` 目录执行 `./mvnw.cmd test`。默认测试采用 H2，仅验证 ORM 映射，不用于证明 MySQL 锁或选课并发规则。测试事务结束后回滚数据。

对专用 MySQL 测试实例先执行同一初始化 SQL，然后使用以下 PowerShell 配置运行相同测试。环境变量在当前终端生效，测试后关闭该终端或清除配置。

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:13316/course_registration_system?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:SPRING_DATASOURCE_USERNAME = '<测试账号>'
$env:SPRING_DATASOURCE_PASSWORD = '<测试密码>'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'com.mysql.cj.jdbc.Driver'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'validate'
./mvnw.cmd test
```

已在临时 MySQL 9.6 实例上执行原始初始化 SQL，通过 Hibernate 表结构校验及 8 项测试，覆盖实体加载、联合主键、共享主键、日期与二进制字段、JSON/金额读写、审计与版本、不可变快照和重复课表/注册约束。
