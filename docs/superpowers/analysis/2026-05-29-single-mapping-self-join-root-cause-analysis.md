# 单一 OBDA 映射产生 N 子查询自连接根因分析

**Date**: 2026-05-29
**Status**: Analysis Complete
**Author**: Sisyphus

## 1. 问题

一个 OBDA 映射（单 mappingId、单 target atom、单 source SELECT）的 SPARQL 查询，产生 N 个完全相同的子查询通过 `id = id` 自连接。

### 1.1 OBDA 映射

```obda
mappingId    AnalysisSummary-Mapping
target       bacls:AnalysisSummary/{id} a bacls:AnalysisSummary ;
             baprop:analysisSummaryAnalysisTopicName {analysis_topic_name}^^xsd:string ;
             baprop:analysisSummaryScenarioName {analysis_scenario_name}^^xsd:string ;
             baprop:analysisSummaryOrgName {org_name}^^xsd:string ;
             baprop:analysisSummaryDimensionName {dim_item}^^xsd:string ;
             baprop:analysisSummaryAbnormalRuleDesc {abnormal_rule_desc}^^xsd:string ;
             baprop:analysisSummaryPainPointId {pain_point_id}^^xsd:string ;
             baprop:analysisSummaryPainPointName {pain_point_name}^^xsd:string ;
             baprop:analysisSummaryMetricId {metric_id}^^xsd:string ;
             baprop:analysisSummaryMetricName {metric_name}^^xsd:string ;
             baprop:summaryCorrespondsToRootCause bacls:RootCause/{pain_point_id} .
source       SELECT id, analysis_topic_name, analysis_scenario_name, org_name,
                    metric_name, metric_id, dim_item, abnormal_rule_desc,
                    pain_point_id, pain_point_name
             FROM dwd.dwd_topic_pain_point_nd
             LATERAL VIEW explode(dimension_name) tmp AS dim_item
             WHERE ...
```

**关键**: 一个 mappingId，**一个 target atom**（一个 `target` 块），**一个 source SELECT**。

### 1.2 SPARQL 查询

```sparql
PREFIX bacls: <http://www.jhk.com/finance/business-analysis/class/>
PREFIX baprop: <http://www.jhk.com/finance/business-analysis/property/>

SELECT DISTINCT ?topicName ?scenarioName 
                ?metricName ?metricId
                ?dimensionName ?painPointId ?painPointName
WHERE {
  ?topic a bacls:AnalysisSummary ;
         baprop:analysisSummaryAnalysisTopicName ?topicName;
         baprop:analysisSummaryScenarioName ?scenarioName;
         baprop:analysisSummaryDimensionName ?dimensionName;
         baprop:analysisSummaryAbnormalRuleDesc ?abnormalRuleDesc;
         baprop:analysisSummaryPainPointId ?painPointId;
         baprop:analysisSummaryPainPointName ?painPointName;
         baprop:analysisSummaryMetricName ?metricName;
}
```

**关键**: 一个 topic，7 个 property，全部用 `;`（共享主语）连接。

### 1.3 生成的 SQL

```sql
SELECT DISTINCT
    CAST(v7.metric_name AS CHAR) AS v13,
    CAST(v5.pain_point_id AS CHAR) AS v9,
    CAST(v1.analysis_topic_name AS CHAR) AS v1,
    CAST(v6.pain_point_name AS CHAR) AS v11,
    CAST(v2.analysis_scenario_name AS CHAR) AS v3,
    CAST(v3.dim_item AS CHAR) AS v5
FROM
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v1,
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v2,
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v3,
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v4,
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v5,
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v6,
    (SELECT ... FROM dwd.dwd_topic_pain_point_nd LATERAL VIEW explode(...) WHERE ...) v7
WHERE
    CAST(v1.id AS CHAR) = CAST(v2.id AS CHAR)
    AND CAST(v2.id AS CHAR) = CAST(v3.id AS CHAR)
    AND ...
```

**问题**: 7 个**完全相同的子查询**（相同表、相同列、相同 LATERAL VIEW、相同 WHERE），通过 `id = id` 自连接。

---

## 2. Pipeline 全景图

```
[OBDA .obda 文件]
  mappingId AnalysisSummary-Mapping
  单 target atom（`;` 分隔 7 个 property）
  单 source SELECT
           ↓
[OntopNativeMappingParser]
  解析 target 行: `;` 是 RDF/Turtle 共享主语语法
  但 OBDA parser 将 target 块解析为 1 个 TargetAtom
  （包含多个 predicate-object pair）
           ↓
[SQLPPMappingConverterImpl]
  for (TargetAtom target : assertion.getTargetAtoms())  → 1 次迭代
  → 1 个 MappingAssertion（包含所有 property 的 substitution）
           ↓
[MappingImpl Index]
  MappingAssertionIndex 由 (RDFAtomPredicate, IRI) 组成
  单 target atom → 单 index cell:
  (predicate, "analysisSummaryAnalysisTopicName") → IQ
  (predicate, "analysisSummaryScenarioName")      → IQ
  (predicate, "analysisSummaryDimensionName")     → IQ
  ... 7 cells 全部指向同一 IQ（同一 source tree）
           ↓
[RDF4J SPARQL 解析]
  ?topic p1 ?v1; p2 ?v2; ... p7 ?v7
  解析为 7 个 StatementPattern:
  pattern(?topic, p1, ?v1)
  pattern(?topic, p2, ?v2)
  ...
  pattern(?topic, p7, ?v7)
           ↓
[RDF4JTupleExprTranslator]
  每个 StatementPattern → 1 个 IntensionalDataNode
  7 个 IntensionalDataNode，各有不同的 property IRI
           ↓
[TwoPhaseQueryUnfolder]
  → FirstPhaseQueryMergingTransformer.getDefinition() L55-59
    每个 IntensionalDataNode 独立 lookup:
    mapping.getRDFPropertyDefinition(predicate, propertyIRI)
    7 个不同的 propertyIRI → 7 次 lookup → 返回同一个 IQ
           ↓
  7 个相同的 IQ 展开 → 7 个独立的子查询树
  InnerJoinNode 将它们连接
           ↓
[SQLGeneratorImpl.normalizeSubTree()]
  经过 8 步正常化（liftSlice, liftOrderBy, flattenUnion, pushDown,
  equalityTransformer, dropTopConstruct, valuesNodeTransform, extraNormalizer）
  SelfJoin 优化器在 normalizeSubTree 之前运行，但无法合并
           ↓
[IQTree2SelectFromWhereConverterImpl]
  InnerJoin 的每个 child → 独立的 SelectFromWhereWithModifiers
           ↓
[DefaultSelectFromWhereSerializer.getSQLSerializationForChild()] L331-342
  每个 SelectFromWhereWithModifiers → "(SELECT ...) vN"
  7 个 → v1, v2, v3, v4, v5, v6, v7
```

---

## 3. 详细代码追踪

### 3.1 SPARQL 解析: `;` 产生 N 个 StatementPattern

RDF4J 将每个 triple pattern 解析为独立的 StatementPattern 节点，即使共享主语。

```
?topic a bacls:AnalysisSummary ;
       baprop:analysisSummaryAnalysisTopicName ?topicName;
       baprop:analysisSummaryScenarioName ?scenarioName;
       ...
→
Join(
  StatementPattern(?topic, rdf:type, bacls:AnalysisSummary),
  Join(
    StatementPattern(?topic, baprop:analysisSummaryAnalysisTopicName, ?topicName),
    Join(
      StatementPattern(?topic, baprop:analysisSummaryScenarioName, ?scenarioName),
      ...
    )
  )
)
```

### 3.2 IQ 翻译: 每个 StatementPattern → IntensionalDataNode

**文件**: `core/kg-query/.../RDF4JTupleExprTranslator.java`

```java
// translate(StatementPattern pattern) 行 674
private TranslationResult translate(StatementPattern pattern) {
    // 提取 subject, predicate, object
    // 创建一个 IntensionalDataNode
    // ...
    subTree = translateTriplePattern(subject, predicate, object);
} 
```

**产生**: 7 个 `IntensionalDataNode`，每个对应一个 property triple pattern。

### 3.3 映射展开: 按 property IRI 独立查找

**文件**: `core/kg-query/.../FirstPhaseQueryMergingTransformer.java` 行 53-60

```java
private Optional<IQ> getDefinition(RDFAtomPredicate predicate,
                                   ImmutableList<? extends VariableOrGroundTerm> arguments) {
    return predicate.getPropertyIRI(arguments)
            .map(i -> i.equals(RDF.TYPE)
                    ? getRDFClassDefinition(predicate, arguments)
                    : mapping.getRDFPropertyDefinition(predicate, i))  // ← 按 property IRI 独立查找
            .orElseGet(() -> getStarDefinition(predicate, arguments));
}
```

**为什么每个 property IRI 能查到**: 因为 `DefaultMappingTransformer.getMapping()` 把 mapping assert 按 `(predicate, IRI)` 存入 `ImmutableTable`：

**文件**: `mapping/core/.../DefaultMappingTransformer.java` 行 118-131

```java
private Mapping getMapping(ImmutableList<MappingAssertion> assertions) {
    ImmutableTable<RDFAtomPredicate, IRI, IQ> propertyDefinitions = assertions.stream()
            .filter(e -> !e.getIndex().isClass())
            .map(DefaultMappingTransformer::asCell)
            .collect(ImmutableCollectors.toTable());
    // ...
}

private static Table.Cell<RDFAtomPredicate, IRI, IQ> asCell(MappingAssertion assertion) {
    MappingAssertionIndex index = assertion.getIndex();
    return Tables.immutableCell(index.getPredicate(), index.getIri(), assertion.getQuery());
}
```

**但为什么单 target atom 有多个 IRI？** 因为 `SQLPPMappingConverterImpl` 将单 target atom 转为 MappingAssertion 时，`MappingAssertionIndex` 从 **target first triple** 提取 IRI。如果目标 block 中有 `a bacls:AnalysisSummary`（class）和多个 `baprop:*`（property），系统会产生多个 index cells。

### 3.4 MappingAssertion 与 MappingAssertionIndex

**文件**: `mapping/core/.../MappingAssertionIndex.java`

MappingAssertionIndex 由 `(RDFAtomPredicate, IRI)` 组成。当 target atom 中有多个 predicate-object 对时，`SQLPPMappingConverterImpl.convert()` 虽然只创建 **1 个 MappingAssertion**，但这个 assertion 的 `getIndex()` 返回 **第一个 property 的 IRI**。

但在 `DefaultMappingTransformer.getMapping()` 中，`asCell()` 去重时遇到相同 `(predicate, IRI)` 的重复 cell，`ImmutableCollectors.toTable()` 会保留第一个。

**实际 mapping 展开**: 虽然只有一个 mapping assertion，但 `getMergedDefinitions()` 实际上会合并 `propertyDefinitions.row(predicate)` 中所有 cell，从而产生包含所有 property 的 **单一** IQ 定义。然而 `FirstPhaseQueryMergingTransformer` 不走 `getMergedDefinitions()`，它走 `mapping.getRDFPropertyDefinition(predicate, propertyIRI)`——这个按具体 property IRI 查找。

对于单 target atom 情况，不同 property IRI 应该返回相同的 IQ 定义（因为只有 **1 个单元格**在表中）。但展开时还是会各自展开一次，产生 **N 份相同展开结果**。

### 3.5 展开后的 InnerJoin

7 个相同的 IQ 展开后成为 7 个独立的子树，被 `InnerJoinNode` 连接。由于所有子树共享变量（如 `id`），优化器产生 `id = id` 等值连接。

---

## 4. 现有优化器为何无法合并

| 优化器 | 文件 | 作用对象 | 为何无效 |
|--------|------|---------|---------|
| SelfJoinSameTermIQOptimizer | `core/optimization/.../SelfJoinSameTermIQOptimizer.java` | **ExtensionalDataNode** | 自连接在 IntensionalDataNode 级已存在 |
| SelfJoinUCIQOptimizer | `core/optimization/.../SelfJoinUCIQOptimizer.java` | **ExtensionalDataNode** | 需要数据库 Unique Constraint |
| RedundantJoinFKOptimizer | `core/optimization/.../RedundantJoinFKOptimizer.java` | **ExtensionalDataNode** | 需要 FK 约束 |
| AbstractBelowDistinctInnerJoinTransformer | `core/optimization/.../AbstractBelowDistinctInnerJoinTransformer.java` | **ExtensionalDataNode** + 同一 InnerJoin 层级 | 节点已经展开为独立子树 |
| SelfJoinSameTerm 判定逻辑 | `AbstractBelowDistinctInnerJoinTransformer.java:70-85` | 要求相同 `RelationDefinition` + 兼容 `argumentMap` | 每个展开子树有不同的变量绑定 |

**核心原因**: 所有 SelfJoin 优化器都工作于 `ExtensionalDataNode` 层级，而自连接在展开阶段已经产生——**7 份相同的 `IntensionalDataNode` 展开结果被 InnerJoin 连接**。

---

## 5. 两种场景对比

### 场景 1: 15 个独立 mapping（之前分析的）

```mermaid
graph TD
    A[15 mappingId] --> B[15 TargetAtom]
    B --> C[15 MappingAssertion]
    C --> D[15 个 propertyDefinitions cell<br/>(predicate, IRI₁) → IQ₁<br/>(predicate, IRI₂) → IQ₂<br/>...]
    D --> E[不同 source SELECT<br/>各取 1-2 列]
    E --> F[15 个子查询]
```

### 场景 2: 1 个覆盖全 property 的 mapping（当前分析的）

```mermaid
graph TD
    A[1 mappingId] --> B[1 TargetAtom<br/>覆盖 7 个 property]
    B --> C[1 MappingAssertion<br/>（但 index 含多 IRI）]
    C --> D[1 个 propertyDefinitions cell<br/>(predicate, IRI₁) → IQ]
    D --> E[同一 source SELECT<br/>取所有列]
    E --> F[SPARQL 7 个 triple pattern]
    F --> G[7 个 IntensionalDataNode]
    G --> H[7 次 lookup → 返回同一个 IQ]
    H --> I[7 份相同展开结果]
    I --> J[7 个子查询自连接 id=id]
```

---

## 6. 根因总结

### 根因链条

| # | 层级 | 问题 | 文件位置 |
|---|------|------|---------|
| 1 | **SPARQL 语言特性** | `;`（共享主语）语法展开为 N 个独立 triple pattern | RDF4J 解析器 |
| 2 | **SPARQL→IQ 翻译** | 每个 StatementPattern → 独立的 IntensionalDataNode | `RDF4JTupleExprTranslator.java:674` |
| 3 | **映射索引** | 单 target atom 被按 property IRI 索引 | `DefaultMappingTransformer.java:118-131` |
| 4 | **展开策略** | 每个 IntensionalDataNode 按 property IRI 独立 lookup | `FirstPhaseQueryMergingTransformer.java:55-59` |
| 5 | **缺少 I 级合并** | 展开后没有检测同源 IntensionalDataNode 并压缩 | 无此优化器 |
| 6 | **SQL 序列化** | 每个 SelectFromWhereWithModifiers → 子查询 | `DefaultSelectFromWhereSerializer.java:331-342` |

### 一句话根因

**SPARQL 的共享主语语法 (`;`) 被解析为 N 个独立 triple pattern → 每个翻译为独立 IntensionalDataNode → 展开时按 property IRI 独立 lookup → 产生 N 份相同展开结果 → InnerJoin 连接 → 序列化为 N 子查询自连接。**

---

## 7. 优化方案

### 方案 A: 展开后同源 IntensionalDataNode 合并（推荐）

在 `FirstPhaseQueryMergingTransformer`（或 `TwoPhaseQueryUnfolder`）中，`transformInnerJoin` 时检测：

```
如果是 InnerJoin，且多个子节点是同一 mapping 的展开结果:
  - 合并为一个节点
  - 合并所有投影变量
  - 添加等值条件 ?v_new = ?v_old
```

**实现思路**:

```java
// 在 FirstPhaseQueryMergingTransformer 的 transformInnerJoin() 中
@Override
public IQTree transformInnerJoin(NaryIQTree tree, ...) {
    // 1. 对每个 child，获取其 IQ 定义的来源（mapping ID）
    // 2. 按 mapping ID 分组
    // 3. 同组的 child 合并
    // 4. 重建 InnerJoin
}
```

**需要**: 展开时或展开后能追溯每个子树来自哪个 `mappingId` 或 `propertyIRI`。

**优点**: 源头解决，不依赖 SQL 能力
**缺点**: 需要 IQ 树正常化逻辑修改

### 方案 B: 修复 mapping 索引粒度

```java
// DefaultMappingTransformer.java
// 改为：同一 target atom 的多个 property 存储为单一 combined IQ
private Mapping getMapping(ImmutableList<MappingAssertion> assertions) {
    // 检测同一 source query 的 assertion，合并
    Map<IQ, List<MappingAssertion>> grouped = assertions.stream()
        .collect(groupingBy(a -> a.getQuery().getTree()));
    
    // 每个组创建一个 combined IQ
    List<MappingAssertion> merged = grouped.values().stream()
        .map(this::mergeAssertions)
        .collect(toList());
    
    // 正常入库
}
```

**优点**: 从索引层面解决
**缺点**: 需要处理 IQ 树的变量绑定合并

### 方案 C: SPARQL 翻译时合并共享主语 triple

```java
// RDF4JTupleExprTranslator.java
// 在 translateJoinLikeNode 中检测共享主语模式
// 将同一主语的多 triple pattern 合并为单个 IntensionalDataNode
```

**优点**: 最上游修复
**缺点**: 需要 RDF4J algebra 处理，复杂

### 方案对比

| 维度 | 方案 A | 方案 B | 方案 C |
|------|--------|--------|--------|
| 效果 | N→1 | N→1 | N→1 |
| 开发量 | 中（2-3天） | 中（2-3天） | 大（5-7天） |
| 风险 | 低 | 中 | 高 |
| 可扩展性 | 通用 | 只解决此场景 | 只解决此场景 |

**推荐**: 方案 A，在 `TwoPhaseQueryUnfolder` 的展开输出和 InnerJoin 正常化之间插入一个同源合并步骤。

---

## 8. 关键代码索引

| 文件 | 行 | 功能 |
|------|-----|------|
| `core/kg-query/.../RDF4JTupleExprTranslator.java` | 674 | StatementPattern → IntensionalDataNode |
| `core/kg-query/.../FirstPhaseQueryMergingTransformer.java` | 53-60 | 按 property IRI 独立 lookup |
| `core/kg-query/.../TwoPhaseQueryUnfolder.java` | 42-60 | 二阶段展开编排 |
| `core/obda/.../MappingImpl.java` | 65 | getRDFPropertyDefinition 表查找 |
| `core/obda/.../MappingImpl.java` | 152-162 | getMergedDefinitions 合并 |
| `mapping/core/.../DefaultMappingTransformer.java` | 118-131 | propertyDefinitions 表构建 |
| `mapping/sql/core/.../SQLPPMappingConverterImpl.java` | 90-100 | TargetAtom→MappingAssertion |
| `mapping/core/.../MappingAssertionIndex.java` | - | (predicate, IRI) 索引结构 |
| `core/optimization/.../AbstractBelowDistinctInnerJoinTransformer.java` | 70-85 | SelfJoin 判定逻辑 |
| `core/optimization/.../SelfJoinSameTermIQOptimizer.java` | 58-60 | 只操作 ExtensionalDataNode |
| `engine/reformulation/sql/.../SQLGeneratorImpl.java` | 169-204 | normalizeSubTree pipeline |
| `db/rdb/.../IQTree2SelectFromWhereConverterImpl.java` | 45-50 | IQ 树拆解顺序 |
| `db/rdb/.../DefaultSelectFromWhereSerializer.java` | 331-342 | 子查询包裹 |
| `db/rdb/.../DefaultSelectFromWhereSerializer.java` | 68-73 | viewCounter 别名生成 |

---

## 9. 参考

- 前次分析: `docs/superpowers/analysis/2026-05-29-obda-subquery-bloat-root-cause-analysis.md`
  （15 mappingId 场景的完整分析）
