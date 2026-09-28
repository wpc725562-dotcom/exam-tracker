-- =============================================================================
--  exam-tracker 演示数据
--  -----------------------------------------------------------------------------
--  前置：先跑过 sql/schema.sql（本脚本假定库和表都已存在）。
--
--  用法：
--    mysql -h 127.0.0.1 -P 3308 -udev -pdev123456 < sql/seed.sql
--
--  ---------------------------------------------------------------------------
--  为什么是「先删再插」而不是 INSERT IGNORE / ON DUPLICATE KEY
--  ---------------------------------------------------------------------------
--  演示数据是**给人看的一份完整快照**，不是需要增量合并的业务数据。
--  用 DELETE 打头有两个好处：
--    ① 幂等 —— 重复执行一百次，结果都一模一样（增量写法会越灌越多）；
--    ② 可修正 —— 改了这个脚本里的数字，重跑一次就能看到新结果，
--       不用先去数据库里手工清理上一次灌进去的垃圾。
--  app_user 上的三条外键（subject / task / checkin）都是 ON DELETE CASCADE，
--  所以删掉这一个用户，他的科目、任务、打卡记录会一起干净地消失。
--  这个脚本**只动 username = 'demo' 这一个用户**，不会碰到任何真实数据。
--
--  ---------------------------------------------------------------------------
--  日期为什么用 CURDATE() 相对计算，而不是写死
--  ---------------------------------------------------------------------------
--  演示数据一旦写死绝对日期，过两个月再看就是「所有任务都过期了、
--  连续打卡天数归零」—— 演示价值归零。全部改成相对 CURDATE() 之后，
--  无论哪一天执行，看到的都是「最近两周很努力、今天还有几件事没做」的活数据。
-- =============================================================================

SET NAMES utf8mb4;
USE `exam_tracker`;

-- -----------------------------------------------------------------------------
--  0. 清掉上一次的演示数据（级联带走 subject / task / checkin）
-- -----------------------------------------------------------------------------
DELETE FROM `app_user` WHERE `username` = 'demo';

-- -----------------------------------------------------------------------------
--  1. 演示用户
--  -----------------------------------------------------------------------------
--  账号：demo / demo123456
--
--  这个哈希不是手写的，是 tools/GenBcrypt.java 用**项目自己的**
--  BCryptPasswordEncoder 算出来的（含自检：生成后立刻验一遍才输出）。
--  换密码时重跑：
--    java -cp "<spring-security-crypto.jar>;<spring-jcl.jar>" tools/GenBcrypt.java 新密码
--
--  exam_date 用「今天 + 167 天」：倒计时卡片永远显示一个像样的正数，
--  而不是写死之后某天变成负数。
-- -----------------------------------------------------------------------------
INSERT INTO `app_user` (`username`, `password_hash`, `nickname`, `exam_date`, `created_at`, `updated_at`)
VALUES ('demo',
        '$2a$10$PvtLJf4hkGWEzsgPsJuA8eTTlSVywpXBuaj9ngseU.g5iaEi.h6ta',
        '演示用户',
        DATE_ADD(CURDATE(), INTERVAL 167 DAY),
        NOW(6), NOW(6));

SET @uid = (SELECT `id` FROM `app_user` WHERE `username` = 'demo');

-- -----------------------------------------------------------------------------
--  2. 四个科目
--  -----------------------------------------------------------------------------
INSERT INTO `subject` (`user_id`, `name`, `color`, `target_minutes_per_week`, `sort_order`, `created_at`, `updated_at`)
VALUES (@uid, '数学',   '#4F46E5', 420, 1, NOW(6), NOW(6)),
       (@uid, '英语',   '#D85A30', 300, 2, NOW(6), NOW(6)),
       (@uid, '计算机', '#1D9E75', 480, 3, NOW(6), NOW(6)),
       (@uid, '语文',   '#BA7517', 180, 4, NOW(6), NOW(6));

SET @math = (SELECT `id` FROM `subject` WHERE `user_id` = @uid AND `name` = '数学');
SET @eng  = (SELECT `id` FROM `subject` WHERE `user_id` = @uid AND `name` = '英语');
SET @cs   = (SELECT `id` FROM `subject` WHERE `user_id` = @uid AND `name` = '计算机');
SET @cn   = (SELECT `id` FROM `subject` WHERE `user_id` = @uid AND `name` = '语文');

-- -----------------------------------------------------------------------------
--  3. 任务
--  -----------------------------------------------------------------------------
--  相对天数 n 表示 DATE_SUB(CURDATE(), INTERVAL n DAY)，n = -1 表示明天。
--
--  ★ 状态分布是**刻意设计**的，不是随便填的 —— 它决定了仪表盘上那几个数字：
--    · D-13 ~ D-8 有完成记录（连续 6 天）
--    · D-7 只有一条 SKIPPED，**故意留出断签**，否则「历史最长连续」没有对比
--    · D-6 ~ D-0 连续 7 天有完成记录 → 当前连续打卡 = 7
--    · 今天和未来有 TODO，让「待办」和「完成率」不是满值
-- -----------------------------------------------------------------------------
INSERT INTO `task` (`user_id`, `subject_id`, `title`, `plan_date`, `plan_minutes`, `priority`, `status`, `note`, `completed_at`, `created_at`, `updated_at`)
VALUES
-- ---- 数学 ----
(@uid, @math, '做一套 2023 年真题（选择 + 填空）', DATE_SUB(CURDATE(), INTERVAL 13 DAY),  90, 'HIGH',   'DONE',    '错题要整理到错题本',       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 13 DAY), '21:10:00'), NOW(6), NOW(6)),
(@uid, @math, '中值定理证明题专项',                DATE_SUB(CURDATE(), INTERVAL 11 DAY),  80, 'HIGH',   'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 11 DAY), '21:30:00'), NOW(6), NOW(6)),
(@uid, @math, '做一套 2024 年真题',                DATE_SUB(CURDATE(), INTERVAL 9 DAY),   90, 'HIGH',   'SKIPPED', '当天临时有事，改到周末补', NULL, NOW(6), NOW(6)),
(@uid, @math, '多元函数微分学',                    DATE_SUB(CURDATE(), INTERVAL 6 DAY),   85, 'HIGH',   'DONE',    '偏导和全微分的几何意义要再想一遍', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 6 DAY), '21:40:00'), NOW(6), NOW(6)),
(@uid, @math, '线性代数：矩阵的秩',                DATE_SUB(CURDATE(), INTERVAL 4 DAY),   90, 'HIGH',   'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '22:00:00'), NOW(6), NOW(6)),
(@uid, @math, '做一套 2022 年真题',                DATE_SUB(CURDATE(), INTERVAL 2 DAY),   95, 'HIGH',   'DONE',    '比上次快了 12 分钟',        TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '21:20:00'), NOW(6), NOW(6)),
(@uid, @math, '错题本整理：曲线积分',              DATE_SUB(CURDATE(), INTERVAL 1 DAY),   55, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '22:15:00'), NOW(6), NOW(6)),
(@uid, @math, '微分方程入门：一阶可分离变量',      CURDATE(),                             80, 'HIGH',   'DONE',    NULL,                       TIMESTAMP(CURDATE(), '20:50:00'), NOW(6), NOW(6)),
(@uid, @math, '错题重做：级数收敛判别',            CURDATE(),                             40, 'LOW',    'TODO',    '睡前顺手做掉',              NULL, NOW(6), NOW(6)),
(@uid, @math, '做一套 2025 年真题',                DATE_ADD(CURDATE(), INTERVAL 1 DAY),   90, 'HIGH',   'TODO',    '掐表做，模拟考场节奏',       NULL, NOW(6), NOW(6)),
(@uid, @math, '二重积分计算',                      DATE_ADD(CURDATE(), INTERVAL 2 DAY),   75, 'MEDIUM', 'TODO',    NULL,                       NULL, NOW(6), NOW(6)),
-- ---- 英语 ----
(@uid, @eng,  '背单词 Unit 12（80 词）',           DATE_SUB(CURDATE(), INTERVAL 13 DAY),  40, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 13 DAY), '07:40:00'), NOW(6), NOW(6)),
(@uid, @eng,  '阅读理解 4 篇',                     DATE_SUB(CURDATE(), INTERVAL 12 DAY),  60, 'HIGH',   'DONE',    '第 3 篇错 2 题，是态度题',   TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 12 DAY), '21:00:00'), NOW(6), NOW(6)),
(@uid, @eng,  '完形填空专项',                      DATE_SUB(CURDATE(), INTERVAL 10 DAY),  50, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 10 DAY), '21:25:00'), NOW(6), NOW(6)),
(@uid, @eng,  '作文模板整理：图表作文',            DATE_SUB(CURDATE(), INTERVAL 9 DAY),   60, 'HIGH',   'DONE',    '开头段和趋势描述各留三个句型', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 9 DAY), '22:05:00'), NOW(6), NOW(6)),
(@uid, @eng,  '背单词 Unit 13',                    DATE_SUB(CURDATE(), INTERVAL 7 DAY),   40, 'MEDIUM', 'SKIPPED', NULL,                       NULL, NOW(6), NOW(6)),
(@uid, @eng,  '翻译练习：长难句拆解',              DATE_SUB(CURDATE(), INTERVAL 5 DAY),   45, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 5 DAY), '21:15:00'), NOW(6), NOW(6)),
(@uid, @eng,  '听力精听（真题音频）',              DATE_SUB(CURDATE(), INTERVAL 3 DAY),   30, 'LOW',    'DONE',    '语速比想象中快，再听一遍',   TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '07:35:00'), NOW(6), NOW(6)),
(@uid, @eng,  '阅读理解 3 篇',                     DATE_SUB(CURDATE(), INTERVAL 1 DAY),   50, 'HIGH',   'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '21:05:00'), NOW(6), NOW(6)),
(@uid, @eng,  '背单词 Unit 14',                    CURDATE(),                             40, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(CURDATE(), '07:45:00'), NOW(6), NOW(6)),
(@uid, @eng,  '作文练习：议论文提纲',              CURDATE(),                             60, 'HIGH',   'TODO',    '先写中文提纲再翻英文',       NULL, NOW(6), NOW(6)),
(@uid, @eng,  '完形填空 2 篇',                     DATE_ADD(CURDATE(), INTERVAL 1 DAY),   45, 'MEDIUM', 'TODO',    NULL,                       NULL, NOW(6), NOW(6)),
-- ---- 计算机 ----
(@uid, @cs,   '数据结构：二叉树的四种遍历',        DATE_SUB(CURDATE(), INTERVAL 12 DAY),  90, 'HIGH',   'DONE',    '非递归写法要能手写出来',     TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 12 DAY), '22:00:00'), NOW(6), NOW(6)),
(@uid, @cs,   '操作系统：进程调度算法',            DATE_SUB(CURDATE(), INTERVAL 10 DAY),  80, 'HIGH',   'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 10 DAY), '21:50:00'), NOW(6), NOW(6)),
(@uid, @cs,   '计算机网络：TCP 三次握手与四次挥手', DATE_SUB(CURDATE(), INTERVAL 8 DAY),  70, 'HIGH',   'DONE',    '为什么是三次不是两次 —— 面试必问', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 8 DAY), '21:30:00'), NOW(6), NOW(6)),
(@uid, @cs,   '排序算法手写：快排与归并',          DATE_SUB(CURDATE(), INTERVAL 6 DAY),   90, 'HIGH',   'DONE',    '边界条件写错了两次，记下来', TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 6 DAY), '22:20:00'), NOW(6), NOW(6)),
(@uid, @cs,   '组成原理：Cache 映射方式',          DATE_SUB(CURDATE(), INTERVAL 4 DAY),   85, 'HIGH',   'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 4 DAY), '21:45:00'), NOW(6), NOW(6)),
(@uid, @cs,   '图论：最短路径（Dijkstra）',        DATE_SUB(CURDATE(), INTERVAL 3 DAY),   90, 'HIGH',   'DONE',    '和 Prim 的区别要分清楚',     TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 3 DAY), '22:10:00'), NOW(6), NOW(6)),
(@uid, @cs,   '操作系统：死锁与银行家算法',        DATE_SUB(CURDATE(), INTERVAL 2 DAY),   75, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '21:35:00'), NOW(6), NOW(6)),
(@uid, @cs,   '计算机网络：HTTP 与 HTTPS',         DATE_SUB(CURDATE(), INTERVAL 1 DAY),   65, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY), '21:55:00'), NOW(6), NOW(6)),
(@uid, @cs,   '数据结构：红黑树插入',              CURDATE(),                             90, 'HIGH',   'DONE',    '旋转的四种情况终于理顺了',   TIMESTAMP(CURDATE(), '21:40:00'), NOW(6), NOW(6)),
(@uid, @cs,   '真题：计算机基础综合',              CURDATE(),                            120, 'HIGH',   'TODO',    '整块时间做，别被打断',       NULL, NOW(6), NOW(6)),
(@uid, @cs,   '数据库：事务与隔离级别',            DATE_ADD(CURDATE(), INTERVAL 1 DAY),   80, 'HIGH',   'TODO',    NULL,                       NULL, NOW(6), NOW(6)),
-- ---- 语文 ----
(@uid, @cn,   '文言文实词 120 个',                 DATE_SUB(CURDATE(), INTERVAL 8 DAY),   50, 'MEDIUM', 'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 8 DAY), '20:40:00'), NOW(6), NOW(6)),
(@uid, @cn,   '现代文阅读 2 篇',                   DATE_SUB(CURDATE(), INTERVAL 5 DAY),   45, 'LOW',    'DONE',    NULL,                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 5 DAY), '20:30:00'), NOW(6), NOW(6)),
(@uid, @cn,   '古诗文默写',                        DATE_SUB(CURDATE(), INTERVAL 2 DAY),   30, 'LOW',    'DONE',    '易错字抄了三遍',             TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 2 DAY), '20:20:00'), NOW(6), NOW(6)),
(@uid, @cn,   '作文素材整理',                      DATE_ADD(CURDATE(), INTERVAL 2 DAY),   40, 'LOW',    'TODO',    NULL,                       NULL, NOW(6), NOW(6));

-- -----------------------------------------------------------------------------
--  4. 打卡记录
--  -----------------------------------------------------------------------------
--  （a）由「已完成的任务」批量派生 —— 手写 40 多条既啰嗦又容易和任务表对不上，
--       直接从 task 派生就永远不会出现「任务完成了但没有打卡记录」这种不一致。
--
--       实际时长用 `计划 × (0.75 ~ 0.95)` 而不是 `= 计划`：
--       真实的人不会每次都刚好按计划时长结束，全等于计划值会让「计划 vs 实际」
--       这个对比失去意义。
--
--       ★ 系数的种子必须是**跨次运行稳定**的。这里用 `DAYOFYEAR(plan_date) + plan_minutes`。
--       一开始写的是 `MOD(t.id, 5)`，实测被证伪：本脚本是「先删再插」，
--       重灌一次自增 id 就整体后移，累计投入分钟会在 1912 / 1916 / 1921 之间漂 ——
--       行数看着幂等，数字却不是。id 是自增的，**天然不适合当种子**。
--
--       GREATEST(1, ...) 是兜底：plan_minutes 最小是 1，乘 0.75 再四舍五入会变 0，
--       而 actual_minutes 有 `至少 1 分钟` 的约束。
-- -----------------------------------------------------------------------------
INSERT INTO `checkin` (`user_id`, `subject_id`, `task_id`, `checkin_date`, `actual_minutes`, `note`, `created_at`, `updated_at`)
SELECT t.`user_id`,
       t.`subject_id`,
       t.`id`,
       t.`plan_date`,
       GREATEST(1, ROUND(t.`plan_minutes` * (0.75 + MOD(DAYOFYEAR(t.`plan_date`) + t.`plan_minutes`, 5) * 0.05))),
       NULL,
       TIMESTAMP(t.`plan_date`, '22:30:00'),
       TIMESTAMP(t.`plan_date`, '22:30:00')
FROM `task` t
WHERE t.`user_id` = @uid
  AND t.`status` = 'DONE';

--  （b）更早的一批「不挂任务」的打卡 —— 记录随手翻笔记、通勤听听力这类时间。
--       它们的作用是给「历史最长连续打卡」造出一段比当前更长的记录，
--       否则仪表盘上「当前 7 天 / 最长 7 天」两个数字一样，看不出差别。
--       ★ 刻意留出 D-15、D-14 两天空档，避免和上面那段（D-13 起）连成一片。
-- -----------------------------------------------------------------------------
INSERT INTO `checkin` (`user_id`, `subject_id`, `task_id`, `checkin_date`, `actual_minutes`, `note`, `created_at`, `updated_at`)
VALUES
(@uid, @eng,  NULL, DATE_SUB(CURDATE(), INTERVAL 25 DAY), 35, '通勤路上背了一组词',        TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 25 DAY), '08:10:00'), NOW(6)),
(@uid, @math, NULL, DATE_SUB(CURDATE(), INTERVAL 24 DAY), 45, '睡前翻了一遍错题本',        TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 24 DAY), '22:40:00'), NOW(6)),
(@uid, @cs,   NULL, DATE_SUB(CURDATE(), INTERVAL 23 DAY), 40, '看了两集操作系统的课',      TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 23 DAY), '21:20:00'), NOW(6)),
(@uid, @eng,  NULL, DATE_SUB(CURDATE(), INTERVAL 22 DAY), 30, '听力泛听，磨耳朵',          TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 22 DAY), '07:50:00'), NOW(6)),
(@uid, @math, NULL, DATE_SUB(CURDATE(), INTERVAL 21 DAY), 50, '把上周的公式抄成一张卡片',  TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 21 DAY), '22:00:00'), NOW(6)),
(@uid, @cn,   NULL, DATE_SUB(CURDATE(), INTERVAL 20 DAY), 25, '读了两篇时评',              TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 20 DAY), '20:15:00'), NOW(6)),
(@uid, @cs,   NULL, DATE_SUB(CURDATE(), INTERVAL 19 DAY), 45, '手写了一遍单链表操作',      TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 19 DAY), '21:50:00'), NOW(6)),
(@uid, @eng,  NULL, DATE_SUB(CURDATE(), INTERVAL 18 DAY), 35, '复习 Unit 11 的错词',       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 18 DAY), '08:05:00'), NOW(6)),
(@uid, @math, NULL, DATE_SUB(CURDATE(), INTERVAL 17 DAY), 40, '重做了三道极限题',          TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 17 DAY), '22:25:00'), NOW(6)),
(@uid, @cs,   NULL, DATE_SUB(CURDATE(), INTERVAL 16 DAY), 30, '过了一遍网络分层模型',      TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 16 DAY), '21:10:00'), NOW(6));

-- -----------------------------------------------------------------------------
--  5. 自检：把演示账号的关键数字打出来，确认灌进去的和预期一致
--  -----------------------------------------------------------------------------
SELECT '科目数'       AS 指标, COUNT(*) AS 值 FROM `subject` WHERE `user_id` = @uid
UNION ALL SELECT '任务总数',   COUNT(*) FROM `task`    WHERE `user_id` = @uid
UNION ALL SELECT '已完成任务', COUNT(*) FROM `task`    WHERE `user_id` = @uid AND `status` = 'DONE'
UNION ALL SELECT '打卡记录数', COUNT(*) FROM `checkin` WHERE `user_id` = @uid
UNION ALL SELECT '累计投入分钟', SUM(`actual_minutes`) FROM `checkin` WHERE `user_id` = @uid
UNION ALL SELECT '打卡天数',   COUNT(DISTINCT `checkin_date`) FROM `checkin` WHERE `user_id` = @uid;

-- 连续打卡天数：从**最近一次**打卡日期往回数，直到遇到第一个空档。
-- 从最近一次而不是从今天起算，是为了和应用的语义对齐 ——
-- 「今天还没打卡」不算断签（否则每天零点一过，所有人的连续天数都会归零）。
WITH RECURSIVE streak AS (
    SELECT MAX(`checkin_date`) AS dt FROM `checkin` WHERE `user_id` = @uid
    UNION ALL
    SELECT DATE_SUB(s.dt, INTERVAL 1 DAY)
    FROM streak s
    WHERE EXISTS (SELECT 1 FROM `checkin` c
                  WHERE c.`user_id` = @uid
                    AND c.`checkin_date` = DATE_SUB(s.dt, INTERVAL 1 DAY))
)
SELECT '从最近打卡日起的连续天数' AS 指标, COUNT(*) AS 值 FROM streak;
