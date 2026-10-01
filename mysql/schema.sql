-- ============================================================================
-- 卡丁车赛道运营管理系统 · 建表 + 种子数据
-- 库名 kart_track，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 五个业务模块：
--   1. kart         卡丁车（车号·车型·排量·功率·状态·累计里程/小时·保养间隔）
--   2. driver       车手（会员号·年龄分组·驾照等级·累计场次·历史最快圈速）
--   3. race_session 场次（赛道配置·分组·最大车辆数·已报名人数·状态）
--   4. lap_record   圈速记录（写入走实体上的 @SQLInsert/@SQLUpdate/@SQLDelete 手写 SQL）
--   5. maintenance  维修保养（工时·配件费·总费用·待修/维修中/已完工）
--
-- 红旗事件处置台（模块六，贯穿 3/4/5）：
--   6a. red_flag_event 红旗事件（原因·发生时刻·处置中/已恢复/已终止；active_session_id 唯一索引
--       从数据库层保证「同一场次同时只有一个生效红旗」，并发双发只能落到同一行）
--   6b. red_flag_check 车辆核查清单（可继续/退出比赛/需要维修；version 乐观锁防过期页面覆盖）
--   6c. lap_record 增加 red_flag_event_id + adjudication：红旗期间圈速保留为待裁定记录，
--       不丢、不入榜，主管逐条确认/作废
--
-- 种子数据保证下面每条业务规则都能被真的触发（不是"理论上可以"）：
--   [R1.1] 车号唯一            → K-01 已存在
--   [R1.2] 车型驾照门槛        → 竞技车要中级以上（无证/初级会被拦）、儿童车只给儿童组
--   [R1.3] 未派车不能记圈速    → 只有 ON_TRACK 的车能登记圈速
--   [R1.4] 保养到期高亮        → K-03/K-05/K-07/K-08 已超保养间隔
--   [R1.5] 派车上限            → 半场 8 台、全场 12 台、夜间 10 台
--   [R2.1] 会员号唯一          → M2026001 已存在
--   [R2.2] 夜间场不接待儿童组  → 儿童车手报名 NIGHT 场次会被拦
--   [R2.3] 报名上限            → 场次人数达 max_karts 后拒绝报名
--   [R3.1] 场次状态机顺序      → 待开赛 → 进行中 → 已结束 / 已取消
--   [R3.2] 无人报名不能开赛    → S2026092402 已有 5 人报名可开；signed_count=0 会被拦
--   [R3.3] 只有进行中能记圈速  → S2026092401 = RUNNING
--   [R4.1] 圈速区间校验        → 20 秒 ~ 300 秒之外视为误录
--   [R4.2] 本场最快圈唯一      → 新纪录会把手写 update 里的旧最快圈复位
--   [R5.1] 总费用 = 工时 × 80 + 配件费
--   [R5.2] 完工复位车辆状态    → 保养类项目完工后重置「上次保养日期」
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS kart_track DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE kart_track;

DROP TABLE IF EXISTS maintenance;
DROP TABLE IF EXISTS red_flag_check;
DROP TABLE IF EXISTS red_flag_event;
DROP TABLE IF EXISTS lap_record;
DROP TABLE IF EXISTS race_session;
DROP TABLE IF EXISTS driver;
DROP TABLE IF EXISTS kart;

-- ----------------------------------------------------------------------------
-- 模块一：卡丁车
-- ----------------------------------------------------------------------------
CREATE TABLE kart (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    kart_no            VARCHAR(16)   NOT NULL COMMENT '车号',
    model              VARCHAR(16)   NOT NULL COMMENT 'FUN 娱乐车 / RACE 竞技车 / KID 儿童车 / DOUBLE 双人车',
    engine_cc          INT           NOT NULL COMMENT '发动机排量（cc）',
    max_power          DECIMAL(5, 2) NOT NULL COMMENT '最大功率（kW）',
    status             VARCHAR(16)   NOT NULL COMMENT 'AVAILABLE 可用 / ON_TRACK 在赛 / MAINTENANCE 维修 / DISABLED 停用',
    total_km           DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '累计里程（km）',
    total_hours        DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '累计运行小时',
    last_service_date  DATE          NULL COMMENT '上次保养日期',
    service_interval_h INT           NOT NULL DEFAULT 50 COMMENT '保养间隔（小时）',
    updated_at         DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kart_no (kart_no),
    KEY idx_kart_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '卡丁车';

-- ----------------------------------------------------------------------------
-- 模块二：车手
-- ----------------------------------------------------------------------------
CREATE TABLE driver (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    member_no      VARCHAR(20)   NOT NULL COMMENT '会员号',
    name           VARCHAR(30)   NOT NULL COMMENT '姓名',
    phone          VARCHAR(20)   NOT NULL COMMENT '手机号',
    age_group      VARCHAR(16)   NOT NULL COMMENT 'KID 儿童 / TEEN 青少年 / ADULT 成人',
    license_level  VARCHAR(16)   NOT NULL COMMENT 'NONE 无证 / BASIC 初级 / MID 中级 / ADVANCED 高级',
    total_sessions INT           NOT NULL DEFAULT 0 COMMENT '累计场次',
    best_lap_ms    INT           NOT NULL DEFAULT 0 COMMENT '历史最快圈速（毫秒），0 表示还没成绩',
    total_spend    DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '总消费金额',
    register_date  DATE          NOT NULL COMMENT '注册日期',
    session_id     BIGINT        NULL COMMENT '当前报名/在赛的场次 id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_member_no (member_no),
    KEY idx_driver_session (session_id),
    KEY idx_driver_best (best_lap_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '车手';

-- ----------------------------------------------------------------------------
-- 模块三：场次
-- ----------------------------------------------------------------------------
CREATE TABLE race_session (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    session_no   VARCHAR(24) NOT NULL COMMENT '场次号',
    start_time   DATETIME    NULL COMMENT '开始时间',
    end_time     DATETIME    NULL COMMENT '结束时间',
    track_config VARCHAR(16) NOT NULL COMMENT 'HALF 半场 / FULL 全场 / NIGHT 夜间',
    group_code   VARCHAR(4)  NOT NULL COMMENT '分组：A / B / C',
    max_karts    INT         NOT NULL COMMENT '最大车辆数',
    signed_count INT         NOT NULL DEFAULT 0 COMMENT '已报名人数',
    status       VARCHAR(16) NOT NULL COMMENT 'PENDING 待开赛 / RUNNING 进行中 / FINISHED 已结束 / CANCELED 已取消',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_no (session_no),
    KEY idx_session_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '场次';

-- ----------------------------------------------------------------------------
-- 模块四：圈速记录（写链路走实体上的 @SQLInsert / @SQLUpdate / @SQLDelete）
-- ----------------------------------------------------------------------------
CREATE TABLE lap_record (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    session_id        BIGINT      NOT NULL COMMENT '场次',
    driver_id         BIGINT      NOT NULL COMMENT '车手',
    kart_id           BIGINT      NOT NULL COMMENT '车辆',
    lap_index         INT         NOT NULL COMMENT '圈序',
    lap_time_ms       INT         NOT NULL COMMENT '圈速（毫秒）',
    is_fastest        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否本场最快圈',
    valid             VARCHAR(16) NOT NULL COMMENT 'VALID 有效 / OUT 无效-出界 / CRASH 无效-碰撞 / VOID 红旗作废',
    created_at        DATETIME    NULL COMMENT '记录时间',
    red_flag_event_id BIGINT      NULL COMMENT '产生于哪次红旗事件；非空=红旗期间上报、处于待裁定/已裁定隔离状态',
    adjudication      VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 正常 / PENDING 红旗待裁定 / CONFIRMED 主管确认有效 / VOIDED 主管作废',
    PRIMARY KEY (id),
    KEY idx_lap_session (session_id),
    KEY idx_lap_driver (driver_id),
    KEY idx_lap_kart (kart_id),
    KEY idx_lap_redflag (red_flag_event_id),
    KEY idx_lap_adjudication (adjudication)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '圈速记录';

-- ----------------------------------------------------------------------------
-- 模块五：维修保养
-- ----------------------------------------------------------------------------
CREATE TABLE maintenance (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(24)   NOT NULL COMMENT '维修单号',
    kart_id    BIGINT        NOT NULL COMMENT '车辆',
    maint_type VARCHAR(16)   NOT NULL COMMENT 'OIL 换机油 / TIRE 换轮胎 / BRAKE 刹车片 / CHAIN 链条 / SPARK 火花塞 / ENGINE 发动机大修 / BODY 车身修复',
    fault_desc VARCHAR(200)  NULL COMMENT '故障描述',
    work_hours DECIMAL(6, 2) NOT NULL DEFAULT 0 COMMENT '工时',
    parts_fee  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '配件费',
    total_fee  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '总费用 = 工时 × 80 + 配件费',
    worker     VARCHAR(30)   NULL COMMENT '维修人',
    start_time DATETIME      NULL COMMENT '开始时间',
    end_time   DATETIME      NULL COMMENT '结束时间',
    status     VARCHAR(16)   NOT NULL COMMENT 'WAITING 待修 / REPAIRING 维修中 / DONE 已完工',
    PRIMARY KEY (id),
    UNIQUE KEY uk_maint_order_no (order_no),
    KEY idx_maint_kart (kart_id),
    KEY idx_maint_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '维修保养';

-- ----------------------------------------------------------------------------
-- 模块六-a：红旗事件
--   active_session_id 只在处置中（ACTIVE）时回填本场场次 id，恢复/终止后置 NULL。
--   配合唯一索引 uk_active_session：数据库层硬性保证同一场次同时只有一个生效红旗，
--   两名值班人员并发发起也只能有一行落库，另一笔撞唯一键后返回同一事件（幂等）。
-- ----------------------------------------------------------------------------
CREATE TABLE red_flag_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    event_no          VARCHAR(28) NOT NULL COMMENT '红旗事件号，如 RF-S2026092401-1',
    session_id        BIGINT      NOT NULL COMMENT '所属场次',
    reason            VARCHAR(200) NOT NULL COMMENT '红旗原因',
    raised_by         VARCHAR(30) NOT NULL COMMENT '发起人（值班员）',
    occurred_at       DATETIME    NOT NULL COMMENT '发生时刻',
    status            VARCHAR(16) NOT NULL COMMENT 'ACTIVE 红旗处理中 / RESUMED 已恢复 / TERMINATED 已强制终止',
    active_session_id BIGINT      NULL COMMENT '仅 ACTIVE 时 = 场次 id，其余为 NULL（唯一索引防并发双红旗）',
    resolution        VARCHAR(16) NULL COMMENT 'RESUME 恢复比赛 / TERMINATE 强制终止',
    resolved_by       VARCHAR(30) NULL COMMENT '处置主管',
    resolved_at       DATETIME    NULL COMMENT '处置时刻',
    resolution_note   VARCHAR(200) NULL COMMENT '处置备注',
    version           INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本（防过期页面重复恢复/终止）',
    created_at        DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_redflag_no (event_no),
    UNIQUE KEY uk_active_session (active_session_id),
    KEY idx_redflag_session (session_id),
    KEY idx_redflag_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '红旗事件';

-- ----------------------------------------------------------------------------
-- 模块六-b：红旗车辆核查清单（发起红旗时按当时仍在赛车辆一次性生成）
--   一台车一行，结论互相独立；某台车退出/送修不释放也不改写其它车的核查结论。
--   version 乐观锁：过期页面拿着旧 version 提交会被拒，不覆盖新结论。
-- ----------------------------------------------------------------------------
CREATE TABLE red_flag_check (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_id       BIGINT      NOT NULL COMMENT '红旗事件',
    session_id     BIGINT      NOT NULL COMMENT '所属场次（冗余便于按场核查）',
    kart_id        BIGINT      NOT NULL COMMENT '车辆',
    driver_id      BIGINT      NULL COMMENT '当时驾驶该车的车手（快照）',
    result         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待核查 / CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修',
    note           VARCHAR(200) NULL COMMENT '核查备注（含故障描述）',
    checked_by     VARCHAR(30) NULL COMMENT '核查值班员',
    checked_at     DATETIME    NULL COMMENT '核查时刻',
    maintenance_id BIGINT      NULL COMMENT '需要维修时生成的待修维修单 id（可追踪）',
    version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at     DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_check_event_kart (event_id, kart_id),
    KEY idx_check_session (session_id),
    KEY idx_check_kart (kart_id),
    KEY idx_check_result (result)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '红旗车辆核查清单';


-- ---------------------------- 卡丁车（12 台） ----------------------------
INSERT INTO kart (id, kart_no, model, engine_cc, max_power, status, total_km, total_hours,
                  last_service_date, service_interval_h, updated_at) VALUES
    (1,  'K-01', 'RACE',   390, 22.00, 'AVAILABLE',   1240.50, 310.20, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 4 DAY),  50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (2,  'K-02', 'RACE',   390, 22.00, 'ON_TRACK',    1180.00, 295.60, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 6 DAY),  50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (3,  'K-03', 'FUN',    270, 13.50, 'ON_TRACK',    2260.80, 640.30, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 52 DAY), 60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (4,  'K-04', 'FUN',    270, 13.50, 'ON_TRACK',    2010.20, 580.10, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 3 DAY),  60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (5,  'K-05', 'FUN',    270, 13.50, 'AVAILABLE',   1800.00, 520.00, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 9 DAY),  60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (6,  'K-06', 'KID',    120,  6.50, 'AVAILABLE',    980.40, 410.20, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 2 DAY),  40, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (7,  'K-07', 'KID',    120,  6.50, 'ON_TRACK',     860.00, 380.50, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 14 DAY), 40, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (8,  'K-08', 'DOUBLE', 340, 16.80, 'ON_TRACK',    1520.60, 470.90, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 12 DAY), 50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (9,  'K-09', 'DOUBLE', 340, 16.80, 'AVAILABLE',   1400.00, 430.00, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 4 DAY),  50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (10, 'K-10', 'RACE',   390, 22.00, 'MAINTENANCE', 2600.00, 710.40, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 96 DAY), 50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (11, 'K-11', 'FUN',    270, 13.50, 'MAINTENANCE', 3050.20, 880.00, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 84 DAY), 60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (12, 'K-12', 'KID',    120,  6.50, 'DISABLED',    3200.00, 990.00, NULL,                                 40, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR));

-- ---------------------------- 车手（12 人） ----------------------------
INSERT INTO driver (id, member_no, name, phone, age_group, license_level, total_sessions,
                    best_lap_ms, total_spend, register_date, session_id) VALUES
    (1,  'M2026001', '陈亦航', '13800000001', 'ADULT', 'ADVANCED', 42, 42350, 12800.00, '2025-03-11', 1),
    (2,  'M2026002', '林骁',   '13800000002', 'ADULT', 'ADVANCED', 36, 42980, 11200.00, '2025-05-02', 1),
    (3,  'M2026003', '赵子昂', '13800000003', 'ADULT', 'MID',      28, 44120,  8600.00, '2025-08-19', 1),
    (4,  'M2026004', '周雨桐', '13800000004', 'TEEN',  'MID',      19, 45260,  6400.00, '2025-11-05', 1),
    (5,  'M2026005', '孙慕青', '13800000005', 'ADULT', 'BASIC',    12, 47890,  3200.00, '2026-01-22', 2),
    (6,  'M2026006', '吴念',   '13800000006', 'TEEN',  'BASIC',     9, 51240,  2600.00, '2026-02-14', 2),
    (7,  'M2026007', '郑一鸣', '13800000007', 'ADULT', 'NONE',      3,     0,   900.00, '2026-06-08', NULL),
    (8,  'M2026008', '何知遥', '13800000008', 'TEEN',  'MID',      15, 46310,  4100.00, '2025-12-30', 1),
    (9,  'M2026009', '小豆丁', '13800000009', 'KID',   'NONE',      6,     0,  1500.00, '2026-04-18', NULL),
    (10, 'M2026010', '糖豆',   '13800000010', 'KID',   'NONE',      4,     0,  1200.00, '2026-05-27', NULL),
    (11, 'M2026011', '顾南',   '13800000011', 'ADULT', 'MID',      22, 44780,  7200.00, '2025-09-16', 1),
    (12, 'M2026012', '李承泽', '13800000012', 'ADULT', 'BASIC',     7, 49050,  2100.00, '2026-03-03', 2);

-- ---------------------------- 场次（10 场，第 1 场进行中） ----------------------------
INSERT INTO race_session (id, session_no, start_time, end_time, track_config, group_code,
                          max_karts, signed_count, status) VALUES
    (1,  'S2026092401', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE), NULL,                                 'FULL',  'A', 12, 8,  'RUNNING'),
    (2,  'S2026092402', DATE_ADD(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE), NULL,                                 'HALF',  'B',  8, 5,  'PENDING'),
    (3,  'S2026092303', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), INTERVAL 20 MINUTE), 'FULL',  'A', 12, 12, 'FINISHED'),
    (4,  'S2026092302', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 26 HOUR), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 25 HOUR),    'HALF',  'C',  8,  8, 'FINISHED'),
    (5,  'S2026092201', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY), INTERVAL 25 MINUTE), 'NIGHT', 'A', 10, 10, 'FINISHED'),
    (6,  'S2026092101', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY), INTERVAL 23 MINUTE), 'FULL',  'B', 12, 11, 'FINISHED'),
    (7,  'S2026092001', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY), INTERVAL 17 MINUTE), 'HALF',  'A',  8,  7, 'FINISHED'),
    (8,  'S2026091901', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY), INTERVAL 22 MINUTE), 'FULL',  'C', 12,  9, 'FINISHED'),
    (9,  'S2026091801', NULL,                              NULL,                                 'NIGHT', 'B', 10,  0, 'CANCELED'),
    (10, 'S2026091701', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY), INTERVAL 15 MINUTE),  'HALF',  'C',  8,  6, 'FINISHED');

-- ---------------------------- 圈速记录（第 1 场进行中，其余为历史场） ----------------------------
INSERT INTO lap_record (id, session_id, driver_id, kart_id, lap_index, lap_time_ms, is_fastest, valid, created_at) VALUES
    (1,  1, 1,  2, 1, 44120, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 13 MINUTE)),
    (2,  1, 2,  3, 1, 43780, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE)),
    (3,  1, 3,  4, 1, 45010, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE)),
    (4,  1, 8,  4, 1, 46890, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE)),
    (5,  1, 11, 8, 1, 45260, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE)),
    (6,  1, 4,  3, 1, 46040, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 MINUTE)),
    (7,  1, 1,  2, 2, 43050, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 MINUTE)),
    (8,  1, 2,  3, 2, 43310, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE)),
    (9,  1, 3,  4, 2, 44580, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE)),
    (10, 1, 11, 8, 2, 44970, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE)),
    (11, 1, 8,  4, 2, 55230, 0, 'OUT',   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE)),
    (12, 1, 4,  3, 2, 61240, 0, 'CRASH', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE)),
    (13, 1, 2,  3, 3, 42980, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE)),
    (14, 1, 1,  2, 3, 43410, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE)),
    (15, 3, 1,  2, 1, 42350, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (16, 3, 2,  3, 1, 43020, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (17, 3, 11, 8, 1, 44780, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (18, 3, 5,  4, 1, 47890, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (19, 4, 3,  5, 1, 45210, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 26 HOUR)),
    (20, 4, 6,  6, 1, 51240, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 26 HOUR)),
    (21, 5, 1,  2, 1, 44180, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (22, 5, 4,  3, 1, 46230, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (23, 5, 12, 9, 1, 49050, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (24, 6, 2,  3, 1, 43360, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY)),
    (25, 6, 8,  7, 1, 46310, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY)),
    (26, 7, 5,  4, 1, 48210, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY)),
    (27, 7, 6,  6, 1, 52180, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY)),
    (28, 8, 3,  5, 1, 45990, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY)),
    (29, 8, 12, 9, 1, 49670, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY)),
    (30, 10, 1, 2, 1, 43980, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY)),
    (31, 10, 4,  3, 1, 46820, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY)),
    (32, 10, 9,  6, 1, 62410, 0, 'OUT',   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY));

-- ---------------------------- 维修保养（12 单） ----------------------------
INSERT INTO maintenance (id, order_no, kart_id, maint_type, fault_desc, work_hours, parts_fee,
                         total_fee, worker, start_time, end_time, status) VALUES
    (1,  'MT-20260901', 1,  'OIL',    '按保养间隔更换机油与机滤',              1.00,  180.00,  260.00, '王师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 DAY), 'DONE'),
    (2,  'MT-20260902', 9,  'BRAKE',  '刹车片厚度不足 2mm',                    2.00,  320.00,  480.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY), 'DONE'),
    (3,  'MT-20260903', 6,  'TIRE',   '左前胎鼓包，已换新胎',                  1.00,  340.00,  420.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY), 'DONE'),
    (4,  'MT-20260904', 5,  'BODY',   '车头护板撞裂，钣金修复并补漆',          3.00,  420.00,  660.00, '张师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY), 'DONE'),
    (5,  'MT-20260905', 3,  'TIRE',   '后轮磨损到极限，已换胎',                1.00,  340.00,  420.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 DAY), 'DONE'),
    (6,  'MT-20260906', 10, 'ENGINE', '发动机异响，二缸压缩比偏低，正在大修',  6.50, 2380.00, 2900.00, '王师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 90 MINUTE), NULL, 'REPAIRING'),
    (7,  'MT-20260907', 11, 'TIRE',   '后轮磨损到极限，正在换胎',              1.50,  680.00,  800.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE), NULL, 'REPAIRING'),
    (8,  'MT-20260908', 12, 'BODY',   '车身多处开裂，钣金修复中',              3.00,  420.00,  660.00, '张师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE), NULL, 'REPAIRING'),
    (9,  'MT-20260909', 1,  'SPARK',  '火花塞积碳，冷启动困难',                1.00,   90.00,  170.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING'),
    (10, 'MT-20260910', 5,  'BODY',   '前保险杠撞击变形，需钣金修复',          3.00,  420.00,  660.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING'),
    (11, 'MT-20260911', 6,  'BRAKE',  '刹车踏板行程偏长，需排气换片',          2.00,  300.00,  460.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING'),
    (12, 'MT-20260912', 9,  'CHAIN',  '链条异响，待本场结束后进厂',            1.50,  160.00,  280.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING');

SET FOREIGN_KEY_CHECKS = 1;
