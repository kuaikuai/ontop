# path:traversalFilter 每层剪枝接入 — 设计方案

**Date**: 2026-11-12
**Status**: Proposed
**Author**: opencode (AI-assisted)
**Related**:
- `2026-11-11-hierarchical-path-static-design.md`（静态化路径主设计，5.6 traversalFilter）
- `2026-11-12-path-spec-deriver-design.md`（PathSpecDeriver，nodeColumns 推导）

## 1. 目标

把 SPARQL 里的遍历剪枝条件接入 `HierarchySqlBuilder` 的**每一层 `level_k` 的 `WHERE`**，实现：

- 不满足条件的节点不进入该层，也不再继续向上展开（断链）；
- 与当前的“结果过滤”（外层 `WHERE`）区分，由 `path:traversalFilter true` 显式开关。

`HierarchySqlBuilder` 已经预留每层 `filterSql` 拼接点，本文补齐“从 SPARQL 条件生成 `TraversalFilter`”这最后一环。

## 2. 现状差距

`HierarchySqlBuilder` 已支持：

```java
String filterSql = filter.flatMap(TraversalFilter::where)
        .map(w -> " WHERE " + w)
        .orElse("");
// level_k: SELECT ... JOIN ... <filterSql>
```

但上游传的 `filter` 始终是 `spec.traversalFilter()`，而：

- `PathSpecDeriver` 推导的 `PathSpec.traversalFilter = Optional.empty()`；
- 翻译层只把 `path:traversalFilter` 当普通 `path:` 元数据（`TrueNode`），没有提取条件；
- 结果：`filterSql` 永远为空，剪枝不生效。

## 3. 对外语法

```sparql
PREFIX prop: <http://www.jhk.com/.../property/>
PREFIX path: <http://ontop.org/path#>

SELECT DISTINCT ?ancestor ?material_code ?bom_version WHERE {
  <.../bom_item/11690056988> prop:parent_bom_item* ?ancestor .
  <.../bom_item/11690056988> path:maxDepth 2 .

  # 显式开启遍历剪枝
  ?ancestor path:traversalFilter true .

  # 剪枝条件（每层节点都必须满足）
  ?ancestor prop:bom_version ?bom_version .
  FILTER(?bom_version = "1")
}
```

语义：

- `level_0`（起点）默认不剪枝；`applyToStart=true` 时起点也必须满足。
- `level_k`（k>=1）的父行必须满足条件，否则不进入该层，祖先不再出现。
- 未写 `path:traversalFilter true` 时，同样的条件退化为**结果过滤**（外层 `WHERE`）。

## 4. 数据流

```text
SPARQL
  → RDF4J: ArbitraryLengthPath + path:traversalFilter + 属性 triple + Filter
  → 翻译层：
      1. 识别 ?ancestor path:traversalFilter true
      2. 收集 ?ancestor prop:X ?v（X 在 spec.nodeColumns）
      3. 收集引用 ?v 的 FILTER/比较
      4. 用 nodeColumns 把 X → 列，序列化成 SQL（带别名 p.）
      5. 与 spec.traversalFilter() 的静态 where 用 AND 合并
      6. HierarchySqlBuilder.build(spec, seed, min, max, dynamicFilter)
  → 每层 level_k: SELECT ... JOIN ... WHERE p.<列> <op> <value>
  → level_0: 按 applyToStart 决定是否加同样条件
```

## 5. 条件提取与序列化

### 5.1 属性 → 列

只允许 `spec.nodeColumns` 中已有的属性：

- `?ancestor prop:bom_version ?v` → `bom_version` 列；
- 若属性不在 `nodeColumns`（跨表/复杂映射），报不支持，或回退到结果过滤。

### 5.2 RDF4J 表达式 → SQL

新增一个受限的 `SparqlConditionSqlSerializer`，输入 RDF4J `ValueExpr`，把变量替换成 `p.<列>`，支持：

| SPARQL | SQL |
|---|---|
| `?v = "1"` | `p.bom_version = '1'` |
| `?v > 100` | `p.num > 100` |
| `?v IN (1,2,3)` | `p.bom_version IN (1,2,3)` |
| `FILTER(?a >= 1 && ?b <= 9)` | `p.a >= 1 AND p.b <= 9` |
| 函数、算术、跨变量 | 不支持 |

### 5.3 别名约定

- self 场景：父行别名 `p`；
- two-table 场景：`dst`（与 `HierarchySqlBuilder` 的 level_k 一致）；
- 动态生成的 `where` 必须是**完整条件片段**（自带别名），`HierarchySqlBuilder` 直接拼 `WHERE <where>`。

## 6. TraversalFilter 构造

翻译层动态构造：

```java
TraversalFilter dynamic = new TraversalFilter(
        Optional.empty(),
        Optional.of("p.bom_version = '1'"),
        applyToStart);

// 与静态配置合并
Optional<TraversalFilter> merged = mergeAnd(spec.traversalFilter(), dynamic);
```

`mergeAnd`：两者的 `where` 用 `AND` 连接；`join` 保留；`applyToStart` 任一为 true 即 true。

`PathSpec.traversalFilter` 仍可保留（配置静态补充），动态条件优先合并。

## 7. HierarchySqlBuilder 接入

当前每层：

```sql
level_k AS (
  SELECT ... FROM <table> p
  JOIN level_{k-1} l ON p.<joinKey> = l.next_key
  [WHERE <traversalFilter.where>]
)
```

需要确认：

1. `filterSql` 使用动态合并后的 `TraversalFilter.where`；
2. `level_0` 在 `applyToStart=true` 时追加 `AND <where>`；
3. `where` 自带别名（`p.` / `dst.`），builder 不加别名。

## 8. 剪枝 vs 结果过滤的开关

- `?ancestor path:traversalFilter true` 存在 → 条件进每层 `WHERE`，不再生成外层过滤；
- 不存在 → 保持现有行为（属性下沉 + 外层 `WHERE`）。

翻译层需要：

- 在 `collectPathMetadata` 收集 `path:traversalFilter`（主语必须是路径终点变量）；
- 标记该路径为剪枝模式；
- 在 `translatePath` 里构造动态 `TraversalFilter`；
- 若剪枝生效，`?v` 对应的属性 triple 不再生成外层 Filter（但仍可投影）。

## 9. 属性投影

剪枝条件消费了 `?v`，但 `?v` 往往也在 `SELECT` 中。建议：

- 属性列仍随路径 CTE 带出（已下沉）；
- `?v` 从路径 relation 列取值并投影；
- 条件只用于每层 `WHERE`，不影响最终投影。

## 10. 边界

| 支持 | 不支持 |
|---|---|
| `path:traversalFilter true` + 单/多属性条件 | 属性不在 `nodeColumns` |
| `=`, `>`, `>=`, `<`, `<=`, `IN`, `AND`, `OR` | 函数、算术、跨变量 FILTER |
| `applyToStart` 控制起点 | 起点由复杂子查询选出 |
| self / two-table 的别名 | 多段谓词组合 |

## 11. 测试

1. **单元**：`SparqlConditionSqlSerializer` 对 `= / > / IN / AND` 的输出。
2. **单元**：`TraversalFilter` 与静态 `where` 的 AND 合并。
3. **集成（H2）**：
   - 中间节点不满足 → 其祖先不出现（断链）；
   - 同一条件不加 `path:traversalFilter` → 祖先仍出现（结果过滤对照）；
   - `applyToStart=true` 时起点不满足 → 结果为空。
4. **SQL 断言**：`level_1`/`level_2` 的 `WHERE` 含条件；外层不再有同名过滤。

## 12. 风险

| 风险 | 缓解 |
|---|---|
| 表达式→SQL 覆盖不足 | 只支持基本比较/`IN`/布尔组合，其余报不支持 |
| 条件属性未下沉 | 要求属性在 `nodeColumns`，否则回退结果过滤 |
| 别名不一致 | 统一 `p`/`dst`，文档约束 `where` 自带别名 |
| 剪枝与结果过滤重复 | 由 `path:traversalFilter` 开关互斥 |
| DB 未把条件下推到 JOIN | `EXPLAIN` 验证；必要时把 `WHERE` 移入 JOIN 条件 |

## 13. 工作量

约 **1–1.5 天**：

- `SparqlConditionSqlSerializer`：0.5 天；
- 翻译层收集/构造 `TraversalFilter`：0.5 天；
- `HierarchySqlBuilder` 合并与 `applyToStart`：0.25 天；
- 测试：0.5 天。

## 14. 参考

| 文件 | 用途 |
|---|---|
| `core/kg-query/.../translation/impl/RDF4JTupleExprTranslator.java` | `collectPathMetadata` / `translatePath` |
| `core/kg-query/.../path/HierarchySqlBuilder.java` | 每层 `WHERE` 拼接 |
| `core/model/.../dbschema/PathSpec.java` | `TraversalFilter` 定义（join/where/applyToStart） |
| `docs/superpowers/specs/2026-11-11-hierarchical-path-static-design.md` | 5.6 traversalFilter 主设计 |
