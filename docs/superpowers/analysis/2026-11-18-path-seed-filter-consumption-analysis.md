# 路径 seed FILTER 消耗：生产 reformulation 崩溃的根因与修复

日期：2026-11-18
状态：已修复并通过全路径套件回归（随修复一并提交）
相关文档：`2026-11-16-path-attribute-sinking-datatype-analysis.md`（同族问题的前一阶段分析）

## 1. 现象

生产环境（Doris + `ontop.sameSourceMerge.enabled=true`）执行层次路径查询
（变量起点 + seed FILTER + 起点节点带一串 OPTIONAL 属性）时报错：

```
SQLSerializationException: The variable bom_item_id1m26 does not appear in the columnIDs
```

本地用 `HierarchicalPathWideQueryTest`（资源 `parent-bom-item-wide/`，全部属性 OPTIONAL +
material join，复刻生产查询形态）复现，报错发生在更早的优化阶段：

```
InvalidIntermediateQueryException: This child ... does not project all the variables
required by the CONSTRUCTION node
```

两者是同一缺陷家族：本地 IQ 树校验（`ConstructionNodeImpl.validateNode`）在
`bindingLiftOptimizer` 阶段就拦住；生产开了同源合并，树能走到 SQL 序列化器才炸。

复现查询形态（生产形状）：

```sparql
SELECT ?start ?node ?depth ?attr1 ... WHERE {
  { SELECT DISTINCT ?start ?node ?depth WHERE {
      ?start a cls:bom_item ;
              prop:parent_bom_item+ ?node ;
              prop:bom_item_id ?v0 ;
              path:maxDepth 2 .
      FILTER(?v0 = "1")            # seed FILTER：固定路径起点
    } LIMIT 200 }
  OPTIONAL { ?start prop:material_code ?mc . }      # 起点属性（REQUIRED / OPTIONAL 均可触发）
  OPTIONAL { ?node  prop:bom_version  ?bv . }        # 节点属性
  ...
}
```

## 2. 根因

问题链条（三个环节缺一不可）：

1. **seed FILTER 只被「借用」而没被「消耗」**。
   `RDF4JTupleExprTranslator.processSeedCondition` 从 `FILTER(?v0 = "1")` 提取常量，
   烤进递归 CTE 的 `level_0 ... WHERE bom_item_id = '1'`，但 FILTER 节点本身仍留在查询树中。

2. **FILTER 常量被主流程优化器下推进 LEFT JOIN 右侧**。
   IQ 归一化时 `FilterNormalizerImpl.simplifyAndPropagateDownConstraint` 把该常量作为
   DownPropagation 向下传播；到 `LeftJoinNodeImpl.applyDescendingSubstitution` 时
   `propagateWithRestrictedScope(rightChild)` 把常量直接内联进 LEFT JOIN **右侧**的
   属性数据节点（键列参数变成 `0:"1"^^CHARACTER VARYING` 常量）。

3. **右侧键变量被消灭，但上层 CONSTRUCT 仍按旧变量集校验**。
   `FilterNormalizer` 抬升 CONSTRUCTION 时用的是**下推前**的 `child::getVariables`
   ——即它仍要求那些已被常量替换掉的键变量（`bom_item_id1m5/1m7/...1m17` 等）。
   子树已不再投影这些变量：
   - 本地：`ConstructionNodeImpl.validateNode` 抛 `InvalidIntermediateQueryException`；
   - 生产（同源合并后）：不合法的变量引用传到 SQL 序列化器
     → `bom_item_id1m26 does not appear in the columnIDs`。

一句话：**一个本该被路径翻译消耗掉的冗余 FILTER 留在了树里，主流程优化器把它下推进
LEFT JOIN 右侧、消灭了 join-back 键变量，而上层 CONSTRUCT 仍按旧变量集要求它们，树因此不合法。**

附带说明：变量名里的 `m` 后缀（`bom_item_id1m26`）来自
`MappingVariableNameNormalizerImpl`（对 mapping 变量追加 `"m" + assertionIndex`），
与 sameSourceMerge 无关，只是让日志变量名更醒目。

## 3. 修复方案（严格限定在路径翻译流程内）

约束：**不触碰主流程代码**（`FilterNormalizerImpl`、`LeftJoinNodeImpl`、
`ConstructionNodeImpl` 等）。全部改动集中在
`core/kg-query/.../RDF4JTupleExprTranslator.java`。

### 3.1 消耗 seed FILTER（`processSeedCondition`）

常量已烤进 CTE seed 后，FILTER 从语义上是冗余的——起点已通过 `path_start_raw`
（CTE 的 `start_iri` 列）与起点 IRI 模板的合一被钉住。因此把该 FILTER 条件加入
`consumedTraversalConditions`（身份集合），翻译阶段直接丢弃，主流程优化器再也
看不到这个常量，从源头断掉下推链条。

安全护栏：

- 只对**注册过的变量路径起点**（新字段 `pathStartNames`，在 `collectPathTerminals`
  中登记）生效；非路径变量的 FILTER 保持原行为；
- 出现**相互矛盾的 seed 常量**时不消耗、保留 FILTER 在树里，由 FILTER 自身保证语义。

### 3.2 `translatePath` 自行绑定 start 常量（地面化起点）

FILTER 被消耗后，起点必须由路径翻译自己钉住：把 CTE 参数表第 3 列（`start_iri`）
的 `path_start_raw` 变量替换为地面常量：

```java
VariableOrGroundTerm startArgument = rawStart;
if (seedKey != null)
    startArgument = termFactory.getDBConstant(seedKey, startKeyType(nodeKeyType));
// argumentMap.put(3, startArgument)，随后 start IRI 模板用同一 startArgument
```

类型由新增的 `startKeyType` 辅助方法取得，与 `PathQuerySupportImpl.toRelation`
中 `start_iri` 列的类型逻辑一致（节点键类型 `nodeKeyType`，回退 DB string）。

**为什么必须有这一步**：只消耗 FILTER 会让起点属性 join-back 退化成
变量-变量连接（`V7.START_IRI = CAST(V4."BOM_ITEM_ID" ...)`），破坏 sameSourceMerge
的可合并性，导致 `HierarchicalPathStringTypeTest.requiredAttributesAreMergedBySameSourceMerge`
回归（PARENT_BOM 扫描次数超过断言上限）。地面化后起点成为 ground IRI，复现
「常量主语查询」形态，join-back 恢复为常量键（`ON 2 = V7."BOM_ITEM_ID"`），可合并。
（常量主语形态此前已被 `fullQueryWithConstantStartOptionals` 用例验证为正确形态。）

## 4. 验证

- `HierarchicalPathWideQueryTest` 两个生产形态复现用例全部通过；修复前无法生成合法 IQ 树，
  修复后 9 个起点 join-back 合并为
  `LEFT OUTER JOIN "PARENT_BOM" V7 ON V6.START_IRI = V7."BOM_ITEM_ID"`（可 sargable、无 CAST）；
- CTE seed 完好：`level_0 ... WHERE "BOM_ITEM_ID" = 2`；
- 全路径套件回归（`HierarchicalPathTest` / `HierarchicalPathStringTypeTest` /
  `HierarchicalPathMixedDatatypeTest` / `HierarchicalPathLangLiteralTest` /
  `HierarchicalPathWideQueryTest` / `ParentBomItemMappingTest`）：
  **30 tests, 0 failures, 0 errors, 3 skipped**（3 个跳过均为既有 `@Ignore` 用例），
  BUILD SUCCESS；
- 关键回归项 `requiredAttributesAreMergedBySameSourceMerge` 通过，SQL 恢复为
  `ON 2 = V7."BOM_ITEM_ID"` 常量键合并形态。

## 5. 不变式与后续

### 不变式（路径翻译流程内）

**凡是已烤进 CTE SQL 的常量/条件（seed 常量、traversal 条件），必须在翻译阶段消耗掉，
不能留在 RDF4J 查询模型里**——否则主流程优化器（FilterNormalizer 下推、LJ 右侧受限传播）
会对它们做路径流程未预期的改写。此前 traversal 条件已按此处理（`processTraversalCondition`），
本次把 seed FILTER 纳入同一不变式。

### 遗留（未触碰，按约定）

主流程的脆弱性本身仍在：FILTER 常量下推消灭 LEFT JOIN 右侧键变量、而 lifted
CONSTRUCTION 仍按下推前变量集校验——这一形状在**非路径查询**上理论上仍可触发。
若以后想根治，候选方向：

1. `FilterNormalizer` 抬升 CONSTRUCTION 时改用下推后的变量集；
2. 或 `LeftJoinNodeImpl` 下推时保留被替换键变量的投影。

均属主流程改动，需另行评估。

### 排障备忘

- IDE（ECJ）编译出的坏类会内嵌 `Unresolved compilation problem` 字节串，Maven 显示
  "Nothing to compile" 但测试全挂；需按字节扫描并清理模块 `target/classes`：
  `[System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($f)).Contains('Unresolved compilation problem')`。
- Maven 与 IDE 同时写同一个 `target/classes` 会互踩：`testCompile` 报一堆
  `NoSuchFileException`（"无法访问/错误的类文件"、包临时不存在），但重跑即恢复。
  遇到这类报错先直接重跑，仍失败再定向删除该模块的 `target` 目录重建；
  平时避免在 IDE rebuild 未结束时启动 `mvn` 命令。
- 本地复现生产问题时，属性 literal 类型必须与 OBDA 声明一致（全 string 资源 vs
  混合类型资源），否则类型不匹配会掩盖真实缺陷（见 2026-11-16 分析文档阶段二）。
