# 实体间层级展开（不定跳路径）— 静态化方案设计

**Date**: 2026-11-11
**Status**: Active — 已实现（v3.0）；零配置（PathSpecDeriver）与属性下沉已落地。
**Supersedes**: `2026-11-05-sparql-recursive-path-design.md`（PathNode 深度方案转为备选/参考）
**Author**: opencode (AI-assisted)

## 实现补充（v3.0）

本节记录 v2.0 之后实际落地的设计变更（代码已提交）。

### PathSpec 字段扩展

- `nodeKeyType`：节点 key 的 DB 类型（如 `BIGINT` / `VARCHAR`），由 `PathSpecDeriver` 从关系列类型自动取，或配置指定。
- `nodeColumns`：节点属性 IRI → 物理列，用于把同表属性下沉到路径 CTE。

### SQL 形态

- `HierarchySqlBuilder` 生成**链式 CTE**（Doris 支持 `(WITH ...) vN`）：

  ```sql
  WITH level_0 AS (...), level_1 AS (...), ..., level_N AS (...)
  SELECT ... FROM level_{minDepth} UNION ALL ... level_N
  ```

- `node_iri` / `predecessor` 输出**原始 key 列**（不再 `CONCAT('', ...)`），与属性表 join 时无 `CAST`。
- 起点字面量按 `nodeKeyType` 决定是否加引号（数值不加，字符串加）。
- 每层 CTE 同时带出 `nodeColumns` 中的属性列，最终 `SELECT` 也投影它们。

### PathRelationDefinition 列顺序

| 索引 | 列 |
|---|---|
| 0 | `node_iri` |
| 1 | `depth` |
| 2 | `predecessor` |
| 3.. | `nodeColumns`（下沉属性） |

translator 的 `argumentMap` 从索引 3 开始绑定属性列，必须与 `toRelation` 的追加顺序保持一致。

### 自动推导（PathSpecDeriver）

启动期从 `OBDASpecification.getSaturatedMapping()` 自动推导，零配置：

- **边推导**：穿透外层 `ConstructionNode` 下的 `DISTINCT` / 内层 `Construction` / `Filter`，找到 `InnerJoin` 的两个 `ExtensionalDataNode`（同一 relation、共享唯一 JOIN 变量）；提取 `table`、`nodeKeyColumn` / `nodeKeyType`、`joinKeyColumn`、`parentJoinKeyColumn`、`iriPrefix`。
- **属性列推导**：从每个属性映射提取 `(table, column)`；支持 `Construction -> Union`（多来源属性），对每个 Union 分支提取，按 `table` 分组。边推导时只取与 `PathSpec.table` 匹配的属性（“以当前表为单位”）。
- 手动 `path-spec.properties` 仍可用，且优先于自动推导。

### 属性下沉（translator）

- 收集 `?ancestor prop:X`（`X` 在 `spec.nodeColumns` 中）。
- 该 triple 翻译成 `TrueNode`，`?X` 由路径 relation 的对应列提供。
- `ConstructionNode` 投影 `?ancestor` 与所有下沉属性变量；下沉属性用 `RDFLiteral(raw, xsd:string)` 包装，避免结果层出现 DB 常量（`"..."^^TEXT`）。
- 未在 `nodeColumns` 中的属性（跨表、复杂映射）仍走普通 join。

### 边界

- 边推导要求：单 relation self-join、两端同 IRI 前缀、共享唯一 JOIN 变量、IRI 模板参数可穿透到单列。
- 属性下沉要求：属性在 `PathSpec.table` 有单列映射；多来源属性按表筛选；复杂映射跳过。
- 异构边（person/company）、多表边仍为 Phase 2。

## 1. 背景与目标

### 1.1 需求

SPARQL 无法直接表达任意谓词的不定跳：

- Ontop 仅支持 `rdfs:subClassOf*`，其他 `:p*` / `:p+` 抛 `Unsupported arbitrary length path`（`RDF4JTupleExprTranslator.java:274-328`）。
- RDF4J 5.1.4 在解析期拒绝 `:p{m,n}`（`MalformedQueryException`），`ArbitraryLengthPath` 只有 `minLength`、没有 `maxLength`。

目标：支持"实体间层级展开"（BOM 父子、组织上下级、人-公司任职等），查询形式：

```sparql
?s :p* ?o .
?s path:maxDepth 9 .
```

并输出 `depth`、`predecessor` 与节点属性。

### 1.2 场景边界

- 支持：同构层级（两端同一实体）、两表同域、异构端点（人/公司，不同 IRI 命名空间）。
- 不支持：多谓词组合（`|`、`/`）、`OPTIONAL`/`UNION` 内的路径、运行时参数化深度、起点由任意图模式/子查询选出。

### 1.3 核心决策

采用**静态化方案**：

1. 边（`parent_bom_item` 等）的物理来源**预配置**为 `PathSpec`，查询期不动态推导。
2. 起点**静态**：Phase 1 仅常量 IRI；Phase 2 支持 `PathSpec.start` 的简单属性过滤。
3. 运行时按模板生成路径 SQL，经 `PathRelationDefinition`（name = `(<pathSql>)`）注入为一个普通关系。
4. **不新增 IQ 节点**，不改 visitor/优化器；复用现有 `serializeRelation` 的派生表输出。
5. 路径中间层同时携带 **节点 IRI（node_iri）** 与 **连接键（join_key）**，两者可以是不同列。
6. 层间按 `join_key`（物理列）连接，对外按 `node_iri`（IRI）连接节点属性，天然支持同表、两表、异构端点。

### 1.4 前置验证（Phase 0，必须先做）

以下假设不成立时，方案需要调整。实现前先写最小验证代码：

| # | 假设 | 现状 / 证据 | 验证方式 |
|---|---|---|---|
| P0-1 | 翻译层能拿到 `MetadataLookup` | **已验证失败**：`OBDASpecification` 只有 mapping/DBParameters；engine 无 `MetadataLookup`；translator 是 singleton | 已改用自定义 `PathRelationDefinition`（见第 8 节） |
| P0-2 | 运行时 `MetadataLookup` 支持 `getBlackBoxView` | **已验证失败**：`getBlackBoxView` 只在 mapping 解析期使用；`ImmutableMetadataLookup` 抛 Unsupported | 已移除该依赖 |
| P0-3 | 能创建路径关系节点 | `CoreSingletons.getIQFactory()` 返回 `IntermediateQueryFactory`，`createExtensionalDataNode` 可用（`IntermediateQueryFactory.java:60`） | 单测直接构造 |
| P0-4 | 派生表 SQL 在目标 DB 合法 | `serializeRelation` 输出 `(<pathSql>) alias` | 对目标 DB 跑嵌套派生表 |
| P0-5 | 路径 IRI 与 Ontop 构造的 IRI 逐字节一致 | Ontop 会做类型转换（如 `BIGINTToTEXT`）与编码 | 用同一 id 比较路径 `node_iri` 与属性映射的 IRI |

## 2. 数据流

```text
SPARQL:  :bom_item/X :p* ?ancestor ;  :bom_item/X path:maxDepth 9
   ↓ RDF4J
ArbitraryLengthPath(subject=常量IRI, pathExpression=:p, object=?ancestor, minLength=0)
   ↓ 翻译层（RDF4JTupleExprTranslator.translate(ArbitraryLengthPath)）
收集 path:maxDepth / path:minDepth；若存在 path:traversalFilter 则解析剪枝条件；构造 SeedSource（Phase 1 为常量）
   ↓
PathSpecRegistry.get(:p)  →  PathSpec
   ↓
HierarchySqlBuilder.build(spec, seedSource, minDepth, maxDepth, traversalFilter)  →  pathSql
   ↓
PathQuerySupport.toRelation(pathSql)  →  PathRelationDefinition（name = "(" + pathSql + ")"）
   ↓
iqFactory.createExtensionalDataNode(rel, {node_iri→?ancestor, depth→?depth, predecessor→?pred})
   ↓ 普通 IQ：与其它 triple join、投影
   ↓ serializer：PathRelationDefinition 内联为派生表
最终 SQL
```

## 3. "静态"的确切含义

| 对象 | 何时确定 | 说明 |
|---|---|---|
| 边物理来源（表、连接列、节点列、IRI 模板） | 预配置 / 管理期 | `PathSpec`，可启动加载，也可 API 热更新 |
| 起点 | 由 `SeedSource` 提供 | Phase 1 仅常量 IRI；Phase 2 支持 `PathSpec.start` 过滤 |
| minDepth / maxDepth | 查询期常量 | 来自 `path:minDepth` / `path:maxDepth` |
| 路径展开 SQL | 查询期生成 | 由模板拼接，不需要人工编写 |
| 节点属性 join | 查询期普通 triple | 例如 `?ancestor :itemCode ?code` |

静态化不等于配置不可变：配置本身可通过 API 在管理期更新（见第 9 节）。

### 3.1 起点抽象（SeedSource）

路径 SQL 生成器不直接接收"常量"，而接收一个 `SeedSource`，`level_0` 由它生成。Phase 1 只启用常量实现，但机制上为"普通查询选起点"预留。

```java
public sealed interface SeedSource {
    record Constant(Iri iri) implements SeedSource {}
    record Values(ImmutableList<Iri> iris) implements SeedSource {}
    record TempTable(String table) implements SeedSource {}   // 预留
}
```

`HierarchySqlBuilder.build(spec, seedSource, minDepth, maxDepth, traversalFilter)` 按 `SeedSource` 生成 `level_0`：

- `Constant`  → 常量 IRI；若 `nodeKeyColumn != joinKeyColumn`，需查节点表解析出 `join_key`（见 6.2）。
- `Values`    → `SELECT node_iri, join_key, 0, node_iri FROM (VALUES ...) v(node_iri, join_key)`
- `TempTable` → `SELECT node_iri, join_key, 0, node_iri FROM <table>`

Phase 1 只有 SPARQL 入口，产生 `Constant`。后续"普通查询选起点"由应用层先执行起点查询，再构造 `Values` / `TempTable` 直接调用同一个 builder，**无需改动 SQL 生成器和路径结构**。

## 4. 对外语法

```sparql
PREFIX :    <http://example.org/bom/>
PREFIX path: <http://ontop.org/path#>

SELECT ?depth ?ancestor ?predecessor ?itemCode WHERE {
  :bom_item/3517912884 :parent_bom_item* ?ancestor .

  :bom_item/3517912884 path:maxDepth 9 .

  ?ancestor path:depth       ?depth .
  ?ancestor path:predecessor ?predecessor .

  ?ancestor :itemCode ?itemCode .
}
```

| 谓词 | 主语 | 含义 | 必需 |
|---|---|---|---|
| `path:maxDepth` | 路径起点 | 最大深度 N，生成 `level_0..level_N` | 是 |
| `path:minDepth` | 路径起点 | 最小深度，必须 >= 1，默认 1；`depth=0` 仅内部种子，不输出 | 否 |
| `path:depth` | 路径终点变量 | 输出当前层深度 | 否 |
| `path:predecessor` | 路径终点变量 | 输出朝起点方向的前驱节点 | 否 |

### 4.1 语义

- `*` / `+` / `^` 的解析与 RDF4J 现有行为完全一致，**不修改**：`*` → `minLength=0`，`+` → `minLength=1`，`^` 按 RDF4J 的 subject/object 角色处理。
- 深度范围由新机制 `path:minDepth` / `path:maxDepth` 决定，不改写 `*` / `+` 的语义：
  - `path:maxDepth` 必填，生成 `level_0..level_N`。
  - `path:minDepth` 可选，**取值必须 >= 1**；未提供时默认 1。
  - `depth = 0`（起点自身）只作为内部种子 `level_0`，**不作为结果输出**。
- 输出范围为 `level_{minDepth}..level_N`（`minDepth >= 1`）。
- `predecessor` 表示朝起点方向的前一个节点，对输出的 `depth >= 1` 生效。
- `path:maxDepth < path:minDepth` 时报错。

### 4.2 Phase 1 限制

- 起点必须是常量 IRI：`:bom_item/X :p* ?o`。
- 不支持 `?s :bomItemId "X" . ?s :p* ?o`（Phase 2 用 `PathSpec.start`，见 10.2）。
- 同一查询只允许一条路径（见 7.2 步骤 9）。

## 5. PathSpec 配置

### 5.1 关键概念：连接键 vs 节点键

路径节点是 RDF 节点，其 IRI 由某一列构造；而边连接可能用另一列。两者必须分开，否则会出现"路径能找到、属性 join 不到"或"跨类型误匹配"。

| 概念 | 含义 | 例 |
|---|---|---|
| `joinKeyColumn` | 本层节点用于连接下一跳的列 | `material_code` |
| `parentJoinKeyColumn` | 下一跳（父侧）用于匹配的列 | `parent_material_code` |
| `nodeKeyColumn` | 构造节点 IRI 的列 | `bom_item_id` |
| `iriTemplate` | 节点 IRI 模板，必须与节点映射一致 | `http://.../bom/bom_item/{bom_item_id}` |

### 5.2 同表（self-relation）

```json
{
  "http://example.org/bom/parent_bom_item": {
    "shape": "self",
    "table": "ods.dp_mpo_mopp_bom_detial_info_dd",
    "joinKeyColumn": "material_code",
    "parentJoinKeyColumn": "parent_material_code",
    "nodeKeyColumn": "bom_item_id",
    "iriTemplate": "http://example.org/bom/bom_item/{bom_item_id}"
  }
}
```

### 5.3 两表 / 异构端点

```json
{
  "http://example.org/hr/employedBy": {
    "shape": "two-table",
    "src": {
      "table": "hr.person", "alias": "p",
      "joinKeyColumn": "p.id",
      "nodeKeyColumn": "p.id",
      "iriTemplate": "http://example.org/hr/person/{id}"
    },
    "dst": {
      "table": "hr.company", "alias": "c",
      "joinKeyColumn": "c.id",
      "nodeKeyColumn": "c.id",
      "iriTemplate": "http://example.org/hr/company/{id}"
    },
    "on": "p.company_id = c.id"
  }
}
```

### 5.4 字段

| 字段 | 适用 | 含义 |
|---|---|---|
| `shape` | 全部 | `self` / `two-table` |
| `table` | self | 物理表（同一张表既作本层也作父层） |
| `joinKeyColumn` | self | 本层连接列 |
| `parentJoinKeyColumn` | self | 父侧匹配列 |
| `nodeKeyColumn` | self | 节点 IRI 列 |
| `iriTemplate` | self | 节点 IRI 模板 |
| `src` / `dst` | two-table | 两端各自的 table/alias/joinKey/nodeKey/iriTemplate |
| `on` | two-table | 两端连接条件 |
| `traversalFilter` | self / two-table | 遍历剪枝条件，见 5.6 |

### 5.5 注册校验

注册时校验：

1. 表、列存在。
2. `iriTemplate` 与节点映射的 target 模板一致（从 `Mapping` 读取对应谓词的 target）。
3. `joinKeyColumn` 与 `parentJoinKeyColumn` 类型可比较。
4. `nodeKeyColumn` 必须在 `iriTemplate` 中出现（占位符匹配）。

任何一项失败都拒绝注册，避免运行期才暴露。

### 5.6 遍历剪枝（traversalFilter）

`traversalFilter` 在每层 `level_k` 生成时加入：**不满足条件的节点不进入该层，也不再继续展开**（断链）。条件有两种来源，可同时使用。

#### 5.6.1 SPARQL 条件（推荐）

属性 IRI → 物理列的映射**默认由 `PropertyColumnRegistry` 在启动期从 OBDA 自动派生**，查询侧直接写条件，不需要手写 `filters`：

```sparql
?s :parent_bom_item* ?ancestor .
?s path:maxDepth 9 .

?ancestor path:traversalFilter true .   # 显式标记：该变量上的条件用于每层剪枝（`true` 为 xsd:boolean 字面量）
?ancestor :num ?num .
FILTER(?num > 100)
```

`PropertyColumnRegistry`（启动期构建）：

1. 遍历所有属性谓词；
2. `mapping.getRDFPropertyDefinition(predicate, iri)` 取映射定义；
3. 从 target 模板和 source 的变量绑定提取"属性 → (table, column, nodeKey)"；
4. 只接受简单映射（单表、单 triple、列直接对应）；Union / join / 表达式映射标记为 `ambiguous`。
5. 自动派生仅覆盖"剪枝属性列位于路径节点表（self 的 `table` / two-table 的 `dst.table`）"的情况；属性在其它表时标记 `ambiguous`，需人工 `filters`（或 5.6.2 静态 join）。

人工覆盖（可选，仅复杂映射需要）：

```json
{
  "traversalFilter": {
    "filters": {
      "http://example.org/bom/num": "num"
    },
    "applyToStart": false
  }
}
```

配置优先于自动派生；两者都拿不到时，该属性不能用于剪枝，报不支持。

翻译层：

1. 识别 `?ancestor path:traversalFilter true`，进入剪枝模式；
2. 对 `?ancestor` 上每个属性 triple，先查配置 `filters`，再查 `PropertyColumnRegistry`，得到"变量 → 列"；
3. 收集引用这些变量的 FILTER / 比较，替换成列表达式；
4. 生成条件（如 `num > 100`），加别名后拼进每层 `WHERE`（self：`p.num > 100`；two-table：`dst.num > 100`）；
5. 这些 triple / FILTER 从外层查询移除，不重复执行。

支持的条件：

| SPARQL | 生成 |
|---|---|
| `?ancestor :num 100` | `p.num = 100` |
| `?ancestor :num ?v . FILTER(?v > 100)` | `p.num > 100` |
| `FILTER(?v >= 10 && ?v <= 100)` | `p.num >= 10 AND p.num <= 100` |
| `FILTER(?v IN (1,2,3))` | `p.num IN (1,2,3)` |
| 函数、算术、跨变量 FILTER | 不支持 |

`FILTER` 必须只引用剪枝属性变量（如 `?num`）；引用路径外变量或其它变量时报不支持。

#### 5.6.2 静态 SQL 片段（可选）

```json
{
  "traversalFilter": {
    "join": { "table": "ods.dp_mpo_mopp_bom_detial_info_dd",
              "alias": "n",
              "on": "n.bom_item_id = p.bom_item_id" },
    "where": "n.status = 'ACTIVE'"
  }
}
```

- `where` 直接作为每层 SQL 条件；别名约定：self `p`，two-table `src` / `dst`，join 表用配置的 alias。
- 与 5.6.1 同时存在时用 `AND` 合并。

#### 5.6.3 公共语义

- `applyToStart` 默认 `false`：`level_0` 起点不受剪枝；置 `true` 时起点也必须满足条件，否则结果为空。
- 剪枝是**断链**语义：被剪掉的节点，其祖先也不会出现。只想"过滤输出、仍穿过不满足节点继续展开"时，应使用结果过滤（外层 join / FILTER），不是 `traversalFilter`。
- 只支持属性在 `?ancestor` 上的条件；跨变量 / 任意复杂表达式不支持。
- 被剪枝消费的属性 triple 不再作为结果输出；如需输出，需额外投影。
- 与 OPTIONAL / 普通属性交互：只有谓词在 `filters` 中的属性 triple 及其 FILTER 被剪枝消费；其它属性（如 `OPTIONAL { ?ancestor :name ?name }`）保留在外层，按 `node_iri` 左连接。
- 语义提升：`path:traversalFilter` 把 `?ancestor` 从"结果中某一层的节点"提升为"路径上每一层（`depth >= 1`）的节点都必须满足条件"；这与结果过滤（只在最终输出筛选）不同。

## 6. 路径 SQL 生成

### 6.1 列契约

路径 SQL 内部固定输出四列：`node_iri`、`join_key`、`depth`、`predecessor`。对外只暴露 `node_iri`、`depth`、`predecessor`（`join_key` 仅用于层间连接）。

### 6.2 模板（嵌套派生表，Phase 1）

文中 `<iriTemplate 前缀>` / `<dstTemplate 前缀>` 指 `iriTemplate` 去掉 `{占位列}` 后的字面前缀（如 `http://.../bom/bom_item/`）。

`SeedSource.Constant` 给的是完整 IRI；生成 `level_0` 时先按 `iriTemplate` 反解出 `nodeKeyColumn` 的值（如 `bom_item/3517912884` → `3517912884`）。`nodeKeyColumn != joinKeyColumn` 时，`level_0` 需要查节点表把 IRI 解析成 `join_key`：

```sql
level_0 AS (
  SELECT CONCAT('<iriTemplate 前缀>', <nodeKeyColumn>) AS node_iri,
         <joinKeyColumn>                                AS join_key,
         0                                              AS depth,
         CONCAT('<iriTemplate 前缀>', <nodeKeyColumn>) AS predecessor
  FROM <table>
  WHERE <nodeKeyColumn> = '<startNodeKey>'
)
```

`<startNodeKey>` 是从 IRI 反解出的值；若 `nodeKeyColumn` 为数值类型，需做与 IRI 构造一致的类型转换（见 6.5）。

`applyToStart=true` 时，`level_0` 的 `WHERE` 追加剪枝条件（含 SPARQL 条件与静态 `where`，别名适配起点行）。

`nodeKeyColumn == joinKeyColumn` 时可退化为常量；`maxDepth=9` 时生成 `level_0..level_9`，每层：

```sql
level_k AS (
  SELECT CONCAT('<iriTemplate 前缀>', p.<nodeKeyColumn>) AS node_iri,
         p.<joinKeyColumn>                        AS join_key,
         k                                        AS depth,
         l.node_iri                               AS predecessor
  FROM <table> p
  JOIN level_{k-1} l ON p.<parentJoinKeyColumn> = l.join_key
  [JOIN <traversalFilter.join>]
  [WHERE <traversalFilter.where>]
)
```

两表：

```sql
level_k AS (
  SELECT CONCAT('<dstTemplate 前缀>', dst.<dstNodeKey>) AS node_iri,
         dst.<dstJoinKey>                        AS join_key,
         k                                       AS depth,
         l.node_iri                              AS predecessor
  FROM <src> JOIN <dst> ON <on>
  JOIN level_{k-1} l ON <srcJoinKey> = l.join_key
  [JOIN <traversalFilter.join>]
  [WHERE <traversalFilter.where>]
)
```

最终输出：

```sql
SELECT node_iri, depth, predecessor FROM level_{minDepth}
UNION ALL ... UNION ALL SELECT node_iri, depth, predecessor FROM level_N
```

`level_0` 只作为内部种子，不进入最终输出（`minDepth >= 1`）。

### 6.3 黑盒 SQL 形态（B4）

- **形态 A（Phase 1 采用）**：整段路径 SQL 用**嵌套派生表**，不出现 `WITH`。`PathRelationDefinition` 以 `(<pathSql>)` 作为关系名，由 `serializeRelation` 直接内联。
- **形态 B（Phase 2 可选）**：内部 `WITH` + 外层 CTE，仅当目标 DB 验证支持子查询内 `WITH` 时启用。

Phase 1 固定形态 A，避免方言兼容风险。

### 6.4 方向 `^`（M2）

- Phase 1 只支持正向。
- 反向依赖 RDF4J `ArbitraryLengthPath` 的 subject/object 角色：先验证 `^:p` 下 `getSubjectVar()`/`getObjectVar()` 是否已交换；若已交换，直接按角色取 `src`/`dst`，不额外处理；若未交换，交换 `joinKeyColumn`/`parentJoinKeyColumn`。
- 未验证前，`^` 报 `OntopUnsupportedKGQueryException`。

### 6.5 IRI 编码（S3）

- `CONCAT` 的列若为数值，需与 Ontop 相同的转换（如 `BIGINTToTEXT`）；字符串则需注意 URL 编码/转义。
- `iriTemplate` 的占位符替换必须与 Ontop 的 IRI 构造逻辑一致；Phase 0-5 用同一 id 做逐字节比对。
- 不一致时，路径结果无法与属性 triple 的 `?ancestor` join。

## 7. 翻译层实现

### 7.1 per-spec 上下文（P0-1）

`RDF4JQueryTranslatorImpl` 是 singleton，而 `DBParameters` 属于 per-`OBDASpecification`，不能直接构造注入。Phase 1 用 ThreadLocal 上下文：

- `PathQueryContext`（`ThreadLocal<PathQuerySupport>`）。
- `QuestQueryProcessor`（per-spec，持有 `obdaSpecification.getDBParameters()`）在 `reformulate` 前 `PathQueryContext.set(support)`，`finally` 清除。
- `RDF4JTupleExprTranslator` 在路径分支读 `PathQueryContext.current()`。
- 稳定后可改为显式参数，避免隐式上下文。

不再需要向翻译层注入 `MetadataLookup`。

### 7.2 步骤

1. 从所在 `Join` 的兄弟节点收集 `path:maxDepth`、`path:minDepth`、`path:depth`、`path:predecessor` 的 `StatementPattern`；`maxDepth`/`minDepth` 的主语必须等于路径起点。
2. 若存在 `path:traversalFilter true`，按 5.6.1 对 `?ancestor` 上的属性 triple 解析"属性 → 列"（配置 `filters` 优先，其次 `PropertyColumnRegistry`），生成剪枝条件；配置里的静态 `where` 用 `AND` 合并。
3. 校验 `subjectVar` 是常量 IRI（Phase 1），构造 `SeedSource.constant(iri)`；否则抛 `OntopUnsupportedKGQueryException`。
4. `registry.get(谓词IRI)` 取 `PathSpec`。
5. 计算 `minDepth`：取 `path:minDepth`（必须 >= 1），未提供时默认 1；校验 `maxDepth >= minDepth`。`HierarchySqlBuilder.build(spec, seedSource, minDepth, maxDepth, traversalFilter)` 生成 `pathSql`。
6. `RelationDefinition rel = PathQueryContext.current().toRelation(pathSql)`（见 1.4b）；`current()` 为空时抛不支持。
7. `ExtensionalDataNode node = iqFactory.createExtensionalDataNode(rel, argumentMap)`，绑定：
   - `?ancestor`（`ArbitraryLengthPath.getObjectVar()`）→ `node_iri`
   - `?depth`（`path:depth` 的 object 变量）→ `depth`
   - `?pred`（`path:predecessor` 的 object 变量）→ `predecessor`
8. `path:maxDepth` / `path:minDepth` / `path:depth` / `path:predecessor` / `path:traversalFilter` 以及被剪枝消费的属性 triple/FILTER 翻译为 `TrueNode`。
9. 返回 `TranslationResult`，外层正常 join / 投影。
10. **多路径**：若同一查询出现多条 `ArbitraryLengthPath`，Phase 1 直接抛 `OntopUnsupportedKGQueryException`；Phase 2 再按 `path:maxDepth` 的主语与路径起点配对。

### 7.3 程序化入口

为"普通查询选起点"预留：应用层先执行起点查询，得到 IRI 集合后直接调用
`HierarchySqlBuilder.build(spec, SeedSource.values(...) / tempTable(...), min, max, traversalFilter)`
生成路径 SQL 并执行，无需经过 SPARQL 的 `ArbitraryLengthPath` 分支。

## 8. 关系注入：PathRelationDefinition（替代 getBlackBoxView）

P0-1/P0-2 已证伪：查询期没有 `MetadataLookup`，`getBlackBoxView` 不可用。改用自定义关系：

- `PathRelationDefinition extends AbstractRelationDefinition`，构造 `super("(" + pathSql + ")", builder)`。
- 列固定：`node_iri`、`depth`、`predecessor`；UC/FK 返回空。
- 列定义用 `DBParameters.getQuotedIDFactory()` + `getDBTypeFactory()` 构造，不依赖 DB metadata。
- 现有 `DefaultSelectFromWhereSerializer.serializeRelation` 直接把 `getAtomPredicate().getName()` 作为关系名，因此会输出 `(<pathSql>) vN`，**不需要改 serializer**。
- 不需要保留或注入 `MetadataProvider`。

## 9. 注册表与热更新

### 9.1 接口

```java
public interface PathSpecRegistry {
    void upsert(PathSpec spec);
    void remove(IRI predicate);
    Optional<PathSpec> get(IRI predicate);
    long version();
}
```

实现：不可变快照 + 原子替换。

```java
private final AtomicReference<ImmutableMap<IRI, PathSpec>> snapshot;
private final AtomicLong version;

public void upsert(PathSpec spec) {
    snapshot.updateAndGet(m -> ImmutableMap.<IRI,PathSpec>builder()
            .putAll(m).put(spec.predicate(), spec).build());
    version.incrementAndGet();
}
```

管理 API：`PUT /admin/paths/{iri}`、`DELETE /admin/paths/{iri}`、`GET /admin/paths`。

### 9.2 缓存失效（S6）

- reformulation cache key 加入 `registry.version()`，或在 `upsert/remove` 后清空缓存。
- `PathRelationDefinition` 按 `pathSql` 直接内联，不需要 relation 级缓存；`pathSql` 已含起点/深度/配置。

### 9.3 SQL 缓存与膨胀（S2）

- 每个不同的 `(path, seed, min, max)` 生成不同的 `pathSql`，内联为派生表。
- 可按 `pathSql` 做 LRU 缓存（可选）；`PathRelationDefinition` 本身不进入 metadata cache。
- 起点很多时，优先考虑 Phase 3 的 `SeedSource.Values` 批量形态，而不是每个起点一条 SQL。

### 9.4 持久化与多实例

- 内存或落盘/落库，启动时加载。
- 多实例：配置中心广播，或各实例分别更新。

## 10. 支持边界

### 10.1 Phase 1

| 支持 | 不支持 |
|---|---|
| 常量 IRI 起点 | 起点由任意图模式/子查询选出 |
| 预配置边（self / two-table） | 任意谓词零配置自动推导 |
| 查询内常量 min/maxDepth | 运行时参数化深度 |
| 单一路径谓词、单个路径 | 多条路径、`|`、`/` 组合 |
| 正向 `:p` | 反向 `^:p`（待验证） |
| 路径外层普通 join / 过滤 | `OPTIONAL` / `UNION` 内的路径 |
| 同构 / 两表同域 / 异构端点 | 三表以上复杂视图 |

### 10.2 Phase 2

- `PathSpec.start`：简单属性过滤（`=`, `IN`, 范围），生成 `level_0` 的 `WHERE`。
- `PathSpec.traversalFilter`：SPARQL 条件驱动的遍历剪枝（基本比较 / `IN` / 布尔组合），每层 `level_k` 加 `WHERE`；属性→列默认由 `PropertyColumnRegistry` 自动派生，复杂映射可用 `filters` 人工覆盖，静态 SQL 片段可并用。
- 反向 `^`。
- 多条路径按起点变量配对。
- 两表 join 的 CTE 物化优化。

`PathSpec.start` 字段：`nodeTable`、`nodeKeyColumn`、`iriTemplate`、`filters`（属性 IRI → 列）。Phase 1 不读取该字段。

## 11. 备选方案对比

| 方案 | 改动 | 能力 | 结论 |
|---|---|---|---|
| **静态化（本文）** | 翻译层单点 + 配置 + 模板 | 常量起点、预配置边、任意有限深度 | **采纳** |
| 零代码视图 | 无代码，DB 建视图 | 深度写死，需手工视图 | 备选：深度固定、无动态 |
| PathNode 内核 | 新 IQ 节点 + visitor + converter + serializer | 通用图路径、动态起点（仍有 MVP 限制） | 备选/长期，见 `2026-11-05-sparql-recursive-path-design.md` |

选择静态化的理由：无需改动通用 IQ 框架，SQL 完全可控，能覆盖"实体间层级展开"这一主力场景。

## 12. 测试策略

单元测试：

1. `HierarchySqlBuilder`：self / two-table / `minDepth>0` / `maxDepth=N` / `nodeKey != joinKey` / `traversalFilter` 的 SQL 结构。
2. `PathSpecRegistry`：upsert/remove/version 单调递增、快照隔离。
3. 翻译分支：常量起点成功、变量起点报不支持、缺失 `path:maxDepth` 报不支持、多路径报不支持。
4. `minDepth` 默认 1；`path:minDepth < 1` 报错；`path:maxDepth < path:minDepth` 报错。
5. 注册校验：`iriTemplate` 与节点映射不一致时拒绝。

集成测试（H2）：

1. 同表三级父子（`bom_item_id` 作 IRI、`material_code` 作连接键），断言 `depth = 1/2`（`depth=0` 不输出）、`predecessor`。
2. 两表人-公司边，断言路径在 company 终止、不跨类型误匹配。
3. **IRI 对齐**：断言路径 `node_iri` 与属性映射生成的 `?ancestor` 完全相等。
4. 配置热更新后，同一 SPARQL 命中新配置（缓存失效）。
5. 多父场景去重策略（按需 `DISTINCT`）。
6. 遍历剪枝：`?ancestor :num ?v . FILTER(?v > 100)` 在每层生效；中间节点不满足时其祖先不出现；`applyToStart` 两种行为。
7. `PropertyColumnRegistry`：简单映射自动派生成功；Union / join 映射标记 `ambiguous`；配置 `filters` 优先于派生结果。

回归：现有 `rdfs:subClassOf*` 行为不变。

## 13. 性能验收（S4）

以 BOM 场景为基准：

- 数据量：单表约 100 万行、9 层、平均 1.2 个父。
- 期望：单个起点、`maxDepth=9` 的路径查询在目标库上 < 1s；8 个起点批量 < 3s。
- 至少覆盖：有索引（`bom_item_id` / `material_code` / `parent_material_code`）与无索引两种。

## 14. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| `PathRelationDefinition` 列类型与目标 DB 不匹配 | 序列化/执行失败 | 用 `getAbstractRootDBType()` 或从 PathSpec 配置列类型 |
| ThreadLocal 上下文未清理 | 串查询 | `QuestQueryProcessor` 的 `finally` 清除 |
| PathSpec IRI 与节点映射不一致 | 属性 join 不到 | 注册校验 `iriTemplate`；Phase 0-5 逐字节比对 |
| `nodeKey != joinKey` 被忽略 | 路径错误/放大 | 4 列契约；测试覆盖 |
| 黑盒无法下推外层过滤 | 性能 | 起点/深度生成时固化；外层过滤只作用结果 |
| 子查询内 `WITH` 不兼容 | 部分 DB 失败 | Phase 1 用嵌套派生表；Phase 2 方言探测 |
| relation 缓存膨胀 | 内存 | LRU 上限；批量起点用 `Values` |
| 同一 SPARQL 命中旧计划 | 热更新无效 | cache key 含 `registry.version()` |
| 多实例配置不一致 | 结果不一致 | 配置中心广播 |
| 路径结果重复（多父/环） | 行数膨胀 | `maxDepth` 截断；按需 `DISTINCT` |
| 剪枝断链与业务预期不符 | 祖先缺失 | 文档明确断链语义；需要"穿过但不过滤掉"时改用结果过滤 |
| SPARQL 剪枝表达式翻译受限 | 部分条件不支持 | 只支持属性 triple + 基本比较/`IN`/布尔组合；复杂 FILTER 报不支持 |
| 属性→列自动派生失败或歧义 | 剪枝不可用 | 回退人工 `filters`；仍拿不到则报不支持 |
| 黑盒无 UC/FK | 优化器保守 | 可接受；必要时自定义 RelationDefinition |

## 15. 实施步骤

- **Phase 0**：已完成。结论：查询期无 `MetadataLookup`、`getBlackBoxView` 不可用；改用 `PathRelationDefinition`（见 `analysis/hierarchical-path-phase0.md`）。
- **Phase 1**：`PathSpec`（self）+ `PathSpecRegistry` + `SeedSource.Constant` + `HierarchySqlBuilder`（嵌套派生表、3 列输出）+ `PathRelationDefinition` + `PathQuerySupport`/`PathQueryContext` + 翻译分支。
- **Phase 2**：two-table / 异构端点、`path:depth` / `path:predecessor`、`minDepth`、`PathSpec.start` 简单过滤、`PathSpec.traversalFilter` SPARQL 条件驱动剪枝（含 `PropertyColumnRegistry`）、反向 `^`、多路径配对。
- **Phase 3**：普通查询选起点（`SeedSource.Values` / `TempTable`）、热更新 API、reformulation cache 版本失效、持久化、方言退化。
- **Phase 4**：单测 + H2 集成测试 + 性能验收 + 回归。

## 16. 参考

| 文件 | 用途 |
|---|---|
| `core/kg-query/.../translation/impl/RDF4JTupleExprTranslator.java:274-328` | 现有 `ArbitraryLengthPath` 处理 |
| `core/kg-query/.../translation/impl/RDF4JQueryTranslatorImpl.java:274` | 构造 translator，需在此注入依赖 |
| `core/model/.../dbschema/impl/AbstractRelationDefinition.java:23` | `PathRelationDefinition` 的父类 |
| `core/model/.../dbschema/DBParameters.java:15-19` | `QuotedIDFactory` / `DBTypeFactory` 来源 |
| `db/rdb/.../generation/serializer/impl/DefaultSelectFromWhereSerializer.java:260-269` | `serializeRelation` 直接使用 relation name，`(<sql>)` 可复用 |
| `db/rdb/.../dbschema/impl/BlackBoxViewDefinition.java:34-38` | 历史参考：匿名 SQL 关系的命名方式（当前方案不采用） |
| `db/rdb/.../generation/serializer/impl/DefaultSelectFromWhereSerializer.java:245-284` | `serializeRelation` 输出关系名与派生表 |
| `db/rdb/.../spec/sqlparser/SelectQueryParser.java:25-33` | 历史参考：`getBlackBoxView` 的原始用途 |
| `docs/superpowers/analysis/bom_path_view.sql` | 手工链式 CTE 参考实现 |
| `docs/superpowers/specs/2026-11-05-sparql-recursive-path-design.md` | PathNode 深度方案（备选） |

## 17. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-11 | 确定静态化方案：PathSpec 预配置 + 模板生成路径 SQL + BlackBoxView 注入 |
| v1.1 | 2026-11-11 | Phase 1 选定常量起点；引入 `SeedSource` 抽象，预留"普通查询选起点" |
| v1.2 | 2026-11-11 | Review 修订：新增 Phase 0 前置验证；PathSpec 拆分 joinKey/nodeKey/iriTemplate；4 列契约；固定嵌套派生表；明确 `+`/minDepth、`^`、多路径、依赖注入、MetadataLookup 可用性、relation 缓存、IRI 编码、性能验收 |
| v1.3 | 2026-11-11 | 明确不修改 `*`/`+`/`^` 的现有逻辑；深度范围仅由新机制 `path:minDepth` / `path:maxDepth` 控制，移除 `+` 硬下界/取 max 规则 |
| v1.4 | 2026-11-11 | `minDepth` 取值必须 >= 1，默认 1；`depth=0` 仅作内部种子、不输出；`maxDepth < minDepth` 报错 |
| v1.5 | 2026-11-11 | 新增 `PathSpec.traversalFilter` 遍历剪枝：每层 `level_k` 加 `JOIN`/`WHERE`，不满足条件的节点断链；含 `applyToStart` 语义与测试 |
| v1.6 | 2026-11-11 | `traversalFilter` 支持在 SPARQL 里写条件：配置只声明属性→列（`filters`），查询用 `path:traversalFilter true` 标记，翻译层把属性 triple/FILTER 转成每层 `WHERE`；静态 SQL 片段可并用 |
| v1.7 | 2026-11-11 | 补充 `traversalFilter` 与 OPTIONAL/普通属性的交互：仅 `filters` 内属性被剪枝消费，其它属性保留外层按 `node_iri` 左连接 |
| v1.8 | 2026-11-11 | 属性→列改为启动期由 `PropertyColumnRegistry` 从 OBDA 自动派生；`filters` 降为复杂映射的可选人工覆盖，不再必填 |
| v1.9 | 2026-11-11 | 评审修订：修正 `minDepth 0` 示例、`join_key`/`node_iri` 连接表述、`build` 签名统一 5 参、`iriTemplate` 术语；补剪枝自动派生边界、语义提升、FILTER 作用域、类型转换、数据流与 Phase 2 描述 |
| v2.0 | 2026-11-11 | Phase 0 实测：查询期无 `MetadataLookup`、`getBlackBoxView` 不可用；改用 `PathRelationDefinition` + `PathQuerySupport` + ThreadLocal per-spec 上下文；明确 Java 11 / JUnit 5 约束 |
| v3.0 | 2026-11-12 | 实现落地：链式 CTE（Doris）、原始 key + `nodeKeyType`、`PathSpecDeriver` 自动推导边与属性列、Union 分支按表筛选、DISTINCT/Construction 穿透、属性下沉 + `RDFLiteral` 构造 |
