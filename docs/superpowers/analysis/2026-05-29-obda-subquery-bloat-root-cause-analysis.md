# OBDA SPARQL → SQL 子查询膨胀根因分析

**Date**: 2026-05-29
**Status**: Analysis Complete
**Author**: Sisyphus

## 1. 问题

SPARQL 查询经过 Ontop OBDA-to-SQL 转换后，生成的 SQL 包含大量冗余子查询：

**原始 SPARQL（15 triple patterns）**
```sparql
SELECT DISTINCT ?scenarioName ?metricName ?dimensionName ...
WHERE {
  ?topic a bacls:AnalysisTopic ; baprop:topicAnalysisTopicName "收入主题" .
  ?topic baprop:includesScenario ?scenario .
  ?scenario a bacls:AnalysisScenario ; baprop:scenarioScenarioName ?scenarioName .
  FILTER(?scenarioName IN ("营业收入", "销量"))
  ?org a bacls:Organization ; baprop:orgOrgName "冰冷事业部" ; baprop:involvesScenario ?scenario .
  ...
}
```

**生成的 SQL（16 个子查询）**
```sql
SELECT DISTINCT ...
FROM
    (SELECT 1 AS uselessVariable FROM dwd.dwd_topic_pain_point_nd ...) v3,
    (SELECT DISTINCT analysis_topic_name, analysis_scenario_name FROM ...) v4,
    (SELECT DISTINCT analysis_scenario_name FROM ...) v5,
    (SELECT DISTINCT org_name FROM ...) v6,
    (SELECT DISTINCT org_name, analysis_scenario_name FROM ...) v7,
    dwd.dwd_topic_pain_point_nd v8,
    (SELECT id, dim_item FROM ... LATERAL VIEW explode(dimension_name) ...) v9,
    (SELECT DISTINCT dim_item FROM ... LATERAL VIEW explode(dimension_name) ...) v10,
    dwd.dwd_topic_pain_point_nd v11,
    (SELECT DISTINCT metric_id, metric_name FROM ...) v12,
    (SELECT DISTINCT metric_id, metric_name FROM ...) v13,  -- 与 v12 完全重复
    dwd.dwd_topic_pain_point_nd v14,
    (SELECT DISTINCT abnormal_rule_desc FROM ...) v15,
    dwd.dwd_topic_pain_point_nd v16,
    dwd.dwd_pain_point_nd v17
WHERE ...  50+ 行复杂连接条件
```

**问题**: 15 个 triple pattern 产生 16 个子查询，其中 v12/v13 完全重复，多个子查询来自同一物理表 `dwd_topic_pain_point_nd`。

---

## 2. Pipeline 全景图

```
OBDA .obda 文件 ───→ SQLPPMappingConverterImpl ──→ 15+ MappingAssertions
                          ↓
                   MappingImpl.getMergedDefinitions()
                   (相同 predicate+IRI → UnionNode)
                          ↓
                   BasicQueryUnfolder 展开
                   (IntensionalDataNode → ExtensionalDataNode)
                          ↓
                   QuestQueryProcessor
                   (rewrite → unfold → optimize → plan)
                          ↓
                   SQLGeneratorImpl.normalizeSubTree()
                   (8 个规范化步骤 + DialectExtraNormalizer)
                          ↓
                   IQTree2SelectFromWhereConverterImpl
                   (IQ tree → SQL algebra SelectFromWhereWithModifiers)
                          ↓
                   DefaultSelectFromWhereSerializer
                   (每个 SelectFromWhereWithModifiers → subquery alias)
                          ↓
                   ┌──────────────────────────────────────┐
                   │  最终 SQL: 16 个子查询的 FROM 子句    │
                   └──────────────────────────────────────┘
```

### 2.1 关键代码文件

| 步骤 | 文件路径 |
|------|---------|
| 映射解析 | `mapping/sql/native/.../OntopNativeMappingParser.java` |
| TargetAtom→MappingAssertion | `mapping/sql/core/.../SQLPPMappingConverterImpl.java` |
| 映射索引 | `core/obda/.../MappingImpl.java` |
| Union合并 | `core/model/.../UnionBasedQueryMergerImpl.java` |
| 查询展开 | `core/kg-query/.../BasicQueryUnfolder.java` |
| SQL生成编排 | `engine/reformulation/sql/.../SQLGeneratorImpl.java` |
| IQ→SQL代数 | `db/rdb/.../IQTree2SelectFromWhereConverterImpl.java` |
| SQL序列化 | `db/rdb/.../DefaultSelectFromWhereSerializer.java` |
| DISTINCT隐式 | `core/model/.../IntensionalDataNodeImpl.java` |
| SelfJoin优化 | `core/optimization/.../SelfJoinSameTermIQOptimizer.java` |
| Spark序列化 | `db/rdb/.../SparkSQLSelectFromWhereSerializer.java` |
| 映射断言合并 | `mapping/core/.../MappingAssertionUnion.java` |

---

## 3. 子查询来源追溯

### 3.1 每一层的展开

每个 `mappingId` 在 OBDA 文件中定义了一个独立的数据源：

```obda
mappingId   AnalysisTopic-Mapping
source      SELECT analysis_topic_name FROM dwd.dwd_topic_pain_point_nd ...

mappingId   AnalysisScenario-Mapping
source      SELECT analysis_scenario_name FROM dwd.dwd_topic_pain_point_nd ...

mappingId   BusinessMetric-Mapping
source      SELECT metric_id, metric_name FROM dwd.dwd_topic_pain_point_nd ...
target      bacls:BusinessMetric/{metric_id} a bacls:BusinessMetric ;
            baprop:businessMetricMetricId {metric_id}^^xsd:string ;
            baprop:businessMetricMetricName {metric_name}^^xsd:string .
```

`SQLPPMappingConverterImpl.java:90-100` 将**每个 targetAtom 转为独立的 MappingAssertion**，而非合并为一条：

```java
for (TargetAtom target : assertion.getTargetAtoms()) {
    builder.add(convert(target, lookup, provenance, tree));
}
```

### 3.2 每个子查询的 OBDA 映射来源

| SQL别名 | 来源映射ID | MAPPING TYPE | 为何独立 |
|---------|-----------|-------------|---------|
| v3 | 空投影 | 存在性检查 | SELECT 1 AS uselessVariable |
| v4 | IncludesScenario-Mapping | 属性断言 | source: `SELECT DISTINCT analysis_topic_name, analysis_scenario_name` |
| v5 | AnalysisScenario-Mapping | RDF type | RDF class assertion → DISTINCT |
| v6 | Organization-Mapping | RDF type | RDF class assertion → DISTINCT |
| v7 | InvolvesScenario-Mapping | 属性断言 | source: `SELECT DISTINCT org_name, analysis_scenario_name` |
| v8 | MetricDimensionUnit-Mapping | 驱动表 | SELECT id — 主数据源 |
| v9 | Dimension-Mapping (explode) | LATERAL VIEW | `SELECT id, dim_item LATERAL VIEW explode(dimension_name)` |
| v10 | Dimension-Mapping (DISTINCT) | RDF type | `SELECT DISTINCT dim_item LATERAL VIEW explode(dimension_name)` — 重复explode |
| v11 | ContainsBusinessMetric-Mapping | 属性断言 | SELECT id, metric_id |
| v12 | BusinessMetric-Mapping targetAtom[0] | RDF type | `a bacls:BusinessMetric` — DISTINCT |
| **v13** | **BusinessMetric-Mapping targetAtom[1]** | **属性断言** | **`baprop:businessMetricMetricName` — 与 v12 完全重复** |
| v14 | HasAbnormalRule-Mapping | 属性断言 | SELECT id, abnormal_rule_desc |
| v15 | AbnormalRule-Mapping | RDF type | DISTINCT abnormal_rule_desc |
| v16 | CorrespondsToPainPoint-Mapping | 属性断言 | SELECT abnormal_rule_desc, pain_point_id |
| v17 | PainPoint-Mapping(另一表) | 另一个表 | `dwd.dwd_pain_point_nd` |

### 3.3 v12=v13 重复的根因分析

`BusinessMetric-Mapping` 的 target 包含两个 targetAtom：

```obda
target  bacls:BusinessMetric/{metric_id} a bacls:BusinessMetric ;
        baprop:businessMetricMetricId {metric_id}^^xsd:string ;
        baprop:businessMetricMetricName {metric_name}^^xsd:string .
```

在 SPARQL 查询中被展开为两个独立的 IntensionalDataNode：

```
?metric a bacls:BusinessMetric .                              → v12
?metric baprop:businessMetricMetricName ?metricName .         → v13
```

这两个展开结果在 IQ 树位于不同分支。虽然它们的 `RelationDefinition` 和 `ArgumentMap` 完全相同，但 `SelfJoinSameTermIQOptimizer` 只能合并同一 InnerJoin 层级下的直系子节点（`AbstractBelowDistinctInnerJoinTransformer.java:38-49`）。

---

## 4. 五层根因详细分析

### 第 1 层: OBDA 映射设计模式

**问题**: 每个 RDF class/property 独立一个 mapping，每个 mapping 有独立 `SELECT DISTINCT`。

**证据**: `SQLPPMappingConverterImpl.java` line 90 — targetAtom 被分割为独立 MappingAssertion。

**为什么这样设计**: OBDA 标准遵循"一个映射断言对应一个 RDF triple pattern"的范式。

### 第 2 层: MappingAssertion 索引合并有限

**问题**: `MappingImpl.getMergedDefinitions()` 只合并相同 (predicate, IRI) 的断言。

**证据**: `MappingImpl.java` lines 152-162 — 合并策略保守。

**关键代码**:
```java
// MappingImpl.java
return queryMerger.mergeDefinitions(definitions);  // 只为相同 predicate 创建 Union
```

### 第 3 层: IntensionalDataNode.isDistinct() == TRUE

**问题**: 每个 triple pattern 都隐式带 DISTINCT。

**证据**: `IntensionalDataNodeImpl.java` lines 51-56:
```java
/**
 * Intensional data nodes are assumed to correspond to triple/quad patterns,
 * which are distinct by definition
 */
@Override
public boolean isDistinct() { return true; }
```

**影响**: 展开为 ExtensionalDataNode 后 DISTINCT 属性保留，产生 `SELECT DISTINCT` 子查询。

### 第 4 层: IQ 树 → SQL 代数的子查询边界

**问题**: 每个 `ConstructionNode` + `DistinctNode` 组合 → 独立的 `SelectFromWhereWithModifiers`。

**证据**: `IQTree2SelectFromWhereConverterImpl.java` lines 45-50、159-161:
```java
// 顶层解构: Slice → Distinct → Construction → OrderBy → Aggregation → Filter
var slice = UnaryIQTreeDecomposition.of(tree, SliceNode.class);
var distinct = UnaryIQTreeDecomposition.of(slice, DistinctNode.class);
// ...
// ConstructionNode → 递归 convert → 新 SelectFromWhereWithModifiers
public SQLExpression transformConstruction(UnaryIQTree tree, ...) {
    return convert(tree, getSignature(tree));
}
```

### 第 5 层: 序列化时的子查询包裹

**问题**: 所有 FROM 中的 `SelectFromWhereWithModifiers` 都被包裹为 `(SELECT ...) vN`。

**证据**: `DefaultSelectFromWhereSerializer.java` lines 331-342:
```java
protected QuerySerialization getSQLSerializationForChild(SQLExpression expression) {
    if (expression instanceof SelectFromWhereWithModifiers) {
        QuerySerialization serialization = expression.acceptVisitor(this);
        RelationID alias = generateFreshViewAlias();  // → v1, v2, v3, ...
        String sql = String.format("(%s) %s", serialization.getString(), alias.getSQLRendering());
        // ...
    }
}
```

`generateFreshViewAlias()` 依赖 `AtomicInteger viewCounter`（line 68-73），每个查询生成一个全局自增的 `v{N}` 别名。

---

## 5. 现有优化器失效原因

| 优化器 | 文件 | 原理 | 为何对当前场景无效 |
|--------|------|------|------------------|
| SelfJoinSameTermIQOptimizer | `core/optimization/.../SelfJoinSameTermIQOptimizer.java` | 检测同 Relation 同 Argument 的冗余 ExtDataNode | **只在同一 InnerJoin 层级**；v12/v13 在不同 IQ 树分支 |
| SelfJoinUCIQOptimizer | `core/optimization/.../SelfJoinUCIQOptimizer.java` | 基于 UC 合并多节点 | 需要表定义 Unique Constraint，且仅在 InnerJoin 同级 |
| RedundantJoinFKOptimizer | `core/optimization/.../RedundantJoinFKOptimizer.java` | 基于 FK 消除冗余 Join | Join 条件为等值连接非 FK-based |
| SubQueryFromComplexJoinExtraNormalizer | `db/rdb/.../SubQueryFromComplexJoinExtraNormalizer.java` | 嵌套 Join 强制子查询 | **仅 Dremio 使用**；Spark 用 AlwaysProjectOrderByTerms |
| AlwaysProjectOrderByTermsNormalizer | `db/rdb/.../AlwaysProjectOrderByTermsNormalizer.java` | ORDER BY 项投影 | 不处理子查询，仅处理排序 |

### SelfJoin 判定逻辑

`AbstractBelowDistinctInnerJoinTransformer.isDetectedAsRedundant()` lines 70-85:

```java
protected boolean isDetectedAsRedundant(ExtensionalDataNode dataNode, 
                                         ExtensionalDataNode otherDataNode) {
    // 条件1: 必须同一 RelationDefinition
    if (!dataNode.getRelationDefinition().equals(otherDataNode.getRelationDefinition()))
        return false;
    // 条件2: 必须共享参数索引映射
    ImmutableSet<Integer> commonIndexes = Sets.intersection(firstIndexes, otherIndexes);
    return Sets.union(firstIndexes, otherIndexes).stream()
            .filter(i -> !(commonIndexes.contains(i) && argumentMap.get(i).equals(otherArgumentMap.get(i))))
            .noneMatch(argumentMap::containsKey);
}
```

此方法仅在 `transformInnerJoin()`（line 38-49）中被调用，只能处理同一 InnerJoin 的直系子节点。

---

## 6. LATERAL VIEW explode 重复分析

**问题**: v9 和 v10 都包含 `LATERAL VIEW explode(dimension_name)`。

**映射**: Dimension-Mapping:
```obda
source  SELECT dim_item
        FROM dwd.dwd_topic_pain_point_nd
        LATERAL VIEW explode(dimension_name) tmp AS dim_item
```

v9 来自 `baprop:containsDimension ?dimension`（属性断言，需要 id+dim_item）。
v10 来自 `?dimension a bacls:Dimension`（class 断言，只需要 DISTINCT dim_item）。

Spark 序列化路径: `SparkSQLSelectFromWhereSerializer.serializeFlatten()` lines 142-163:
```java
return serializeFlattenAsSubQuery(flattenedVar, allColumnIDs, subQuerySerialization, Stream.of(flattenFunctionCallWithAlias));
```

该方法始终将 flatten 表达为子查询（`serializeFlattenAsSubQuery` line 491-515），即使子查询内容简单（在一个简单 FROM 之上加 LATERAL VIEW）。

---

## 7. 优化方案

### 方案 A: IQ Tree 级优化器（推荐，根治 80%+）

**原理**: 在 `DialectExtraNormalizer` 阶段新增 SparkSQL/Hive 专用的 normalizer，合并同一 InnerJoin 下同物理表的 ExtensionalDataNode。

**新增文件**: `db/rdb/src/main/java/.../normalization/impl/SparkSQLExtraNormalizer.java`

**策略**:
```
遍历 InnerJoin 的所有子节点:
  - 按 RelationDefinition 分组
  - 对同一物理表的节点:
    1. 合并所有投影列 (UNION argumentMap)
    2. 合并所有过滤条件 (AND)
    3. 生成单一 ExtensionalDataNode
    4. 添加变量重绑定表达式
```

**配置**: 在 `sql-default.properties` 中注册：
```properties
org.apache.hive.jdbc.HiveDriver-normalizer = it.unibz.inf.ontop.generation.normalization.impl.SparkSQLExtraNormalizer
```

**预期效果**: 16 个子查询 → 2-3 个（1 个 `dwd_topic_pain_point_nd` + 1 个 `dwd_pain_point_nd` + 可能的 LATERAL VIEW）

**依赖注入**: 参考 `SubQueryFromComplexJoinExtraNormalizer` 的 Guice Singleton 模式（line 27-32）。

### 方案 B: SQL 后处理优化（快速，50% 效果）

**原理**: 在 `normalizeSubTree()` 最后一步或 Serializer 中，检测 FROM 子句中同表子查询，折叠为 CTE 或合并为单一 Join。

**实例**:
```sql
-- 优化前
FROM (SELECT DISTINCT metric_id, metric_name FROM t) v12,
     (SELECT DISTINCT metric_id, metric_name FROM t) v13,   -- 重复

-- 优化后  
FROM (SELECT DISTINCT metric_id, metric_name FROM t) v12
-- 移除 v13，将 v13 的引用替换为 v12
```

### 方案 C: 映射设计优化（人工，立即见效）

**原理**: 将同一个物理表的多个映射合并为更少的宽映射：

```obda
-- 合并前: 10+ 个独立映射
mappingId AnalysisTopic-Mapping
source SELECT analysis_topic_name FROM dwd.dwd_topic_pain_point_nd ...

mappingId BusinessMetric-Mapping
source SELECT metric_id, metric_name FROM dwd.dwd_topic_pain_point_nd ...

-- 合并后: 2 个宽映射
mappingId WideTopicMapping
source SELECT id, analysis_topic_name, analysis_scenario_name, org_name,
              metric_id, metric_name, dimension_name, abnormal_rule_desc
       FROM dwd.dwd_topic_pain_point_nd

mappingId PainPointMapping
source SELECT pain_point_id, pain_point_name FROM dwd.dwd_pain_point_nd ...
```

**单 target 覆盖所有 triple**:
```obda
target  bacls:Wide/{id} a bacls:Wide ;
        baprop:topicAnalysisTopicName {analysis_topic_name}^^xsd:string ;
        baprop:scenarioScenarioName {analysis_scenario_name}^^xsd:string ;
        baprop:orgOrgName {org_name}^^xsd:string ;
        baprop:businessMetricMetricId {metric_id}^^xsd:string ;
        baprop:businessMetricMetricName {metric_name}^^xsd:string ;
        baprop:abnormalRuleDesc {abnormal_rule_desc}^^xsd:string .
```

---

## 8. 方案对比

| 维度 | 方案 A (IQ 树优化) | 方案 B (SQL 后处理) | 方案 C (映射重构) |
|------|-------------------|-------------------|-----------------|
| **效果** | ～16子查询→2-3个 | ～16子查询→8-10个 | ～16子查询→2-4个 |
| **开发量** | 3-5天 | 1-2天 | 0.5天（人工修改） |
| **代码修改** | 新增 1 个文件 + 修改配置 | 修改 Serializer | 修改 .obda 映射文件 |
| **风险** | 中等（新 normalizer 逻辑） | 低（SQL 文本层操作） | 低（纯映射调整） |
| **维护成本** | 低 | 低（将来需要持续优化） | 高（业务变更时映射需同步） |
| **可扩展性** | 适用于所有同表场景 | 只解决已知模式 | 只解决当前映射 |
| **结合Ontop优化器** | 深度融合 | 独立 | 绕过优化器 |

---

## 9. 方案 A 实现指引

### 9.1 核心逻辑

```java
@Singleton
public class SparkSQLExtraNormalizer extends DefaultDelegatingIQTreeVariableGeneratorTransformer 
        implements DialectExtraNormalizer {

    @Inject
    protected SparkSQLExtraNormalizer(CoreSingletons coreSingletons) {
        super(new Transformer(coreSingletons.getIQFactory())::transform);
    }
    
    private static class Transformer extends DefaultRecursiveIQTreeVisitingTransformer {
        @Override
        public IQTree transformInnerJoin(NaryIQTree tree, InnerJoinNode rootNode,
                                          ImmutableList<IQTree> children) {
            // 1. 按 RelationDefinition 分组
            Map<RelationDefinition, List<IQTree>> grouped = groupByRelation(children);
            
            // 2. 对每组 >1 的节点执行合并
            List<IQTree> merged = new ArrayList<>();
            for (var entry : grouped.entrySet()) {
                if (entry.getValue().size() > 1 && isSameTable(entry.getValue())) {
                    merged.add(mergeExtensionalDataNodes(entry.getValue()));
                } else {
                    merged.addAll(entry.getValue());
                }
            }
            
            // 3. 重建 InnerJoin
            return iqTreeTools.createInnerJoinTree(
                rootNode.getOptionalFilterCondition(), merged);
        }
    }
}
```

### 9.2 需要处理的问题

1. **变量冲突**: 合并后原有变量名可能冲突，需要重命名
2. **类型兼容**: 同列在不同子查询中可能类型不一致
3. **NULL 语义**: 合并后需要注意 NULL 传播
4. **DISTINCT 语义**: 合并后是否需要 DISTINCT 的判定

### 9.3 参考实现

- `SubQueryFromComplexJoinExtraNormalizer.java` — 模板方法模式
- `SelfJoinUCIQOptimizer.java` — 同表节点合并逻辑
- `AbstractSelfJoinSimplifier.java` — 节点合并与变量重绑定

---

## 10. 关键代码索引

| 文件 | 关键行 | 功能 |
|------|-------|------|
| `DefaultSelectFromWhereSerializer.java` | 68-73 | viewCounter 子查询别名生成 |
| `DefaultSelectFromWhereSerializer.java` | 83 | DISTINCT 字符串插入 |
| `DefaultSelectFromWhereSerializer.java` | 142-158 | 空投影 → "1 AS uselessVariable" |
| `DefaultSelectFromWhereSerializer.java` | 331-342 | SelectFromWhereWithModifiers → 子查询包裹 |
| `DefaultSelectFromWhereSerializer.java` | 432-444 | FlattenExpression → explode 序列化入口 |
| `DefaultSelectFromWhereSerializer.java` | 491-515 | serializeFlattenAsSubQuery 子查询模式 |
| `SparkSQLSelectFromWhereSerializer.java` | 142-163 | LATERAL VIEW EXPLODE_OUTER 实现 |
| `IQTree2SelectFromWhereConverterImpl.java` | 45-50 | IQ 树顶层解构顺序 |
| `IQTree2SelectFromWhereConverterImpl.java` | 130-234 | 各节点类型 → SQL 代数转换 |
| `IQTree2SelectFromWhereConverterImpl.java` | 174-181 | FlattenNode → SQLFlattenExpression |
| `IntensionalDataNodeImpl.java` | 51-56 | isDistinct() = true 硬编码 |
| `SQLPPMappingConverterImpl.java` | 90-100 | TargetAtom → 独立 MappingAssertion |
| `MappingImpl.java` | 152-162 | getMergedDefinitions 保守合并 |
| `SubQueryFromComplexJoinExtraNormalizer.java` | 39-59 | Join 嵌套 → 子查询强制 |
| `AbstractBelowDistinctInnerJoinTransformer.java` | 70-85 | SelfJoin 冗余判定逻辑 |
| `AbstractSelfJoinSimplifier.java` | 52-130 | UC-based 节点合并 |
| `MappingAssertionUnion.java` | 367-462 | CQC 映射断言合并 |
| `sql-default.properties` | 117-133 | Spark/Hive 的 normalizer/serializer 配置 |
| `SQLGeneratorImpl.java` | 169-204 | normalizeSubTree 完整 pipeline |
