# PathSpecDeriver — 从 OBDA 自动推导路径边定义 — 设计方案

**Date**: 2026-11-12
**Status**: Proposed
**Author**: opencode (AI-assisted)
**Related**: `2026-11-11-hierarchical-path-static-design.md`

## 1. 目标与范围

在启动期扫描 `OBDASpecification.getSaturatedMapping()`，对每个属性谓词尝试从映射 IQ 自动推导 `PathSpec.Self`，成功后注册到 `PathSpecRegistry`，使 `:p*` + `path:maxDepth` **无需手动 `path-spec.properties`** 即可生效。

范围（Phase 1）：

- 仅识别“单表 self-join 边、两端同 IRI 前缀、节点 key 为单列”的映射；
- 其余跳过，回退到手动 `path-spec.properties` 或普通 join。

非目标：

- 异构边（person/company）、多表 join 边的自动推导（Phase 2）；
- 属性列自动推导与下沉（另一个独立能力）。

## 2. 输入 / 输出与优先级

- 输入：`Mapping`、`PathSpecRegistry`。
- 输出：无返回值；对可识别的谓词 `registry.upsert(spec)`。
- 优先级：手动配置 > 自动推导。`upsert` 前检查 `registry.get(predicate)` 是否已存在，存在则不覆盖。

## 3. 识别条件（边 IQ 形态）

真实 `parent_bom_item` 的映射 IQ：

```text
triple(sm1, pm1, om1)
CONSTRUCT [sm1, pm1, om1] [
  sm1 = RDF(IRIStringTemplate(bom_item/ + {INTEGERToTEXT(BOM_ITEM_ID1m1)}), IRI),
  pm1 = <parent_bom_item>,
  om1 = RDF(IRIStringTemplate(bom_item/ + {INTEGERToTEXT(BOM_ITEM_ID2m1)}), IRI)
]
JOIN
  EXTENSIONAL "PARENT_BOM" (0: BOM_ITEM_ID1m1, 2: MATERIAL_CODE2m1)
  EXTENSIONAL "PARENT_BOM" (0: BOM_ITEM_ID2m1, 1: MATERIAL_CODE2m1)
```

必须同时满足：

1. 顶层可解构出 `ConstructionNode`，其 child 是 `InnerJoin`，恰好两个 `ExtensionalDataNode`；
2. 两个 `ExtensionalDataNode` 的 `RelationDefinition` 相同（self-join）；
3. `ConstructionNode` 中 subject/object 变量（从 IQ 的 projection atom 取）都是 IRI 模板；
4. subject 与 object 的 IRI 模板字符串前缀相同；
5. 模板参数可穿透常见文本转换函数（如 `INTEGERToTEXT`）得到单一列变量；
6. 两个 Extensional 恰好共享一个 JOIN 变量。

任一不满足 → 跳过该谓词。

## 4. 推导步骤

1. 从 IQ 的 projection atom `triple(s, p, o)` 取 subject 变量 `s`、object 变量 `o`、predicate IRI。
2. 解构 `ConstructionNode`，取 substitution：
   - `s -> IRIStringTemplate(prefix, {exprS})`
   - `o -> IRIStringTemplate(prefix, {exprO})`
3. 从 `IRIStringTemplateFunctionSymbol.getTemplateComponents()` 提取 string 前缀，断言 `exprS/exprO` 前缀一致。
4. 穿透 `exprS/exprO` 的文本转换函数，取底层列变量 `varS/varO`。
5. 在两个 Extensional 的 `argumentMap` 中定位：
   - 包含 `varS` 的 alias = 子侧，记列索引 `idxS`；
   - 包含 `varO` 的 alias = 父侧，记列索引 `idxO`；
   - 两者必须是不同 alias。
6. 求两个 Extensional 的共享变量集合，必须恰好一个 `joinVar`：
   - 在子侧 alias 中找 `joinVar` 的列索引 `idxChildJoin`；
   - 在父侧 alias 中找 `joinVar` 的列索引 `idxParentJoin`。
7. 列名取自 `relation.getAttribute(index + 1).getID()`；构造 `PathSpec.Self`：

| 字段 | 取值 |
|---|---|
| `predicate` | predicate IRI |
| `table` | `relation.getID().getSQLRendering()`（或 atom name） |
| `nodeKeyColumn` | `attribute(idxS + 1)`（需与 `idxO` 列一致） |
| `parentJoinKeyColumn` | `attribute(idxChildJoin + 1)`（子侧 `PARENT_MATERIAL_CODE`） |
| `joinKeyColumn` | `attribute(idxParentJoin + 1)`（父侧 `MATERIAL_CODE`） |
| `iriPrefix` | 步骤 3 的前缀 |
| `traversalFilter` | `Optional.empty()` |

方向约定必须与 `HierarchySqlBuilder` 一致：

- `level_0.next_key = parentJoinKeyColumn`（子侧下一跳列）；
- `level_k JOIN p ON p.joinKeyColumn = l.next_key`（父侧匹配列）。

## 5. 边界与回退

跳过并 DEBUG 记录原因：

- 非 self-join（两个 relation 不同）；
- 两个以上 Extensional、嵌套 Join、Union；
- subject/object 不是 IRI 模板；
- subject/object 前缀不同（异构边）；
- IRI 模板参数是复合表达式，无法穿透到单列；
- 共享 JOIN 变量数量 ≠ 1；
- 找不到列索引或列名。

被跳过的谓词仍可：

- 用 `path-spec.properties` 手动注册；
- 或走普通 join 查询。

## 6. 集成点

`QuestQueryProcessor` 构造（已持有 `obdaSpecification` 与 `pathSpecRegistry`）：

```java
PathSpecDeriver.deriveAndRegister(obdaSpecification.getSaturatedMapping(), pathSpecRegistry);
PathSpecConfigLoader.loadIfConfigured(pathSpecRegistry);
```

顺序：先自动推导，再加载手动配置。`PathSpecConfigLoader.upsert` 覆盖同名 predicate，保证手动优先。

## 7. 代码结构

| 文件 | 职责 |
|---|---|
| `core/kg-query/.../path/PathSpecDeriver.java` | 遍历 Mapping、推导、注册 |
| `core/kg-query/.../path/PathSpecDeriverTest.java` | 用真实 IQ 断言 6 字段 |
| `engine/.../QuestQueryProcessor.java` | 调用 `deriveAndRegister` |

建议的方法：

- `derive(Mapping, predicateIri, IQ) -> Optional<PathSpec.Self>`
- `extractIriTemplate(term) -> Optional<(prefix, columnVar)>`
- `unwrapTextCast(term) -> Optional<Variable>`
- `findAlias(nodes, var) -> Optional<(node, columnIndex)>`
- `sharedJoinVariable(left, right) -> Optional<Variable>`

## 8. 测试

1. **单元**：加载 `parent-bom-item` 的 Mapping，断言推导出：
   - `table = PARENT_BOM`
   - `nodeKeyColumn = BOM_ITEM_ID`
   - `parentJoinKeyColumn = PARENT_MATERIAL_CODE`
   - `joinKeyColumn = MATERIAL_CODE`
   - `iriPrefix = http://example.org/ontology#bom_item/`
2. **集成**：不配置 `path-spec.properties`，`HierarchicalPathTest` 通过 deriver 自动注册后仍通过。
3. **回归**：手动配置存在时，deriver 不覆盖。

## 9. 风险

| 风险 | 缓解 |
|---|---|
| IQ 形态与预期不符 | 严格条件，其余跳过；DEBUG 记录原因 |
| IRI 模板转换函数不止 `INTEGERToTEXT` | 只穿透已知 DB 文本转换函数，其余跳过 |
| 列名大小写/引号 | 使用 `QuotedID.getSQLRendering()` |
| 多共享 JOIN 变量 | 跳过，回退手动配置 |
| 异构边（person/company） | Phase 2，先跳过 |

## 10. 工作量

约 **2–2.5 天**：

- `PathSpecDeriver` 实现：1 天；
- IQ 解析调优（IRI 模板、函数穿透、alias 识别）：0.5–1 天；
- 测试：0.5 天。

## 11. 参考

| 文件 | 用途 |
|---|---|
| `core/obda/.../spec/mapping/Mapping.java:25-39` | `getRDFPropertyDefinition` / `getRDFProperties` |
| `engine/.../QuestQueryProcessor.java` | 构造处调用 deriver |
| `core/model/.../dbschema/PathSpec.java` | `PathSpec.Self` 字段 |
| `core/kg-query/.../path/HierarchySqlBuilder.java` | 方向约定（`joinKeyColumn` / `parentJoinKeyColumn`） |
| `db/rdb/.../generation/.../DefaultSQLIQTree2NativeNodeGenerator.java` | 参考 IQ 解构方式 |
| `docs/superpowers/analysis/hierarchical-path-phase0.md` | Phase 0 结论 |

## 实现更新（v1.1）

实际落地时相对第 4 节的补充：

1. **边 IQ 形态穿透**：真实映射 IQ 是 `Construction -> DISTINCT -> Construction -> InnerJoin(两个 Extensional)`，不是 `Construction -> InnerJoin`。`derive` 先取顶层 `ConstructionNode`，再用 `findJoinChildren` 递归穿透 `DISTINCT` / 内层 `Construction` / `Filter` 找到 `InnerJoin`，然后才读取两个 `ExtensionalDataNode`。

2. **属性列推导与 Union 分支**：`derivePropertyColumns` 先跳过顶层 `ConstructionNode`，若其为 `Union`（多来源属性，如 `material_code` 同时来自 BOM 表与 material 主数据表），对每个分支 `deriveSinglePropertyColumn`，返回多个 `(table, column)`。`collectTableColumns` 按 `table` 分组；边推导时只取与 `PathSpec.table` 相同的属性（“以当前表为单位”）。

3. **节点属性下沉的 RDF 构造**：`PathSpecDeriver` 只负责推导 `nodeColumns`。真正把 `?ancestor prop:X` 下沉到路径 relation 的是 `RDF4JTupleExprTranslator`：
   - 收集谓词在 `nodeColumns` 中的 triple，翻译成 `TrueNode`；
   - 从路径 relation 的对应列绑定 `?X`；
   - `ConstructionNode` 用 `RDFLiteral(raw, xsd:string)` 包装下沉属性，避免结果层出现 `"..."^^TEXT` DB 常量。

4. **列索引约定**：`PathRelationDefinition` 的列顺序为 `node_iri`(0)、`depth`(1)、`predecessor`(2)、`nodeColumns`(3..)。translator 的 `argumentMap` 从索引 3 开始绑定属性列，必须与 `toRelation` 的追加顺序一致。

### 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-12 | 初版：边推导设计 |
| v1.1 | 2026-11-12 | 补充 DISTINCT/Construction 穿透、Union 属性列按表筛选、属性下沉 + RDFLiteral 构造、列索引约定 |
