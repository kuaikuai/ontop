# SPARQL 路径遍历（递归路径算子）— 设计方案

**Date**: 2026-11-05
**Status**: Active — 已采纳 PathNode 深度方案（静态化方案在 IRI 后处理上受阻）。
**Author**: opencode (AI-assisted)

## 0. 当前推荐方案（零代码，优先实施）

本文第 1–12 节描述的是**改内核**的深度方案（新增 `PathNode` IQ 节点 + CTE 生成），
只有在必须使用标准 SPARQL 属性路径语法（`:parent_material*`）时才需要。

调研结论（基于 ontop 5.6.0-SNAPSHOT 源码验证）：

| 能力 | 结论 | 依据 |
|---|---|---|
| 任意长度属性路径 | 不支持，仅 `rdfs:subClassOf*` | `RDF4JTupleExprTranslator.java:274-327` |
| 范围属性路径 `{m,n}` | **解析期即拒绝** | RDF4J 5.1.4 `SPARQLParser` 实测抛 `MalformedQueryException`；`ArbitraryLengthPath` 仅有 `minLength`、无 `maxLength`（`javap` 确认，详见 2.2.1） |
| 递归 CTE (`WITH RECURSIVE`) | 不支持 | 全仓无生成代码；`SelectQueryParser` 拒绝 `WITH` |
| SQL lens 参数化 | 不支持，lens SQL 是静态文本 | `JsonSQLLens.java:60-105`；`LensUnfolderImpl.java:58` |
| SPARQL rules 递归 | 不支持，检测依赖环即拒绝 | `RuleExtractorImpl.java:192-202` |
| lens 含 `WITH`+`UNION` | 需连库取元数据，否则创建失败 | `ApproximateSelectQueryAttributeExtractor.java:49`；`AbstractDBMetadataProvider.java:562-564` |

**零代码方案**：把手工递归 CTE 建为数据库视图，ontop 当普通表映射。

- 视图 SQL：`docs/superpowers/analysis/bom_path_view.sql`
- 映射文件：`docs/superpowers/analysis/bom_path.obda`

优点：SQL 就是手工版；起点/深度通过 `WHERE start_node = ?` / `FILTER(?depth <= ?)` 下推，
数据库可把条件下推进 CTE 链；结果仍是普通 RDF 三元组，可与现有映射任意 join；
不触发 ontop 的黑盒视图路径。

局限：查询侧不是标准属性路径语法（`:p*`）；深度/起点通过过滤表达而非语法。

## 1. 问题与目标

### 1.1 背景

Nebula Graph 等图数据库支持路径查询，例如：

```text
MATCH p=(n:bom_item)-[e:parent_bom_item*1..9]->(m:bom_item)
WHERE n.bom_item.id = "3517912884"
RETURN p
```

Ontop 当前的 SPARQL 属性路径支持非常有限：

- `RDF4JTupleExprTranslator.translate(ArbitraryLengthPath)` 只硬编码支持 `rdfs:subClassOf*`；
- 其他任意长度路径直接抛 `Unsupported arbitrary length path`；
- 没有递归 CTE，也没有深度、前驱等路径元数据。

### 1.2 目标

为 Ontop 增加一个"路径遍历算子"，支持：

1. 用标准 SPARQL 属性路径 `:p+` / `:p*` 表达遍历；
2. 用 `path:` 扩展谓词控制最大深度，并输出深度与前驱；
3. 起点可以是常量，也可以是**普通图模式 / FILTER 选出的变量**（如按 `a cls:bom_item` + 条件筛选）；
4. 底层生成 `WITH level_0 AS ..., level_1 AS ...` 链式 CTE + `UNION ALL`，性能接近手写 SQL；
5. 支持 DAG 多父场景：所有路径每行一条。

### 1.3 非目标

- 不支持运行时参数化的 `maxDepth`（必须是查询内常量）。
- 不支持 `WITH RECURSIVE`。
- 不支持 `?`（零或一）路径；RDF4J 将其展开为 `Union(ZeroLengthPath, StatementPattern)`，MVP 遇到 `ZeroLengthPath` 报不支持。
- 不支持 `|`、`/` 等多段谓词组合；单个 `^` 反向支持。
- 不改变现有 `rdfs:subClassOf*` 行为。

## 2. 对外语法与语义

### 2.1 语法

```sparql
PREFIX :     <http://example.org/bom/>
PREFIX cls:  <http://example.org/class/>
PREFIX prop: <http://example.org/prop/>
PREFIX path: <http://ontop.org/path#>

SELECT ?depth ?ancestor ?predecessor ?itemcode ?itemname WHERE {
  # 起点可由普通图模式 + FILTER 选出
  ?material a cls:bom_item ;
            prop:somevalue ?v .
  FILTER(str(?v) = "valuexxx")

  # 标准属性路径：从 ?material 向上遍历
  #   `*` 含起点(depth 0..N)；`+` 只含祖先(depth 1..N)
  ?material :parent_material* ?ancestor .

  # 可选参数
  ?material path:maxDepth 9 .
  # ?material path:minDepth 0 .

  # 输出路径元数据
  ?ancestor path:depth       ?depth .
  ?ancestor path:predecessor ?predecessor .

  # 普通属性
  ?ancestor :bomItemId ?bomId ;
            :itemCode  ?itemcode ;
            :itemName  ?itemname .
}
```

也支持常量起点：

```sparql
:material/11690056988 :parent_material* ?ancestor .
:material/11690056988 path:maxDepth 9 .
```

### 2.2 扩展谓词

| 谓词 | 主语 | 含义 | 是否必需 |
|---|---|---|---|
| `path:maxDepth` | 路径起点变量/常量 | 最大深度 N，生成 `level_0..level_N` | 必需（当前） |
| `path:minDepth` | 路径起点变量/常量 | 最小深度，默认 0 | 可选 |
| `path:depth` | 路径终点变量 | 输出当前层深度 | 可选 |
| `path:predecessor` | 路径终点变量 | 输出路径前驱（朝起点方向的节点） | 可选 |

这些三元组只作为算子元数据，不参与 RDF 映射展开。

#### 2.2.1 为什么用 `path:maxDepth` 而不是 `{m,n}`

SPARQL 规范的 `PathMod` 只有 `?`、`*`、`+`，`{m,n}` 并非 SPARQL 1.1 标准属性路径语法（属 Jena ARQ 等实现的扩展）。RDF4J 5.1.4 实测：

- `SPARQLParser` 对 `:p{2}`、`:p{1,3}`、`:p{2,}` 均在**解析期**抛 `MalformedQueryException`（期望集合仅含 `? * + / |`）；
- 代数层 `ArbitraryLengthPath` 只有 `minLength: long`，**没有 `maxLength`**，即使解析器接受上界也无字段可存。

因此在 RDF4J 上，`maxDepth` 只能来自：

1. 查询内扩展谓词（本方案选用的 `path:maxDepth`）；
2. 在解析前/后自行处理 `{m,n}` 语法并重写（需绕过或扩展 RDF4J parser，成本更高）。

方案选用第 1 种。若将来 RDF4J 提供上界表达，可改用标准语法，并保留扩展谓词作为兼容。

### 2.3 语义

- `*`（零或多）：结果**含起点**，`depth = 0..N`。
- `+`（一或多）：结果**只含祖先**，`depth = 1..N`。
- `?`（零或一）：RDF4J 展开为 `Union(ZeroLengthPath, StatementPattern)`，MVP **不支持**；若支持，等价于 `minDepth=0, maxDepth=1`。
- `depth` 从起点计：`depth = 0` 为起点，每沿一条边 +1。
- `predecessor` 表示路径上朝起点方向的前一个节点；`depth = 0`（起点）时前驱为起点自己。
- `minDepth` 默认取 RDF4J 的 `minLength`（`*` → 0，`+` → 1），`path:minDepth` 可覆盖。
- 多父 / 多路径：每一层保留所有路径，`UNION ALL` 不去重（层内可按输出列 `DISTINCT`）。
- 方向：由 `pathExpression` 的实际 subject/object 角色决定；正向沿 subject→object，`^` 反向沿 object→subject。
- `maxDepth = N` 生成 `level_0..level_N`（共 N+1 层）。
- `minDepth > 0` 时，最终结果从 `level_{minDepth}` 开始 `UNION ALL`。
- 起点可以是一组种子节点（由图模式 / FILTER 选出），路径对每个种子分别展开。

### 2.4 起点支持范围

MVP 支持：

1. **常量起点**：`:material/A :parent_material* ?ancestor .`（`*`/`+` 均可，语义见 2.3）
2. **同 Join 层的变量起点**：起点变量由普通三元组 / FILTER 绑定，例如：

   ```sparql
   ?material a cls:bom_item ; prop:somevalue ?v .
   FILTER(str(?v) = "valuexxx")
   ?material :parent_material* ?ancestor .
   ```

MVP 不支持（报 `OntopUnsupportedKGQueryException`）：

- 起点变量由**更深层子查询、OPTIONAL、UNION 分支**绑定；
- 起点变量与路径终点变量出现在同一个非路径三元组里，无法安全拆分；
- 起点变量在路径之外的另一个 Join 层被绑定（无法安全提取起点模式）。

## 3. 映射要求

为生成手工版 SQL（每层 `FROM ods e JOIN level_{k-1} l ON l.parent_material_code = e.material_code`），
**边映射的 source 必须是单表扫描**（无 self-join、无聚合、无复杂 WHERE），并且 src/dst 直接对应物理表两列。

推荐映射：

```sql
-- 路径边：material_code -> parent_material_code（单表）
SELECT material_code        AS src_key,
       parent_material_code AS dst_key
FROM ods.dp_mpo_mopp_bom_detial_info_dd
```

对应 RDF 映射：

```text
:material/{src_key} :parent_material :material/{dst_key} .
```

同时暴露普通属性（供最终输出 bom_item_id / itemcode / itemname）：

```text
:material/{material_code} :bomItemId    {bom_item_id} .
:material/{material_code} :itemCode     {item_code} .
:material/{material_code} :itemName     {item_name} .
```

### 3.1 单表约束（生成手工版的关键）

- converter 在生成 `level_k` 时**直接引用边映射展开后的物理表名 + 列名**，不生成
  `edge` CTE / `(SELECT ... AS src ... AS dst)` 子查询；
- 只有 source 是单表时，ontop 才能拿到稳定的物理表名与列名；
- 如果 source 是 self-join / 聚合 / 复杂视图，则无法内联，退回"edge 子查询"形态，
  功能正确但每层会重复计算该视图，性能下降；
- 输出列（`bom_item_id` 等）不进入边映射，而是通过普通属性映射在**最终结果上 join 一次**
  得到（见 6.3）。

> 注意：如果边映射是 `bom_item_id -> bom_item_id` 的 self-join，功能仍可用，但每一层都会重复计算 self-join，性能会明显下降。

## 4. 架构设计

### 4.1 总体数据流

```text
SPARQL 字符串
  -> RDF4J QueryParserUtil.parseQuery
  -> ParsedQuery (TupleExpr)
  -> [新增] 识别 Join 中的 ArbitraryLengthPath + path: 扩展三元组
  -> [新增] 提取起点绑定子图 startTree
  -> IQ(PathNode(startTree, edge))
  -> QueryUnfolder (unfolding)
  -> IQ(PathNode(startTree', edge'))
  -> 优化器链（PathNode 作为二元节点透传）
  -> NativeQueryGenerator / SQLGenerator
  -> IQTree2SelectFromWhereConverter [识别 PathNode]
  -> SQL 代数 + CTE
  -> DefaultSelectFromWhereSerializer [输出 WITH level_0 ...]
```

### 4.2 新增 IQ 节点 `PathNode`

`PathNode` 是一个**二元非交换节点**（`extends BinaryNonCommutativeOperatorNode`），参考 `LeftJoinNodeImpl` 的骨架：

- left child = `startTree`：起点候选集，输出 `seedVariable`；
- right child = `edge`：边（`IntensionalDataNode`，unfolding 后为 `ExtensionalDataNode`），subject 为 `seedVariable`，object 为 `edgeObjectVariable`。

字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `seedVariable` | `Variable` | 起点变量，也是 edge 的 subject |
| `nodeVariable` | `Variable` | 路径输出节点，也是 edge 的 object（同一变量在不同层取不同值） |
| `depthVariable` | `Optional<Variable>` | 输出深度变量 |
| `predecessorVariable` | `Optional<Variable>` | 输出前驱变量 |
| `maxDepth` | `int` | 最大深度 |
| `minDepth` | `int` | 最小深度，默认取 `minLength`（`*`→0，`+`→1） |

关键方法：

```java
public interface PathNode extends BinaryNonCommutativeOperatorNode {
    Variable getSeedVariable();
    Variable getNodeVariable();
    Optional<Variable> getDepthVariable();
    Optional<Variable> getPredecessorVariable();
    int getMinDepth();
    int getMaxDepth();

    @Override
    default <T> T acceptVisitor(BinaryNonCommutativeIQTree tree, IQTreeVisitor<T> visitor,
                                IQTree startTree, IQTree edgeTree) {
        return visitor.transformPathNode(tree, this, startTree, edgeTree);
    }
}
```

`getVariables()` 返回 `{nodeVariable, depthVariable, predecessorVariable}`；`seedVariable` 是内部变量。

> **防剪枝**：`seedVariable` 必须出现在 `PathNode` 消费/依赖的变量集合里（例如在
> `getLocallyDefinedVariables` 之外额外声明"required variables"），否则优化器的变量剪枝
> （`NotRequiredVariableRemover` 等）可能把 `startTree` 中仅供 seed 使用的列剪掉，导致
> `level_0` 失去种子。

### 4.3 翻译层识别与 startTree 提取

位置：`core/kg-query/.../translation/impl/RDF4JTupleExprTranslator.java`

#### 4.3.1 收集路径元数据

在 `translateJoinLikeNode(BinaryTupleOperator)` 中：

1. 扫描 Join 直接子节点中的 `StatementPattern`，谓词 IRI 属于 `http://ontop.org/path#`；
2. 收集：
   - `maxDepth` / `minDepth`：主语（起点变量/常量）-> 元数据；
   - `depth` / `predecessor`：主语（终点变量）-> 元数据；
3. 这些 `StatementPattern` 翻译为 `TrueNode`，不生成数据节点。

#### 4.3.2 提取起点绑定子图 startTree

设 `ArbitraryLengthPath` 的 subject 变量为 `?start`（常量起点时无此步）。

从 Join 的其它子节点中提取"只与 `?start` 相连、且不经过路径终点 `?ancestor` / path 元数据变量"的三元组、Filter、BIND 子图，闭包后作为 `startTree`。

规则：

1. 初始集合 = 与 `?start` 直接共享变量的三元组 / Filter；
2. 递归加入与集合内变量相连的节点；
3. 遇到以下情况停止并报不支持：
   - 节点同时涉及路径终点变量 `?ancestor`；
   - 节点同时涉及其它路径算子的输出变量；
   - 起点模式跨越 Join 层 / 子查询边界。

提取出的 `startTree` 从原 Join 中移除；剩余子节点作为 PathNode 的外部 join（例如 `?ancestor :itemCode ?itemcode`）。

> 简化实现（MVP）：只处理"起点模式与路径三元组在同一 Join 层"的情况；不满足时报明确错误。后续可扩展变量连通分析。

#### 4.3.3 构造 PathNode

1. 翻译 `startTree` 得到 IQ 子树（输出 `?start`）；
2. 翻译 `getPathExpression()` 得到 edge 的 `IntensionalDataNode`，按实际 subject/object 角色
   确定 `seedVariable`（起点方向）与 `nodeVariable`（终点方向）：
   - 正向：subject = seed，object = node；
   - 反向 `^`：subject = node，object = seed；
3. 用 `getMinLength()` 确定默认 `minDepth`（`*`→0，`+`→1），`path:minDepth` 覆盖；
4. 构造 PathNode：

   ```java
   PathNode pathNode = iqFactory.createPathNode(
           seedVariable, nodeVariable, depthVar, predecessorVar, minDepth, maxDepth);
   IQTree pathTree = iqFactory.createBinaryNonCommutativeIQTree(
           pathNode, startTree, edgeTree);
   ```

5. `RDF4JQueryTranslatorImpl.translateQuery` 的投影变量已由 `tree.getVariables()` 决定。

### 4.4 unfolding 与优化透传

- `PathNode` 作为 `BinaryNonCommutativeOperatorNode`，`DefaultRecursiveIQTreeVisitingTransformer.transformBinaryNonCommutativeNode` 会自动递归两个 child。
- `BasicQueryUnfolder` / `AbstractQueryMergingTransformer` 递归进入 startTree 与 edge，把它们展开为 `ExtensionalDataNode`。
- 其他优化器默认透传 `PathNode`。
- `PathNode.normalizeForOptimization` 返回用规范化后的两个 child 重建的节点。

### 4.5 SQL 生成

#### 4.5.1 converter 识别

位置：`db/rdb/.../generation/algebra/impl/IQTree2SelectFromWhereConverterImpl.java`

在 `convert(...)` 的节点分派中增加 `PathNode` 分支：

```java
if (tree.getRootNode() instanceof PathNode) {
    return convertPathNode((BinaryNonCommutativeIQTree) tree,
            (PathNode) tree.getRootNode(), tree.getLeftChild(), tree.getRightChild());
}
```

`convertPathNode` 的职责：

1. startTree 必须是 `ExtensionalDataNode` / 可生成 SQL 的子树；
2. edge 必须是 `ExtensionalDataNode`；
3. 从 startTree 的 atom 读取 seed 列；从 edge 的 atom 读取 src/dst 列；
4. 构造 `PathDefinition implements RelationDefinition`：
   - 携带 startTree SQL 表达式、edge SQL 表达式、src/dst/seed 列、`maxDepth`、`minDepth`、输出列；
5. 返回 `SelectFromWhereWithModifiers`，FROM 是 `SQLTable(PathDefinition)`，投影是 PathNode 的输出变量。

#### 4.5.2 serializer 生成 CTE

位置：`db/rdb/.../generation/serializer/impl/DefaultSelectFromWhereSerializer.java`

在 `DefaultRelationVisitingSerializer.visit(SQLTable)` 中增加分支：

```java
if (relation instanceof PathDefinition) {
    return serializePathAsCTEs((PathDefinition) relation, sqlTable.getArgumentMap());
}
```

`serializePathAsCTEs` 生成（边映射**直接引用物理表**，不生成 edge 视图，与手工版一致）：

```sql
WITH
level_0 AS (
  SELECT e.material_code, e.parent_material_code,
         e.material_code AS predecessor, 0 AS depth
  FROM <edge 物理表> e
  JOIN (<startTree SQL 输出种子 material_code>) s
    ON s.material_code = e.material_code
),
level_1 AS (
  SELECT e.material_code, e.parent_material_code,
         l.material_code AS predecessor, 1 AS depth
  FROM <edge 物理表> e
  JOIN level_0 l ON l.parent_material_code = e.material_code
),
...
level_N AS (
  SELECT e.material_code, e.parent_material_code,
         l.material_code AS predecessor, N AS depth
  FROM <edge 物理表> e
  JOIN level_{N-1} l ON l.parent_material_code = e.material_code
)
SELECT material_code AS node, depth, predecessor FROM level_0
UNION ALL SELECT material_code AS node, depth, predecessor FROM level_1
...
UNION ALL SELECT material_code AS node, depth, predecessor FROM level_N
```

列角色（通用规则）：

- 正向：subject 列 = 起点侧（`material_code`），object 列 = 跳转侧（`parent_material_code`）；
- 反向 `^`：subject / object 列交换（RDF4J 已在 TupleExpr 层处理）；
- 每层 join 条件统一为 `level_{k-1}.<object列> = level_k.<subject列>`；
- `node` = 本层 `<subject列>`，`predecessor` = 上一层 `<subject列>`。

实现方式：

- 复用 `QuerySerializationImpl` 的 `cteMap`（`ImmutableMap<String,String>`，保序）；
- CTE 名带路径 id 前缀 `p{k}_level_{n}`，避免多路径冲突；
- **边映射直接引用物理表**（`FROM ods... e`），不生成 edge CTE / 子查询；
- `level_0` 从物理表取整行（含 subject/object 两列），用 `startTree` 的种子列 join 筛选；
  若 `startTree` 是单表简单条件，可进一步内联为 `level_0` 的 WHERE（见 6.3）。

#### 4.5.3 depth / predecessor 的表达

- `depth = k` 作为常量列：`k AS depth`；
- `node`：`level_k` 的本层 `<subject列>`（正向为 `material_code`）；
- `predecessor`：`level_0` 为本层 `<subject列>`（即 seed 自己）；`level_k` 为 `level_{k-1}` 的 `<subject列>`。

#### 4.5.4 minDepth 过滤

若 `minDepth > 0`，主查询直接跳过 `level_0..level_{minDepth-1}`，即 `UNION ALL` 从
`level_{minDepth}` 开始。这样既避免生成无用层，也保证结果 `depth >= minDepth`。

## 5. 代码结构

### 5.1 新增类

| 文件 | 作用 |
|---|---|
| `core/model/.../iq/node/PathNode.java` | 路径节点接口 |
| `core/model/.../iq/node/impl/PathNodeImpl.java` | 路径节点实现（参考 `LeftJoinNodeImpl`） |
| `core/kg-query/.../translation/impl/PathMetadata.java` | 路径扩展三元组的收集结果 |
| `core/kg-query/.../translation/impl/StartPatternExtractor.java` | 从 Join 中提取起点绑定子图 |
| `db/rdb/.../dbschema/impl/PathDefinition.java` | 携带路径参数的关系定义 |

### 5.2 修改文件

| 文件 | 改动 |
|---|---|
| `core/model/.../injection/IntermediateQueryFactory.java` | 新增 `createPathNode(...)` |
| `core/model/.../iq/visit/IQTreeVisitor.java` | 新增 `transformPathNode(BinaryNonCommutativeIQTree, PathNode, IQTree, IQTree)` |
| `core/model/.../iq/transform/impl/DefaultIQTreeVisitingTransformer.java` | 委托 `transformBinaryNonCommutativeNode` |
| `core/model/.../iq/visit/impl/AbstractIQTreeGenericVisitingTransformer.java` | 返回 `done()` |
| `core/kg-query/.../translation/impl/RDF4JTupleExprTranslator.java` | 识别路径元数据、提取 startTree、构造 PathNode |
| `db/rdb/.../generation/algebra/impl/IQTree2SelectFromWhereConverterImpl.java` | `PathNode` → `PathDefinition` |
| `db/rdb/.../generation/serializer/impl/DefaultSelectFromWhereSerializer.java` | `PathDefinition` → CTE |
| `db/rdb/.../injection/impl/OntopSQLCoreModule.java` | 如有新工厂则注册 |
| `db/rdb/src/main/resources/.../sql-default.properties` | 如有新实现类则配置 |

### 5.3 测试文件

| 文件 | 作用 |
|---|---|
| `core/optimization/.../PathNodeTransparencyTest.java` | PathNode 在优化器链中的透传 |
| `binding/rdf4j/.../RecursivePathTest.java` | 端到端 SPARQL → SQL（常量起点 + 变量起点） |
| `test/lightweight-tests/...` | 回归测试 |

## 6. 详细流程

### 6.1 变量起点示例

输入：

```sparql
?material a cls:bom_item ; prop:somevalue ?v .
FILTER(str(?v) = "valuexxx")

?material :parent_material* ?ancestor .
?material path:maxDepth 5 .
?ancestor path:depth ?depth .
?ancestor path:predecessor ?predecessor .
```

RDF4J 生成（简化）：

```text
Join(
  StatementPattern(?material, rdf:type, cls:bom_item),
  StatementPattern(?material, prop:somevalue, ?v),
  Filter(str(?v) = "valuexxx"),
  ArbitraryLengthPath(:parent_material, ?material, ?ancestor, minLength=0),
  StatementPattern(?material, path:maxDepth, 5),
  StatementPattern(?ancestor, path:depth, ?depth),
  StatementPattern(?ancestor, path:predecessor, ?predecessor)
)
```

翻译结果：

```text
PathNode(seed=?material, edgeObject=?next, node=?ancestor,
         depth=?depth, predecessor=?predecessor, maxDepth=5)
  left  = startTree(
            ?material a cls:bom_item .
            ?material prop:somevalue ?v .
            FILTER(str(?v) = "valuexxx"))
  right = edge(IntensionalDataNode(?material, :parent_material, ?next))
```

### 6.2 unfolding

```text
PathNode(...)
  left  = ExtensionalDataNode(<cls:bom_item + prop:somevalue + FILTER 展开结果>)
  right = ExtensionalDataNode(relation=material_edge, src=?, dst=?)
```

### 6.3 SQL 生成

边映射 source 为单表，起点模式（`a cls:bom_item` + FILTER）内联为 `level_0` 的 WHERE：

```sql
WITH
p0_level_0 AS (
  SELECT e.material_code, e.parent_material_code,
         e.material_code AS predecessor, 0 AS depth
  FROM ods.dp_mpo_mopp_bom_detial_info_dd e
  WHERE e.class = 'bom_item' AND e.somevalue = 'valuexxx'   -- 起点模式内联
),
p0_level_1 AS (
  SELECT e.material_code, e.parent_material_code,
         l.material_code AS predecessor, 1 AS depth
  FROM ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN p0_level_0 l ON l.parent_material_code = e.material_code
),
p0_level_2 AS (
  SELECT e.material_code, e.parent_material_code,
         l.material_code AS predecessor, 2 AS depth
  FROM ods.dp_mpo_mopp_bom_detial_info_dd e
  JOIN p0_level_1 l ON l.parent_material_code = e.material_code
),
...   -- p0_level_3 .. p0_level_5 同理
SELECT p.depth, p.material_code AS ancestor, p.predecessor,
       d.bom_item_id AS bomId, d.item_code AS itemcode, d.item_name AS itemname
FROM (
  SELECT * FROM p0_level_0
  UNION ALL SELECT * FROM p0_level_1
  UNION ALL SELECT * FROM p0_level_2
  ...   -- 依次 UNION ALL 到 p0_level_5
) p
JOIN ods.dp_mpo_mopp_bom_detial_info_dd d ON d.material_code = p.material_code;
```

这就是用户的手工版：每层 `FROM ods... e JOIN level_{k-1} l ON l.parent_material_code = e.material_code`，
每层保留 `material_code`（node）+ `parent_material_code`（下一跳），`bom_item_id`/`itemcode` 等
在最终结果上 join 一次得到。

## 7. 测试策略

### 7.1 单元测试

1. **PathNode 透传**：构造含 `PathNode` 的 IQ，跑 `GENERAL_STRUCTURAL_AND_SEMANTIC_IQ_OPTIMIZER`，断言 PathNode 仍在且两个 child 被正确处理。
2. **PathNode 变量**：断言 `getVariables()` 返回 `{node, depth, predecessor}`。
3. **startTree 提取**：给定 Join，断言起点模式被正确拆出，路径终点三元组留在外部。
4. **CTE 生成**：直接测试 serializer 对 `PathDefinition` 的输出包含 `WITH p0_level_0 AS`、`UNION ALL`。

### 7.2 集成测试

1. 用 H2 内存库建 BOM 表，插入 3 层父子数据；
2. **常量起点**：执行第 2.1 节第二种查询，断言 depth=0/1/2；
3. **变量起点**：执行第 2.1 节第一种查询（`a cls:bom_item` + FILTER），断言只从匹配的种子出发；
4. 断言生成 SQL 含 `WITH ... level_0 AS`。

### 7.3 回归测试

- 现有 `rdfs:subClassOf*` 测试必须保持通过；
- `test/sparql-compliance` 中 property-path 相关用例不受影响。

### 7.4 运行命令

```bash
mvn test -pl core/optimization -Dtest=PathNodeTransparencyTest
mvn test -pl core/kg-query
mvn test -pl db/rdb
mvn test -pl binding/rdf4j -Dtest=RecursivePathTest
```

## 8. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| PathNode 出现在优化器未被识别的节点导致异常 | 中 | 高 | 作为二元非交换节点实现，默认 `transformBinaryNonCommutativeNode` 透传；补充分支测试 |
| startTree 提取错误（漏拆或多拆） | 高 | 高 | MVP 只支持同 Join 层的起点模式；用变量连通闭包 + 明确报错；单测覆盖 |
| unfolding 不进入 PathNode 两个 child | 低 | 高 | 依赖 `DefaultRecursiveIQTreeVisitingTransformer` 递归；端到端测试覆盖 |
| SQL 代数/序列化层 CTE 冲突 | 中 | 中 | CTE 名带路径 id 前缀 `p{k}_level_{n}` |
| 超深展开导致 SQL 过大 | 中 | 中 | 限制 `maxDepth`（如 ≤ 20）；超限报 `OntopUnsupportedKGQueryException` |
| 数据库不支持 `WITH` | 低 | 中 | 主流数据库均支持；不支持时可回退到内联子查询（Phase 1 形态） |
| startTree 含聚合/ORDER BY/LIMIT 被破坏 | 中 | 中 | 作为子查询保留修饰；测试覆盖 |
| seedVariable 被优化器剪枝，level_0 失去种子 | 中 | 高 | PathNode 声明消费 seedVariable；单测验证剪枝后仍保留 |
| 方向 `^` 处理错误（subject/object 列接反） | 中 | 中 | 按 pathExpression 实际 subject/object 角色映射；反向用例测试 |
| 多路径去重语义偏差 | 中 | 中 | 明确 `UNION ALL` 保留所有路径；层内 `DISTINCT` 可选 |
| 边映射非单表，无法内联回物理表 | 高 | 中 | 退回 edge 子查询形态（功能正确，性能下降）；文档明确单表约束 |
| converter 拿不到物理表列名，内联失败 | 中 | 高 | 依赖单表 source 的 RA 解析结果；若无法内联则报错或退回子查询 |

## 9. 不纳入范围

- `WITH RECURSIVE`。
- 动态/运行时 `maxDepth`。
- 起点模式跨越子查询 / OPTIONAL / UNION 边界。
- 起点变量与路径终点变量出现在同一非路径三元组。
- 路径表达式组合（`|`、`/` 等多段组合；单个 `^` 反向支持）。
- 路径上的边属性输出。
- 命名图（quad）中的路径（MVP 仅支持 default graph）。

## 10. 分期实施

- **Phase 1**：`PathNode(startTree, edge)` + 翻译层（含 startTree 提取）+ unfolding + 普通 `UNION ALL`（不出 CTE）。目标是打通语法和语义，覆盖常量起点与同层变量起点。
- **Phase 2**：SQL 代数 `PathDefinition` + converter 特判 + serializer CTE，出 `WITH p{k}_level_0..level_N`；边映射 source 为单表时**直接内联物理表**（`FROM ods e`），生成手工版 SQL。

Phase 1 与 Phase 2 的结果集完全一致，仅 SQL 形态不同。

## 11. 参考文件

| 文件 | 用途 |
|---|---|
| `core/kg-query/.../translation/impl/RDF4JTupleExprTranslator.java:274` | 现有 `ArbitraryLengthPath` 处理 |
| `core/kg-query/.../translation/impl/RDF4JQueryTranslatorImpl.java:93` | 查询翻译入口 |
| `core/model/.../iq/node/LeftJoinNode.java` | 二元非交换节点参考 |
| `core/model/.../iq/node/impl/LeftJoinNodeImpl.java` | 二元非交换节点实现参考 |
| `core/model/.../iq/visit/IQTreeVisitor.java:16` | visitor 接口 |
| `core/model/.../iq/transform/impl/DefaultRecursiveIQTreeVisitingTransformer.java:68` | 二元节点递归透传 |
| `core/kg-query/.../unfolding/impl/BasicQueryUnfolder.java:64` | unfolding 入口 |
| `db/rdb/.../generation/algebra/impl/IQTree2SelectFromWhereConverterImpl.java:42` | IQ → SQL 代数 |
| `db/rdb/.../generation/serializer/impl/DefaultSelectFromWhereSerializer.java:77` | SQL 序列化 / CTE |
| `core/model/.../injection/IntermediateQueryFactory.java:30` | 节点工厂 |
| `org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath`（RDF4J 5.1.4） | 仅 `minLength`，无 `maxLength`；`{m,n}` 解析期被拒 |

## 12. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-05 | 初版：方案 C（链式 CTE），PathNode 架构 |
| v1.1 | 2026-11-05 | 引入 startTree，支持变量/图模式起点（MVP） |
| v1.2 | 2026-11-05 | 审查修正：`*`/`+` 语义、删 edgeObjectVariable、防剪枝、方向映射、edge CTE 复用 |
| v1.3 | 2026-11-05 | 去掉 edge CTE，边映射直接内联物理表生成手工版 SQL；明确单表约束 |
| v1.4 | 2026-11-05 | 状态调整：标为 Deferred（深度方案）；新增第 0 节记录零代码方案与源码级能力调研结论 |
| v1.5 | 2026-11-11 | 补录 RDF4J 5.1.4 对 `{m,n}` 的实测结论：解析期拒绝、`ArbitraryLengthPath` 无 `maxLength`；新增 2.2.1 说明 `path:maxDepth` 的由来；补充 `?`/`ZeroLengthPath` 为 MVP 非目标 |
