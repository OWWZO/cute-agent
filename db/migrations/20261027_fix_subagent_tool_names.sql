-- 20261027: 修正子 Agent 定义表里两个失效的工具名
-- 背景：
--   SubAgentToolFilter 按工具名做白名单。名字对不上 = 静默拒绝：
--   子 Agent 少拿能力，但没有任何报错，也没有日志。
--   2026-10-27 把定义表里 38 个工具名逐个与代码里的 getName() 核对，
--   36 个对得上，2 个是错的：
--
--   1) skill_tool —— 代码里没有这个名字。
--      当前 skill 能力由两个工具提供：skill_view（读技能）+ skills_search（搜技能）。
--      定义表只写 skill_tool 的 4 个类型（deep-search / code-executor / data-analyst /
--      report_agent）因此全都拿不到 skill 能力。
--
--   2) get_html_canvas_guide —— 该工具已删除（2026-10-18 genui 树下线时一并移除，
--      见 .workbuddy-ai/memory/MEMORY.md）。report_agent 仍引用它，属残留。
--      直接移除该名字，不做替换：它没有后继工具。
--
-- 手工执行（项目无 Flyway/Liquibase）：ai-agent-station 库。

-- ============================================================
-- 0) 备份
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_agent_sub_agent_definition_bak_toolnames_20261027` AS
SELECT * FROM `ai_agent_sub_agent_definition`;

-- ============================================================
-- 1) 预演：修正前
-- ============================================================
-- SELECT agent_key, allowed_tools_json FROM ai_agent_sub_agent_definition
--  WHERE deleted=0 AND status=1
--    AND (JSON_CONTAINS(allowed_tools_json, '"skill_tool"')
--      OR JSON_CONTAINS(allowed_tools_json, '"get_html_canvas_guide"'));

-- ============================================================
-- 2) skill_tool → skill_view + skills_search
--    两个工具一起给：只给 skill_view 会让模型无法发现有哪些技能。
-- ============================================================
UPDATE `ai_agent_sub_agent_definition`
SET `allowed_tools_json` = JSON_ARRAY_APPEND(
        JSON_ARRAY_APPEND(
            JSON_REMOVE(`allowed_tools_json`,
                        JSON_UNQUOTE(JSON_SEARCH(`allowed_tools_json`, 'one', 'skill_tool'))),
            '$', 'skill_view'),
        '$', 'skills_search')
WHERE `deleted` = 0
  AND JSON_CONTAINS(`allowed_tools_json`, '"skill_tool"');

-- ============================================================
-- 3) 移除已删除的 get_html_canvas_guide
-- ============================================================
UPDATE `ai_agent_sub_agent_definition`
SET `allowed_tools_json` = JSON_REMOVE(
        `allowed_tools_json`,
        JSON_UNQUOTE(JSON_SEARCH(`allowed_tools_json`, 'one', 'get_html_canvas_guide')))
WHERE `deleted` = 0
  AND JSON_CONTAINS(`allowed_tools_json`, '"get_html_canvas_guide"');

-- ============================================================
-- 4) 验收
-- ============================================================
-- 4a) 两个坏名字都应消失
-- SELECT COUNT(*) AS bad FROM ai_agent_sub_agent_definition
--  WHERE deleted=0 AND status=1
--    AND (JSON_CONTAINS(allowed_tools_json, '"skill_tool"')
--      OR JSON_CONTAINS(allowed_tools_json, '"get_html_canvas_guide"'));
-- 期望 0
--
-- 4b) skill 能力覆盖：原本写 skill_tool 的 4 个类型现在都应含 skill_view
-- SELECT agent_key,
--        JSON_CONTAINS(allowed_tools_json, '"skill_view"') AS has_skill_view,
--        JSON_CONTAINS(allowed_tools_json, '"skills_search"') AS has_skills_search
--   FROM ai_agent_sub_agent_definition WHERE deleted=0 AND status=1
--  ORDER BY agent_key;

-- ============================================================
-- 5) 回滚
-- ============================================================
-- UPDATE ai_agent_sub_agent_definition d
--   JOIN ai_agent_sub_agent_definition_bak_toolnames_20261027 b ON b.id = d.id
--    SET d.allowed_tools_json = b.allowed_tools_json
--  WHERE d.deleted = 0;
