-- 高校课程注册系统：课程设计模拟演示数据（MySQL 8.0+/9.6）
-- 前提：先执行 04-MySQL9.6初始化.sql，使用普通 MySQL profile 运行后端。
-- 本脚本只重建数据，不修改表结构；不要在 H2 demo profile 中执行。
-- 执行前停止后端，执行完成后重新启动并登录，避免缓存/并发写入影响演示。
-- 执行账号需要业务表读写、临时表以及 CREATE ROUTINE / ALTER ROUTINE / EXECUTE 权限。
-- mysql 客户端执行：
-- SOURCE D:/AAAMyProjects/courseRegistrationSystem/课程设计文档/08-模拟演示数据.sql;
-- MySQL Workbench 可打开本文件并执行整个脚本。
--
-- 覆盖范围：当前库的全部 19 张业务表，以及存在时的 Spring Session 登录会话。
-- 旧账号、师生、目录、选课、成绩、账单、通知、审计都会被覆盖；只用于课程设计演示。
-- DELETE 按外键依赖顺序执行，不禁用外键、不 TRUNCATE。异常自动回滚所有数据变更。
-- 显式使用固定主键，可反复运行；保留 AUTO_INCREMENT 高水位，新增业务仍可正常写入。
-- 所有姓名和人员资料均为虚构；SSN 留空，避免依赖机器上的加密密钥。
-- 所有 29 个账号的密码均为 demo-only-2026，下面使用已验证的 BCrypt(cost=12) 摘要。
--
-- 学期日期以执行时刻为基准：业务时刻存 UTC，教学日期使用 Asia/Shanghai。
-- 1 / DEMO-ENDED：教学已结束 150 天，注册已关闭；有成绩和最终账单，可演示成绩录入。
-- 2 / DEMO-ENDING：教学还有 7 天结束，注册已关闭；有最终课表和账单，暂不能录成绩。
-- 3 / DEMO-OPEN：新学期，正在选课；教学 14 天后开始，加退课 14 天后截止。
-- CLOSED 在本项目中表示注册关闭，教学是否结束还必须比较 end_date。
--
-- 规模：20 名学生（18 在读、1 停用、1 毕业）、8 名教师、1 名教务员；
--       12 门不同课程，每学期独立镜像，共 36 条课程、42 个教学班、672 个上课时间。
--       53 张课表、34 张最终账单，以及成绩、取消/退课、草稿、教师资格、审计。
--
-- 推荐演示：
-- registrar.demo：师生维护；历史关闭结果/名单/财务通知；新学期立即截止并关闭注册。
-- professor.demo：张明，查看授课计划和名单；在 DEMO-ENDED 中录入/修改成绩。
-- professor.02 ~ professor.08：其余教师；professor.07 可认领新学期操作系统教学班。
-- student.demo：林晨，已正式选课，历史有备选补位；可展示课表、成绩、退课。
-- student.02 ~ student.15：其他已注册学生；student.14 有单门退课，student.15 有小班选课。
-- student.16：整张课表已撤回，可重新建立草稿并提交。
-- student.17：没有历史先修成绩，新学期有可直接提交的“四主选+两备选”草稿。
-- student.18：草稿包含满员、先修不满足、时间冲突，可展示校验提示后修改草稿。
-- student.19：停用账号；student.20：毕业学生。两者均不能登录，可演示账号状态限制。
--
-- 新学期关闭演示：操作系统(3010)未分配教师，student.11~13 的主选将取消，
-- 随后补位到备选计算机导论(3004)；线性代数(3005)仅 1 人，会因低于 3 人取消。
-- 如先认领操作系统或提交新草稿，关闭结果会随真实业务变化。
-- 建议本机保持 BILLING_DISPATCH_ENABLED=false，查看模拟通知而不连接财务服务。
-- 目录默认 24 小时过期；下次演示可重跑本脚本，或仅执行以下语句保留业务变更：
-- UPDATE catalog_snapshot SET verified_at=UTC_TIMESTAMP(3)
-- WHERE source_kind='MOCK' AND source_version='COURSE-DESIGN-DEMO-V1';

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `course_registration_system`;

DROP PROCEDURE IF EXISTS `crs_seed_course_design_demo`;
DELIMITER $$
CREATE PROCEDURE `crs_seed_course_design_demo`()
BEGIN
    DECLARE v_now DATETIME(3);
    DECLARE v_today DATE;
    DECLARE v_time_zone VARCHAR(64);
    DECLARE v_password_hash VARCHAR(60) DEFAULT
        '$2a$12$4XXwUaqT5pmeEkcsZUzRcun3L3upEWN2j.SaDu5RHzFVRC29wcGZi';

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS crs_demo_students, crs_demo_professors,
            crs_demo_courses, crs_demo_slots, crs_demo_weeks, crs_demo_plans,
            crs_demo_meetings_json, crs_demo_invoice_json;
        SET SESSION time_zone = v_time_zone;
        RESIGNAL;
    END;

    SET v_time_zone = @@session.time_zone;
    SET SESSION time_zone = '+00:00';
    SET v_now = UTC_TIMESTAMP(3);
    SET v_today = DATE(DATE_ADD(v_now, INTERVAL 8 HOUR));

    DROP TEMPORARY TABLE IF EXISTS crs_demo_students, crs_demo_professors,
        crs_demo_courses, crs_demo_slots, crs_demo_weeks, crs_demo_plans,
        crs_demo_meetings_json, crs_demo_invoice_json;

    START TRANSACTION;

    -- 一、清理旧数据。会话表未初始化时也允许执行。
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema=DATABASE() AND table_name='SPRING_SESSION_ATTRIBUTES') THEN
        DELETE FROM SPRING_SESSION_ATTRIBUTES;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema=DATABASE() AND table_name='SPRING_SESSION') THEN
        DELETE FROM SPRING_SESSION;
    END IF;
    DELETE FROM audit_log;
    DELETE FROM outbox_event;
    DELETE FROM billing_invoice_item;
    DELETE FROM billing_invoice;
    DELETE FROM registration_close;
    DELETE FROM grade;
    DELETE FROM enrollment;
    DELETE FROM schedule_choice;
    DELETE FROM schedule;
    DELETE FROM professor_qualification;
    DELETE FROM offering_meeting;
    DELETE FROM course_offering;
    DELETE FROM course_prerequisite;
    DELETE FROM courses;
    UPDATE semester SET active_snapshot_id=NULL;
    DELETE FROM catalog_snapshot;
    DELETE FROM semester;
    DELETE FROM professors;
    DELETE FROM students;
    DELETE FROM accounts;

    -- 二、师生及可登录账号。
    CREATE TEMPORARY TABLE crs_demo_students (
        id BIGINT PRIMARY KEY, name VARCHAR(100), birth_date DATE,
        status VARCHAR(20), graduation_date DATE
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_students VALUES
        (1,'林晨','2005-03-12','ACTIVE',NULL),
        (2,'苏雨桐','2005-07-08','ACTIVE',NULL),
        (3,'陈子航','2004-11-21','ACTIVE',NULL),
        (4,'王欣怡','2005-01-16','ACTIVE',NULL),
        (5,'刘宇轩','2004-09-05','ACTIVE',NULL),
        (6,'赵思涵','2005-04-23','ACTIVE',NULL),
        (7,'周景然','2005-06-30','ACTIVE',NULL),
        (8,'吴可欣','2004-12-09','ACTIVE',NULL),
        (9,'郑明哲','2005-02-14','ACTIVE',NULL),
        (10,'孙若曦','2005-08-19','ACTIVE',NULL),
        (11,'何书远','2004-10-02','ACTIVE',NULL),
        (12,'徐梓宁','2005-05-11','ACTIVE',NULL),
        (13,'高一帆','2004-06-27','ACTIVE',NULL),
        (14,'马诗悦','2005-09-03','ACTIVE',NULL),
        (15,'罗嘉树','2004-04-18','ACTIVE',NULL),
        (16,'梁知夏','2005-12-06','ACTIVE',NULL),
        (17,'宋安然','2006-03-24','ACTIVE',NULL),
        (18,'唐星野','2006-08-15','ACTIVE',NULL),
        (19,'许文博','2004-07-01','INACTIVE',NULL),
        (20,'沈清禾','2003-02-20','GRADUATED',DATE_SUB(v_today, INTERVAL 100 DAY));

    CREATE TEMPORARY TABLE crs_demo_professors (
        id BIGINT PRIMARY KEY, name VARCHAR(100), birth_date DATE, department VARCHAR(100)
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_professors VALUES
        (1,'张明','1980-04-12','计算机学院'),
        (2,'李静','1983-09-25','数学学院'),
        (3,'王芳','1986-02-18','外国语学院'),
        (4,'陈磊','1978-11-06','计算机学院'),
        (5,'刘颖','1984-06-30','计算机学院'),
        (6,'赵诚','1981-03-15','软件学院'),
        (7,'周敏','1987-08-22','计算机学院'),
        (8,'孙博','1982-12-09','人工智能学院');

    INSERT INTO accounts (id,username,password_hash,role,enabled,failed_attempts,version)
    VALUES (1,'registrar.demo',v_password_hash,'REGISTRAR',TRUE,0,0);
    INSERT INTO accounts (id,username,password_hash,role,enabled,failed_attempts,version)
    SELECT 100+id, IF(id=1,'student.demo',CONCAT('student.',LPAD(id,2,'0'))),
           v_password_hash,'STUDENT',id<>19,0,0 FROM crs_demo_students;
    INSERT INTO accounts (id,username,password_hash,role,enabled,failed_attempts,version)
    SELECT 200+id, IF(id=1,'professor.demo',CONCAT('professor.',LPAD(id,2,'0'))),
           v_password_hash,'PROFESSOR',TRUE,0,0 FROM crs_demo_professors;
    INSERT INTO students (id,account_id,name,status,birth_date,graduation_date,version)
    SELECT id,100+id,name,status,birth_date,graduation_date,0 FROM crs_demo_students;
    INSERT INTO professors (id,account_id,name,birth_date,status,department,version)
    SELECT id,200+id,name,birth_date,'ACTIVE',department,0 FROM crs_demo_professors;

    -- 三、三个学期及独立发布快照；先建学期，再设置活动快照，解决循环外键。
    INSERT INTO semester
        (id,code,name,start_date,end_date,registration_start,registration_end,
         add_drop_end,status,currency,closed_at,version)
    VALUES
        (1,'DEMO-ENDED','基础学期（已结束）',
         DATE_SUB(v_today,INTERVAL 260 DAY),DATE_SUB(v_today,INTERVAL 150 DAY),
         DATE_SUB(v_now,INTERVAL 290 DAY),DATE_SUB(v_now,INTERVAL 280 DAY),
         DATE_SUB(v_now,INTERVAL 270 DAY),'CLOSED','CNY',DATE_SUB(v_now,INTERVAL 270 DAY),0),
        (2,'DEMO-ENDING','进阶学期（7天后结束）',
         DATE_SUB(v_today,INTERVAL 105 DAY),DATE_ADD(v_today,INTERVAL 7 DAY),
         DATE_SUB(v_now,INTERVAL 130 DAY),DATE_SUB(v_now,INTERVAL 120 DAY),
         DATE_SUB(v_now,INTERVAL 110 DAY),'CLOSED','CNY',DATE_SUB(v_now,INTERVAL 110 DAY),0),
        (3,'DEMO-OPEN','新学期（选课中）',
         DATE_ADD(v_today,INTERVAL 14 DAY),DATE_ADD(v_today,INTERVAL 126 DAY),
         DATE_SUB(v_now,INTERVAL 3 DAY),DATE_ADD(v_now,INTERVAL 7 DAY),
         DATE_ADD(v_now,INTERVAL 14 DAY),'OPEN','CNY',NULL,0);
    INSERT INTO catalog_snapshot
        (id,semester_id,source_version,content_hash,status,fetched_at,verified_at,published_at,source_kind)
    SELECT id,id,'COURSE-DESIGN-DEMO-V1',NULL,'PUBLISHED',v_now,v_now,v_now,'MOCK'
    FROM semester;
    UPDATE semester SET active_snapshot_id=id;

    -- 四、12 门课程。legacy_course_id 在三个快照中稳定，供跨学期先修匹配。
    CREATE TEMPORARY TABLE crs_demo_courses (
        n INT PRIMARY KEY, code VARCHAR(64), name VARCHAR(200), department VARCHAR(100),
        credits DECIMAL(4,1), professor_id BIGINT, tuition DECIMAL(10,2)
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_courses VALUES
        (1,'CS101','程序设计基础','计算机学院',4.0,1,480.00),
        (2,'MA101','高等数学','数学学院',4.0,2,420.00),
        (3,'EN101','大学英语','外国语学院',3.0,3,300.00),
        (4,'CS102','计算机导论','计算机学院',2.0,4,240.00),
        (5,'MA102','线性代数','数学学院',3.0,2,360.00),
        (6,'CS103','离散数学','计算机学院',3.0,4,360.00),
        (7,'CS201','数据结构','计算机学院',4.0,1,520.00),
        (8,'CS202','数据库原理','计算机学院',3.0,5,450.00),
        (9,'SE201','软件工程','软件学院',3.0,6,450.00),
        (10,'CS203','操作系统','计算机学院',4.0,7,520.00),
        (11,'AI201','人工智能基础','人工智能学院',3.0,8,480.00),
        (12,'SE301','软件测试实践','软件学院',2.0,6,320.00);
    INSERT INTO courses (id,snapshot_id,legacy_course_id,code,name,department,credits)
    SELECT s.id*100+c.n,s.id,CONCAT('DEMO-',c.code),c.code,c.name,c.department,c.credits
    FROM semester s CROSS JOIN crs_demo_courses c;

    -- 基础学期不要求先修；后续学期直接先修：7/8/9/10 -> 1，11 -> 2，12 -> 7。
    -- 已选第二学期的数据结构尚未结束，不能作为第三学期软件测试的已通过先修。
    INSERT INTO course_prerequisite (course_id,prerequisite_course_id)
    SELECT s.id*100+c.n, s.id*100+CASE WHEN c.n=11 THEN 2 WHEN c.n=12 THEN 7 ELSE 1 END
    FROM semester s CROSS JOIN crs_demo_courses c WHERE s.id IN (2,3) AND c.n>=7;

    -- A 班覆盖全部课程；程序设计、高数各加一个 B 班。不同班仍属于同门课。
    -- 软件测试与大学英语故意同时间，只有未提交的错误草稿会同时包含它们。
    CREATE TEMPORARY TABLE crs_demo_slots (
        slot INT PRIMARY KEY, course_n INT, section_code VARCHAR(32),
        weekday INT, start_period INT, end_period_exclusive INT, room VARCHAR(100)
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_slots VALUES
        (1,1,'A',1,1,3,'教学楼A-101'),(2,2,'A',1,3,5,'教学楼A-201'),
        (3,3,'A',2,1,3,'教学楼B-101'),(4,4,'A',2,3,5,'教学楼A-102'),
        (5,5,'A',3,1,3,'教学楼A-202'),(6,6,'A',3,3,5,'教学楼A-103'),
        (7,7,'A',4,1,3,'实验楼-301'),(8,8,'A',4,3,5,'实验楼-302'),
        (9,9,'A',5,1,3,'软件楼-201'),(10,10,'A',5,3,5,'实验楼-303'),
        (11,11,'A',1,5,7,'智能楼-401'),(12,12,'A',2,1,3,'软件楼-202'),
        (13,1,'B',4,5,7,'教学楼A-104'),(14,2,'B',5,5,7,'教学楼A-203');
    INSERT INTO course_offering
        (id,semester_id,snapshot_id,course_id,legacy_offering_id,section_code,
         professor_id,source_teacher_name,min_students,max_students,enrolled_count,
         status,tuition,currency,version)
    SELECT s.id*1000+t.slot,s.id,s.id,s.id*100+t.course_n,
           CONCAT('DEMO-',c.code,'-',t.section_code),t.section_code,
           IF(t.slot=10 OR (s.id=1 AND t.slot=7),NULL,c.professor_id),
           NULL,3,10,0,'OPEN',c.tuition,'CNY',0
    FROM semester s CROSS JOIN crs_demo_slots t JOIN crs_demo_courses c ON c.n=t.course_n;
    CREATE TEMPORARY TABLE crs_demo_weeks (n INT PRIMARY KEY) ENGINE=InnoDB;
    INSERT INTO crs_demo_weeks VALUES (1),(2),(3),(4),(5),(6),(7),(8),
        (9),(10),(11),(12),(13),(14),(15),(16);
    INSERT INTO offering_meeting
        (id,offering_id,week_no,weekday,start_period,end_period_exclusive,room)
    SELECT o.id*100+w.n,o.id,w.n,t.weekday,t.start_period,t.end_period_exclusive,t.room
    FROM course_offering o JOIN crs_demo_slots t ON t.slot=MOD(o.id,1000)
    CROSS JOIN crs_demo_weeks w;

    INSERT INTO professor_qualification (professor_id,course_id,source_ref,imported_at)
    SELECT t.professor_id,c.id,'COURSE-DESIGN-DEMO',v_now
    FROM courses c JOIN crs_demo_courses t ON t.n=MOD(c.id,100);
    -- 周敏还具备软件测试资格，便于演示可认领课程与资格限制。
    INSERT INTO professor_qualification (professor_id,course_id,source_ref,imported_at)
    SELECT 7,id,'COURSE-DESIGN-DEMO',v_now FROM courses WHERE MOD(id,100)=12;

    -- 五、课表意向。每个首次提交都有四门主选、两门不同课程的备选。
    CREATE TEMPORARY TABLE crs_demo_plans (
        semester_id BIGINT, student_id BIGINT, p1 INT, p2 INT, p3 INT, p4 INT, a1 INT, a2 INT,
        PRIMARY KEY (semester_id,student_id)
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_plans
    SELECT 1,id,
           IF(id<=8 OR id=19,1,13),IF(id<=8 OR id=19,2,14),
           IF(id<=8 OR id=19,3,4),IF(id<=8 OR id=19,IF(id=1,7,5),6),
           IF(id<=8 OR id=19,IF(id=1,5,4),3),IF(id<=8 OR id=19,IF(id=1,4,6),5)
    FROM crs_demo_students WHERE id<=16 OR id IN (19,20);
    INSERT INTO crs_demo_plans
    SELECT 2,id,
           CASE WHEN id<=6 THEN 7 WHEN id<=12 OR id=17 THEN 3 ELSE 1 END,
           CASE WHEN id<=6 THEN 8 WHEN id<=12 OR id=17 THEN 4 ELSE 2 END,
           CASE WHEN id<=6 THEN 9 WHEN id<=12 OR id=17 THEN 5 ELSE 7 END,
           CASE WHEN id<=6 THEN IF(id=1,10,11) WHEN id<=12 OR id=17 THEN 6 ELSE 8 END,
           CASE WHEN id<=6 THEN IF(id=1,11,3) WHEN id<=12 THEN 7 WHEN id=17 THEN 1 ELSE 9 END,
           CASE WHEN id<=6 THEN 6 WHEN id<=12 THEN 8 WHEN id=17 THEN 2 ELSE 11 END
    FROM crs_demo_students WHERE id<=17;
    INSERT INTO crs_demo_plans
    SELECT 3,id,
           CASE WHEN id<=10 THEN 1 WHEN id<=13 THEN 6 WHEN id<=15 THEN 2
                WHEN id<=17 THEN 13 ELSE 1 END,
           CASE WHEN id<=5 THEN 2 WHEN id<=10 THEN 14 WHEN id<=13 THEN 9
                WHEN id<=15 THEN 5 WHEN id<=17 THEN 14 ELSE 3 END,
           CASE WHEN id<=5 THEN 3 WHEN id<=10 THEN 4 WHEN id<=15 THEN 11
                WHEN id=16 THEN 4 WHEN id=17 THEN 3 ELSE 7 END,
           CASE WHEN id<=5 THEN 7 WHEN id<=10 THEN 8 WHEN id<=13 THEN 10
                WHEN id<=15 THEN 9 WHEN id=16 THEN 8 WHEN id=17 THEN 6 ELSE 12 END,
           CASE WHEN id<=5 OR id BETWEEN 11 AND 13 THEN 4 WHEN id<=16 THEN 3 ELSE 9 END,
           CASE WHEN id<=5 OR id BETWEEN 11 AND 15 THEN 8 WHEN id<=10 THEN 9
                WHEN id=16 THEN 6 ELSE 11 END
    FROM crs_demo_students WHERE id<=18;

    INSERT INTO schedule
        (id,student_id,semester_id,status,draft_revision,submitted_revision,latest_revision,
         first_submitted_at,version,created_at,updated_at)
    SELECT p.semester_id*100+p.student_id,p.student_id,p.semester_id,
           CASE WHEN p.semester_id=2 AND p.student_id=17 THEN 'EXPIRED'
                WHEN p.semester_id<3 THEN 'FINALIZED' WHEN p.student_id=16 THEN 'WITHDRAWN'
                WHEN p.student_id>=17 THEN 'DRAFT' ELSE 'REGISTERED' END,
           IF(p.semester_id=3 AND p.student_id=16,NULL,1),
           IF((p.semester_id=3 AND p.student_id>=16) OR (p.semester_id=2 AND p.student_id=17),NULL,1),
           1,
           IF((p.semester_id=3 AND p.student_id>=17) OR (p.semester_id=2 AND p.student_id=17),NULL,
              DATE_ADD(DATE_ADD(s.registration_start,INTERVAL 1 DAY),INTERVAL p.student_id MINUTE)),
           0,s.registration_start,v_now
    FROM crs_demo_plans p JOIN semester s ON s.id=p.semester_id;

    INSERT INTO schedule_choice (id,schedule_id,revision,offering_id,kind,priority)
    SELECT s.id*1000+10+j.pos,s.id,1,p.semester_id*1000+j.slot,
           IF(j.pos<=4,'PRIMARY','ALTERNATE'),IF(j.pos<=4,j.pos,j.pos-4)
    FROM crs_demo_plans p JOIN schedule s ON s.id=p.semester_id*100+p.student_id
    JOIN JSON_TABLE(JSON_ARRAY(p.p1,p.p2,p.p3,p.p4,p.a1,p.a2),'$[*]'
         COLUMNS (pos FOR ORDINALITY,slot INT PATH '$')) j ON TRUE;

    INSERT INTO enrollment
        (id,schedule_id,offering_id,state,source,reason,enrolled_at,updated_at)
    SELECT s.id*100+MOD(c.offering_id,1000),s.id,c.offering_id,
           CASE WHEN s.semester_id=3 AND s.student_id=16 THEN 'DROPPED'
                WHEN s.id=101 AND MOD(c.offering_id,1000)=7 THEN 'CANCELLED'
                WHEN s.id=201 AND MOD(c.offering_id,1000)=10 THEN 'CANCELLED'
                WHEN s.id=314 AND MOD(c.offering_id,1000)=5 THEN 'DROPPED' ELSE 'ENROLLED' END,
           'PRIMARY',
           CASE WHEN s.semester_id=3 AND s.student_id=16 THEN 'SCHEDULE_WITHDRAWN'
                WHEN (s.id=101 AND MOD(c.offering_id,1000)=7)
                  OR (s.id=201 AND MOD(c.offering_id,1000)=10) THEN 'NO_PROFESSOR'
                WHEN s.id=314 AND MOD(c.offering_id,1000)=5 THEN 'STUDENT_DROPPED' ELSE NULL END,
           s.first_submitted_at,IF(s.semester_id<3,t.closed_at,v_now)
    FROM schedule s JOIN schedule_choice c ON c.schedule_id=s.id
    JOIN semester t ON t.id=s.semester_id
    WHERE s.first_submitted_at IS NOT NULL AND c.kind='PRIMARY';
    INSERT INTO enrollment
        (id,schedule_id,offering_id,state,source,reason,enrolled_at,updated_at)
    SELECT s.id*100+MOD(c.offering_id,1000),s.id,c.offering_id,'ENROLLED','ALTERNATE',NULL,
           t.closed_at,t.closed_at
    FROM schedule s JOIN semester t ON t.id=s.semester_id
    JOIN schedule_choice c ON c.schedule_id=s.id
    WHERE s.id IN (101,201) AND c.kind='ALTERNATE' AND c.priority=1;

    -- 林晨/何书远等保留正式课表；马诗悦(14)退掉线性代数后提交了第 2 个版本。
    INSERT INTO schedule_choice (id,schedule_id,revision,offering_id,kind,priority)
    SELECT schedule_id*1000+20+IF(kind='PRIMARY',
           IF(priority=1,1,priority-1),priority+3),schedule_id,2,offering_id,kind,
           IF(kind='PRIMARY',IF(priority=1,1,priority-1),priority)
    FROM schedule_choice WHERE schedule_id=314 AND revision=1 AND offering_id<>3005;
    UPDATE schedule SET draft_revision=2,submitted_revision=2,latest_revision=2,version=1
    WHERE id=314;

    -- 人数从有效注册反算，避免写入不匹配计数。关闭学期按最终人数取消未开成的班。
    UPDATE course_offering o SET enrolled_count=
        (SELECT COUNT(*) FROM enrollment e WHERE e.offering_id=o.id AND e.state='ENROLLED');
    UPDATE course_offering SET
        status=IF(professor_id IS NULL OR enrolled_count<3,'CANCELLED','CLOSED'),
        cancel_reason=CASE WHEN professor_id IS NULL THEN 'NO_PROFESSOR'
                           WHEN enrolled_count<3 THEN 'BELOW_MINIMUM' ELSE NULL END
    WHERE semester_id IN (1,2);

    -- 六、只为教学已结束的基础学期记录成绩。包括 A/B/C/D/F/I 以及一个未录入成绩。
    -- 林晨程序设计已通过；嘉树计算机导论 F、知夏离散数学 I、停用学生线性代数未录入。
    INSERT INTO grade (enrollment_id,value,recorded_by,recorded_at,updated_at,version)
    SELECT e.id,
           CASE WHEN s.student_id=15 AND c.code='CS102' THEN 'F'
                WHEN s.student_id=16 AND c.code='CS103' THEN 'I'
                ELSE ELT(1+MOD(s.student_id+MOD(c.id,100),4),'A','B','C','D') END,
           o.professor_id,DATE_SUB(v_now,INTERVAL 149 DAY),DATE_SUB(v_now,INTERVAL 149 DAY),0
    FROM enrollment e JOIN schedule s ON s.id=e.schedule_id
    JOIN course_offering o ON o.id=e.offering_id JOIN courses c ON c.id=o.course_id
    WHERE s.semester_id=1 AND e.state='ENROLLED'
      AND NOT (s.student_id=19 AND c.code='MA102');

    -- 七、关闭结果、不可变账单、明细、模拟财务消息。
    INSERT INTO registration_close (id,semester_id,run_id,registrar_account_id,closed_at,summary)
    SELECT s.id,s.id,CONCAT('50000000-0000-4000-8000-',LPAD(s.id,12,'0')),1,s.closed_at,
        JSON_OBJECT(
            'cancelledOfferings',(SELECT COUNT(*) FROM course_offering o
                                 WHERE o.semester_id=s.id AND o.status='CANCELLED'),
            'leveledSchedules',1,'promotedEnrollments',1,
            'billingEvents',(SELECT COUNT(*) FROM schedule p
                             WHERE p.semester_id=s.id AND p.first_submitted_at IS NOT NULL),
            'pendingBillingEvents',(SELECT COUNT(*) FROM schedule p
                                    WHERE p.semester_id=s.id AND p.first_submitted_at IS NOT NULL),
            'offerings',(SELECT JSON_ARRAYAGG(JSON_OBJECT(
                'offeringId',CAST(o.id AS CHAR),'courseName',c.name,'sectionCode',o.section_code,
                'status',o.status,'enrolledCount',o.enrolled_count,
                'cancelReason',COALESCE(o.cancel_reason,'')))
                FROM course_offering o JOIN courses c ON c.id=o.course_id WHERE o.semester_id=s.id))
    FROM semester s WHERE s.id IN (1,2);

    INSERT INTO billing_invoice
        (id,schedule_id,student_id,semester_id,close_id,total_amount,currency,created_at)
    SELECT s.id,s.id,s.student_id,s.semester_id,s.semester_id,
           COALESCE(SUM(o.tuition),0.00),'CNY',t.closed_at
    FROM schedule s JOIN semester t ON t.id=s.semester_id
    LEFT JOIN enrollment e ON e.schedule_id=s.id AND e.state='ENROLLED'
    LEFT JOIN course_offering o ON o.id=e.offering_id
    WHERE s.semester_id IN (1,2) AND s.first_submitted_at IS NOT NULL
    GROUP BY s.id,s.student_id,s.semester_id,t.closed_at;

    CREATE TEMPORARY TABLE crs_demo_meetings_json (
        offering_id BIGINT PRIMARY KEY, meetings JSON NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_meetings_json
    SELECT offering_id,JSON_ARRAYAGG(JSON_OBJECT('weekNo',week_no,'weekday',weekday,
        'startPeriod',start_period,'endPeriodExclusive',end_period_exclusive,'room',room))
    FROM offering_meeting GROUP BY offering_id;
    INSERT INTO billing_invoice_item
        (id,invoice_id,offering_id,course_code,course_name,section_code,professor_name,
         meeting_snapshot,amount)
    SELECT e.id,i.id,o.id,c.code,c.name,o.section_code,p.name,m.meetings,o.tuition
    FROM billing_invoice i JOIN enrollment e ON e.schedule_id=i.schedule_id AND e.state='ENROLLED'
    JOIN course_offering o ON o.id=e.offering_id JOIN courses c ON c.id=o.course_id
    JOIN professors p ON p.id=o.professor_id JOIN crs_demo_meetings_json m ON m.offering_id=o.id;

    CREATE TEMPORARY TABLE crs_demo_invoice_json (
        invoice_id BIGINT PRIMARY KEY, items JSON NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO crs_demo_invoice_json
    SELECT invoice_id,JSON_ARRAYAGG(JSON_OBJECT('offeringId',CAST(offering_id AS CHAR),
        'courseCode',course_code,'courseName',course_name,'sectionCode',section_code,
        'professorName',professor_name,'meetings',meeting_snapshot,'amount',CAST(amount AS CHAR)))
    FROM billing_invoice_item GROUP BY invoice_id;
    INSERT INTO outbox_event
        (event_id,invoice_id,event_type,payload,state,attempts,next_attempt_at,
         last_error,delivered_at,created_at,updated_at)
    SELECT CONCAT('60000000-0000-4000-8000-',LPAD(i.id,12,'0')),i.id,'INVOICE_FINALIZED',
           JSON_OBJECT('eventId',CONCAT('60000000-0000-4000-8000-',LPAD(i.id,12,'0')),
               'eventType','INVOICE_FINALIZED','studentId',CAST(i.student_id AS CHAR),
               'semesterId',CAST(i.semester_id AS CHAR),'semesterCode',s.code,
               'currency',i.currency,'items',COALESCE(j.items,JSON_ARRAY()),
               'totalAmount',CAST(i.total_amount AS CHAR)),
           CASE WHEN i.student_id IN (14,15) THEN 'PENDING'
                WHEN i.student_id=16 THEN 'RETRY' ELSE 'DELIVERED' END,
           CASE WHEN i.student_id IN (14,15) THEN 0 WHEN i.student_id=16 THEN 2 ELSE 1 END,
           IF(i.student_id IN (14,15,16),DATE_ADD(v_now,INTERVAL 1 DAY),i.created_at),
           IF(i.student_id=16,'模拟财务服务暂不可用',NULL),
           IF(i.student_id IN (14,15,16),NULL,DATE_ADD(i.created_at,INTERVAL 5 MINUTE)),
           i.created_at,v_now
    FROM billing_invoice i JOIN semester s ON s.id=i.semester_id
    LEFT JOIN crs_demo_invoice_json j ON j.invoice_id=i.id;

    INSERT INTO audit_log (id,actor_account_id,action,target_type,target_id,request_id,occurred_at,detail)
    SELECT id,1,'CLOSE_REGISTRATION','SEMESTER',CAST(id AS CHAR),
           CONCAT('70000000-0000-4000-8000-',LPAD(id,12,'0')),closed_at,
           JSON_OBJECT('synthetic',TRUE,'description','模拟学期注册关闭')
    FROM semester WHERE id IN (1,2);
    INSERT INTO audit_log (id,actor_account_id,action,target_type,target_id,request_id,occurred_at,detail)
    SELECT s.id,100+s.student_id,
           IF(s.student_id=16,'WITHDRAW_SCHEDULE',IF(s.student_id>=17,'SAVE_DRAFT','SUBMIT_SCHEDULE')),
           'SCHEDULE',CAST(s.id AS CHAR),CONCAT('70000000-0000-4000-8000-',LPAD(s.id,12,'0')),
           v_now,JSON_OBJECT('synthetic',TRUE)
    FROM schedule s WHERE s.semester_id=3;
    INSERT INTO audit_log (id,actor_account_id,action,target_type,target_id,request_id,occurred_at,detail)
    VALUES (999,1,'LOAD_DEMO_DATA','DATABASE',DATABASE(),
        '70000000-0000-4000-8000-000000000999',v_now,
        JSON_OBJECT('synthetic',TRUE,'semesters',3,'students',20,'professors',8));

    -- 八、提交前校验关键业务一致性。失败会回滚清理和全部插入。
    IF EXISTS (SELECT 1 FROM course_offering o WHERE o.enrolled_count<>
        (SELECT COUNT(*) FROM enrollment e WHERE e.offering_id=o.id AND e.state='ENROLLED')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo seed: enrollment count mismatch';
    END IF;
    IF EXISTS (SELECT schedule_id FROM enrollment WHERE state='ENROLLED'
               GROUP BY schedule_id HAVING COUNT(*)>4) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo seed: more than four enrolled courses';
    END IF;
    IF EXISTS (SELECT 1 FROM billing_invoice i WHERE i.total_amount<>
        (SELECT COALESCE(SUM(t.amount),0.00) FROM billing_invoice_item t WHERE t.invoice_id=i.id)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo seed: invoice total mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM enrollment e JOIN schedule s ON s.id=e.schedule_id
        JOIN course_offering o ON o.id=e.offering_id
        WHERE e.state='ENROLLED' AND s.semester_id<3
          AND (o.status<>'CLOSED' OR o.professor_id IS NULL OR o.enrolled_count<3)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo seed: invalid finalized offering';
    END IF;
    IF EXISTS (SELECT 1 FROM enrollment e JOIN schedule s ON s.id=e.schedule_id
        JOIN course_offering o ON o.id=e.offering_id
        JOIN course_prerequisite r ON r.course_id=o.course_id
        JOIN courses required_course ON required_course.id=r.prerequisite_course_id
        WHERE e.state='ENROLLED' AND NOT EXISTS (
            SELECT 1 FROM grade g JOIN enrollment passed_e ON passed_e.id=g.enrollment_id
            JOIN schedule passed_s ON passed_s.id=passed_e.schedule_id
            JOIN semester passed_term ON passed_term.id=passed_s.semester_id
            JOIN course_offering passed_o ON passed_o.id=passed_e.offering_id
            JOIN courses passed_c ON passed_c.id=passed_o.course_id
            WHERE passed_s.student_id=s.student_id AND passed_e.state='ENROLLED'
              AND passed_term.status='CLOSED' AND passed_term.end_date<v_today
              AND g.value IN ('A','B','C','D')
              AND passed_c.legacy_course_id=required_course.legacy_course_id)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo seed: prerequisite not passed';
    END IF;

    DROP TEMPORARY TABLE crs_demo_students, crs_demo_professors, crs_demo_courses,
        crs_demo_slots, crs_demo_weeks, crs_demo_plans, crs_demo_meetings_json, crs_demo_invoice_json;
    COMMIT;
    SET SESSION time_zone = v_time_zone;
END$$
DELIMITER ;

CALL `crs_seed_course_design_demo`();
DROP PROCEDURE `crs_seed_course_design_demo`;

-- 成功后显示学期日期、数量以及账号清单。
SELECT id,code,name,start_date,end_date,status FROM semester ORDER BY id;
SELECT '学生' AS category,COUNT(*) AS row_count FROM students
UNION ALL SELECT '教师',COUNT(*) FROM professors
UNION ALL SELECT '账号',COUNT(*) FROM accounts
UNION ALL SELECT '课程镜像（12门×3学期）',COUNT(*) FROM courses
UNION ALL SELECT '教学班',COUNT(*) FROM course_offering
UNION ALL SELECT '上课时间',COUNT(*) FROM offering_meeting
UNION ALL SELECT '课表',COUNT(*) FROM schedule
UNION ALL SELECT '有效选课',COUNT(*) FROM enrollment WHERE state='ENROLLED'
UNION ALL SELECT '成绩',COUNT(*) FROM grade
UNION ALL SELECT '最终账单',COUNT(*) FROM billing_invoice;
SELECT a.username,a.role,a.enabled,COALESCE(s.name,p.name,'教务演示员') AS name
FROM accounts a LEFT JOIN students s ON s.account_id=a.id
LEFT JOIN professors p ON p.account_id=a.id ORDER BY a.id;
