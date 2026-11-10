# 精确类型模式（Exact Type Reasoning）— 设计方案

**Date**: 2026-11-10
**Status**: Revised (v1.1, post-review)
**Author**: opencode (AI-assisted)

## 1. 问题与目标

### 1.1 现象

当本体中一个属性被多个实体共享，并声明了多条 `rdfs:domain`（或存在子类关系）时，查询：

```sparql
?n0 a <.../class/material> .
?n0 <.../property/material_code> "1649607" .
```

会被重写成两张表的 `UNION ALL`（`material_base_info_dd` 与 `bom_item_detial_info_dd`），即使查询已经用 `a :material` 显式限定了实体类型。

### 1.2 根因

ontop 在构建 mapping 时会做 **TBox 饱和（T-mapping saturation）**：结合本体的子类 / 子属性 / domain / range / 等价公理，派生出额外的 mapping 断言。被派生的 `a :material`（来自 bom_item 表）与显式的 `a :material`（来自 material 表）被合并进同一个 `classDefinitions` 表，`rdf:type` 展开时无法区分。

### 1.3 目标

提供一个**全局配置开关**，让 `rdf:type` 查询只匹配**显式 / 非饱和**的类型映射：

- `full`（默认）：保持现状，使用全部 class 映射（含 TBox 饱和派生）。
- `exact`：只使用非派生的 class 映射。

### 1.4 语义定义

| 来源 | full | exact |
|---|---|---|
| mapping 文件里字面写的 `a :C` | ✅ | ✅ |
| facts 文件（`--facts`）中的类型 | ✅ | ✅ |
| SPARQL rules 输出的类型 | ✅ | ✅ |
| **TBox 饱和派生**的 `a :C` | ✅ | ❌ |

即 exact 模式**只排除 `TMappingSaturatorImpl` 派生的断言**，不是"只认字面映射"。

## 2. 设计

### 2.1 配置

新增键：

```properties
# full | exact
ontop.reasoning.type = full
```

接口（`mapping/core/.../injection/OntopMappingSettings.java`）：

```java
enum TypeReasoningMode { FULL, EXACT }

TypeReasoningMode getTypeReasoningMode();

String TYPE_REASONING_MODE = "ontop.reasoning.type";
```

- 默认 `full`（向后兼容）。
- 非法值抛 `InvalidOntopConfigurationException`。
- 默认值写入 `mapping/core/src/main/resources/it/unibz/inf/ontop/injection/mapping-default.properties`。

### 2.2 在饱和输出层剔除 class 派生条目（核心）

**不能**采用"给断言打 `inferred` 布尔标记、再在 `DefaultMappingTransformer` 下游过滤"的做法。原因（已核实代码）：

- `EquivalencesDAG.getSub` 是**自反**的（包含自身），见 `EquivalencesDAG.java:21-27`；
- `TMappingSaturatorImpl` 的 class 分支通过 `getSubsumees`（基于 `getSub`）取到"类自身 + 所有子类"的原始断言（`TMappingSaturatorImpl.java:122`、`:171-174`）；
- 这些来源在 `saturate(...)` 内被 `MappingAssertionUnion` 合并为**单个** IQ（`TMappingSaturatorImpl.java:158-168`、`MappingAssertionUnion.java:340`）。

因此 `saturated[class A]` 同时包含"子类派生"与"A 自身的字面映射"，无法用单一 `inferred` 标记区分；并且 `original` 分支会因 `saturated.containsKey` 把被覆盖的字面映射一并丢弃（`TMappingSaturatorImpl.java:131-137`）。两种标记取值都会出错：

- 标 `true` → 字面映射被误删（查询结果为空）；
- 标 `false` → 派生仍在（特性无效）。

**正确做法**：让 `MappingSaturator` 感知 `TypeReasoningMode`，在 `saturate(...)` 构建出 `saturated` 映射之后、`Stream.concat(...)` 之前，exact 模式下**移除所有 key 为 class 的派生条目**：

```java
ImmutableMap<MappingAssertionIndex, MappingAssertion> saturated = ...; // 现有构建逻辑

ImmutableMap<MappingAssertionIndex, MappingAssertion> effectiveSaturated =
        settings.getTypeReasoningMode() == TypeReasoningMode.EXACT
                ? saturated.entrySet().stream()
                        .filter(e -> !e.getKey().isClass())
                        .collect(ImmutableCollectors.toMap())
                : saturated;

return Stream.concat(
        effectiveSaturated.values().stream(),
        original.asMap().entrySet().stream()
                .filter(e -> !effectiveSaturated.containsKey(e.getKey()))
                ...);
```

因为 `effectiveSaturated` 不再含 class 键，`original` 分支会**自动保留**那些独立的字面 class 断言（不再被 `containsKey` 过滤），且它们未与派生合并。

- 属性分支（object/data property）**保持原样**——满足 §7"仅影响 class 断言，保留 sub-property 饱和"。
- 注意：**不能**用"exact 时整体跳过 `saturate`"来实现，那会连属性 sub-property 饱和一起禁用，违反 §7。
- `TMappingSaturatorImpl` 需注入 `OntopMappingSettings`（构造器注入，与其它 transformer 一致）。

> 由属性存在性（`ObjectSomeValuesFrom` / `DataSomeValuesFrom`）派生的 class 断言，其 `toIndex` 也是 class，同样会被上面的过滤剔除，符合 §1.4"排除 TBox 饱和派生"。

### 2.3 Mapping 构建保持不变

`DefaultMappingTransformer.getMapping(...)`（`DefaultMappingTransformer.java:118`）**不做**任何 exact 过滤：

- class 表与属性表均直接来自已经过滤过的断言列表；
- `MappingImpl` **不改**：`getRDFClassDefinition`、`getRDFClasses`、`getMergedClassDefinitions` 自动一致。

> 不需要给 `MappingAssertion` 增加 `inferred` 字段，也不需要改动 `MappingAssertionUnion` / `RuleExecutorImpl` 等直接 `new MappingAssertion(...)` 的构造点。

### 2.4 诊断门禁（关键）

exact 模式下在 `TMappingSaturatorImpl.saturate(...)` 中统计被剔除的 class 键数量并输出：

```text
Exact type reasoning enabled: dropped N inferred class mappings
```

（DEBUG 级别按 class IRI 列出被剔除的 key。）

> N 是"被剔除的饱和 class 条目数"，由于合并已经发生，它**不等于**原始派生断言个数，不要用 N 反推断言数。

诊断矩阵：

| 观察 | 结论 | 后续动作 |
|---|---|---|
| exact 后 union 消失、单表 | 来源是 TBox 饱和 | 本设计已解决 |
| exact 后仍然两张表 | 来源是①映射字面 或 ③查询重写 | 见 §2.5 |

诊断方法：

1. 分别在 `full` / `exact` 下执行同一查询，对比生成的 SQL；
2. 若两者相同 → 不是饱和造成的：
   - 检查 mapping 文件：是否字面写了 `a :material`（来源①）；
   - 若 mapping 没有 → 是查询重写层（来源③），见 §2.5。

### 2.5 后续（条件触发，不纳入本次实现）

若诊断判定来源是查询重写层（ontop 用 TBox 的 domain/range/子类在重写时展开 `rdf:type`），
需要追加：

- 在 exact 模式下向重写器传入"精简 TBox"（去掉 domain/range/子类相关公理，仅保留属性层级），
  或只对 `rdf:type` 原子跳过 domain/range 展开。

相关代码位置（供后续设计参考）：

- `engine/reformulation/core/.../rewriting/impl/TreeWitnessRewriterReasoner.java:103`
  （`ObjectPropertyExpression::getDomain`）
- `engine/reformulation/core/.../rewriting/impl/TreeWitnessGenerator.java:62`
  （`property.getRange()`）

本设计只交付 §2.1–§2.4；§2.5 由诊断结果决定是否另开设计。

## 3. 代码结构

### 3.1 修改文件

| 文件 | 改动 |
|---|---|
| `mapping/core/.../injection/OntopMappingSettings.java` | 新增枚举、读取方法、配置键 |
| `mapping/core/src/main/resources/.../mapping-default.properties` | 新增 `ontop.reasoning.type = full` |
| `mapping/core/.../injection/impl/OntopMappingSettingsImpl.java` | 实现 `getTypeReasoningMode()` 与校验 |
| `mapping/core/.../spec/mapping/transformer/impl/TMappingSaturatorImpl.java` | 注入 `OntopMappingSettings`；exact 时剔除 `saturated` 中 key 为 class 的条目；诊断日志 |

**不改动**：`MappingAssertion.java`、`DefaultMappingTransformer.java`、`MappingImpl`（`core/obda`）。

> 因此也不需要处理 `MappingAssertionUnion.java:340`、`RuleExecutorImpl.java:84`/`:94`、`ABoxFactIntoMappingConverterImpl.java:103`、`SQLPPMappingConverterImpl.java:153` 等直接 `new MappingAssertion(...)` 的构造点。

### 3.2 测试文件

| 文件 | 用途 |
|---|---|
| `mapping/core/src/test/.../TMappingSaturationExactModeTest.java` | exact 下 class 派生条目被剔除、字面 class 断言保留、属性饱和仍生效 |
| `binding/rdf4j/src/test/.../ExactTypeReasoningTest.java` | 端到端：full 两表 / exact 单表 |
| `mapping/core/src/test/.../TypeReasoningModeSettingsTest.java` | 默认值、非法值 |

## 4. 详细流程

### 4.1 构建期

```text
mapping assertions
  -> sameAsInverseRewriter
  -> TMappingSaturatorImpl.saturate(...)
       full  : saturated 全保留（含 class 派生合并）
       exact : 从 saturated 中剔除 key 为 class 的条目
                 -> original 分支补回独立的字面 class 断言
  -> ... 其它 transformer ...
  -> DefaultMappingTransformer.getMapping(...)   （不做 exact 过滤）
  -> MappingImpl
```

### 4.2 查询期

```text
?x a :C
  -> BasicQueryUnfolder.getRDFClassDefinition(predicate, :C)
   -> MappingImpl.classDefinitions.get(...)
        full  : 字面 + facts + rules + 派生
        exact : 字面 + facts + rules（不含派生）
```

## 5. 测试策略

### 5.1 单元测试

1. `TMappingSaturatorImpl`（exact）：
   - 给定 `B ⊑ A`，`T1 → :b/{id} a B`、`T2 → :a/{id} a A`；
   - `full`：`a A` 的合并结果包含来自 T1 的派生（union）；
   - `exact`：输出中 `a A` 只来自 T2、`a B` 只来自 T1，二者独立、未被合并；不含由 B 派生的 `a A`。
   - 属性 sub-property 饱和在 `exact` 下仍生效（回归）。
2. 配置：
   - 未配置 → `FULL`；
   - `exact`/`full` 解析正确；
   - 非法值 → `InvalidOntopConfigurationException`。

### 5.2 集成测试

用**子类**场景（饱和一定覆盖，避免依赖 domain 的实现细节）：

- 本体：`B rdfs:subClassOf A`；
- mapping1：表 `T1` → `:b/{id} a B`；
- mapping2：表 `T2` → `:a/{id} a A`；
- `full`：`SELECT ?x WHERE { ?x a :A }` 命中 T1 与 T2；
- `exact`：只命中 T2。

再补一个**多 domain**场景作为回归（预期行为取决于诊断结果，若 union 仍在则标记为"待 §2.5"）。

最后补一个**属性 sub-property**回归：`p ⊑ q` 且存在 `p` 映射时，exact 下 `?s q ?o` 仍应命中 `p` 的映射（确认本设计只影响 class 断言）。

### 5.3 运行命令

```bash
mvn test -pl mapping/core -Dtest=TMappingSaturationExactModeTest
mvn test -pl mapping/core -Dtest=TypeReasoningModeSettingsTest
mvn test -pl binding/rdf4j -Dtest=ExactTypeReasoningTest
```

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| union 实际来自重写层而非饱和 | 中 | 高 | §2.4 诊断门禁先确认；必要时触发 §2.5 |
| union 来自 mapping 文件字面写法 | 中 | 中 | 诊断会显示 exact 无效；结论是改 mapping 生成器 |
| 用户依赖类型推断 | 中 | 中 | 默认 `full`，行为不变 |
| `?x a ?C` 变量查询行为变化 | 低 | 中 | class 派生条目统一剔除，`getRDFClasses`/合并逻辑一致；补测试 |
| `owl:equivalentClass` 语义预期分歧 | 低 | 低 | 文档写明 exact 只认字面；等价派生属于饱和 |
| 诊断日志泄露过多 IRI | 低 | 低 | INFO 只给数量，DEBUG 才列 IRI |

## 7. 不纳入范围

- 映射文件中字面写的类型断言。
- 属性 sub-property 推断（仅影响 class 断言）。
- 按查询 / 按会话控制（标准 SPARQL 无法表达）。
- 重写层 domain/range 类型展开（§2.5，条件触发）。
- 本体错误修复（多 domain、错误子类）——那是数据建模问题。

## 8. 参考文件

| 文件 | 用途 |
|---|---|
| `core/obda/.../spec/ontology/EquivalencesDAG.java:21-27` | `getSub` 自反语义（§2.2 核心依据） |
| `mapping/core/.../spec/mapping/transformer/impl/TMappingSaturatorImpl.java:89` | TBox 饱和入口 |
| `mapping/core/.../spec/mapping/transformer/impl/MappingAssertionUnion.java:340` | 饱和合并出口（原始与派生合并为单个 IQ） |
| `mapping/core/.../spec/mapping/transformer/impl/DefaultMappingTransformer.java:118` | `getMapping(...)` 构建 class 表（本设计不改） |
| `core/obda/.../spec/mapping/impl/MappingImpl.java:69` | `getRDFClassDefinition` 读取点 |
| `core/kg-query/.../unfolding/impl/BasicQueryUnfolder.java` | `rdf:type` 展开 |
| `mapping/core/.../injection/OntopMappingSettings.java` | 配置接口 |
| `engine/reformulation/core/.../rewriting/impl/TreeWitnessRewriterReasoner.java:103` | 重写层 domain 使用（§2.5 参考） |
| `engine/reformulation/core/.../rewriting/impl/TreeWitnessGenerator.java:62` | 重写层 range 使用（§2.5 参考） |

## 9. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-10 | 初版：全局 `ontop.reasoning.type` + 断言标记 + 构造期过滤 + 诊断门禁 |
| v1.1 | 2026-11-10 | 评审修订：放弃 `inferred` 标记 + 下游过滤；改为在饱和输出层剔除 class 派生条目；修正行号引用；补充属性饱和回归 |
