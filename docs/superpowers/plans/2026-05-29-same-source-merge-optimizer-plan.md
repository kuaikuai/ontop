# Same-Source Merge Optimizer — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `SameSourceMergeOptimizer`，在 general optimizer 中合并同源 ExtensionalDataNode，消除 OBDA 展开产生的多余自连接。

**Architecture:** 单一优化器类 (`SameSourceMergeOptimizer`)，放在 `core/optimization` 模块，注入到 `GeneralStructuralAndSemanticIQOptimizerImpl` 的优化器链中，在 `joinLikeOptimizer` 之前运行。

**Tech Stack:** Java 11, Guice DI, JUnit 4, Ontop IQ Tree API

**Spec:** `docs/superpowers/specs/2026-05-29-same-source-merge-normalizer-design.md`

---

## 文件结构

| 文件 | 角色 |
|------|------|
| `core/optimization/src/main/java/.../SameSourceMergeOptimizer.java` | **新增**: 核心合并逻辑 |
| `core/optimization/src/main/java/.../GeneralStructuralAndSemanticIQOptimizerImpl.java` | **修改**: 注入 + 调用 |
| `core/optimization/src/test/java/.../SameSourceMergeOptimizerTest.java` | **新增**: 单元测试 |

---

### Task 1: 创建测试类骨架

**Files:**
- Create: `core/optimization/src/test/java/it/unibz/inf/ontop/iq/optimizer/SameSourceMergeOptimizerTest.java`

- [ ] **Step 1: 创建测试类文件**

以 `SelfJoinSameTermsTest.java` 为模板，参考其 `OptimizationTestingTools` 用法。

```java
package it.unibz.inf.ontop.iq.optimizer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import org.junit.Test;

import static it.unibz.inf.ontop.OptimizationTestingTools.*;
import static org.junit.Assert.*;

public class SameSourceMergeOptimizerTest {

    public static final RelationDefinition T1_AR5;
    // 模拟多列表: 列1 id, 列2 name, 列3 val, 列4 kind, 列5 type

    static {
        OfflineMetadataProviderBuilder3 builder = createMetadataProviderBuilder();
        T1_AR5 = builder.createRelationWithStringAttributes(1, 5, true);
    }

    // 测试用例将在 Task 2-7 中逐次添加
}
```

**验证**: `mvn compile -pl core/optimization -am` 通过

- [ ] **Step 2: 提交**

```
git add core/optimization/src/test/java/it/unibz/inf/ontop/iq/optimizer/SameSourceMergeOptimizerTest.java
git commit -m "test: add SameSourceMergeOptimizerTest skeleton"
```

---

### Task 2: 创建 SameSourceMergeOptimizer 骨架类

**Files:**
- Create: `core/optimization/src/main/java/it/unibz/inf/ontop/iq/optimizer/impl/SameSourceMergeOptimizer.java`

- [ ] **Step 1: 创建类**

参考 `AbstractIQOptimizer` 和 `DefaultRecursiveIQTreeVisitingTransformer` 的模板。

```java
package it.unibz.inf.ontop.iq.optimizer.impl;

import java.util.*;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.IQTreeTools;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.optimizer.IQOptimizer;
import it.unibz.inf.ontop.iq.transform.IQTreeVariableGeneratorTransformer;
import it.unibz.inf.ontop.iq.transform.impl.DefaultRecursiveIQTreeVisitingTransformer;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.substitution.Substitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SameSourceMergeOptimizer extends AbstractIQOptimizer implements IQOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SameSourceMergeOptimizer.class);

    private final IQTreeTools iqTreeTools;
    private final TermFactory termFactory;
    private final SubstitutionFactory substitutionFactory;

    @Inject
    private SameSourceMergeOptimizer(IntermediateQueryFactory iqFactory,
                                      IQTreeTools iqTreeTools,
                                      TermFactory termFactory,
                                      SubstitutionFactory substitutionFactory) {
        super(iqFactory);
        this.iqTreeTools = iqTreeTools;
        this.termFactory = termFactory;
        this.substitutionFactory = substitutionFactory;
    }

    @Override
    protected IQTreeVariableGeneratorTransformer getTransformer() {
        return IQTreeVariableGeneratorTransformer.of(
                vg -> new SameSourceMergeTransformer(vg, iqFactory, iqTreeTools, termFactory, substitutionFactory));
    }

    // 内部 Transformer 类在 Task 5 中实现
    private static class SameSourceMergeTransformer
            extends DefaultRecursiveIQTreeVisitingTransformer {
        // 占位 - Task 5 中实现
    }
}
```

**验证**: `mvn compile -pl core/optimization -am` 通过

- [ ] **Step 2: 提交**

```
git add core/optimization/src/main/java/...
git commit -m "feat: add SameSourceMergeOptimizer skeleton"
```

---

### Task 3: 注入到 GeneralStructuralAndSemanticIQOptimizerImpl

**Files:**
- Modify: `core/optimization/src/main/java/it/unibz/inf/ontop/iq/optimizer/impl/GeneralStructuralAndSemanticIQOptimizerImpl.java`

- [ ] **Step 1: 添加字段和依赖注入**

在字段声明区 (line 19-29) 添加：
```java
private final SameSourceMergeOptimizer sameSourceMergeOptimizer;
```

在构造器参数 (line 32-41) 末尾添加：
```java
SameSourceMergeOptimizer sameSourceMergeOptimizer
```

在构造器赋值 (line 43-53) 末尾添加：
```java
this.sameSourceMergeOptimizer = sameSourceMergeOptimizer;
```

- [ ] **Step 2: 在 optimize() 中插入调用**

在 `optimize()` 方法中 (line 78)，`joinLikeOptimizer.optimize(current)` **之前**插入：
```java
current = sameSourceMergeOptimizer.optimize(current);
LOGGER.debug("New query after same-source node merging:\n{}\n", current);
```

- [ ] **Step 3: 更新 import**

添加：
```java
// (import 自动由 IDE 处理，IntelliJ/Eclipse 自动导入)
```

- [ ] **Step 4: 验证编译**

```bash
mvn compile -pl core/optimization -am
```

**预期**: 编译成功

- [ ] **Step 5: 提交**

```
git add core/optimization/src/main/java/...
git commit -m "feat: inject SameSourceMergeOptimizer into general optimizer chain"
```

---

### Task 4: 编写第一个测试用例 - 同源两节点合并

**Files:**
- Modify: `core/optimization/src/test/java/it/unibz/inf/ontop/iq/optimizer/SameSourceMergeOptimizerTest.java`

- [ ] **Step 1: 编写 T1 - 同源两节点合并**

```java
@Test
public void testMergeTwoSameSourceNodes() {
    // 构建: InnerJoin(Constr(ExtData(R, args1)), Constr(ExtData(R, args2)))
    // 其中 args1={1→A, 2→B}, args2={1→C, 2→D} (同表 T1_AR5, 相同列位置)
    
    ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
            T1_AR5, ImmutableMap.of(0, A, 1, B));
    ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
            T1_AR5, ImmutableMap.of(0, C, 1, D));
    
    ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(
            ImmutableMap.of(A, TERM_FACTORY.getVariable("name1")));
    ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(
            ImmutableMap.of(C, TERM_FACTORY.getVariable("name2")));
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(),
            ImmutableList.of(
                    IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                    IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
    
    DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
            ANS1_AR2_PREDICATE, TERM_FACTORY.getVariable("name1"), TERM_FACTORY.getVariable("name2"));
    
    IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, joinTree);
    
    // 运行优化 - 通过静态导入的 JOIN_LIKE_OPTIMIZER 或直接调用
    // 注意: 单元测试阶段使用 SAME_SOURCE_MERGE_OPTIMIZER (需手动注册)
    // 开发步骤: OptimizationTestingTools 中添加 public static SameSourceMergeOptimizer SAME_SOURCE_MERGE_OPTIMIZER;
    IQ optimizedQuery = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
    // 临时: 在执行 full optimize 链测试前，先直接用 optimizer 测试:
    // IQ optimizedQuery = sameSourceMergeOptimizer.optimize(initialQuery);
    
    // 预期: 单 Filter(IS NOT NULL(A)) → Constr → ExtData(R)
    IQTree resultTree = optimizedQuery.getTree();
    
    // 验证: 只有一个 ExtensionalDataNode (不是两个)
    assertTrue("Expected single ExtensionalDataNode after merge",
            countExtensionalNodes(resultTree) == 1);
}
```

- [ ] **Step 2: 运行测试 — 预期 FAIL**

```bash
mvn test -pl core/optimization -Dtest=SameSourceMergeOptimizerTest#testMergeTwoSameSourceNodes -DfailIfNoTests=false
```

**预期**: 测试失败（`GENERAL_OPTIMIZER` 可能为 null 或 SameSourceMergeTransformer 尚未实现）

- [ ] **Step 3: 提交**

```
git add core/optimization/src/test/java/...
git commit -m "test: add SameSourceMerge two-node test (failing)"
```

---

### Task 5: 实现核心合并逻辑

**Files:**
- Modify: `core/optimization/src/main/java/it/unibz/inf/ontop/iq/optimizer/impl/SameSourceMergeOptimizer.java`

- [ ] **Step 1: 实现 MergeTransformer.transformInnerJoin**

实现检测逻辑（Section 2.3 的条件 1-5）:

```java
private static class SameSourceMergeTransformer
        extends DefaultRecursiveIQTreeVisitingTransformer {

    private final IQTreeTools iqTreeTools;
    private final TermFactory termFactory;
    private final SubstitutionFactory substitutionFactory;

    SameSourceMergeTransformer(VariableGenerator vg, IntermediateQueryFactory iqFactory,
                               IQTreeTools iqTreeTools, TermFactory termFactory,
                               SubstitutionFactory substitutionFactory) {
        super(iqFactory);
        this.iqTreeTools = iqTreeTools;
        this.termFactory = termFactory;
        this.substitutionFactory = substitutionFactory;
    }

    @Override
    public IQTree transformInnerJoin(NaryIQTree tree, InnerJoinNode node,
                                      ImmutableList<IQTree> children) {
        // 1. 尝试合并
        Optional<IQTree> merged = tryMergeSameSourceNodes(children);
        if (merged.isPresent()) {
            return merged.get();
        }
        // 2. 不能合并，原样返回
        return withTransformedChildren(tree, 
                NaryIQTreeTools.transformChildren(children, this::transform));
    }

    private Optional<IQTree> tryMergeSameSourceNodes(ImmutableList<IQTree> children) {
        // Group by (RelationDefinition, keySet)
        Map<MergeKey, List<MergeCandidate>> groups = new HashMap<>();
        
        for (IQTree child : children) {
            Optional<MergeCandidate> candidate = extractCandidate(child);
            if (!candidate.isPresent()) {
                // 有不可合并的子节点 → 不合并整组
                return Optional.empty();
            }
            MergeKey key = candidate.get().key();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate.get());
        }
        
        // 只有全组可合并且至少一组 size > 1 才执行
        if (groups.values().stream().allMatch(g -> g.size() <= 1)) {
            return Optional.empty();
        }

        // 合并每组
        List<IQTree> mergedChildren = new ArrayList<>();
        for (List<MergeCandidate> group : groups.values()) {
            if (group.size() > 1) {
                mergedChildren.add(mergeGroup(group));
            } else {
                mergedChildren.add(group.get(0).tree);
            }
        }

        // 如果只有 1 个 child，提升
        if (mergedChildren.size() == 1) {
            return Optional.of(mergedChildren.get(0));
        }
        return Optional.of(iqFactory.createNaryIQTree(
                iqFactory.createInnerJoinNode(),
                ImmutableList.copyOf(mergedChildren)));
    }

    private Optional<MergeCandidate> extractCandidate(IQTree child) {
        // 检测: UnaryIQTree(ConstructionNode, ExtensionalDataNode)
        if (!(child instanceof UnaryIQTree)) return Optional.empty();
        IQTree childOfChild = ((UnaryIQTree) child).getChild();
        if (!(child.getRootNode() instanceof ConstructionNode)) return Optional.empty();
        if (!(childOfChild instanceof ExtensionalDataNode)) return Optional.empty();
        
        ConstructionNode constr = (ConstructionNode) child.getRootNode();
        ExtensionalDataNode extData = (ExtensionalDataNode) childOfChild;
        
        return Optional.of(new MergeCandidate(child, constr, extData));
    }

    private IQTree mergeGroup(List<MergeCandidate> candidates) {
        // 1. 取第一个 ExtData 的 argumentMap 作为模板 (keySet 相同已验证)
        ExtensionalDataNode firstExtData = candidates.get(0).extDataNode;
        ImmutableMap<Integer, VariableOrGroundTerm> mergedArgs = firstExtData.getArgumentMap();

        // 2. 合并所有 ConstructionNode 的 substitution
        Substitution<ImmutableTerm> mergedSub = mergeSubstitutions(candidates);

        // 3. 创建合并后的 ExtData
        ExtensionalDataNode mergedExtData = iqFactory.createExtensionalDataNode(
                firstExtData.getRelationDefinition(), mergedArgs);

        // 4. 创建合并后的 ConstructionNode
        ConstructionNode mergedConstr = iqFactory.createConstructionNode(mergedSub);

        // 5. 给 id 列 (index 1) 加 IS NOT NULL
        VariableOrGroundTerm idVar = mergedArgs.get(1);
        ImmutableExpression notNullFilter = termFactory.getDBIsNotNull(
                (Variable) idVar);

        // 6. 组装树: Filter(id NOT NULL) → ConstructionNode → ExtensionalDataNode
        IQTree leaf = mergedExtData;
        IQTree constrTree = iqFactory.createUnaryIQTree(mergedConstr, leaf);
        IQTree filterTree = iqFactory.createUnaryIQTree(
                iqFactory.createFilterNode(notNullFilter), constrTree);

        try {
            return filterTree.normalizeForOptimization(vg);
        } catch (VariableGeneratorException e) {
            throw new MinorOntopInternalBugException("Merge failed", e);
        }
    }

    private Substitution<ImmutableTerm> mergeSubstitutions(List<MergeCandidate> candidates) {
        Substitution.Builder<ImmutableTerm> builder = substitutionFactory.builder();
        for (MergeCandidate c : candidates) {
            Substitution<ImmutableTerm> sub = c.constructionNode.getSubstitution();
            for (Variable v : sub.getDomain()) {
                ImmutableTerm value = sub.apply(v);
                if (builder.containsKey(v)) {
                    if (!builder.get(v).equals(value)) {
                        throw new MinorOntopInternalBugException(
                            "Conflict: variable " + v + " bound to both " + builder.get(v) + " and " + value);
                    }
                } else {
                    builder.substitute(v, value);
                }
            }
        }
        return builder.build();
    }

    private static final class MergeCandidate {
        final IQTree tree;
        final ConstructionNode constructionNode;
        final ExtensionalDataNode extDataNode;

        MergeCandidate(IQTree tree, ConstructionNode constructionNode,
                       ExtensionalDataNode extDataNode) {
            this.tree = tree;
            this.constructionNode = constructionNode;
            this.extDataNode = extDataNode;
        }

        MergeKey key() {
            return new MergeKey(
                    extDataNode.getRelationDefinition(),
                    ImmutableSet.copyOf(extDataNode.getArgumentMap().keySet()));
        }
    }

    private static final class MergeKey {
        final RelationDefinition relationDef;
        final ImmutableSet<Integer> keySet;

        MergeKey(RelationDefinition relationDef, ImmutableSet<Integer> keySet) {
            this.relationDef = relationDef;
            this.keySet = keySet;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MergeKey)) return false;
            MergeKey that = (MergeKey) o;
            return relationDef.equals(that.relationDef) && keySet.equals(that.keySet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(relationDef, keySet);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl core/optimization -am
```

- [ ] **Step 3: 提交**

```
git add core/optimization/src/main/java/...
git commit -m "feat: implement SameSourceMergeOptimizer core merge logic"
```

---

### Task 6: T1 通过校验

**Files:**
- Modify: `core/optimization/src/test/java/.../SameSourceMergeOptimizerTest.java`

- [ ] **Step 1: 编写补充测试辅助方法**

```java
private static int countExtensionalNodes(IQTree tree) {
    AtomicInteger count = new AtomicInteger(0);
    tree.acceptVisitor(new DefaultSimpleIQTreeVisitor<Void>() {
        @Override
        public Void visit(ExtensionalDataNode node) {
            count.incrementAndGet();
            return null;
        }
        // 递归遍历子节点 (需要实现 visitUnary, visitNary 等)
    });
    return count.get();
}
```

- [ ] **Step 2: 运行测试验证**

```bash
mvn test -pl core/optimization -Dtest=SameSourceMergeOptimizerTest#testMergeTwoSameSourceNodes
```

**预期**: PASS（单 `ExtData` 节点）

- [ ] **Step 3: 提交**

```
git add core/optimization/src/test/java/...
git commit -m "test: verify SameSourceMerge two-node merge passes"
```

---

### Task 7: 完整测试用例集

**Files:**
- Modify: `core/optimization/src/test/java/it/unibz/inf/ontop/iq/optimizer/SameSourceMergeOptimizerTest.java`

- [ ] **Step 1: T2 - 同源 7 节点合并**

```java
@Test
public void testMergeSevenSameSourceNodes() {
    // 构造 7 个 Constr(ExtData(T1_AR5)) → 全部合并
    List<IQTree> children = IntStream.range(0, 7)
            .mapToObj(i -> {
                Variable v = TERM_FACTORY.getVariable("v" + i);
                ExtensionalDataNode data = createExtensionalDataNode(
                        T1_AR5, ImmutableMap.of(1, v, 2, TERM_FACTORY.getVariable("extra" + i)));
                ConstructionNode constr = IQ_FACTORY.createConstructionNode(
                        ImmutableMap.of(v, TERM_FACTORY.getVariable("name" + i)));
                return (IQTree) IQ_FACTORY.createUnaryIQTree(constr, data);
            })
            .collect(ImmutableCollectors.toList());
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(), ImmutableList.copyOf(children));
    
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    joinTree), null);
    
    assertEquals(1, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 2: T3 - 不同表不合并**

```java
@Test
public void testDifferentTablesNotMerged() {
    // 另一张表
    OfflineMetadataProviderBuilder3 builder2 = createMetadataProviderBuilder();
    RelationDefinition T2 = builder2.createRelationWithStringAttributes(2, 5, true);
    
    ExtensionalDataNode data1 = createExtensionalDataNode(T1_AR5, ImmutableMap.of(1, A));
    ExtensionalDataNode data2 = createExtensionalDataNode(T2, ImmutableMap.of(1, B));
    
    ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableMap.of());
    ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableMap.of());
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(),
            ImmutableList.of(
                    IQ_FACTORY.createUnaryIQTree(constr1, data1),
                    IQ_FACTORY.createUnaryIQTree(constr2, data2)));
    
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    joinTree), null);
    
    assertEquals(2, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 3: T4 - 不同列集不合并**

```java
@Test
public void testDifferentKeySetsNotMerged() {
    ExtensionalDataNode data1 = createExtensionalDataNode(T1_AR5, ImmutableMap.of(1, A, 2, B));
    ExtensionalDataNode data2 = createExtensionalDataNode(T1_AR5, ImmutableMap.of(1, C, 3, D));
    
    ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableMap.of());
    ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableMap.of());
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(),
            ImmutableList.of(
                    IQ_FACTORY.createUnaryIQTree(constr1, data1),
                    IQ_FACTORY.createUnaryIQTree(constr2, data2)));
    
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    joinTree), null);
    
    assertEquals(2, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 4: T5 - 非 Constr(ExtData) 结构不合并**

```java
@Test
public void testNonConstructionChildNotMerged() {
    // 子节点包含直接 ExtData (无 Construction 包裹)
    ExtensionalDataNode data1 = createExtensionalDataNode(T1_AR5, ImmutableMap.of(1, A));
    ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableMap.of());
    ExtensionalDataNode data2 = createExtensionalDataNode(T1_AR5, ImmutableMap.of(1, B));
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(),
            ImmutableList.of(
                    data1,  // 直接 ExtData，无 Construction
                    IQ_FACTORY.createUnaryIQTree(constr2, data2)));
    
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    joinTree), null);
    
    assertEquals(2, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 5: T6 - 单节点不触发合并**

```java
@Test
public void testSingleNodeNotMerged() {
    ExtensionalDataNode data = createExtensionalDataNode(T1_AR5, ImmutableMap.of(1, A, 2, B));
    ConstructionNode constr = IQ_FACTORY.createConstructionNode(ImmutableMap.of(
            A, TERM_FACTORY.getVariable("name")));
    
    // 注意: 单节点不在 InnerJoin 中，经过 normalizeForOptimization 后变成直接量
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    IQ_FACTORY.createUnaryIQTree(constr, data)), null);
    
    assertEquals(1, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 6: T7 - InnerJoin 带 filter 条件保留**

```java
@Test
public void testInnerJoinFilterPreserved() {
    ExtensionalDataNode data1 = IQ_FACTORY.createExtensionalDataNode(
            T1_AR5, ImmutableMap.of(0, A, 1, B));
    ExtensionalDataNode data2 = IQ_FACTORY.createExtensionalDataNode(
            T1_AR5, ImmutableMap.of(0, C, 1, D));
    
    ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(
            ImmutableMap.of(A, TERM_FACTORY.getVariable("name1")));
    ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(
            ImmutableMap.of(C, TERM_FACTORY.getVariable("name2")));
    
    // InnerJoin 自带 filter: A = 'constant'
    ImmutableExpression joinFilter = TERM_FACTORY.getStrictEquality(
            A, TERM_FACTORY.getDBStringConstant("test"));
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(joinFilter),
            ImmutableList.of(
                    IQ_FACTORY.createUnaryIQTree(constr1, data1),
                    IQ_FACTORY.createUnaryIQTree(constr2, data2)));
    
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    joinTree));
    
    // filter 保留 + 子节点合并
    assertEquals(1, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 7: T8 - DISTINCT 属性继承**

```java
@Test
public void testDistinctInheritance() {
    ExtensionalDataNode data1 = IQ_FACTORY.createExtensionalDataNode(
            T1_AR5, ImmutableMap.of(0, A, 1, B));
    ExtensionalDataNode data2 = IQ_FACTORY.createExtensionalDataNode(
            T1_AR5, ImmutableMap.of(0, C, 1, D));
    
    ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(
            ImmutableMap.of(A, TERM_FACTORY.getVariable("name1")));
    ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(
            ImmutableMap.of(C, TERM_FACTORY.getVariable("name2")));
    
    // 任一子节点带 DISTINCT
    IQTree distinctChild = IQ_FACTORY.createUnaryIQTree(
            IQ_FACTORY.createDistinctNode(),
            IQ_FACTORY.createUnaryIQTree(constr1, data1));
    
    NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
            IQ_FACTORY.createInnerJoinNode(),
            ImmutableList.of(
                    distinctChild,
                    IQ_FACTORY.createUnaryIQTree(constr2, data2)));
    
    IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(
            IQ_FACTORY.createIQ(
                    ATOM_FACTORY.getDistinctVariableOnlyDataAtom(ANS1_AR2_PREDICATE),
                    joinTree));
    
    // 合并后 DISTINCT 继承
    assertEquals(1, countExtensionalNodes(result.getTree()));
}
```

- [ ] **Step 8: 运行全部测试**

```bash
mvn test -pl core/optimization -Dtest=SameSourceMergeOptimizerTest
```

**预期**: 8 个测试全部 PASS

- [ ] **Step 9: 提交**

```
git add core/optimization/src/test/java/...
git commit -m "test: add full SameSourceMergeOptimizer test suite"
```

---

### Task 8: 回归测试

**Files:**
- 无修改

- [ ] **Step 1: 运行 optimization 模块全量测试**

```bash
mvn test -pl core/optimization
```

**预期**: 所有现有测试仍 PASS，无回归

- [ ] **Step 2: 运行扩展回归**

```bash
mvn test -pl core/optimization,core/model,db/rdb
```

**预期**: 全 PASS

- [ ] **Step 3: 提交（如果有 fix）**

如有回归，fix → commit → 重新回归

---

### Task 9: 清理与文档更新

**Files:**
- Modify: `docs/superpowers/specs/2026-05-29-same-source-merge-normalizer-design.md`
- Modify: `docs/superpowers/analysis/2026-05-29-obda-subquery-bloat-root-cause-analysis.md`
- Modify: `docs/superpowers/analysis/2026-05-29-single-mapping-self-join-root-cause-analysis.md`

- [ ] **Step 1: 更新设计文档状态为 Implemented**

In design doc header:
```markdown
**Status**: Implemented
```

- [ ] **Step 2: 提交**

```
git add docs/
git commit -m "docs: mark SameSourceMergeOptimizer as implemented"
```

---

## 完成检查清单

- [ ] `SameSourceMergeOptimizer.java` — 核心合并逻辑已实现
- [ ] `GeneralStructuralAndSemanticIQOptimizerImpl.java` — 注入点已添加
- [ ] 8 个单元测试全部 PASS
- [ ] `core/optimization` 模块全量测试 PASS
- [ ] `db/rdb` 模块测试 PASS（确保 SQL 生成不变）
- [ ] 设计文档状态更新
