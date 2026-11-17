# 路径属性下沉的 RDF datatype 支持：设计

日期：2026-11-17（v3.1，按三轮评审修订）
状态：Proposed
关联：`2026-11-11-hierarchical-path-static-design.md`（v3.0）、`2026-11-16-path-attribute-sinking-datatype-analysis.md`（失败分析）

## 1. 背景

路径 CTE 会把节点属性列一路携带（`nodeColumns`），`translatePath` 对下沉属性统一构造：

```java
termFactory.getRDFLiteralFunctionalTerm(rawAttribute, typeFactory.getXsdStringDatatype())
```

即**所有下沉属性都被当作 `xsd:string`**。当属性实际声明为 `xsd:integer` / `xsd:decimal` 时：

- `FILTER(?x = 1)`（数值）恒 false → EMPTY；
- `ORDER BY` / 算术 / 聚合语义错误；
- 最终 `CONSTRUCT` 输出 `xsd:string`，与 OBDA 声明不一致。

之前尝试「按 DB 列类型下沉」失败：DB 类型与声明可能冲突（`bom_item_id` 是 BIGINT 列，但 OBDA 声明 `^^xsd:string`）。

## 2. 目标

1. 下沉属性的 RDF datatype 与 **mapping 声明**一致（OBDA 的 `^^xsd:integer` 等；声明缺失时由 `MappingDatatypeFiller` 的 DB 类型兜底）。
2. 属性 datatype 缺失时退回 `xsd:string`，保持向后兼容。
3. 带语言标签的字面量（`"x"@en`）**不下沉**，回表保持 `@en` 语义。
4. 为后续「起点属性下沉（start_* 列）」提供同一套 datatype 机制。

## 3. 关键结论（已实测）

| 候选来源 | 结论 |
|---|---|
| `getSaturatedMapping()` 的 IQ | object 是裸变量；`AtomPredicate.getBaseTypesForValidation()` 只有 `[IRI or Bnode, IRI, RDFTerm]`，**拿不到具体 datatype** |
| `SingleTermTypeExtractor` | 返回 **DB 类型**（INTEGER/DECIMAL），与声明可能冲突，**不能作为权威** |
| ontology `rdfs:range` | 信息性来源；`MappingDatatypeFiller` 已消费过，本设计不单独使用 |
| **未饱和 `MappingAssertion`** | ✅ object 定义为 `RDF(lexical, <datatype>)`，由 `MappingDatatypeFiller` 填充，**权威来源** |

两个已核实的实现事实（决定本设计）：

- `TypeFactoryImpl.getDatatype(IRI)` 对未知 IRI **不抛异常**，而是合成一个 string-backed concrete datatype（保留原 IRI，ancestry 为 `rdfs:Literal`）。所以「未知 → 回退 xsd:string」不会发生，自定义 datatype 会被原样保留——这符合目标 1。
- `rdf:langString` 的语言标签只存在于 `TypeFactoryImpl` 的 `langTypeCache`（按 tag 缓存），`datatypeCache` 里没有；用 IRI 反查 `getDatatype(RDF.LANGSTRING)` 只会得到**无语言标签**的 datatype。因此 **lang literal 必须在提取期（能拿到带 tag 的 `RDFDatatype` 对象）识别**，不能在下沉期判断。

## 4. 设计

### 4.1 数据流

```
DefaultMappingTransformer.transform(ImmutableList<MappingAssertion> mapping, ...)
  └─ collectPropertyDatatypes(mapping)                      // saturate 之前
       Map<(table, propertyIRI), datatypeIRI>
       Set<(table, propertyIRI)>            // lang literal 属性
  └─ OBDASpecificationImpl(..., ImmutableMap datatypes, ImmutableSet langProperties)
       OBDASpecification.getPropertyDatatypes() / getLangLiteralProperties()
  └─ QuestQueryProcessor 构造
       PathSpecDeriver.deriveAndRegister(mapping, registry, datatypes, langProperties)
  └─ PathSpec.Self.nodeColumnTypes: Map<propertyIRI, datatypeIRI>   // lang 属性已排除出 nodeColumns
  └─ RDF4JTupleExprTranslator.translatePath
       RDFLiteral(rawColumn, typeFactory.getDatatype(属性级 datatype))   // 按列 index 对齐，按属性查 datatype
```

### 4.2 提取（`DefaultMappingTransformer`）

位置：`mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/DefaultMappingTransformer.java`，`transform(...)` 方法入口处、`sameAsInverseRewriter.rewrite(...)` 之前（datatype 提取不受 sameAs 改写影响，越早越简单，且必然早于 `mappingSaturator.saturate(...)`）。该类当前没有 slf4j 字段，实施时需新增 `LOGGER`。

**粒度：`(table, propertyIRI)`**，与 `PathSpec.Self` 按表一致。

```java
private static Extraction extract(ImmutableList<MappingAssertion> assertions) {
    Map<String, String> datatypes = new HashMap<>();
    Set<String> langProperties = new HashSet<>();

    for (MappingAssertion assertion : assertions) {
        if (assertion.getIndex().isClass())                 // class assertion
            continue;
        /*
         * 实施时发现：此处 atom 的谓词位置是**变量**（triple(s,p,o)），
         * predicate.getPropertyIRI(args) 恒为空；property IRI 只能取自
         * assertion.getIndex()（meta-mapping 中可能非 ground，需跳过）。
         */
        IRI propertyIRI;
        try {
            propertyIRI = assertion.getIndex().getIri();
        } catch (MappingAssertion.NoGroundPredicateOntopInternalBugException e) {
            continue;                                     // meta-mapping
        }
        RDFAtomPredicate predicate = assertion.getRDFAtomPredicate();
        ImmutableList<? extends VariableOrGroundTerm> args =
                assertion.getProjectionAtom().getArguments();

        Optional<PropertyType> type = extractPropertyType(assertion);
        if (!type.isPresent())                        // object property（IRI 型）等
            continue;

        Optional<String> table = extractTable(assertion);
        if (!table.isPresent()) {
            LOGGER.warn("Cannot determine table for property {}, skipping datatype extraction",
                    propertyIRI.get());
            continue;
        }

        String key = key(table.get(), propertyIRI.get().getIRIString());
        if (type.get().langLiteral) {
            langProperties.add(key);
            continue;                                  // lang 属性不进 datatype map
        }
        String previous = datatypes.get(key);
        if (previous != null && !previous.equals(type.get().datatypeIri)) {
            LOGGER.warn("Datatype conflict for property {} on table {}: {} vs {}; keeping the first",
                    propertyIRI.get(), table.get(), previous, type.get().datatypeIri);
            continue;
        }
        datatypes.putIfAbsent(key, type.get().datatypeIri);
    }
    return new Extraction(datatypes, langProperties);
}

private static String key(String table, String propertyIri) {
    return table + "\u0000" + propertyIri;
}

private static final class PropertyType {
    final String datatypeIri;
    final boolean langLiteral;
}

private static final class Extraction {
    final Map<String, String> datatypes;
    final Set<String> langProperties;
}
```

`extractPropertyType`：从 object 定义取 `RDF(lexical, type)` 的 datatype **对象**（不是 IRI），以便可靠判断 lang：

```java
private static Optional<PropertyType> extractPropertyType(MappingAssertion assertion) {
    RDFAtomPredicate predicate = assertion.getRDFAtomPredicate();
    VariableOrGroundTerm object =
            predicate.getObject(assertion.getProjectionAtom().getArguments());
    if (!(object instanceof Variable))
        return Optional.empty();
    ImmutableTerm definition = assertion.getTopSubstitution().apply((Variable) object);
    Optional<RDFDatatype> datatype = findDatatype(definition);
    return datatype.map(dt -> new PropertyType(
            dt.getIRI().getIRIString(), dt.getLanguageTag().isPresent()));
}

private static Optional<RDFDatatype> findDatatype(ImmutableTerm term) {
    if (term instanceof RDFConstant) {
        RDFTermType type = ((RDFConstant) term).getType();
        return (type instanceof RDFDatatype) ? Optional.of((RDFDatatype) type) : Optional.empty();
    }
    if (term instanceof RDFTermTypeConstant) {
        RDFTermType type = ((RDFTermTypeConstant) term).getRDFTermType();
        return (type instanceof RDFDatatype) ? Optional.of((RDFDatatype) type) : Optional.empty();
    }
    if (term instanceof ImmutableFunctionalTerm) {
        for (ImmutableTerm sub : ((ImmutableFunctionalTerm) term).getTerms()) {
            Optional<RDFDatatype> found = findDatatype(sub);
            if (found.isPresent())
                return found;
        }
    }
    return Optional.empty();                          // IRI 型 object、未知结构
}
```

`extractTable`：**规则必须与 deriver 一致**：

1. 从 `definition` 用 `unwrapColumn` 风格穿透（`IF_ELSE_NULL`/cast）得到**词法变量**；
2. 在 query 树中找含该变量的 `ExtensionalDataNode`（先看 join children，再看 union 分支）；
3. 表名取 `relation.getAtomPredicate().getName()`（**不是** SQL rendering），与 `PathSpecDeriver` 的 `tableColumns` 口径一致；
4. 多个候选（join 场景）→ 只取「定义词法变量」的那一个；仍不唯一 → warn + 返回 empty；
5. **UNION 分支**：用 `NaryIQTreeTools.UnionDecomposition` 逐支提取，每支得到独立的 `(table, PropertyType)`；某支解析失败 → 该支 warn + 跳过（不污染其它支）。

因此实际实现中，`extract(...)` 的内层循环应遍历「assertion × union 分支」得到的 `(table, PropertyType)` 列表，而不是「一个 assertion 一个 table」。

说明：
- 只遍历 `mapping` 参数；`factsAsMapping` / ontology facts 被有意排除（fact 声明无法下沉）；
- `RDFConstant` 分支覆盖 mapping target 中的常量字面量；
- 单个 assertion 内的 datatype 一致性由 `MappingDatatypeFillerImpl` 保证（`typeInferences.size() > 1` 会抛异常），因此「递归取第一个命中」在 assertion 内安全；
- **object property 不适用**：其 object 是 IRI 型，`findDatatype` 返回 empty → 不进 map；deriver 的 `derivePropertyColumns` 也只收 literal 列；
- 「词法变量穿透 + 定位 ExtensionalDataNode」与 `PathSpecDeriver` 是**两份平行实现**，非重复设计：模块依赖方向不允许 `mapping/core` 依赖 `core/kg-query`，无法共享，勿试图合并（会产生循环依赖）。

### 4.3 传递（`OBDASpecification`）

`core/obda/src/main/java/it/unibz/inf/ontop/spec/OBDASpecification.java`：

```java
/** (table, property IRI) -> declared RDF datatype IRI. */
default Map<String, String> getPropertyDatatypes() {
    return Collections.emptyMap();
}

/** (table, property IRI) whose values carry a language tag (must not be sunk). */
default Set<String> getLangLiteralProperties() {
    return Collections.emptySet();
}
```

`OBDASpecificationImpl`：**单一 `@Assisted` 构造器**新增两个参数（唯一创建点是 `SpecificationFactory.createSpecification`，由 `DefaultMappingTransformer` 调用），字段用 `ImmutableMap` / `ImmutableSet`（多查询线程共享）。

### 4.4 存储（`PathSpec.Self`）

`core/model/src/main/java/it/unibz/inf/ontop/dbschema/PathSpec.java` 恢复：

```java
private final Map<String, String> nodeColumnTypes;   // propertyIRI -> datatypeIRI

public Self(..., Map<String, String> nodeColumns, Map<String, String> nodeColumnTypes,
            Optional<TraversalFilter> traversalFilter) { ... }

public Map<String, String> nodeColumnTypes() { return nodeColumnTypes; }
```

保留旧构造器（`nodeColumnTypes = emptyMap()`）——`PathSpecConfigLoader` 依赖它（与 `OBDASpecificationImpl` 不同：后者的唯一创建点可直接改）。

### 4.5 deriver 填充（**在此排除 lang 属性**）

`PathSpecDeriver`：

1. **`collectTableColumns` 跳过 lang 属性**（生产 `nodeColumns` 的地方）：

```java
if (langProperties.contains(key(table, propertyIri)))
    continue;                       // 不进 nodeColumns → 不 sink → 翻译时按普通属性回表
```

2. 构造 `nodeColumnTypes`（只对有 datatype 的属性）：

```java
Map<String, String> nodeColumnTypes = new HashMap<>();
for (String propertyIri : nodeColumns.keySet()) {
    String datatype = propertyDatatypes.get(key(table, propertyIri));
    nodeColumnTypes.put(propertyIri,
            datatype != null ? datatype : XSD.STRING.getIRIString());
}
```

`deriveAndRegister(mapping, registry, datatypes, langProperties)` 新增两个参数；`QuestQueryProcessor` 传 `obdaSpecification.getPropertyDatatypes()` / `getLangLiteralProperties()`。

**关键**：lang 属性在 `nodeColumns` 生成阶段即被排除，因此：

- `isSinkProperty` 不会命中（`nodeColumns().get(predicate)` 为 null）；
- 它按普通属性回表，保持 `@en`；
- **不会出现「变量被吞但无绑定」**（阶段一 bug 不会复现）。

### 4.6 下沉（`translatePath`）——按列 index，按属性取 datatype

**保留现有「按去重后的列推进 `index`」的结构**（`translatePath` 与 `PathQuerySupportImpl.toRelation` 的列契约不能动），但 datatype 解析保持**属性级**：把现有 `columnToVariables`（`Map<列, Set<变量>>`）改为按列保留属性级结构（`Map<列, Map<属性IRI, Set<变量>>>`）。同一 raw 列变量在不同 substitution 条目中包不同 datatype 完全合法（条目相互独立），因此同列多属性各自取到**自己的** datatype，不存在列级串号：

```java
Map<String, Map<String, Set<String>>> columnToPropertyVariables = new HashMap<>();
for (Map.Entry<String, String> e : selfSpec.nodeColumns().entrySet()) {
    String propertyIri = e.getKey();
    Set<String> variableNames = sinkPropertyVariables.get(propertyIri);
    if (variableNames != null && !variableNames.isEmpty())
        columnToPropertyVariables.computeIfAbsent(e.getValue(), k -> new LinkedHashMap<>())
                .put(propertyIri, variableNames);
}

int index = 4;
for (String column : nodeColumns) {                    // nodeColumns = relation 的列顺序
    Map<String, Set<String>> propertyVariables = columnToPropertyVariables.get(column);
    if (propertyVariables != null && !propertyVariables.isEmpty()) {
        Variable rawAttribute = termFactory.getVariable("path_col_" + index + "_raw");
        argumentMap.put(index, rawAttribute);
        for (Map.Entry<String, Set<String>> pv : propertyVariables.entrySet()) {
            // 按属性 IRI 取 datatype（nodeColumnTypes §4.5 已保证有默认值，此处 null 仅防御）
            RDFDatatype datatype = datatypeFor(selfSpec.nodeColumnTypes().get(pv.getKey()));
            for (String variableName : pv.getValue())
                attributeSubstitutions.put(termFactory.getVariable(variableName),
                        termFactory.getRDFLiteralFunctionalTerm(rawAttribute, datatype));
        }
    }
    index++;                                            // 无变量的列也要推进，保持与 relation 对齐
}
```

> 注：不引入 v2 的 `columnDatatypes`（按列 `putIfAbsent` 收敛 datatype）。同列多属性声明不同 datatype 时，属性级解析各自正确，无需冲突 warn——冲突场景已在 §4.2 的 `(table, propertyIRI)` 粒度层面处理。

`datatypeFor`：

```java
private RDFDatatype datatypeFor(String datatypeIri) {
    if (datatypeIri == null)                              // 仅防御：§4.5 已保证非 null
        return typeFactory.getXsdStringDatatype();
    return typeFactory.getDatatype(rdfFactory.createIRI(datatypeIri));
}
```

- 不再有 `try/catch RuntimeException`：`getDatatype` 对未知 IRI 会**合成** concrete datatype（保留 IRI），这正是目标 1 想要的行为；
- 不再有「手写 XSD 快速路径」；
- lang 属性已在 deriver 阶段排除，`translatePath` 不做 datatype 是否为 lang 的判断。

### 4.7 列类型（可选增强，不在本期）

`PathQuerySupportImpl.toRelation` 仍把所有 `nodeColumns` 声明为 `stringType`，SQL 层由 Ontop 自动 `CAST`。按 datatype 声明列类型可减少 CAST，但非必需，推迟。

## 5. 测试计划

| 用例 | 资源 | 断言 |
|---|---|---|
| `findDatatype` 形态单测 | 纯单元测试 | 裸 `RDF(lexical, xsd:integer)`、`IF_ELSE_NULL` 包裹、`RDFConstant`、IRI 型 object（empty）、lang literal（`langLiteral=true`） |
| integer 属性下沉 + 数值比较 | `parent-bom-item`（`bom_version ^^xsd:integer`） | `FILTER(?bv = 1)` 非 EMPTY；SQL 含 `bom_version = 1` |
| CONSTRUCT datatype | 同上 | CONSTRUCT 中 `bom_version` 为 `^^xsd:integer` |
| string 属性下沉 + 字符串比较 | `parent-bom-item-string` | `FILTER(?bv = "1")` 非 EMPTY |
| decimal 属性 | `parent-bom-item-string` 新增独立列/属性（不改既有 `quantity` 声明） | `FILTER(?q > 0)`、`ORDER BY` 数值序 |
| **lang literal** | 新增 mapping/ontology（`"x"@en` 属性） | **非 EMPTY、不抛 `InvalidIntermediateQueryException`**；该属性回表，结果带 `@en` |
| 同列多属性不同 datatype | `parent-bom-item-string`（`bom_code`/`unit` 别名同列，临时改一个声明为 `^^xsd:integer`） | 两个变量**各自**按属性声明取 datatype：integer 变量参与数值比较、string 变量参与字符串比较，互不串号 |
| 跨 assertion 冲突 | 新增测试 mapping（同属性两表不同 datatype） | 提取期 warn，结果确定（第一个） |
| **同列同属性同变量重复 pattern** | 查询重复同一属性三元组 | 列 index 对齐，无错位/重复列 |
| 缺 datatype 回退 | properties 配置模式 | `nodeColumnTypes` 空 → `xsd:string` |
| 回归 | `HierarchicalPathTest`、`HierarchicalPathStringTypeTest`、`ParentBomItemMappingTest`、`PathSpecRegistryImplTest` | 全绿 |

## 6. 风险与回退

| 风险 | 处理 |
|---|---|
| `getTopSubstitution()` 不含 object 定义 | `findDatatype` 返回 empty → 不进 map → `xsd:string` |
| `RDFTermTypeConstant` 包裹层级变化 | 递归查找 + 单测覆盖 `IF_ELSE_NULL` |
| 同属性跨 assertion datatype 冲突 | 提取期 warn + 取第一个（确定性）；测试覆盖 |
| 自定义/未知 datatype | `getDatatype` 合成保留（符合目标 1），**不回退 string** |
| 语言标签字面量 | **提取期**识别 → deriver 排除出 `nodeColumns` → 回表；`translatePath` 不判断 |
| 同列多属性 datatype 冲突 | 不存在：`translatePath` 按属性 IRI 解析 datatype（§4.6），同列多属性各自取声明值；测试覆盖 |
| 无法确定表的属性 | 提取期 warn + 跳过（不进 map，落 `xsd:string`） |
| 配置模式无 datatype | 默认 `xsd:string`（现状不变） |

回退方式：`getPropertyDatatypes()` / `getLangLiteralProperties()` 返回空集合即恢复当前行为。

## 7. 分阶段实施

1. **P1**：提取（含 lang / UNION 逐支 / table 解析）+ 传递 + `nodeColumnTypes` + deriver 排除 + `translatePath`（本设计，约 260 行含测试）；
2. **P2**：起点属性下沉（`start_*` 列），复用 `nodeColumnTypes` 与 lang 排除规则；
3. **P3**（可选）：`toRelation` 按 datatype 声明列类型。

## 8. 验收标准

- `parent-bom-item`（integer）与 `parent-bom-item-string`（string）两个资源**同时**通过；
- 数值属性的 `FILTER` / `ORDER BY` / 算术在真实 Doris 上正确；
- `CONSTRUCT` 输出的 datatype 与 mapping 声明一致；
- lang literal 属性不下沉、结果保留 `@en`、查询不报 `InvalidIntermediateQueryException`；
- 同列多属性、跨 assertion 冲突、同列重复 pattern 三类边界正确；
- 缺 datatype 场景与当前版本一致（无回归）。

## 9. 评审修订记录

### v1 → v2

| 问题 | 修订 |
|---|---|
| 1（高）列级聚合丢失属性粒度 | 改为按属性 IRI 解析 datatype |
| 2（高）跨 assertion 冲突静默覆盖 | key 改为 `(table, propertyIRI)`；冲突 warn + 取第一个 |
| 3（中）`RDFConstant` 未处理 | `findDatatypeIri` 增加分支 |
| 4（中）lang literal 被转 `xsd:string` | 增加 `isLangLiteral`（**v3 已推翻此方案**） |
| 5（低）手写 switch | 改用 `TypeFactory.getDatatype` |
| 6（低）旧构造器 / `HashMap` | 单构造器 + `ImmutableMap` |
| 7（低）`rdfs:range` 未使用 | 改为信息性来源 |
| 8（细节）类型 / facts | 加防御性检查与说明 |

### v2 → v3

| 问题 | 修订 |
|---|---|
| **A（高）** lang 排除位置错误（会复现阶段一 bug） | 排除上移到 **deriver 的 `nodeColumns` 生成**（`collectTableColumns` 跳过 lang 属性）；`translatePath` 不再判断 |
| **B（高）** `isLangLiteral` 永不触发（`rdf:langString` 反查无 tag） | lang 识别移到**提取期**（拿 `RDFDatatype` 对象 + `getLanguageTag()`），以 `Set<(table,property)>` 显式传递 |
| **C（中）** 「未知 datatype 回退 string」与 `getDatatype` 实际行为不符 | 文档改为「合成保留（符合目标 1）」；删除 try/catch 与「快速路径」残留 |
| **D（中）** 伪代码按属性迭代破坏列 index 对齐 | §4.6 恢复按列 index 结构，仅按属性查 datatype；无变量的列也推进 index |
| **E（中）** `extractTable` 未定义 | §4.2 明确：取定义词法列的 `ExtensionalDataNode`、表名用 `getAtomPredicate().getName()`、UNION 逐支提取、解析不唯一 warn + 跳过 |
| **F（低）** 缺 LOGGER / object property | 明确补 LOGGER；说明 object property 不适用本机制 |
| 测试缺口 | 新增 lang literal（非 EMPTY/无异常）、同列同属性重复 pattern 用例 |

### v3 → v3.1（第三轮评审）

| 问题 | 修订 |
|---|---|
| **§4.6 列级 `columnDatatypes` 塌缩**（v1 问题 1 部分回归，且与 §5/§8 断言矛盾） | 删除 `columnDatatypes`/`putIfAbsent`；`columnToVariables` 改为 `columnToPropertyVariables`（`Map<列, Map<属性IRI, Set<变量>>>`），datatype 始终按属性 IRI 解析；§5 同列多属性断言改为「各自按属性声明取 datatype」（资源需临时改一个声明为 integer 才能真正验证）；§6 风险表该行改为「不存在」 |
| 提取时机模糊 | §4.2 明确为「方法入口处、`sameAsInverseRewriter` 之前」 |
| 平行实现易被误合并 | §4.2 说明补充模块依赖方向理由（`mapping/core` 不可依赖 `core/kg-query`，会产生循环依赖） |
| `datatypeFor` null 分支含义 | 标注为仅防御（§4.5 已保证非 null） |

### 实施修订（2026-11-17，随实现回填）

| 发现 | 处置 |
|---|---|
| **§4.2 伪码取 property IRI 的方式错误**：未饱和 assertion 的投影 atom 为 `triple(s, p, o)`，谓词位置是**变量**，`predicate.getPropertyIRI(args)` 恒为空（首次联测提取结果为 `{}`，整数过滤用例转 IQ 即 `EMPTY`）。property IRI 只能取自 `assertion.getIndex().getIri()` | §4.2 伪码已改为 `assertion.getIndex()` 方案：`isClass()` 跳过 class assertion；`getIri()` 抛 `NoGroundPredicateOntopInternalBugException` 时 try/catch 跳过 meta-mapping |
| §4.7 的假设成立：CTE 列保持 string 类型、外层 RDF 壳携带 datatype，SPARQL.EQ lowering 自动生成 `CAST(col AS BIGINT) = 1` | P3（给 CTE 列定 DB 类型）无需实施 |
| 数值过滤用例的根因即上述提取缺陷（string vs integer 走 `computeDefaultDifferentTypeEqualityOrInequality` → NULL → `EMPTY`） | 修复后用例通过，SQL 形态符合预期 |
| 同列多属性下沉时原生 SQL 仅投影一个共享列（RDF 重建发生在 CONSTRUCT 层），测试不能直接按列序取值 | 测试改为 SPARQL 层 `executeQuery` 断言值 + SQL 形态断言（`BIGINT`/`CAST`/`path_col_` 出现次数） |
| 新增资源 `parent-bom-item-lang/`（lang 排除 + 同 spec 整数属性仍下沉）、`parent-bom-item-mixed/`（同列多 datatype + 跨 assertion 冲突 + 同列重复 pattern） | 覆盖 §5 全部新增行；回归 30 用例全绿（3 个跳过为存量） |
