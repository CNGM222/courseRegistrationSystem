-- 高校课程注册系统：MySQL 9.6 初始化脚本
-- 编写依据：01-开发计划.md、02-需求分析文档.md、03-设计文档.md，以及现有 7 个 Java 类。
-- 范围：设计文档第 4 章的 19 张业务表；InnoDB、utf8mb4、UTC、毫秒精度。
-- 执行方式：使用 mysql --default-character-set=utf8mb4 -u <用户名> -p 登录后执行：
-- SOURCE D:/AAAMyProjects/courseRegistrationSystem/课程设计文档/04-MySQL9.6初始化.sql;
-- 数据库名统一为 course_registration_system；连接账号需有建库、建表和 ALTER 权限。
-- CREATE IF NOT EXISTS 允许重复执行，不清空数据，也不升级已存在但结构不同的表。
-- MySQL DDL 会隐式提交；首次安装失败后排查错误再重跑，后续结构变更另写迁移。
--
-- 与实体类的对照及待同步项（本文件不修改 Java 代码）：
-- 1. Account/Student/Professor/Course 的 @Table 分别为 accounts/students/professors/courses，
--    本脚本沿用这些表名，其余表沿用设计文档名称。
-- 2. CoursePrerequisite.java 当前为空；按文档补建 course_prerequisite，联合主键
--    (course_id, prerequisite_course_id)，后续实体需使用 @EmbeddedId 或 @IdClass。
-- 3. 按文档使用 DATE：Professor.birthDate、Semester.startDate/endDate 应改为 LocalDate；
--    当前 LocalDateTime 与这里的 DATE 映射不一致。其他业务时刻使用 DATETIME(3)。
-- 4. Semester.currency 按文档使用 CHAR(3)，Java 类型应由 byte[] 改为 String。
--    Semester.closed_at 对应数据库 closed_at；可改用 closedAt 并显式标注 @Column。
-- 5. Course 应补齐 (snapshot_id, legacy_course_id) 的 JPA 联合唯一约束。
--    SQL 按业务必填性补全 NOT NULL/DEFAULT/CHECK；实体写入时仍须提供必填值，
--    显式写入 NULL 不会使用数据库默认值。@Version 由 JPA/业务事务递增。
-- 6. 按文档通用规范补充可维护表的 created_at/updated_at；现有实体未映射的列由数据库填充。
--    LocalDateTime 本身无时区，应用与连接池也需统一 UTC，建议 Hibernate 使用 ddl-auto=validate。
-- 7. 当前 pom.xml 尚无 MySQL JDBC 驱动、Spring Session JDBC；本 SQL 不代替后端依赖配置。
--    SPRING_SESSION/SPRING_SESSION_ATTRIBUTES 由所选 Spring Session 版本的官方
--    org/springframework/session/jdbc/schema-mysql.sql 管理，不在此复制框架会话表。
--
-- 业务边界：数据库检查单行范围和引用存在性；以下仍由服务事务校验：
-- 账号与人员角色匹配；活动快照属于本学期且已发布；先修同快照、无环；
-- 教学班的课程/快照/学期和币种一致；注册最多四门、首次提交恰好 4+2；
-- 先修通过、同课程去重、教师资格、时间冲突、计数与 ENROLLED 行数一致；
-- 成绩归属及录入时点；账单归属、总额与明细一致；关闭及 Outbox 原子提交。
-- 注册写事务按学期→人员→课表→教学班 ID 升序加锁，使用 READ COMMITTED。

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

CREATE DATABASE IF NOT EXISTS `course_registration_system`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `course_registration_system`;

-- 一、身份与人员

CREATE TABLE IF NOT EXISTS `accounts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号 ID',
    `username` VARCHAR(64) NOT NULL COMMENT '登录名；唯一比较不区分大小写，应用统一规范化',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码摘要',
    `role` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单角色',
    `enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否允许登录',
    `failed_attempts` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until` DATETIME(3) NULL COMMENT '锁定到期时间 UTC',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_accounts_username` (`username`),
    CONSTRAINT `ck_accounts_role` CHECK (`role` IN ('STUDENT', 'PROFESSOR', 'REGISTRAR')),
    CONSTRAINT `ck_accounts_enabled` CHECK (`enabled` IN (0, 1)),
    CONSTRAINT `ck_accounts_attempts` CHECK (`failed_attempts` >= 0),
    CONSTRAINT `ck_accounts_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号';

CREATE TABLE IF NOT EXISTS `students` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `account_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    `birth_date` DATE NOT NULL,
    `graduation_date` DATE NULL,
    `ssn_ciphertext` VARBINARY(512) NULL COMMENT '社会安全号码加密密文；演示仅用合成数据',
    `ssn_fingerprint` BINARY(32) NULL COMMENT '标准化号码 HMAC-SHA256；密钥不入库',
    `deleted_at` DATETIME(3) NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_students_account` (`account_id`),
    UNIQUE KEY `uk_students_ssn` (`ssn_fingerprint`),
    CONSTRAINT `fk_students_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_students_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE', 'GRADUATED')),
    CONSTRAINT `ck_students_dates` CHECK (`graduation_date` IS NULL OR `graduation_date` >= `birth_date`),
    CONSTRAINT `ck_students_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学生档案；逻辑删除';

CREATE TABLE IF NOT EXISTS `professors` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `account_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `birth_date` DATE NOT NULL,
    `status` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    `department` VARCHAR(100) NOT NULL,
    `ssn_ciphertext` VARBINARY(512) NULL,
    `ssn_fingerprint` BINARY(32) NULL COMMENT '本表唯一，不用于跨角色去重',
    `deleted_at` DATETIME(3) NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_professors_account` (`account_id`),
    UNIQUE KEY `uk_professors_ssn` (`ssn_fingerprint`),
    CONSTRAINT `fk_professors_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_professors_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT `ck_professors_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='教师档案；逻辑删除';

-- 二、学期与课程目录
-- 先建学期，活动快照外键在快照表创建后补充，全程保持外键检查开启。

CREATE TABLE IF NOT EXISTS `semester` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `start_date` DATE NOT NULL COMMENT '本地校历教学开始日期',
    `end_date` DATE NOT NULL COMMENT '本地校历教学结束日期',
    `registration_start` DATETIME(3) NOT NULL COMMENT '选课开始 UTC，包含边界',
    `registration_end` DATETIME(3) NOT NULL COMMENT '常规选课结束 UTC',
    `add_drop_end` DATETIME(3) NOT NULL COMMENT '加退课结束 UTC，不包含边界',
    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PREPARATION',
    `active_snapshot_id` BIGINT NULL COMMENT '准备阶段允许为空，发布完成后设置',
    `currency` CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
    `closed_at` DATETIME(3) NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semester_code` (`code`),
    UNIQUE KEY `uk_semester_active_snapshot` (`active_snapshot_id`),
    CONSTRAINT `ck_semester_dates` CHECK (`start_date` < `end_date`),
    CONSTRAINT `ck_semester_registration_window` CHECK (
        `registration_start` < `registration_end` AND `registration_end` <= `add_drop_end`),
    CONSTRAINT `ck_semester_status` CHECK (`status` IN ('PREPARATION', 'OPEN', 'CLOSED')),
    CONSTRAINT `ck_semester_currency` CHECK (REGEXP_LIKE(`currency`, '^[A-Z]{3}$', 'c')),
    CONSTRAINT `ck_semester_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学期与注册门锁';

CREATE TABLE IF NOT EXISTS `catalog_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `semester_id` BIGINT NOT NULL,
    `source_version` VARCHAR(128) COLLATE utf8mb4_0900_as_cs NULL,
    `content_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '目录内容摘要',
    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STAGING',
    `fetched_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `verified_at` DATETIME(3) NULL,
    `published_at` DATETIME(3) NULL,
    `source_kind` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `last_error` VARCHAR(500) NULL COMMENT '脱敏错误摘要',
    PRIMARY KEY (`id`),
    KEY `idx_snapshot_semester_status` (`semester_id`, `status`),
    CONSTRAINT `fk_snapshot_semester` FOREIGN KEY (`semester_id`) REFERENCES `semester` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_snapshot_status` CHECK (`status` IN ('STAGING', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT `ck_snapshot_source_kind` CHECK (`source_kind` IN ('LEGACY', 'MOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有版本的课程目录快照';

-- MySQL 没有 ADD CONSTRAINT IF NOT EXISTS，查询元数据后只在首次执行时添加。
SET @crs_active_snapshot_fk_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'semester'
          AND CONSTRAINT_NAME = 'fk_semester_active_snapshot' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    ),
    'DO 0',
    'ALTER TABLE `semester` ADD CONSTRAINT `fk_semester_active_snapshot` FOREIGN KEY (`active_snapshot_id`) REFERENCES `catalog_snapshot` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT'
);
PREPARE crs_add_active_snapshot_fk FROM @crs_active_snapshot_fk_sql;
EXECUTE crs_add_active_snapshot_fk;
DEALLOCATE PREPARE crs_add_active_snapshot_fk;
SET @crs_active_snapshot_fk_sql = NULL;

CREATE TABLE IF NOT EXISTS `courses` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `snapshot_id` BIGINT NOT NULL,
    `legacy_course_id` VARCHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '跨快照匹配先修的外部稳定 ID',
    `code` VARCHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `name` VARCHAR(200) NOT NULL,
    `department` VARCHAR(100) NOT NULL,
    `credits` DECIMAL(4,1) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_courses_snapshot_legacy` (`snapshot_id`, `legacy_course_id`),
    KEY `idx_courses_snapshot_department_code` (`snapshot_id`, `department`, `code`),
    CONSTRAINT `fk_courses_snapshot` FOREIGN KEY (`snapshot_id`) REFERENCES `catalog_snapshot` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_courses_credits` CHECK (`credits` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按快照隔离的课程镜像';

CREATE TABLE IF NOT EXISTS `course_prerequisite` (
    `course_id` BIGINT NOT NULL,
    `prerequisite_course_id` BIGINT NOT NULL,
    PRIMARY KEY (`course_id`, `prerequisite_course_id`),
    KEY `idx_prerequisite_reverse` (`prerequisite_course_id`),
    CONSTRAINT `fk_prerequisite_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_prerequisite_required_course` FOREIGN KEY (`prerequisite_course_id`) REFERENCES `courses` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_prerequisite_not_self` CHECK (`course_id` <> `prerequisite_course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='直接先修关系；导入服务校验同快照及无环';

CREATE TABLE IF NOT EXISTS `course_offering` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `semester_id` BIGINT NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `course_id` BIGINT NOT NULL,
    `legacy_offering_id` VARCHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `section_code` VARCHAR(32) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `professor_id` BIGINT NULL COMMENT '本地实际授课教师，目录同步不得覆盖',
    `source_teacher_name` VARCHAR(100) NULL COMMENT '目录源教师姓名，仅作参考',
    `min_students` SMALLINT NOT NULL DEFAULT 3,
    `max_students` SMALLINT NOT NULL DEFAULT 10,
    `enrolled_count` SMALLINT NOT NULL DEFAULT 0 COMMENT '只统计 ENROLLED，由注册事务维护',
    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
    `cancel_reason` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    `tuition` DECIMAL(10,2) NOT NULL COMMENT '教学班固定费用，开放后冻结',
    `currency` CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_offering_snapshot_legacy` (`snapshot_id`, `legacy_offering_id`),
    KEY `idx_offering_professor_semester_status` (`professor_id`, `semester_id`, `status`),
    KEY `idx_offering_semester_status_course` (`semester_id`, `status`, `course_id`),
    KEY `idx_offering_course` (`course_id`),
    CONSTRAINT `fk_offering_semester` FOREIGN KEY (`semester_id`) REFERENCES `semester` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_offering_snapshot` FOREIGN KEY (`snapshot_id`) REFERENCES `catalog_snapshot` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_offering_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_offering_professor` FOREIGN KEY (`professor_id`) REFERENCES `professors` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_offering_capacity` CHECK (`min_students` = 3 AND `max_students` = 10),
    CONSTRAINT `ck_offering_count` CHECK (`enrolled_count` BETWEEN 0 AND `max_students`),
    CONSTRAINT `ck_offering_status` CHECK (`status` IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT `ck_offering_cancel_reason` CHECK (
        `cancel_reason` IS NULL OR `cancel_reason` IN ('NO_PROFESSOR', 'BELOW_MINIMUM')),
    CONSTRAINT `ck_offering_tuition` CHECK (`tuition` >= 0),
    CONSTRAINT `ck_offering_currency` CHECK (REGEXP_LIKE(`currency`, '^[A-Z]{3}$', 'c')),
    CONSTRAINT `ck_offering_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='教学班目录与本地任课、注册状态';

CREATE TABLE IF NOT EXISTS `offering_meeting` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `offering_id` BIGINT NOT NULL,
    `week_no` SMALLINT NOT NULL,
    `weekday` SMALLINT NOT NULL COMMENT '星期一为 1，星期日为 7',
    `start_period` SMALLINT NOT NULL,
    `end_period_exclusive` SMALLINT NOT NULL COMMENT '结束节次不含此值；1~2 节存 [1,3)',
    `room` VARCHAR(100) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_meeting_slot` (`offering_id`, `week_no`, `weekday`, `start_period`, `end_period_exclusive`),
    CONSTRAINT `fk_meeting_offering` FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_meeting_week` CHECK (`week_no` BETWEEN 1 AND 30),
    CONSTRAINT `ck_meeting_weekday` CHECK (`weekday` BETWEEN 1 AND 7),
    CONSTRAINT `ck_meeting_periods` CHECK (
        `start_period` BETWEEN 1 AND 13 AND `end_period_exclusive` BETWEEN 1 AND 13
        AND `start_period` < `end_period_exclusive`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按实际教学周展开的上课时间';

CREATE TABLE IF NOT EXISTS `professor_qualification` (
    `professor_id` BIGINT NOT NULL,
    `course_id` BIGINT NOT NULL,
    `source_ref` VARCHAR(128) NULL,
    `imported_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`professor_id`, `course_id`),
    KEY `idx_qualification_course` (`course_id`),
    CONSTRAINT `fk_qualification_professor` FOREIGN KEY (`professor_id`) REFERENCES `professors` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_qualification_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='教师授课资格；注册开始前导入';

-- 三、课表、注册与成绩

CREATE TABLE IF NOT EXISTS `schedule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` BIGINT NOT NULL,
    `semester_id` BIGINT NOT NULL,
    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    `draft_revision` BIGINT NULL COMMENT '当前草稿逻辑版本；空方案也有合法版本',
    `submitted_revision` BIGINT NULL COMMENT '已提交逻辑版本；不指向 choice 主键',
    `latest_revision` BIGINT NOT NULL DEFAULT 0,
    `first_submitted_at` DATETIME(3) NULL COMMENT '首次成功提交时间；删除课表也不重置',
    `version` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_schedule_student_semester` (`student_id`, `semester_id`),
    KEY `idx_schedule_semester_submitted_student` (`semester_id`, `first_submitted_at`, `student_id`),
    CONSTRAINT `fk_schedule_student` FOREIGN KEY (`student_id`) REFERENCES `students` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_schedule_semester` FOREIGN KEY (`semester_id`) REFERENCES `semester` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_schedule_status` CHECK (`status` IN ('DRAFT', 'REGISTERED', 'WITHDRAWN', 'FINALIZED', 'EXPIRED')),
    CONSTRAINT `ck_schedule_latest_revision` CHECK (`latest_revision` >= 0),
    CONSTRAINT `ck_schedule_draft_revision` CHECK (
        `draft_revision` IS NULL OR `draft_revision` BETWEEN 1 AND `latest_revision`),
    CONSTRAINT `ck_schedule_submitted_revision` CHECK (
        `submitted_revision` IS NULL OR `submitted_revision` BETWEEN 1 AND `latest_revision`),
    CONSTRAINT `ck_schedule_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学生学期课表聚合根';

CREATE TABLE IF NOT EXISTS `schedule_choice` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `schedule_id` BIGINT NOT NULL,
    `revision` BIGINT NOT NULL COMMENT '由课表行锁事务递增分配，保存后不可变',
    `offering_id` BIGINT NOT NULL,
    `kind` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `priority` SMALLINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_choice_revision_offering` (`schedule_id`, `revision`, `offering_id`),
    UNIQUE KEY `uk_choice_revision_priority` (`schedule_id`, `revision`, `kind`, `priority`),
    KEY `idx_choice_offering` (`offering_id`),
    CONSTRAINT `fk_choice_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_choice_offering` FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_choice_revision` CHECK (`revision` > 0),
    CONSTRAINT `ck_choice_kind_priority` CHECK (
        (`kind` = 'PRIMARY' AND `priority` BETWEEN 1 AND 4)
        OR (`kind` = 'ALTERNATE' AND `priority` BETWEEN 1 AND 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变版本的主选及备选意向；不占位';

CREATE TABLE IF NOT EXISTS `enrollment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `schedule_id` BIGINT NOT NULL,
    `offering_id` BIGINT NOT NULL,
    `state` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ENROLLED',
    `source` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `reason` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'USER_DROP/SCHEDULE_DELETE/NO_PROFESSOR/BELOW_MINIMUM 等',
    `enrolled_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_enrollment_schedule_offering` (`schedule_id`, `offering_id`),
    KEY `idx_enrollment_offering_state` (`offering_id`, `state`),
    KEY `idx_enrollment_schedule_state` (`schedule_id`, `state`),
    CONSTRAINT `fk_enrollment_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_enrollment_offering` FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_enrollment_state` CHECK (`state` IN ('ENROLLED', 'DROPPED', 'CANCELLED')),
    CONSTRAINT `ck_enrollment_source` CHECK (`source` IN ('PRIMARY', 'ALTERNATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际注册；重新选回同班复用原记录';

CREATE TABLE IF NOT EXISTS `grade` (
    `enrollment_id` BIGINT NOT NULL COMMENT '一条实际注册最多一条当前成绩',
    `value` CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `recorded_by` BIGINT NOT NULL COMMENT '实际授课教师 ID，归属由服务验证',
    `recorded_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `version` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`enrollment_id`),
    KEY `idx_grade_recorded_by` (`recorded_by`),
    CONSTRAINT `fk_grade_enrollment` FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_grade_professor` FOREIGN KEY (`recorded_by`) REFERENCES `professors` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_grade_value` CHECK (`value` IN ('A', 'B', 'C', 'D', 'F', 'I')),
    CONSTRAINT `ck_grade_version` CHECK (`version` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前字母成绩；无行代表未录入，I 代表不完整';

-- 四、注册关闭、计费与审计

CREATE TABLE IF NOT EXISTS `registration_close` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `semester_id` BIGINT NOT NULL,
    `run_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关闭操作 UUID',
    `registrar_account_id` BIGINT NOT NULL,
    `closed_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `summary` JSON NOT NULL COMMENT '取消、补位、学生与账单数量等摘要',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_close_semester` (`semester_id`),
    UNIQUE KEY `uk_close_run` (`run_id`),
    KEY `idx_close_registrar` (`registrar_account_id`),
    CONSTRAINT `fk_close_semester` FOREIGN KEY (`semester_id`) REFERENCES `semester` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_close_registrar` FOREIGN KEY (`registrar_account_id`) REFERENCES `accounts` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成功关闭结果；与业务关闭在同一事务中提交';

CREATE TABLE IF NOT EXISTS `billing_invoice` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `schedule_id` BIGINT NOT NULL,
    `student_id` BIGINT NOT NULL,
    `semester_id` BIGINT NOT NULL,
    `close_id` BIGINT NOT NULL,
    `total_amount` DECIMAL(10,2) NOT NULL COMMENT '最终总额，零门正式课表为 0.00',
    `currency` CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invoice_schedule` (`schedule_id`),
    UNIQUE KEY `uk_invoice_student_semester` (`student_id`, `semester_id`),
    KEY `idx_invoice_semester` (`semester_id`),
    KEY `idx_invoice_close` (`close_id`),
    CONSTRAINT `fk_invoice_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `schedule` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_invoice_student` FOREIGN KEY (`student_id`) REFERENCES `students` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_invoice_semester` FOREIGN KEY (`semester_id`) REFERENCES `semester` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_invoice_close` FOREIGN KEY (`close_id`) REFERENCES `registration_close` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_invoice_amount` CHECK (`total_amount` >= 0),
    CONSTRAINT `ck_invoice_currency` CHECK (REGEXP_LIKE(`currency`, '^[A-Z]{3}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变最终计费快照';

CREATE TABLE IF NOT EXISTS `billing_invoice_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `invoice_id` BIGINT NOT NULL,
    `offering_id` BIGINT NOT NULL,
    `course_code` VARCHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `course_name` VARCHAR(200) NOT NULL,
    `section_code` VARCHAR(32) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `professor_name` VARCHAR(100) NOT NULL,
    `meeting_snapshot` JSON NOT NULL COMMENT '关闭时完整上课时间列表',
    `amount` DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invoice_item_offering` (`invoice_id`, `offering_id`),
    KEY `idx_invoice_item_offering` (`offering_id`),
    CONSTRAINT `fk_invoice_item_invoice` FOREIGN KEY (`invoice_id`) REFERENCES `billing_invoice` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_invoice_item_offering` FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_invoice_item_amount` CHECK (`amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='最终课表与费用明细快照';

CREATE TABLE IF NOT EXISTS `outbox_event` (
    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '重试全过程保持同一 UUID',
    `invoice_id` BIGINT NOT NULL,
    `event_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INVOICE_FINALIZED',
    `payload` JSON NOT NULL COMMENT '冻结的财务负载；不含密码、SSN 或完整历史成绩',
    `state` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    `attempts` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `locked_until` DATETIME(3) NULL,
    `claim_token` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '每次领取生成的新租约 UUID',
    `last_error` VARCHAR(500) NULL,
    `delivered_at` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`event_id`),
    UNIQUE KEY `uk_outbox_invoice_type` (`invoice_id`, `event_type`),
    KEY `idx_outbox_due` (`state`, `next_attempt_at`),
    KEY `idx_outbox_lease` (`state`, `locked_until`),
    CONSTRAINT `fk_outbox_invoice` FOREIGN KEY (`invoice_id`) REFERENCES `billing_invoice` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `ck_outbox_type` CHECK (`event_type` = 'INVOICE_FINALIZED'),
    CONSTRAINT `ck_outbox_state` CHECK (`state` IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED')),
    CONSTRAINT `ck_outbox_attempts` CHECK (`attempts` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='财务事务消息；至少一次投递及接收端幂等';

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `actor_account_id` BIGINT NULL COMMENT '系统事件可为空',
    `action` VARCHAR(64) NOT NULL,
    `target_type` VARCHAR(32) NOT NULL,
    `target_id` VARCHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    `request_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `occurred_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `detail` JSON NOT NULL COMMENT '必要变更与原因；禁止写入密码和完整敏感标识',
    PRIMARY KEY (`id`),
    KEY `idx_audit_target_time` (`target_type`, `target_id`, `occurred_at`),
    KEY `idx_audit_actor_time` (`actor_account_id`, `occurred_at`),
    KEY `idx_audit_request` (`request_id`),
    CONSTRAINT `fk_audit_actor` FOREIGN KEY (`actor_account_id`) REFERENCES `accounts` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控业务审计';

-- 五、可选教务员初始化
-- 如需创建初始教务员，先在同一个 SQL 会话中从本地配置设置以下变量，再执行本文件：
-- SET @crs_registrar_username = 'registrar';
-- SET @crs_registrar_password_hash = '<由应用 BCrypt 编码器生成的 60 字符摘要>';
-- 不设置有效摘要时仅创建表；已有同名账号不覆盖、不提升角色、不重置密码。
-- 不要用 MySQL SHA2/MD5/PASSWORD 函数替代 BCrypt，也不要把真实密码或密钥写入本文件。

SET @crs_registrar_username = COALESCE(@crs_registrar_username, 'registrar');
INSERT INTO `accounts` (`username`, `password_hash`, `role`, `enabled`)
SELECT @crs_registrar_username, @crs_registrar_password_hash, 'REGISTRAR', TRUE
WHERE @crs_registrar_password_hash IS NOT NULL
  AND CHAR_LENGTH(@crs_registrar_username) BETWEEN 1 AND 64
  AND REGEXP_LIKE(CAST(@crs_registrar_password_hash AS CHAR CHARACTER SET utf8mb4),
                  '^[$]2[aby][$](0[4-9]|[12][0-9]|3[01])[$][./A-Za-z0-9]{53}$', 'c')
  AND NOT EXISTS (SELECT 1 FROM `accounts` WHERE `username` = @crs_registrar_username);
SET @crs_registrar_password_hash = NULL;
SET @crs_registrar_username = NULL;

-- 业务数据导入顺序：
-- 1. 创建 PREPARATION 学期，active_snapshot_id 保持 NULL。
-- 2. 导入 STAGING 快照、课程、先修、教学班、时间与教师资格。
-- 3. 完整校验通过后，在事务中发布快照并更新学期 active_snapshot_id，再开放为 OPEN。
-- 4. 当前及历史学期、边界测试数据使用独立导入脚本；本文件不伪造目录或已注册数据。
