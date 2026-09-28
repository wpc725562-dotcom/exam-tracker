-- =============================================================================
--  exam-tracker 建库建表脚本
--  -----------------------------------------------------------------------------
--  用法（需要 root 或具备建库/授权权限的账号）：
--    mysql -h 127.0.0.1 -P 3308 -uroot -p < sql/schema.sql
--
--  **幂等**：全部用 IF NOT EXISTS，重复执行不会报错、也不会清数据。
--  想从零开始，先 `DROP DATABASE exam_tracker;` 再跑一遍。
--
--  为什么表结构写在 SQL 里、而不是靠 JPA 的 ddl-auto=update 自动生成：
--    · update 会「顺手」改表，加一个字段可能悄悄改掉列类型或丢索引；
--    · 生产环境的表结构变更必须走审核、可回溯，不能由应用启动时的副作用决定；
--    · 所以应用侧配的是 **validate** —— 实体和表对不上就直接启动失败，
--      让问题在部署那一刻暴露，而不是等某条 SQL 在运行时炸掉。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
--  1. 库与账号
-- -----------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `exam_tracker`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 业务账号（只对本库有权限）。如果这个账号不存在，取消下面两行的注释。
-- 已有环境里通常已经建好了 `dev`，重复执行 GRANT 是安全的。
-- CREATE USER IF NOT EXISTS 'dev'@'%' IDENTIFIED WITH mysql_native_password BY 'dev123456';
-- GRANT ALL PRIVILEGES ON `exam_tracker`.* TO 'dev'@'%';

USE `exam_tracker`;

-- -----------------------------------------------------------------------------
--  2. 用户
--  -----------------------------------------------------------------------------
--  表名用 app_user 而不是 user：user 在多个数据库里都是保留字或系统表名，
--  写 SQL 时到处要加反引号，换数据库还可能直接报错。
--
--  字符集/排序规则显式写出来，不依赖库的默认值 ——
--  否则将来有人改了库的默认字符集，新加的表就会和旧表不一致，
--  跨表 join 时会报 "Illegal mix of collations"。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `app_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)  NOT NULL                COMMENT '登录名，全局唯一',
  `password_hash` VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希（60 字符），绝不存明文',
  `nickname`      VARCHAR(50)  DEFAULT NULL            COMMENT '昵称',
  `exam_date`     DATE         DEFAULT NULL            COMMENT '考试日期，用于倒计时',
  `created_at`    DATETIME(6)  NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME(6)  NOT NULL                COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  -- 唯一索引是防重复注册的**唯一可靠防线**。
  -- 「先查再插」在并发下会漏（两个请求同时通过检查），这里才是最终兜底。
  UNIQUE KEY `uk_app_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户';

-- -----------------------------------------------------------------------------
--  3. 科目
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `subject` (
  `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`                 BIGINT      NOT NULL                COMMENT '归属用户',
  `name`                    VARCHAR(50) NOT NULL                COMMENT '科目名，同一用户下唯一',
  `color`                   VARCHAR(16) DEFAULT NULL            COMMENT '十六进制颜色，如 #4F46E5',
  `target_minutes_per_week` INT         NOT NULL                COMMENT '每周计划投入分钟数',
  `sort_order`              INT         NOT NULL                COMMENT '展示顺序',
  `created_at`              DATETIME(6) NOT NULL                COMMENT '创建时间',
  `updated_at`              DATETIME(6) NOT NULL                COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  -- 唯一性作用域是「同一个用户内」而不是全局 —— 两个人都可以有「数学」这门课
  UNIQUE KEY `uk_subject_user_name` (`user_id`, `name`),
  KEY `idx_subject_user` (`user_id`),
  CONSTRAINT `fk_subject_user` FOREIGN KEY (`user_id`)
    REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '科目';

-- -----------------------------------------------------------------------------
--  4. 任务
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `task` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      BIGINT       NOT NULL                COMMENT '归属用户',
  `subject_id`   BIGINT       NOT NULL                COMMENT '所属科目',
  `title`        VARCHAR(200) NOT NULL                COMMENT '任务标题',
  `plan_date`    DATE         NOT NULL                COMMENT '计划完成日期',
  `plan_minutes` INT          NOT NULL                COMMENT '计划投入分钟数',
  `priority`     VARCHAR(16)  NOT NULL                COMMENT 'HIGH / MEDIUM / LOW',
  `status`       VARCHAR(16)  NOT NULL                COMMENT 'TODO / DONE / SKIPPED',
  `note`         VARCHAR(500) DEFAULT NULL            COMMENT '备注',
  `completed_at` DATETIME(6)  DEFAULT NULL            COMMENT '完成时间，仅 DONE 时有值',
  `created_at`   DATETIME(6)  NOT NULL                COMMENT '创建时间',
  `updated_at`   DATETIME(6)  NOT NULL                COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  -- 复合索引的**列顺序很重要**：本项目所有查询都以 user_id 开头（数据隔离），
  -- 所以 user_id 必须放最左。反过来写成 (plan_date, user_id) 就用不上。
  KEY `idx_task_user_date`   (`user_id`, `plan_date`),
  KEY `idx_task_user_status` (`user_id`, `status`),
  KEY `idx_task_subject`     (`subject_id`),
  CONSTRAINT `fk_task_user` FOREIGN KEY (`user_id`)
    REFERENCES `app_user` (`id`) ON DELETE CASCADE,
  -- 科目删掉时任务一起删：删科目是用户明确确认过的动作（服务层要求 force=true），
  -- 留着一堆「属于已删除科目」的任务没有意义。
  CONSTRAINT `fk_task_subject` FOREIGN KEY (`subject_id`)
    REFERENCES `subject` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '每日任务';

-- -----------------------------------------------------------------------------
--  5. 打卡记录
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `checkin` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        BIGINT       NOT NULL                COMMENT '归属用户',
  `subject_id`     BIGINT       NOT NULL                COMMENT '科目',
  `task_id`        BIGINT       DEFAULT NULL            COMMENT '关联任务，可为空',
  `checkin_date`   DATE         NOT NULL                COMMENT '打卡日期',
  `actual_minutes` INT          NOT NULL                COMMENT '实际投入分钟数',
  `note`           VARCHAR(500) DEFAULT NULL            COMMENT '备注',
  `created_at`     DATETIME(6)  NOT NULL                COMMENT '创建时间',
  `updated_at`     DATETIME(6)  NOT NULL                COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_checkin_user_date` (`user_id`, `checkin_date`),
  KEY `idx_checkin_subject`   (`subject_id`),
  CONSTRAINT `fk_checkin_user` FOREIGN KEY (`user_id`)
    REFERENCES `app_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_checkin_subject` FOREIGN KEY (`subject_id`)
    REFERENCES `subject` (`id`) ON DELETE CASCADE,
  -- ★ 这里是 SET NULL 而不是 CASCADE，是一个刻意的设计决定：
  --   任务可以删（「整理待办」），但「那天确实学了 90 分钟」是既成事实，不该跟着消失。
  --   如果级联删除，用户删几个任务后会发现累计时长和连续打卡天数莫名其妙变少了。
  CONSTRAINT `fk_checkin_task` FOREIGN KEY (`task_id`)
    REFERENCES `task` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '学习打卡记录';

-- -----------------------------------------------------------------------------
--  6. 自检
-- -----------------------------------------------------------------------------
SELECT
  DATABASE()                AS current_database,
  @@character_set_database  AS charset,
  @@collation_database      AS collation;

SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'exam_tracker'
ORDER BY TABLE_NAME;
