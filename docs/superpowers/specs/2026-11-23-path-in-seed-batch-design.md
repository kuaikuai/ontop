# path 起点种子支持 IN 批量输入 — 设计方案

**Date**: 2026-11-23
**Status**: Implemented（已提交 version5 `503c5125be`，测试全绿；未推送）
**Author**: Qoder (AI-assisted)
**Related**:
- `2026-11-11-hierarchical-path-static-design.md`（静态化路径主设计，SeedSource 层次）
- `2026-11-12-traversal-filter-pushdown-design.md`（TraversalFilter 每层剪枝，消费 invariant 同源）
- `2026-11-19-doris-left-join-merge-root-cause-and-fix.md`（start pin 常量键合并的来历）
- `analysis/doris-ab/in-seed-verify*.jsh/.log`（本设计 7 节实证的本地产物，含凭据不入库）

## 1. 目标

起点条件从"单常量"扩展为"一批 ID"：一次查询输入多个起点，每个起点各自展开路径，输出全部路径行的并集。目标形态（生产查询的直接扩展）：

```sparql
?n0 a cls:bom_item .
?n1 a cls:bom_item .
?n0 prop:parent_bom_item+ ?n1 .
?n0 path:maxDepth 2 .
?n0 prop:bom_item_id ?v0 .
FILTER(?v0 IN ("3517912884", "3517912885", ...))    -- 一批 ID
```

期望 SQL：`level_0` 的 `WHERE bom_item_id = 3517912884` 变为 `WHERE bom_item_id IN (...)`，其余 CTE 结构零改动。

## 2. 现状差距

| # | 环节 | 现状 | 差距 |
|---|---|---|---|
| 1 | `RDF4JTupleExprTranslator.constantFor` | 只识别 `Compare(EQ)` / `SameTerm` 单常量 | 识别 `In` / `Or(EQ,...)` 提取常量列表 |
| 2 | `pathVariableSeed` | `Map<String,String>` 单值 | `Map<String,List<String>>`（或 SeedSource 直接存列表） |
| 3 | `SeedSource` | `Constant` 生效；`Values` / `TempTable` 只是占位壳 | 启用 `Values` |
| 4 | `HierarchySqlBuilder.nodeKeyLiteral` | 渲染 `= <literal>`，遇非 Constant 抛异常 | 渲染 `IN (<literal>, ...)`，列表级 numeric/转义处理 |
| 5 | `translatePath` start pin | `seedKey != null` 时把 `path_start_raw` pin 成 ground DBConstant | 单值保留 pin；多值回退 `rawStart` 变量绑定 |

## 3. 语义模型（已实证成立，无需设计）

`start_iri` 本来就是 **per-row 起点键**（`level_0: start_iri = nodeKeyColumn`，逐层透传 `l.start_iri`），单起点只是其退化情形。多起点下：

- `depth` / `predecessor` / `traversalFilter` 逐层剪枝全部 per-row，零改动；
- `?n0` 与 CTE 的绑定退化为 `start_iri` 变量-变量 join，每行自动匹配自己的起点；
- 终点属性下沉（`path_col_*`）不受影响。

## 4. 设计

### 4.1 FILTER 识别（翻译层）

`constantFor` 扩展两条分支，归一化为"起点 id 变量 → 常量集合"：

1. **`In` 节点**：RDF4J 5.1.4 存在专用代数节点 `org.eclipse.rdf4j.query.algebra.In extends CompareSubQueryValueOperator`（`In(ValueExpr, TupleExpr)`，已 javap 实证）。**实施第 0 步**必须先打印真实查询的代数树确认常量列表在 `In` 里的具体表示（`TupleExpr` 子查询形态），再写提取代码；
2. **`Or` 展开链**：`FILTER(?v = "a" || ?v = "b")`（`In` 被解析器脱糖或用户手写的等价式），递归收集每臂的 EQ 常量。

规则（与既有保守策略一致）：

- 提取出的集合**全部可烘焙**（每个元素都是 `ValueConstant`）才消费 FILTER，且**整组**进 `consumedTraversalConditions`（identity-based，缺一角即触发 FilterNormalizer 下传播崩溃族——见 traversalFilter 设计的同类 invariant）；
- 任一元素不可烘焙（表达式/子查询）→ 整组留树，回退旧路径（语义由 FILTER 自身保证，性能退化为逐属性 LEFT JOIN）；
- 与现有"矛盾种子"逻辑合流：`EQ` 与 `IN` 同现或多个 `IN` 同现时做**常量集合求交**；交集为空 → 留树（查询语义为空集，由 FILTER 保证）；
- 列表去重后为空 / 环境异常 → 维持现状行为（`translatePath` 返回 null，走不支持分支）。

### 4.2 SQL 渲染（CTE 层）

`HierarchySqlBuilder`：`nodeKeyLiteral(SeedSource)` → `nodeKeyPredicate(SeedSource)`：

```sql
-- 现状
WHERE `bom_item_id` = 3517912884
-- 多起点
WHERE `bom_item_id` IN (3517912884, 3517912885, ...)
```

- 每个 IRI 沿用 `substring(lastIndexOf('/') + 1)` 提键；
- 列表**整体**按 `nodeKeyType` 决定 numeric/字符串（复用 `isNumericType`；任一元素无法按型渲染即 fail-fast）；
- 转义沿用 `''` 规则；
- **列表长度上限 1000**（防巨型 SQL / Doris 解析开销），超限抛带提示的异常（建议分批）。`SeedSource.TempTable` 明确不做（违背 path flow 无状态/只读原则）。

### 4.3 start pin 分派（翻译层）

```java
VariableOrGroundTerm startArgument = rawStart;
if (seedKeys != null && seedKeys.size() == 1)
    startArgument = termFactory.getDBConstant(seedKeys.get(0), startKeyType(nodeKeyType));
```

- **单值**：保留 11-19 修复的常量 pin（起点属性回表常量键 join，same-source merge 形态 `ON 1=1`）；
- **多值**：`path_start_raw` 回退变量绑定（即 11-19 修复注释所述 "variable-variable at LJ level" 形态）。起点属性回表合并**依赖 UNI 键约束开关**（`ontop.mysql.doris.uniqueModelKeyConstraints=true`）而非常量——**部署前提：多起点批量查询需开关 on**，否则宽查询回落 31-LJ 形态。实施时须做开关 on/off × 单/多起点 2×2 的 SQL/行数 A/B。

### 4.4 数据流

```text
SPARQL: FILTER(?v0 IN ("a","b"))
  → collectTraversalConditions / processSeedCondition
      识别 In / Or(EQ,...) → 常量集合（求交/去重）
      整组进 consumedTraversalConditions
  → translatePath: pathVariableSeeds → SeedSource.Values
      size==1 → pin DBConstant；size>1 → rawStart 变量
  → HierarchySqlBuilder: level_0 WHERE key IN (...)
  → IQ: start_iri 变量-变量 join（多值）或常量键 join（单值）
```

## 5. 语义决策（已定，写入用户文档即可）

| 决策点 | 结论 |
|---|---|
| `perLevelLimit` 多起点语义 | **全局每层截断**（一个 `level_k` 装所有起点的第 k 层后 LIMIT）。不做 per-start 窗口函数分区（一期不引入） |
| 结果基数 | 每起点行数线性叠加，容易触顶 `LIMIT 2000`；文档提示消费方按需调大 |
| 多个 seed FILTER 通道 | 任一起点 id 属性（bom_item_id / parent_material_code 等）上的 IN 均可作种子集合，跨通道求交 |
| 语法范围 | 一期只支持 FILTER 内 `IN` 与 `Or` 等价式；`VALUES` 子句（BindingSetAssignment，收集点不同）二期 |

## 6. 改动面（收敛在 path flow 内）

| 文件 | 改动 |
|---|---|
| `core/kg-query/.../impl/RDF4JTupleExprTranslator.java` | `constantFor` 加 In/Or 分支；`pathVariableSeed` 值类型改列表；`translatePath` 分派 `SeedSource.Values` 与 pin 分派 |
| `core/kg-query/.../path/HierarchySqlBuilder.java` | `nodeKeyLiteral` → `nodeKeyPredicate`（IN 渲染 + 上限校验） |
| `core/kg-query/.../path/SeedSource.java` | 启用既有 `Values` 壳 |

不触碰：主 IQ 归一化/优化链路、`PathSpecDeriver`、映射/元数据侧。遵守 path flow 无跨 `translate()` 调用状态约束（translator 无新增成员按查询隔离）。

## 7. 生产 Doris 实证（2026-11-23，只读直连）

方法：同一 CTE（子→父方向、minDepth=1/maxDepth=2、生产投影形态），`level_0` 分别用 `= s`（单种子基线）与 `IN (...)`（多种子），比对总行数、逐 seed multiset（`depth, node_iri, predecessor × count`）、byDepth 分解与 `start_iri` 归属。产物 `analysis/doris-ab/in-seed-verify*.jsh/.log`（本地，含凭据不入库）。

| 检查项 | 结果 |
|---|---|
| 总行数可加性（两轮）| 26=0+1+6... 第二轮 822 = 411 + 411 **PASS** |
| level_2 数据路径 | 实际压测：2 级链种子每种子 depth1=71 / depth2=340 行 |
| 逐 seed multiset / byDepth | 全部 **PASS**（逐字节一致的集合与计数） |
| `start_iri` 归属 | depth1/depth2 样本行全部正确 |
| 性能 | 3 种子单查 50~76ms/次；1 条 IN 查询 109~116ms——**IN 成本 ≈ 单种子查询，非 N 倍** |

结论：**`=` → `IN` 对 CTE 层就是全部改动，语义与性能均成立**。

附带发现（方向相关，须在 obda 方向恢复决策中一并确认）：已知生产种子 `3517912884` 在子→父方向下向上展开为 **0 行**（其 `parent_material_code='VB0300Z0516JBASCP'` 不匹配任何行 material_code），只在父→子方向有 38 行展开。

## 8. 测试计划

- `HierarchySqlBuilderTest`：IN 渲染（numeric / string / 引号转义）、空列表与超限（>1000）抛异常、混型 fail-fast；
- `HierarchicalPathTest`（H2 数据加第二条 2 级链）：
  - 多起点各自得到正确的 `depth`/`predecessor`/`start_iri`；
  - `FILTER IN` 与手写 `Or` 等价式结果一致；
  - EQ + IN 同现求交；交集为空 → 留树不消费（无 InvalidIntermediateQueryException）；
  - 不可烘焙 IN（子查询元素）→ 回退不崩；
  - 单种子 IN(1 个值) 仍生成常量 pin（向后兼容）；
- 生产形态回归：多起点 + 31 OPTIONAL + UNI 开关 on/off 的 SQL 形态/行数 A/B。

## 9. 工作量

约 **1~1.5 天**：代数形态实测（第 0 步）0.5d、seed 识别与结构扩展 0.5d、SqlBuilder IN 渲染 0.5d、pin 分派与 A/B 实测 0.5d、测试补齐 0.5d（部分并行）。

## 10. 风险

| 风险 | 缓解 |
|---|---|
| RDF4J `In` 常量列表代数形态未实测 | 第 0 步先打印代数树；`Or` 分支兜底两种形态 |
| 多值回变量后起点属性 join 合并退化 | UNI 键开关作为部署前提 + 2×2 A/B 实测 |
| perLevelLimit 语义静默变化 | 用户文档明示"全局每层截断" |
| 巨型 SQL（千级 ID） | 上限 1000 + 异常提示分批；TempTable 明确不做 |
| 基数膨胀触顶 LIMIT | 文档提示；不做隐式改写 |

## 实现更新（2026-11-23）

### 第 0 步实测：代数形态钉死

用 `analysis/doris-ab/in-algebra-probe.jsh`（SPARQLParser 直解 + 代数树打印）实测 RDF4J 5.1.4：

| FILTER 写法 | 代数节点 |
|---|---|
| `?v IN ("2","3","5")` | **`ListMemberOperator`**（`NAryValueOperator`，`getArguments()` = [Var, ValueConstant...]）——不是 `In`，也不是 Or 脱糖 |
| `?v = "2" \|\| ?v = "3"` | `Or(Compare(EQ)...)` |
| `IN(...) && !=` | `And(ListMemberOperator, Compare(NE))` |

注：HEAD 已有的 11-20 提交（`RDF4JValueExprTranslator` 把 IN 折叠成平衡 Or）只作用于**留在树里**的 FILTER 的值表达式翻译；seed 消费发生在 `getTree()` 起始的原始 TupleExpr 上，看到的仍是 `ListMemberOperator`。两条形态均已支持。

### 落地改动（3 个主文件 + 2 个测试）

| 文件 | 改动 |
|---|---|
| `RDF4JTupleExprTranslator.java` | `pathVariableSeed` 值类型 `String` → `List<String>`；新增 `pathVariableSeedId`（记录种子来源 id 变量）；`constantFor`/`constantForEquality` 重写为 `seedValuesFor`/`collectSeedValues`/`collectSeedValuesFromEquality`（识别 EQ/SameTerm、ListMemberOperator、Or=并集、And=交集，任一部分不可烘焙则整体返回 null 留树）；`processSeedCondition` 多次贡献求交、空交集/异通道留树（保留旧语义）；`translatePath` 单值 → `SeedSource.Constant`（保留 pin），多值 → `SeedSource.Values`（start 保持 `rawStart` 变量，由 `start_iri` 列携带 per-row 起点身份） |
| `HierarchySqlBuilder.java` | `nodeKeyLiteral(seed)` 拆为 `nodeKeyPredicate(seed)`（`= literal` / `IN (l1, ..., ln)`，逐元素 numeric 判定与引号转义）+ `nodeKeyLiteral(iri)`；上限 `MAX_SEED_VALUES = 1000`（超限/空列表抛 `IllegalArgumentException`，提示分批） |
| `SeedSource.java` | 零改动（`Values` 壳直接启用） |
| `HierarchySqlBuilderTest.java` | 新增 4 用例：IN 渲染、字符串引号转义、>1000 拒绝、空列表拒绝；顺手修复预存失败断言（投影列早已含 `start_iri`） |
| `HierarchicalPathTest.java` | 新增 7 用例：多起点 IN（行集=逐起点祖先）、Or 等价、单值 IN 保留 pin（`= 2` 且无 `IN (`）、And 求交 pin、双独立 FILTER 求交、多起点+OPTIONAL 属性回表非 EMPTY 且逐 seed 归属正确 |

### 实测结果

- `core/kg-query`：10/10 全绿；
- `binding/rdf4j` `HierarchicalPathTest`：21/21（2 跳过为既有 @Ignore）；
- `binding/rdf4j` 全模块：682 用例，唯一失败 `ANRTutelleTest.testLJ4`（干净树 + 干净 install 复现，预存失败，与 path 无关）；
- 生成的 SQL 形态与设计一致：多值时 `level_0` 为 `WHERE "BOM_ITEM_ID" IN (2, 3)`，起点属性回表 `LEFT JOIN ... ON V4.START_IRI = V5."BOM_ITEM_ID"`（start_iri 变量-变量 join，与生产 31-OPTIONAL 形态同构）；单值时退化为旧的常量 pin 形态（`2 = V3.START_IRI`），完全向后兼容。

### 实测中发现并修正的两点

1. **原生 SQL 投影列序不与 SPARQL 变量一一对应**：pin 形态下起点是 ground 常量，不出现在 raw target SQL 投影里（既有行为，`constantStartTraversesOneLevel` 同理）；多种子+OPTIONAL 时 IRI 模板列被分解为多列。测试断言相应改为值域断言/SQL 包含断言。
2. **旧 `processSeedCondition` 的隐含 bug 顺手修**：旧代码按"值相等"判断冲突，两个不同 id 通道（如 `bom_item_id` 与 `parent_material_code`）给出相同字符串值时会错误消费第二个 FILTER、丢掉该属性约束；新实现按"来源 id 变量"判断，异通道一律留树，语义更保守且正确。

### 遗留（未做）

- 生产形态多起点 + 31 OPTIONAL + UNI 开关 on/off 的 A/B 实测（需生产 obda 方向恢复决策后进行）；
- VALUES/TempTable 二期（`SeedSource.Values` 已覆盖 ≤1000 批量场景）；
- 不可烘焙 IN（子查询元素）回退行为的专项测试（当前回退路径 = 旧的不支持异常，无回归）。
