-- 20261026: QQ 多租户渠道（specs/027-im-channel-qq）
-- 用途：
--   1) session_id 加宽 64 -> 128，容纳群聊会话键 qq_group_{group_openid}_{member_openid}（74 字符）
--   2) ai_agent_dialogue_session 加 binding_id，供 owner 查看自己 bot 下的会话
--   3) 新建 ai_agent_channel_binding / ai_agent_channel_identity
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。
--
-- ⚠️ VARCHAR(64) -> VARCHAR(128) 在 utf8mb4 下长度前缀 1B -> 2B，跨过 256 字节边界，
--    MySQL 8 无法用 INPLACE，走 COPY 算法（重建表 + 锁写）。
--    ai_agent_dialogue_run / ai_agent_working_memory_message 数据量大时需低峰执行，
--    或用 pt-online-schema-change 逐表切换。

-- ============================================================
-- 1) session_id 加宽（21 处；逐条执行便于失败定位）
-- ============================================================
ALTER TABLE `ai_agent_dialogue_session`              MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_dialogue_session`              MODIFY COLUMN `parent_session_id` VARCHAR(128) NULL COMMENT '父会话ID（仅审计与授权穿透；子会话不继承父工作记忆）';
ALTER TABLE `ai_agent_dialogue_run`                  MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_user_question`                 MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_desktop_control`               MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_plan_approval`                 MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_tool_output_deep_search`       MODIFY COLUMN `session_id` VARCHAR(128) NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_tool_output_code_interpreter`  MODIFY COLUMN `session_id` VARCHAR(128) NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_tool_output_data_analysis`     MODIFY COLUMN `session_id` VARCHAR(128) NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_tool_output_multimodal_agent`  MODIFY COLUMN `session_id` VARCHAR(128) NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_tool_output_image_generation`  MODIFY COLUMN `session_id` VARCHAR(128) NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_tool_output_canvas_publish`    MODIFY COLUMN `session_id` VARCHAR(128) NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_working_memory_turn`           MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_working_memory_message`        MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_working_memory_compaction`     MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_ltm_fork_execution`            MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_session_todo`                  MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID（listId）';
ALTER TABLE `ai_agent_background_task`               MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID';
ALTER TABLE `ai_agent_ltm_curated_entry`             MODIFY COLUMN `source_session_id` VARCHAR(128) NULL COMMENT '写入来源 session';
ALTER TABLE `ai_agent_session_capability`            MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '会话 ID';
ALTER TABLE `ai_agent_featured_conversation`         MODIFY COLUMN `session_id` VARCHAR(128) NOT NULL COMMENT '原会话ID';

-- ============================================================
-- 2) 会话归属到绑定（NULL = Web UI 原生会话，行为不变）
-- ============================================================
ALTER TABLE `ai_agent_dialogue_session`
    ADD COLUMN `binding_id` BIGINT NULL COMMENT '所属 IM 渠道绑定（NULL = Web UI 原生会话）' AFTER `user_id`,
    ADD KEY `idx_dialogue_session_binding` (`binding_id`, `deleted`, `last_active_at` DESC);

-- ============================================================
-- 3) 绑定表
-- ⚠️ app_secret 明文存储（用户决策）。能读本表即可接管对应 bot，禁止在任何日志中打印。
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_channel_binding` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `owner_user_id` VARCHAR(64)  NOT NULL COMMENT '绑定者 user_account.user_id',
    `platform`      VARCHAR(32)  NOT NULL DEFAULT 'qqbot' COMMENT '平台标识',
    `app_id`        VARCHAR(64)  NOT NULL COMMENT 'QQ 机器人 AppID',
    `app_secret`    VARCHAR(128) NOT NULL COMMENT 'QQ 机器人 AppSecret（明文，禁止打印）',
    `bot_openid`    VARCHAR(128) NULL COMMENT '扫码者 openid（仅展示，非授权依据）',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/ERROR/DISABLED',
    `last_error`    VARCHAR(512) NULL COMMENT '最近一次连接错误，供前端展示',
    `connected_at`  DATETIME(3)  NULL COMMENT '最近一次连接成功时间',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_binding_app` (`platform`, `app_id`, `deleted`),
    UNIQUE KEY `uk_channel_binding_owner` (`owner_user_id`, `platform`, `deleted`),
    KEY `idx_channel_binding_status` (`status`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='IM 渠道机器人绑定（app_secret 明文）';

-- ============================================================
-- 4) 平台身份表（QQ 用户 -> 内部 user_id，按 binding 隔离）
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_channel_identity` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `binding_id`       BIGINT       NOT NULL COMMENT '所属 binding',
    `platform`         VARCHAR(32)  NOT NULL DEFAULT 'qqbot' COMMENT '平台标识',
    `platform_user_id` VARCHAR(128) NOT NULL COMMENT 'QQ openid',
    `user_id`          VARCHAR(64)  NOT NULL COMMENT '内部身份 ch_{bindingId}_{hash}',
    `display_name`     VARCHAR(128) NULL COMMENT 'QQ 昵称，仅日志与排障',
    `first_seen_at`    DATETIME(3)  NOT NULL COMMENT '首次出现',
    `last_seen_at`     DATETIME(3)  NOT NULL COMMENT '最近出现',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_channel_identity` (`binding_id`, `platform`, `platform_user_id`, `deleted`),
    KEY `idx_channel_identity_user` (`user_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='IM 平台用户到内部身份的映射';
