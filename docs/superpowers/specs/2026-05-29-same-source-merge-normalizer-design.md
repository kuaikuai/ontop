# Same-Source Merge Optimizer — 设计方案

**Date**: 2026-05-29
**Status**: Implemented
**Author**: Sisyphus

## 1. 问题

OBDA 查询中，无论单个或多个 mapping，SPARQL 的共享主语 triple pattern 展开后产生 **N 个完全相同的子查询**通过 `id = id` 自连接。

### 1.1 表象

```sql
-- 场景1: 15 个独立 mapping → 16 个子查询
FROM (SELECT DISTINCT metric_id, metric_name FROM t) v12,
     (SELECT DISTINCT metric_id, metric_name FROM t) v13,  -- 重复!
     ...

-- 场景2: 1 个合并 mapping → 7 个子查询自连接
FROM (SELECT all_cols FROM t ...) v1,
     (SELECT all_cols FROM t ...) v2,
     (SELECT all_cols FROM t ...) v3,  -- 全部相同!
     ...
WHERE CAST(v1.id) = CAST(v2.id) AND CAST(v2.id) = CAST(v3.id) ...
```

### 1.2 根因

SPARQL `;` 共享主语语法解析为 N 个独立 `StatementPattern` → 每 pattern 翻译为 `IntensionalDataNode` → 展开时分别查找 mapping 定义 → 产生 N 份相同的展开结果 → `InnerJoin` 连接 → N 子查询。

传统 SelfJoin 优化器 (`SelfJoinSameTermIQOptimizer`, `SelfJoinUCIQOptimizer`) 全部在 `ExtensionalDataNode` 层操作，且依赖 `Variable.equals()` 对象等价性判断。但每次展开都调用 `getFreshInstance()` 重命名所有变量，导致 `argumentMap.get(i).equals(otherArgumentMap.get(i))` 永远为 false。

**关键代码**:
- `AbstractQueryMergingTransformer.java:62` — `iqTreeTools.getFreshInstance(definition, variableGenerator)`
- `AbstractBelowDistinctInnerJoinTransformer.java:83` — `argumentMap.get(i).equals(otherArgumentMap.get(i))`

### 1.3 术语

| 术语 | 说明 |
|------|------|
| **同源节点** | 同一 OBDA mapping 展开得到的多个 `ExtensionalDataNode`，引用同一物理表且列位置相同 |
| **合并（Merge）** | 将同源节点压缩为单一节点，合并所有变量绑定 |
| **Normalizer/Optimizer** | 本方案实现为 `IQOptimizer`，在 `GeneralStructuralAndSemanticIQOptimizerImpl` 链中运行 |

---

## 2. 设计

### 2.1 核心思路

新增一个 `IQOptimizer`（**按模块边界要求，放在 `core/optimization` 模块**，不是 `db/rdb`），注入到 `GeneralStructuralAndSemanticIQOptimizerImpl` 的优化器链中。在 `joinLikeOptimizer`（含 SelfJoin）之前运行，检测并合并同源展开结果。

### 2.2 模块位置与注入

**新增文件**（单一文件，无新增 Maven 模块）:
```
core/optimization/src/main/java/it/unibz/inf/ontop/iq/optimizer/impl/
└── SameSourceMergeOptimizer.java
```

**修改文件**:
```
core/optimization/src/main/java/it/unibz/inf/ontop/iq/optimizer/impl/
└── GeneralStructuralAndSemanticIQOptimizerImpl.java  ← 注入 + 调用
```

**注入点**: `GeneralStructuralAndSemanticIQOptimizerImpl` 构造器中添加 `SameSourceMergeOptimizer` 参数，在 `optimize()` 方法中、`joinLikeOptimizer` 之前调用：

```java
// GeneralStructuralAndSemanticIQOptimizerImpl
private final SameSourceMergeOptimizer sameSourceMergeOptimizer;  // 新增字段

@Override
public IQ optimize(IQ query, ...) {
    IQ liftedQuery = bindingLiftOptimizer.optimize(query);
    // ...

    IQ current = pushedIntoDistinct;
    do {
        // ... authorization, context evaluation ...

        // ********** 新增: 合并同源节点 **********
        current = sameSourceMergeOptimizer.optimize(current);

        current = joinLikeOptimizer.optimize(current);  // SelfJoin 等

        // ... lensUnfolder, flattenLifter ...
    } while (true);
    // ...
}
```

**理由**: 
- `core/optimization` 模块已有所有 IQ 优化器，且 `GeneralStructuralAndSemanticIQOptimizerImpl` 已经在 `unfolding` 之后运行（由 `QuestQueryProcessor.java:108-117` 确保）
- 不修改 `QuestQueryProcessor`，不引入循环依赖
- 对所有 dialect 生效（不是 dialect-specific）

### 2.3 检测条件

一个 `InnerJoin` 的子节点 `children[i]` 被判定为"可合并"，当且仅当 **全部满足**：

1. `children[i]` 是 `UnaryIQTree`，根节点是 `ConstructionNode`
2. `children[i]` 的子节点是 `ExtensionalDataNode`
3. 两个 `ExtensionalDataNode` 的 `RelationDefinition.equals()` 返回 true
4. 两个 `ExtensionalDataNode` 覆盖**同一组列位置**（`argumentMap.keySet()` 相同）

**排除条件（bail-out）**:

| 条件 | 行为 |
|------|------|
| 子节点结构不是 `Constr(ExtData)` | 跳过此节点，保留原 InnerJoin |
| 两个 ExtData 的 `RelationDefinition` 不同 | 不在同一组 |
| 两个 ExtData 的 `argumentMap.keySet()` 不同 | 不在同一组 |
| InnerJoin 有非平凡的 filter condition | 保留 filter，合并子节点后重新评估 |
| 组内只有一个节点 | 不合并 |

### 2.4 合并算法

```
输入: InnerJoin(children=[
    Constr₁(sub₁, ExtData(R, args₁)), 
    Constr₂(sub₂, ExtData(R, args₂)),
    ...,
    Constr_K(sub_K, ExtData(R, args_K))
])
其中所有 ExtData 的 RelationDefinition.equals() == true
     所有 ExtData 的 args.keySet() 相同

Step 1: 提取统一的 ID 列变量
        选取第一个 ExtData 的 id 列变量作为 canonical_id

Step 2: 构建合并后的 Substitution
        mergedSub = sub₁ ⊕ sub₂ ⊕ ... ⊕ sub_K
        其中 ⊕ 是 substitution 组合:
          - 如果 key 在所有 subᵢ 中 value 相同，保留一个
          - 如果 key 只在一个 subᵢ 中出现，保留
          - 如果 key 在两个 subᵢ 中有不同 value → 抛出异常
            (不应出现，因为每个变量由不同 triple pattern 唯一绑定)

Step 3: 构建合并后的 ExtensionalDataNode
        mergedArgs = Union(args₁, args₂, ..., args_K)
        因为 keySet 相同（已由检测保证），选取第一个 args 作为模板
        其中需要的变量来自 mergedSub 的 domain

Step 4: 构建合并后的树
        mergedTree = 
          Filter(id_col IS NOT NULL)           ← 等价于原 id=id 连接
            → ConstructionNode(mergedSub)
              → mergedExtData(R, mergedArgs)

Step 5: 替换 InnerJoin
        如果组外还有其他 children:
          mergedChildren = [mergedTree] + otherChildren
          return InnerJoin(mergedChildren)
        如果只有这组:
          return mergedTree  // 提升为单子节点树

Step 6: normalizeForOptimization
        对合并结果调用 normalizeForOptimization(variableGenerator)
```

### 2.5 边界处理

#### 2.5.1 LATERAL VIEW explode 的处理

当 `ExtensionalDataNode` 的父节点链中存在 `FlattenNode`:

**检测**: `child.getRootNode() instanceof ConstructionNode && child.getChild().getRootNode() instanceof FlattenNode`

**处理**: 合并时只保留一个 `FlattenNode`，将 flatten 应用到合并后的单一 `ExtData` 上。不在合并后的 `ExtData` 上重复 EXPLODE。

#### 2.5.2 DISTINCT 的传递

如果任何子节点上有 `DISTINCT` 属性（来自 `DistinctNode`），合并后的节点继承 DISTINCT。

#### 2.5.3 SUBSTITUTION 冲突规则

| 情况 | 行为 |
|------|------|
| key 相同, value 相同 | 保留一个 |
| key 只在一个 ConstrNode 出现 | 保留 |
| key 相同, value 不同 | 不可能发生（每个变量由不同 triple pattern 唯一绑定）；如果发生，抛出 `MinorOntopInternalBugException` |
| key 相同, value 是不同类型 | 不应该发生；如果发生，抛出异常 |

#### 2.5.4 跨 mapping 来源

不同 mapping（不同 `RDFAtomPredicate` + IRI）产生的同表同列 ExtData → 理论上仍可合并，但为了安全，首次实现限定为：只合并当它们来自 **相同的 `RelationDefinition` 对象引用** 时（通过 `==` 确认是同一对象）。

---

## 3. 代码结构

### 3.1 核心类

```java
@Singleton
public class SameSourceMergeOptimizer extends AbstractIQOptimizer 
        implements IQOptimizer {

    private final IntermediateQueryFactory iqFactory;
    private final IQTreeTools iqTreeTools;
    private final TermFactory termFactory;
    private final SubstitutionFactory substitutionFactory;

    @Inject
    private SameSourceMergeOptimizer(IntermediateQueryFactory iqFactory,
                                      IQTreeTools iqTreeTools,
                                      TermFactory termFactory,
                                      SubstitutionFactory substitutionFactory) {
        super(iqFactory);
        // ...
    }

    @Override
    protected IQTreeVariableGeneratorTransformer getTransformer() {
        return vg -> new MergeTransformer(vg, ...);
    }

    private static class MergeTransformer 
            extends DefaultRecursiveIQTreeVisitingTransformer {
        
        @Override
        public IQTree transformInnerJoin(NaryIQTree tree, InnerJoinNode node,
                                          ImmutableList<IQTree> children) {
            return tryMergeSameSourceNodes(children, node, tree.getVariables());
        }
        // ... 合并逻辑见 2.4
    }
}
```

### 3.2 修改文件（仅 1 处注入）

```
core/optimization/src/main/java/.../GeneralStructuralAndSemanticIQOptimizerImpl.java
```
- 新字段: `private final SameSourceMergeOptimizer sameSourceMergeOptimizer;`
- 构造器添加参数
- `optimize()` 方法中，在 `joinLikeOptimizer.optimize(current)` 之前插入:
  ```java
  current = sameSourceMergeOptimizer.optimize(current);
  ```

---

## 4. 测试策略

### 4.1 单元测试

| 测试 | 输入 | 预期 |
|------|------|------|
| **同源 2 节点合并** | `InnerJoin(2×Constr(ExtData(R)))` | 单 `Filter(id⊥)→Constr(ExtData(R))`，两个变量都保留 |
| **同源 7 节点合并** | `InnerJoin(7×Constr(ExtData(R)))` | 单 `Filter(id⊥)→Constr(ExtData(R))` |
| **不同表不合并** | `Constr(ExtData(R₁))` + `Constr(ExtData(R₂))` | 原样 |
| **不同列集不合并** | 两个 ExtData 的 `keySet()` 不同 | 原样 |
| **非 Constr(ExtData) 不合并** | 子节点有其他结构 | 原样 |
| **含 Flatten** | 同源 + LATERAL VIEW | 单表 + 单 Flatten |
| **含 DISTINCT** | 任一子节点有 DistinctNode | 合并后继承 DISTINCT |
| **InnerJoin 有 filter** | filter 条件保留 | filter + 合并后子节点 |

### 4.2 集成测试

| 场景 | SPARQL | 验证 |
|------|--------|------|
| 场景 1: 多 mapping | 15+ triple pattern | SQL 子查询数 ≤ 3 |
| 场景 2: 单 mapping | 7 property | SQL 子查询数 ≤ 2 |

### 4.3 回归测试（必须保持不变的场景）

| 场景 | 验证 |
|------|------|
| 跨表 JOIN | SQL 不变化 |
| 含 FILTER 条件 | FILTER 完整保留 |
| 含 AGGREGATE | 分组聚合不受影响 |
| 含 ORDER BY | 排序不受影响 |
| 含 OFFSET/LIMIT | 分页不受影响 |
| 含 OPTIONAL | LEFT JOIN 不受影响 |
| 非同源表自连接（不同列集） | 保留自连接 |

```bash
mvn test -pl core/optimization
mvn test -pl db/rdb
mvn test -pl test/lightweight-tests
```

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 变量冲突 | 低 | 中 | 展开时 `getFreshInstance()` 确保变量唯一 |
| Flatten 重复 | 低 | 低 | 合并后只保留一个 FlattenNode |
| IS NOT NULL 丢失 | 低 | 高 | 合并后显式添加 |
| 与 SelfJoin 冲突 | 极低 | 低 | 本 optimizer 先运行，SelfJoin 收合并后树 |
| 性能影响 | 极低 | 低 | 每组一次 `keySet().equals()`，O(n) 扫描 |
| 模块循环依赖 | 无 | — | **已修正**：仅放在 `core/optimization` |

---

## 6. 参考文件

| 文件 | 用途 |
|------|------|
| `AbstractBelowDistinctInnerJoinTransformer.java:70-85` | SelfJoin 检测逻辑 |
| `AbstractSelfJoinSimplifier.java` | 分组模式参考 |
| `GeneralStructuralAndSemanticIQOptimizerImpl.java` | **注入点** |
| `QuestQueryProcessor.java:108-117` | 展开后顺序确认 |
| `TwoPhaseQueryUnfolder.java` | 展开完成触发点 |
| `RDF4JTupleExprTranslator.java:674` | 源头 triple pattern→IntensionalDataNode |
| `DefaultMappingTransformer.java:118-131` | mapping 索引构建 |
| `IQTree2SelectFromWhereConverterImpl.java:45-50` | IQ 树→SQL 代数 |

---

## 7. 不纳入范围

- 修改 OBDA 映射文件
- 修改 SPARQL 解析器（RDF4JTupleExprTranslator）
- 修改现有 SelfJoin 优化器
- 添加 SQL 文本后处理（CTE 折叠）
- 新 DialectExtraNormalizer（非 dialect-specific）

---

## 8. 分析文档关联

- `docs/superpowers/analysis/2026-05-29-obda-subquery-bloat-root-cause-analysis.md`
- `docs/superpowers/analysis/2026-05-29-single-mapping-self-join-root-cause-analysis.md`

---

## 9. 修订记录

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-05-29 | 初始版本 |
| v1.1 | 2026-05-29 | Momus 评审修订: 模块边界→core/optimization、合并语义(id IS NOT NULL)、排除条件、substitution 冲突规则、回归测试扩展 |

---

## 10. 实现细节补充（2026-06-02）

> 以下内容为实现后发现的设计要点，未在原方案中预见到。

### 10.1 为什么要求"共同位置"（Common Positions）

合并两个 children 的本质是让它们共享**同一个 SQL 查询**。如果它们引用的列完全不一样（position 不重叠），合并后会导致：

- **列别名冲突**：同一 position 在不同 child 有不同语义，合并后无法确定绑定到哪个变量
- **SQL 语义错误**：mergedExtData 的 `argumentMap` 无法同时满足两个 children 的不同需求

**具体例子**：
```
Child1: position 6 → var A, position 7 → var B
Child2: position 8 → var C, position 9 → var D
        ↑
        无共同位置！position 6,7,8,9 全部互不相交
```

若强行合并，mergedExtData 的 position 6 应该绑定 A 还是 C？语义不清。

### 10.2 两阶段合并策略（Strict vs Relaxed Merge）

实现中发现，仅用 strict merge（要求 keySet 完全相同）不够灵活。扩展为两阶段：

**Phase 1: Strict Merge**（严格合并）
- 分组依据：`(RelationDefinition, keySet)` — 必须**完全相同**
- 要求：所有 children 的 `argumentMap.keySet()` 一模一样
- 适用场景：OBDA 展开的典型情况（多 mapping 展开，每个子查询选择相同列）

**Phase 2: Relaxed Merge**（宽松合并）
- 分组依据：仅 `RelationDefinition` — keySet 可以不同
- 触发条件：
  1. 共同位置（keySet 交集）非空
  2. 共同位置的变量通过 join condition **等价**（如 `A = C`）
  3. 非共同位置**互不相交**（无 position 冲突）
- 适用场景：同 IRI 但选择不同列的 children（如 `analysisSummaryAbnormalRuleDesc` position 6 和 `analysisSummaryPainPointName` position 8）

### 10.3 变量等价检测

通过 join condition 中的等值表达式建立变量等价图：

```java
// 从 InnerJoinNode 的 filter condition 提取
condition.get().flattenAND()
    .filter(expr -> expr.isVar2VarEquality())  // DBStrictEqFunctionSymbol
    .forEach(expr -> {
        Variable v1 = (Variable) expr.getTerm(0);
        Variable v2 = (Variable) expr.getTerm(1);
        // v1 = v2
    });

// 传递闭包：A=C 且 C=D → A=D
computeConnectedComponent(start, directPairs);  // DFS
```

**示例**：
```
JOIN STRICT_EQ2(CAST(v1.id AS CHAR), CAST(v2.id AS CHAR))
    STRICT_EQ2(CAST(v2.id AS CHAR), CAST(v3.id AS CHAR))
    ...
```
→ 提取出 `v1 = v2, v2 = v3` → 传递闭包得 `v1 = v2 = v3`

### 10.4 `canMergeSafely()` 的三个安全检查

```java
// 检查1: 必须有共同位置
commonPositions = intersection(args₁.keySet, args₂.keySet, ...)
if (commonPositions.isEmpty()) return false;

// 检查2: 共同位置必须使用等价变量
// position P 在 child0 映射到 A，在 child1 映射到 C
// 只有 A = C（通过 join condition）时才安全合并
if (!areVariablesEquivalent(A, C, equivalenceClasses)) return false;

// 检查3: 非共同位置必须互不相交
// position P 在 child0 和 child1 都出现，但不在 commonSet 中
// → 意味着两个 child 都选了同一列用于不同目的，不能合并
```

### 10.5 为什么之前 Ontop 没有这个优化

| 原因 | 说明 |
|------|------|
| **特定场景触发** | 用户的 SPARQL 查询使用 `;` 共享主语语法 + OBDA mapping 展开，触发 N 个相同子查询自连接 |
| **现有 SelfJoin 不适用** | 现有优化器在 `ExtensionalDataNode` 级别工作，依赖 `Variable.equals()` 对象相等；展开时每次调用 `getFreshInstance()` 重命名变量，导致永远不相等 |
| **设计优先级** | Ontop 已有 SelfJoinUCIQOptimizer、SelfJoinSameTermIQOptimizer 等，但它们处理的是"同一变量在两个 ExtData 中出现"的情况，不处理"不同变量绑定到相同 id 列"的情况 |
| **你自己实现** | SameSourceMergeOptimizer 是你在 2026-06-01 新实现的功能，之前不存在于 Ontop 代码库 |

### 10.6 Git 历史（关键 Commits）

| Commit | 日期 | 消息 |
|--------|------|------|
| `53b42a41c1` | 2026-06-01 | feat: add SameSourceMergeOptimizer skeleton |
| `1bd492950c` | 2026-06-01 | feat: implement SameSourceMergeOptimizer core merge logic |
| `c3acfabe00` | 2026-06-01 | fix: remove IS_NOT_NULL filter from merge result |
| `9a101d9c2c` | 2026-06-01 | fix: relax SameSourceMergeOptimizer to allow merge when common positions use equivalent variables |
| `bf34772007` | 2026-06-01 | fix: improve variable equivalence detection to handle more equality formats |
| `5ef8f57721` | 2026-06-01 | fix: extract variable names from qualified expressions |
| `e49b23186b` | 2026-06-01 | fix: improve variable extraction from equality expressions |
| `11c5134d57` | 2026-06-01 | refactor: rewrite variable equivalence detection using type-safe expression AST |
| `9f09ea432a` | 2026-06-01 | refactor: improve SameSourceMergeOptimizer merge logic robustness |

**注意**：所有 commits 都在同一天（2026-06-01），全部由同一作者实现。这是**新功能**，不是已有代码的 bug 修复。
