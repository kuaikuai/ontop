-- =====================================================================
-- 不定跳 / 变长路径 —— 零代码方案（数据库视图 + ontop 普通映射）
-- 目标：验证「动态起点 + 预先展开 N 层」在真实库上是否可裁剪、可接受
--
-- 节点标识：bom_item_id
-- 上升链接：child.parent_material_code = parent.material_code
-- 输出：start_bom_item_id, bom_item_id, material_code,
--       parent_material_code, depth, predecessor
--
-- 语义：
--   depth = 0 是起点自己，predecessor = 起点自己
--   depth = k 是起点的第 k 级祖先，predecessor = 朝起点方向的前一个 bom_item_id
--   多父 DAG：每个起点保留全部路径（每行一条）
-- =====================================================================


-- =====================================================================
-- 0. 索引（条件下推的前提，必须建）
-- =====================================================================
-- CREATE INDEX idx_bom_bom_item_id            ON ods.dp_mpo_mopp_bom_detial_info_dd(bom_item_id);
-- CREATE INDEX idx_bom_material_code          ON ods.dp_mpo_mopp_bom_detial_info_dd(material_code);
-- CREATE INDEX idx_bom_parent_material_code   ON ods.dp_mpo_mopp_bom_detial_info_dd(parent_material_code);


-- =====================================================================
-- 1. 兼容性自检：CTE（WITH）能否出现在视图定义里
--    有些数据库版本拒绝 "CREATE VIEW ... AS WITH ..."
-- =====================================================================
-- CREATE OR REPLACE VIEW v_cte_probe AS
-- WITH t AS (SELECT 1 AS a) SELECT * FROM t;
-- SELECT * FROM v_cte_probe;          -- 期望返回 1
-- DROP VIEW IF EXISTS v_cte_probe;
--
-- 若报错：改用第 5 节的「固定起点视图」或第 6 节的「无 WITH 嵌套写法」。
--
-- 另测递归 CTE 支持（可选，仅用于参考）：
-- WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM t WHERE n < 3)
-- SELECT * FROM t;                    -- 期望 1,2,3


-- =====================================================================
-- 2. 主视图：0..5 层，动态起点（start_bom_item_id 作为过滤列）
-- =====================================================================
CREATE OR REPLACE VIEW ods.bom_path_5 AS
WITH
level_0 AS (
  SELECT  bom_item_id              AS start_bom_item_id,
          bom_item_id,
          material_code,
          parent_material_code,
          0                        AS depth,
          bom_item_id              AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd
),
level_1 AS (
  SELECT  l.start_bom_item_id,
          e.bom_item_id,
          e.material_code,
          e.parent_material_code,
          1                        AS depth,
          l.bom_item_id            AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_0 l ON l.parent_material_code = e.material_code
),
level_2 AS (
  SELECT  l.start_bom_item_id,
          e.bom_item_id,
          e.material_code,
          e.parent_material_code,
          2                        AS depth,
          l.bom_item_id            AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_1 l ON l.parent_material_code = e.material_code
),
level_3 AS (
  SELECT  l.start_bom_item_id,
          e.bom_item_id,
          e.material_code,
          e.parent_material_code,
          3                        AS depth,
          l.bom_item_id            AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_2 l ON l.parent_material_code = e.material_code
),
level_4 AS (
  SELECT  l.start_bom_item_id,
          e.bom_item_id,
          e.material_code,
          e.parent_material_code,
          4                        AS depth,
          l.bom_item_id            AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_3 l ON l.parent_material_code = e.material_code
),
level_5 AS (
  SELECT  l.start_bom_item_id,
          e.bom_item_id,
          e.material_code,
          e.parent_material_code,
          5                        AS depth,
          l.bom_item_id            AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_4 l ON l.parent_material_code = e.material_code
)
SELECT * FROM level_0
UNION ALL SELECT * FROM level_1
UNION ALL SELECT * FROM level_2
UNION ALL SELECT * FROM level_3
UNION ALL SELECT * FROM level_4
UNION ALL SELECT * FROM level_5;


-- =====================================================================
-- 3. 正确性 + EXPLAIN（判断条件下推是否生效）
-- =====================================================================
-- 3.1 结果（应为 0..5 层的祖先链；depth 0 = 起点）
-- SELECT * FROM bom_path
-- WHERE start_bom_item_id = '11690056988'
-- ORDER BY depth, bom_item_id;
--
-- 3.2 MySQL 8：
-- EXPLAIN FORMAT=TREE
-- SELECT * FROM bom_path WHERE start_bom_item_id = '11690056988' ORDER BY depth;
-- EXPLAIN ANALYZE
-- SELECT * FROM bom_path WHERE start_bom_item_id = '11690056988' ORDER BY depth;
--
-- 3.3 Doris：
-- EXPLAIN
-- SELECT * FROM bom_path WHERE start_bom_item_id = '11690056988' ORDER BY depth;
-- EXPLAIN VERBOSE
-- SELECT * FROM bom_path WHERE start_bom_item_id = '11690056988' ORDER BY depth;
--
-- 观察点：
--   ✅ 通过：level_0 那次扫描带上了 bom_item_id 的谓词（index/prefix/range scan），
--            没有出现 "full table scan" / "SCAN" 后再 filter。
--   ❌ 不通过：level_0 仍为全表扫描，起点过滤只出现在最外层。
--   → 不通过时，用第 5 节的「固定起点视图」，或走改内核（PathNode）方案。


-- =====================================================================
-- 4. 计时对比（主视图 vs 固定起点手写视图）
-- =====================================================================
-- SET @t = NOW(6);
-- SELECT COUNT(*) FROM bom_path WHERE start_bom_item_id = '11690056988';
-- SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) AS us_dynamic_view;
--
-- SET @t = NOW(6);
-- SELECT COUNT(*) FROM bom_path_fixed;
-- SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6)) AS us_fixed_view;
--
-- Doris 用法类似（用当前时间函数），重点看 EXPLAIN 的扫描行数/耗时。


-- =====================================================================
-- 5. 固定起点视图（起点写死；调用方按需建，代价最小）
-- =====================================================================
CREATE OR REPLACE VIEW bom_path_fixed AS
WITH
level_0 AS (
  SELECT  bom_item_id, material_code, parent_material_code,
          0 AS depth, bom_item_id AS start_bom_item_id, bom_item_id AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd
  WHERE   bom_item_id = '11690056988'
),
level_1 AS (
  SELECT  e.bom_item_id, e.material_code, e.parent_material_code,
          1 AS depth, l.start_bom_item_id, l.bom_item_id AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_0 l ON l.parent_material_code = e.material_code
),
level_2 AS (
  SELECT  e.bom_item_id, e.material_code, e.parent_material_code,
          2 AS depth, l.start_bom_item_id, l.bom_item_id AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_1 l ON l.parent_material_code = e.material_code
),
level_3 AS (
  SELECT  e.bom_item_id, e.material_code, e.parent_material_code,
          3 AS depth, l.start_bom_item_id, l.bom_item_id AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_2 l ON l.parent_material_code = e.material_code
),
level_4 AS (
  SELECT  e.bom_item_id, e.material_code, e.parent_material_code,
          4 AS depth, l.start_bom_item_id, l.bom_item_id AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_3 l ON l.parent_material_code = e.material_code
),
level_5 AS (
  SELECT  e.bom_item_id, e.material_code, e.parent_material_code,
          5 AS depth, l.start_bom_item_id, l.bom_item_id AS predecessor
  FROM    ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN    level_4 l ON l.parent_material_code = e.material_code
)
SELECT * FROM level_0
UNION ALL SELECT * FROM level_1
UNION ALL SELECT * FROM level_2
UNION ALL SELECT * FROM level_3
UNION ALL SELECT * FROM level_4
UNION ALL SELECT * FROM level_5;


-- =====================================================================
-- 6. 备选：若数据库拒绝 "CREATE VIEW ... AS WITH ..."
--    用嵌套派生表写 0..2（其余层同理手工展开）。
--    注意：这是最容易踩坑的形态，建议优先用第 5 节的固定起点视图。
-- =====================================================================
-- CREATE OR REPLACE VIEW bom_path_nested AS
-- SELECT 0 AS depth, material_code AS start_node, material_code AS node,
--        parent_material_code, material_code AS predecessor
-- FROM   ods.dp_mpo_mopp_bom_detial_info_dd
-- UNION ALL
-- SELECT 1, l.start_node, e.material_code, e.parent_material_code, l.node
-- FROM   ods.dp_mpo_mopp_bom_detial_info_dd e
-- JOIN  (SELECT material_code AS start_node, material_code AS node, parent_material_code
--        FROM ods.dp_mpo_mopp_bom_detial_info_dd) l
--   ON  l.parent_material_code = e.material_code
-- UNION ALL
-- SELECT 2, l.start_node, e.material_code, e.parent_material_code, l.node
-- FROM   ods.dp_mpo_mopp_bom_detial_info_dd e
-- JOIN  (SELECT 1 AS depth, l.start_node, e.material_code AS node, e.parent_material_code
--        FROM   ods.dp_mpo_mopp_bom_detial_info_dd e
--        JOIN  (SELECT material_code AS start_node, material_code AS node, parent_material_code
--               FROM ods.dp_mpo_mopp_bom_detial_info_dd) l
--          ON  l.parent_material_code = e.material_code) l
--   ON  l.parent_material_code = e.material_code;
