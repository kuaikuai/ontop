# 精确类型模式（Exact Type Reasoning）— 重写层设计（§2.5）

**Date**: 2026-11-10
**Status**: Design (approved, pending spec review)
**Author**: opencode (AI-assisted)
**Related**: `2026-11-10-exact-type-reasoning-design.md`（v1.1，mapping 饱和层）

## 1. 背景与问题

前序特性（`ontop.reasoning.type=exact`）已在 mapping 饱和层剔除 TBox 派生的 `rdf:type` 映射，并在服务中生效（日志：`Exact type reasoning enabled: dropped 81 inferred class mappings`）。但查询仍被重写成 5 张表的 `UNION ALL`：

```sparql
SELECT DISTINCT ?p1 WHERE {
  ?p1 a cls:material ;
      prop:material_code ?code .
  FILTER(?code = "1649607")
}
```

生成 SQL 命中 `material_base_info`、`bom_item`、`material_mould_output`、`material_equipment_capability`、`line_side_stock` 五张表。

### 1.1 已完成的诊断

- exact 已生效（丢弃 81 个饱和 class 映射），排除"exact 未启用"。
- 本体中**没有** `subClassOf material` 公理，排除命名子类展开。
- mapping 只在各自表字面写各自类（`material`、`material_mould_output`…），排除来源①"字面写 `a :material`"。
- `prop:material_code` 被声明了 5 条 `rdfs:domain`：`material`、`bom_item`、`material_mould_output`、`material_equipment_capability`、`line_side_stock`。

因此 union 来自**查询重写层**：`∃material_code.⊤ ⊑ material` 使重写器认为"任何 `material_code` 主语都是 `material`"，从而删除了显式 `a :material` 约束，`material_code` 直接展开到全部 5 张表。

### 1.2 仓库内复现

`ExactTypeReasoningDomainExactTest#testExactWithPropertyAtom`：

- 本体：`:code a owl:DatatypeProperty ; rdfs:domain :A`
- 映射：`t1 → :a/{id} a :A ; :code {code}`，`t2 → :b/{id} :code {code}`
- 查询：`?v a :A ; :code ?c`
- exact 实际返回 `a/1,a/2,b/3,b/4`（期望仅 `a/1,a/2`）。

生成的 SQL 中 `a:A` 与 `:code` 约束都被消去，仅剩 `t1 ∪ t2` 的主语模板，证实是重写层的 domain 蕴含。

## 2. 根因

`engine/reformulation/core/.../rewriting/impl/DummyRewriter.java`：

- `setTBox`（`:75-90`）用 TBox 生成 ABox 线性包含依赖（Sigma）：
  - `traverseDAG(reasoner.classesDAG(), c -> c instanceof OClass, ...)`（`:87`）
  - `getAtom(ClassExpression, ...)`（`:152-162`）把 `ObjectSomeValuesFrom`/`DataSomeValuesFrom` 翻译成属性原子。
  - 于是 `∃material_code.⊤ ⊑ material` 生成依赖 `(x material_code y) ⇒ (x a material)`。
- `rewrite` → `removeRedundantAtoms`（`:109-134`）用 `sigma.chaseAtom(atom)` 判断原子是否冗余：BGP 里有 `(x material_code y)` 时，显式的 `(x a material)` 被判为冗余并删除。
- `TreeWitnessRewriter extends DummyRewriter`（`TreeWitnessRewriter.java:58`），继承同一 `setTBox`/`removeRedundantAtoms`，因此默认路径与 existential 路径都受影响。

结果：`?x a :A ; :code ?y` 退化为 `?x :code ?y`，属性在所有映射表上展开 → 多表 union。

## 3. 语义定义

`ontop.reasoning.type`：

| 行为 | full | exact |
|---|---|---|
| 显式 / 字面 `a :C` | ✅ | ✅ |
| facts 中的类型 | ✅ | ✅ |
| SPARQL rules 输出的类型 | ✅ | ✅ |
| mapping 饱和派生的 `a :C` | ✅ | ❌（Task 2 已实现） |
| `rdfs:domain` / `rdfs:range` 蕴含的 `a :C` | ✅ | ❌（本设计） |
| 命名子类 `B ⊑ C` 使 `a :B` 归入 `C` | ✅ | ❌（严格的显式类型；Task 2 已实现） |
| 属性 sub-property 推理 | ✅ | ✅（保留） |

即 exact = **严格只认显式类型**：`?x a :C` 不因 domain/range/命名子类而纳入额外实例；属性层级推理不受影响。

## 4. 设计

### 4.1 配置下沉到共享层

重写器位于 `engine/reformulation/core`，不依赖 `mapping/core`（`ontop-mapping-core`）。为避免新增模块依赖，将类型模式定义上移到 `core/model`：

- `OntopModelSettings`（`core/model/.../injection/OntopModelSettings.java`）新增：

```java
enum TypeReasoningMode { FULL, EXACT }

TypeReasoningMode getTypeReasoningMode();

String TYPE_REASONING_MODE = "ontop.reasoning.type";
```

- `OntopModelSettingsImpl` 实现：

```java
@Override
public TypeReasoningMode getTypeReasoningMode() {
    return getProperty(TYPE_REASONING_MODE)
            .map(String::trim)
            .map(OntopModelSettingsImpl::parseTypeReasoningMode)
            .orElse(TypeReasoningMode.FULL);
}

private static TypeReasoningMode parseTypeReasoningMode(String value) {
    if ("full".equalsIgnoreCase(value))
        return TypeReasoningMode.FULL;
    if ("exact".equalsIgnoreCase(value))
        return TypeReasoningMode.EXACT;
    throw new InvalidOntopConfigurationException(
            "Invalid value for " + TYPE_REASONING_MODE + ": '" + value + "' (expected 'full' or 'exact')");
}
```

- 移除 `OntopMappingSettings` 中重复的枚举/方法声明与 `OntopMappingSettingsImpl` 的覆盖实现（继承即可）。
- 保留 `mapping-default.properties` 的 `ontop.reasoning.type = full`（显式默认；属性缺失时代码默认仍为 `FULL`）。
- 更新 `TMappingSaturatorImpl` 与配置测试中的类型引用为 `OntopModelSettings.TypeReasoningMode`。

### 4.2 `DummyRewriter` exact 门禁

`DummyRewriter` 注入 `OntopModelSettings`（`OntopAbstractModule.configureCoreConfiguration()` 已把 settings 实例绑定到该类型），构造时预计算：

```java
private final boolean exactTypeReasoning;
...
this.exactTypeReasoning = settings.getTypeReasoningMode() == OntopModelSettings.TypeReasoningMode.EXACT;
```

`setTBox` 中：exact 时**完全跳过 class DAG 的 ABox 依赖构建**，属性 DAG 保持不变：

```java
traverseDAG(reasoner.objectPropertiesDAG(), p -> !p.isInverse(), ope -> getAtom(x, ope, y), builder);
traverseDAG(reasoner.dataPropertiesDAG(), p -> true, dpe -> getAtom(x, dpe, y), builder);

if (!exactTypeReasoning) {
    // exact: no class ABox dependencies -> rdf:type atoms are never eliminated
    traverseDAG(reasoner.classesDAG(), c -> (c instanceof OClass), c -> getAtom(c, x, () -> y), builder);
}
```

为什么整体跳过而不是只跳过 domain/range body：

- 任何 class 依赖都允许 `removeRedundantAtoms` 用别的原子消除显式 `rdf:type`；
- 即使只为 `OClass` body（命名子类）保留依赖，`?x a A . ?x a B`（`B ⊑ A`）也会删掉 `a:A`，使结果不再要求"显式同时是 A 和 B"，违背严格语义；
- domain/range 只是本次报告触发点，整体跳过 class 依赖能一次性覆盖 domain、range 与命名子类，且实现更简单。

效果：

- exact 时不生成 `(x property y) ⇒ (x a C)` 等 class 依赖，`removeRedundantAtoms` 不会删除任何 `rdf:type` 原子；
- 显式 `a:C` 与属性原子同时存在时，join 后只落到显式 `C` 映射；
- sub-property 依赖保留，属性层级推理不变。

`TreeWitnessRewriter` 构造器增加 `OntopModelSettings` 参数并透传给 `super(...)`；其 `setTBox` 调用 `super.setTBox`，自动继承门禁。

### 4.3 不改动

- `TMappingSaturatorImpl`（Task 2 的饱和层过滤）。
- `DefaultMappingTransformer`、`MappingAssertion`、`MappingImpl`。
- `TreeWitnessRewriterReasoner` / `TreeWitnessGenerator`（见 §7）。

## 5. 代码结构

| 文件 | 改动 |
|---|---|
| `core/model/.../injection/OntopModelSettings.java` | 新增枚举/方法/键 |
| `core/model/.../injection/impl/OntopModelSettingsImpl.java` | 实现解析与默认值 |
| `mapping/core/.../injection/OntopMappingSettings.java` | 移除重复声明（继承） |
| `mapping/core/.../injection/impl/OntopMappingSettingsImpl.java` | 移除重复实现 |
| `mapping/core/.../transformer/impl/TMappingSaturatorImpl.java` | 引用改为 `OntopModelSettings.TypeReasoningMode` |
| `engine/reformulation/core/.../rewriting/impl/DummyRewriter.java` | 注入 `OntopModelSettings`；exact 时跳过 class DAG 依赖构建 |
| `engine/reformulation/core/.../rewriting/impl/TreeWitnessRewriter.java` | 构造器透传 settings |

测试文件：

| 文件 | 用途 |
|---|---|
| `mapping/core/src/test/.../TypeReasoningModeSettingsTest.java` | 引用改为 `OntopModelSettings`；用例不变 |
| `binding/rdf4j/src/test/.../ExactTypeReasoningDomainExactTest.java` | 新增 `testExactWithPropertyAtom`（仅 a/1,a/2；SQL 无 `ontology#b/`） |
| `binding/rdf4j/src/test/.../ExactTypeReasoningDomainFullTest.java` | 新增 `testFullWithPropertyAtom`（4 值） |
| `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/existential.properties` | `ontop.reasoning.type=exact` + `ontop.existentialReasoning=true` |
| `binding/rdf4j/src/test/.../ExactTypeReasoningDomainExistentialTest.java` | 验证 `TreeWitnessRewriter` 路径同样只返回 a/1,a/2 |

## 6. 详细流程

```text
SPARQL: ?x a :A ; :code ?y
  -> QueryRewriter.setTBox(tbox)
       full  : 构建 class DAG 依赖（含 domain/range、命名子类）-> Sigma 含 (code => a:A)
       exact : 跳过 class DAG 依赖 -> Sigma 不含任何 class 依赖
  -> rewrite -> removeRedundantAtoms
       full  : (a:A) 被 (code) 蕴含判为冗余 -> 删除
       exact : (a:A) 保留（无 class 依赖）-> 与 code join
  -> 展开 mapping
       exact : a:A 仅显式映射表
```

## 7. 不纳入范围

- 存在性树见证（existential tree witness）中对 domain/range 的进一步展开：本设计只门禁 `DummyRewriter` 的 ABox 依赖；默认 `ontop.existentialReasoning=false`，且当前问题查询不涉及存在性模式。若后续发现 tree witness 路径仍引入 domain/range 类型，另行设计。
- 本体建模修复（同一属性多 `rdfs:domain`）。本设计不要求改本体，但多 domain 本身违反 OWL 交集语义，建议另作数据治理。
- 修改 full 模式行为。

## 8. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| `TreeWitnessRewriter` 路径仍展开 domain/range | 低 | 中 | 增加 existential 开启的回归测试；若失败另开设计 |
| exact 语义变化（domain/range 不再推断类型） | 中 | 中 | 文档明确 exact = 严格显式类型；默认 full 不变 |
| 配置下沉影响其他 settings 实现 | 低 | 低 | 仅新增方法并提供默认实现；编译与全量测试验证 |
| reformulation cache 命中旧计划 | 中 | 中 | 部署后重启/清缓存 |

## 9. 参考文件

| 文件 | 用途 |
|---|---|
| `engine/reformulation/core/.../rewriting/impl/DummyRewriter.java:75-90` | `setTBox` 构建 ABox 依赖 |
| `engine/reformulation/core/.../rewriting/impl/DummyRewriter.java:109-134` | `removeRedundantAtoms` 删除冗余原子 |
| `engine/reformulation/core/.../rewriting/impl/DummyRewriter.java:136-149` | `traverseDAG`（本设计不改其实现） |
| `engine/reformulation/core/.../rewriting/impl/TreeWitnessRewriter.java:58-86` | 继承 DummyRewriter |
| `core/model/.../injection/OntopModelSettings.java` | 配置下沉目标 |
| `core/model/.../injection/impl/OntopModelSettingsImpl.java` | 解析与默认值 |
| `core/model/.../injection/impl/OntopAbstractModule.java:86` | `OntopModelSettings` 绑定为 settings 实例 |
| `binding/rdf4j/src/test/.../ExactTypeReasoningDomainExactTest.java` | 复现用例 |
| `engine/reformulation/core/src/main/resources/.../reformulation-default.properties:8` | `ontop.existentialReasoning=false` 默认 |
| `engine/reformulation/core/src/main/resources/.../reformulation-default.properties:53` | `ExistentialQueryRewriter → TreeWitnessRewriter` |

## 10. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-11-10 | 初版：exact 在 `DummyRewriter.setTBox` 跳过 domain/range 派生 class 依赖；配置下沉到 `OntopModelSettings` |
