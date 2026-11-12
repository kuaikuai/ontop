# 实体间层级展开（静态化路径）— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Ontop 内核中新增"实体间层级展开"能力：把 `?s :p* ?o` + `path:maxDepth` 翻译成一条链式派生表 SQL，经 `PathRelationDefinition` 注入，支持常量起点与 `path:depth` / `path:predecessor`。

**Architecture:** 翻译层（`RDF4JTupleExprTranslator.translate(ArbitraryLengthPath)`）识别扩展谓词，用 `PathSpecRegistry` 取边的物理定义，`HierarchySqlBuilder` 生成路径 SQL，`PathQuerySupport.toRelation(sql)` 构造 `PathRelationDefinition`（name = `(<sql>)`），`IntermediateQueryFactory.createExtensionalDataNode` 注入为普通 IQ 节点。`DBParameters` 通过 `PathQueryContext`（ThreadLocal）传入。不新增 IQ 节点，不改优化器。

**Tech Stack:** Java 11、Maven、Guice、RDF4J 5.1.4、JUnit 5、H2（测试）。

**范围:** 本计划覆盖 Phase 0（前置验证）与 Phase 1（同表、常量起点、嵌套派生表、基础 depth/predecessor）。Phase 2（两表/异构/剪枝）与 Phase 3（两阶段起点/热更新）另行计划。

---

## 执行状态（2026-11-11）

- **Phase 0 已完成**，结论见 `docs/superpowers/analysis/hierarchical-path-phase0.md`：
  - P0-1/P0-2 失败：查询期无 `MetadataLookup`，`getBlackBoxView` 不可用。
  - 改用 `PathRelationDefinition`（name=`(<sql>)`）+ `PathQuerySupport` + `PathQueryContext`（ThreadLocal）。
  - P0-3 成立（`createExtensionalDataNode` 可用）。
- **Task 1.1–1.3 已完成并提交**（单测通过）。
- **实现偏差**：
  - 项目是 **Java 11**：不能用 `sealed` / `record` / `instanceof` 模式匹配。
  - surefire 使用 JUnit Platform：测试用 **JUnit 5**（`org.junit.jupiter.api`）。
- **Task 1.4 起按 fallback 执行**，原 `getBlackBoxView` 步骤作废：
  - 1.4a `PathRelationDefinition`（core/model）。
  - 1.4b `PathQuerySupport` + `PathQuerySupportImpl` + `PathQueryContext`（core/kg-query）。
  - 1.4c `QuestQueryProcessor` 设置/清除上下文（engine）。
  - 1.4d `RDF4JTupleExprTranslator` 路径分支。
  - 1.5 / 1.6 保持。

---

## 文件结构

| 动作 | 文件 | 职责 | 状态 |
|---|---|---|---|
| Create | `core/model/.../dbschema/PathSpec.java` | 路径边定义（Java 11 普通类） | 已完成 |
| Create | `core/model/.../dbschema/PathSpecRegistry.java` | 注册表接口 | 已完成 |
| Create | `core/model/.../dbschema/impl/PathSpecRegistryImpl.java` | 快照 + 版本 | 已完成 |
| Create | `core/kg-query/.../path/SeedSource.java` | 起点抽象 | 已完成 |
| Create | `core/kg-query/.../path/HierarchySqlBuilder.java` | 路径 SQL 生成 | 已完成 |
| Create | `core/model/.../dbschema/impl/PathRelationDefinition.java` | `(<sql>)` 关系（替代 getBlackBoxView） | 待做 |
| Create | `core/kg-query/.../path/PathQuerySupport.java` | 接口：spec + toRelation | 待做 |
| Create | `core/kg-query/.../path/PathQuerySupportImpl.java` | 用 DBParameters 构造 relation | 待做 |
| Create | `core/kg-query/.../path/PathQueryContext.java` | ThreadLocal per-spec 上下文 | 待做 |
| Modify | `core/kg-query/.../RDF4JTupleExprTranslator.java` | 路径分支 + `path:` 元数据 | 待做 |
| Modify | `engine/.../QuestQueryProcessor.java` | set/clear `PathQueryContext` | 待做 |
| Create | `core/kg-query/.../HierarchySqlBuilderTest.java` | SQL 生成单测（JUnit 5） | 已完成 |
| Create | `core/model/.../PathSpecRegistryImplTest.java` | 注册表单测（JUnit 5） | 已完成 |
| Create | `binding/rdf4j/.../HierarchicalPathTest.java` | H2 端到端 | 待做 |

---

## Phase 0：前置验证（必须先做）

> Phase 0 是探索性 spike，不强制 TDD；结论记录到 `docs/superpowers/analysis/hierarchical-path-phase0.md`。任一项失败，先停下来调整 Phase 1 的落点。

### Task 0.1：验证 `MetadataLookup` 能注入到翻译层

**Files:**
- Inspect: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JQueryTranslatorImpl.java:66-90`
- Inspect: `core/model/src/main/java/it/unibz/inf/ontop/injection/CoreSingletons.java`

- [ ] **Step 1: 确认 `CoreSingletons` 是否暴露 metadata**

Run:
```bash
rg -n "MetadataLookup|getMetadata" core/model/src/main/java/it/unibz/inf/ontop/injection/CoreSingletons.java
```
Expected: 无 `MetadataLookup`（已知）。记录结论。

- [ ] **Step 2: 确认上游谁能提供 `MetadataLookup`**

Run:
```bash
rg -n "MetadataLookup" engine core/kg-query | rg -v test
```
Expected: 找到 `DBMetadataProvider`/`MetadataProvider` 被注入的位置（如 `QuestQueryProcessor` 或 `OntopQueryEngine`）。记录可用注入点。

- [ ] **Step 3: 写探针测试，打印运行时实现类型**

Create `core/model/src/test/java/it/unibz/inf/ontop/dbschema/MetadataLookupProbeTest.java`:
```java
package it.unibz.inf.ontop.dbschema;

import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataLookupProbeTest {
    @Test
    public void runtimeLookupIsNotImmutable() {
        // 由 Phase 0 的 DI 探针替换为实际注入的实例
        MetadataLookup lookup = Phase0Probe.lookup();
        System.out.println("Runtime MetadataLookup: " + lookup.getClass().getName());
        assertFalse(lookup instanceof it.unibz.inf.ontop.dbschema.impl.ImmutableMetadataLookup);
    }
}
```
> `Phase0Probe` 在 Step 2 找到的 DI 组装点中临时实现，仅用于打印。

- [ ] **Step 4: 运行探针**

Run:
```bash
mvn -q -pl core/model -am -Dtest=MetadataLookupProbeTest -DfailIfNoTests=false test
```
Expected: 打印实现类名；若为 `ImmutableMetadataLookup`，停止并改用"自定义 RelationDefinition"方案。

### Task 0.2：验证 `getBlackBoxView` 可用（已完成：失败，历史）

- [ ] **Step 1: 在探针里调用一次**

在 `MetadataLookupProbeTest` 增加：
```java
@Test
public void blackBoxViewIsSupported() throws Exception {
    MetadataLookup lookup = Phase0Probe.lookup();
    RelationDefinition rel = lookup.getBlackBoxView("SELECT 1 AS c");
    assertNotNull(rel);
    assertEquals(1, rel.getAttributes().size());
}
```
Expected: PASS；若抛 `UnsupportedOperationException`，停止并报告。

- [ ] **Step 2: 运行**

Run:
```bash
mvn -q -pl core/model -am -Dtest=MetadataLookupProbeTest -DfailIfNoTests=false test
```

### Task 0.3：验证 `createExtensionalDataNode` 可用（历史：原用 getBlackBoxView，实际用 PathRelationDefinition）

**Files:**
- Test: `core/model/src/test/java/it/unibz/inf/ontop/iq/ExtensionalFromBlackBoxTest.java`

- [ ] **Step 1: 写失败测试**

```java
package it.unibz.inf.ontop.iq;

import it.unibz.inf.ontop.dbschema.MetadataLookup;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.injection.CoreSingletons;
import it.unibz.inf.ontop.iq.node.ExtensionalDataNode;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExtensionalFromBlackBoxTest {
    @Test
    public void createsExtensionalNodeFromSql() throws Exception {
        CoreSingletons cs = Phase0Probe.coreSingletons();
        MetadataLookup lookup = Phase0Probe.lookup();
        RelationDefinition rel = lookup.getBlackBoxView("SELECT 1 AS node_iri, 0 AS depth");
        ExtensionalDataNode node = cs.getIQFactory()
                .createExtensionalDataNode(rel, com.google.common.collect.ImmutableMap.of());
        assertNotNull(node);
        assertTrue(node.getVariables().isEmpty());
    }
}
```

- [ ] **Step 2: 运行**

Run:
```bash
mvn -q -pl core/model -am -Dtest=ExtensionalFromBlackBoxTest -DfailIfNoTests=false test
```
Expected: PASS。

### Task 0.4：验证目标 DB 的嵌套派生表/CTE 形态

- [ ] **Step 1: 在 H2 上跑两种形态**

Run:
```bash
# 在 H2 console 或测试中执行
SELECT * FROM (SELECT 1 AS a) t1 JOIN (SELECT 1 AS a) t2 ON t1.a = t2.a;
WITH e AS (SELECT 1 AS a) SELECT * FROM e;
```
Expected: 都成功。记录 MySQL/Doris 是否支持子查询内 `WITH`（Phase 1 用派生表，不依赖 WITH）。

### Task 0.5：验证 IRI 逐字节一致

- [ ] **Step 1: 用现有映射查一个节点的 IRI**

在 `bom_path.obda` 环境中执行：
```sparql
SELECT ?s WHERE { ?s a :BomItem ; :bomItemId "3517912884" }
```
记录返回的 IRI 字符串。

- [ ] **Step 2: 用 SQL 手工构造同样的 IRI**

```sql
SELECT CONCAT('http://example.org/bom/bom_item/', bom_item_id) AS iri
FROM ods.dp_mpo_mopp_bom_detial_info_dd WHERE bom_item_id = '3517912884';
```
Expected: 与 Step 1 逐字节相同；若不同（类型转换/编码），记录差异并在 `HierarchySqlBuilder` 采用相同转换。

- [ ] **Step 3: 写结论**

Create `docs/superpowers/analysis/hierarchical-path-phase0.md`，记录 5 项结论与发现的注入点。

---

## Phase 1：最小可用（同表、常量起点）

### Task 1.1：`PathSpec` 与 `SeedSource`（已完成）

**Files:**
- Create: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/PathSpec.java`
- Create: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/SeedSource.java`
- Test: `core/model/src/test/java/it/unibz/inf/ontop/dbschema/PathSpecTest.java`

- [ ] **Step 1: 写失败测试**

```java
package it.unibz.inf.ontop.dbschema;

import org.junit.Test;
import static org.junit.Assert.*;

public class PathSpecTest {
    @Test
    public void selfSpecKeepsColumns() {
        PathSpec.Self spec = new PathSpec.Self(
                "http://example.org/bom/parent_bom_item",
                "ods.t", "material_code", "parent_material_code",
                "bom_item_id", "http://example.org/bom/bom_item/");
        assertEquals("material_code", spec.joinKeyColumn());
        assertEquals("bom_item_id", spec.nodeKeyColumn());
    }
}
```

- [ ] **Step 2: 运行，确认编译失败**

Run:
```bash
mvn -q -pl core/model -am -Dtest=PathSpecTest -DfailIfNoTests=false test
```
Expected: 编译失败（`PathSpec` 不存在）。

> `PathSpec.Self.iriPrefix` 对应设计文档 `iriTemplate` 去掉 `{占位列}` 后的前缀；Phase 2 再支持完整模板。

- [ ] **Step 3: 实现 `PathSpec`**

`core/model/src/main/java/it/unibz/inf/ontop/dbschema/PathSpec.java`:
```java
package it.unibz.inf.ontop.dbschema;

import java.util.Optional;

public sealed interface PathSpec {

    String predicate();

    Optional<TraversalFilter> traversalFilter();

    record Self(String predicate,
                String table,
                String joinKeyColumn,
                String parentJoinKeyColumn,
                String nodeKeyColumn,
                String iriPrefix,
                Optional<TraversalFilter> traversalFilter) implements PathSpec {}

    record Endpoint(String table, String alias,
                    String joinKeyColumn, String nodeKeyColumn, String iriTemplate) {}

    record TwoTable(String predicate,
                    Endpoint src,
                    Endpoint dst,
                    String on,
                    Optional<TraversalFilter> traversalFilter) implements PathSpec {}

    record TraversalFilter(Optional<String> join, Optional<String> where, boolean applyToStart) {}
}
```

- [ ] **Step 4: 实现 `SeedSource`**

`core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/SeedSource.java`:
```java
package it.unibz.inf.ontop.query.translation.path;

import com.google.common.collect.ImmutableList;

public sealed interface SeedSource {
    record Constant(String iri) implements SeedSource {}
    record Values(ImmutableList<String> iris) implements SeedSource {}
    record TempTable(String table) implements SeedSource {}
}
```

- [ ] **Step 5: 运行，确认通过**

Run:
```bash
mvn -q -pl core/model -am -Dtest=PathSpecTest -DfailIfNoTests=false test
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add core/model/src/main/java/it/unibz/inf/ontop/dbschema/PathSpec.java \
        core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/SeedSource.java \
        core/model/src/test/java/it/unibz/inf/ontop/dbschema/PathSpecTest.java
git commit -m "feat(path): add PathSpec and SeedSource models"
```

### Task 1.2：`HierarchySqlBuilder`（同表、嵌套派生表）（已完成）

**Files:**
- Create: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/HierarchySqlBuilder.java`
- Test: `core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/path/HierarchySqlBuilderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import org.junit.Test;
import java.util.Optional;
import static org.junit.Assert.*;

public class HierarchySqlBuilderTest {
    private final PathSpec.Self spec = new PathSpec.Self(
            "http://example.org/bom/parent_bom_item",
            "ods.t", "material_code", "parent_material_code",
            "bom_item_id", "http://example.org/bom/bom_item/",
            Optional.empty());

    @Test
    public void buildsLevelsAndUnionAll() {
        String sql = new HierarchySqlBuilder().build(spec, new SeedSource.Constant(
                "http://example.org/bom/bom_item/3517912884"), 1, 2, Optional.empty());
        assertTrue(sql.contains("level_0"));
        assertTrue(sql.contains("level_1"));
        assertTrue(sql.contains("level_2"));
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("material_code"));
        assertFalse(sql.contains("level_3"));
    }

    @Test
    public void minDepthSkipsEarlierLevels() {
        String sql = new HierarchySqlBuilder().build(spec, new SeedSource.Constant(
                "http://example.org/bom/bom_item/3517912884"), 2, 3, Optional.empty());
        assertFalse(sql.contains("SELECT node_iri, depth, predecessor FROM level_0"));
        assertTrue(sql.contains("FROM level_2"));
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
mvn -q -pl core/kg-query -am -Dtest=HierarchySqlBuilderTest -DfailIfNoTests=false test
```
Expected: 编译失败。

- [ ] **Step 3: 实现 `HierarchySqlBuilder`**

```java
package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpec.TraversalFilter;

import java.util.Optional;

public class HierarchySqlBuilder {

    public String build(PathSpec spec, SeedSource seed, int minDepth, int maxDepth,
                        Optional<TraversalFilter> filter) {
        if (!(spec instanceof PathSpec.Self))
            throw new IllegalArgumentException("Phase 1 supports self only");
        if (maxDepth < minDepth || minDepth < 1)
            throw new IllegalArgumentException("require 1 <= minDepth <= maxDepth");

        PathSpec.Self self = (PathSpec.Self) spec;
        String level0 = "SELECT "
                + iri(self, self.nodeKeyColumn()) + " AS node_iri, "
                + self.joinKeyColumn() + " AS join_key, 0 AS depth, "
                + iri(self, self.nodeKeyColumn()) + " AS predecessor "
                + "FROM " + self.table() + " "
                + "WHERE " + self.nodeKeyColumn() + " = " + nodeKeyLiteral(seed);

        StringBuilder sb = new StringBuilder("SELECT node_iri, depth, predecessor FROM (\n");
        sb.append(level0).append("\n");

        String previous = level0;
        for (int k = 1; k <= maxDepth; k++) {
            String body = "SELECT "
                    + iri(self, "p." + self.nodeKeyColumn()) + " AS node_iri, "
                    + "p." + self.joinKeyColumn() + " AS join_key, "
                    + k + " AS depth, l.node_iri AS predecessor "
                    + "FROM " + self.table() + " p "
                    + "JOIN (" + previous + ") l ON p." + self.parentJoinKeyColumn() + " = l.join_key"
                    + filter.flatMap(TraversalFilter::where).map(w -> " WHERE " + w).orElse("");
            sb.append("UNION ALL\n").append(body).append("\n");
            previous = body;
        }
        sb.append(") path_rows WHERE depth >= ").append(minDepth);
        return sb.toString();
    }

    private String iri(PathSpec.Self self, String nodeKeyExpression) {
        return "CONCAT('" + self.iriPrefix() + "', " + nodeKeyExpression + ")";
    }

    private String nodeKeyLiteral(SeedSource seed) {
        if (!(seed instanceof SeedSource.Constant c))
            throw new IllegalArgumentException("Phase 1 supports constant seed only");
        String iri = c.iri();
        // 反解：取最后一个 '/' 之后
        String key = iri.substring(iri.lastIndexOf('/') + 1);
        return "'" + key.replace("'", "''") + "'";
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run:
```bash
mvn -q -pl core/kg-query -am -Dtest=HierarchySqlBuilderTest -DfailIfNoTests=false test
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/HierarchySqlBuilder.java \
        core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/path/HierarchySqlBuilderTest.java
git commit -m "feat(path): add HierarchySqlBuilder for self relations"
```

### Task 1.3：`PathSpecRegistry`（已完成）

**Files:**
- Create: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/PathSpecRegistry.java`
- Create: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/impl/PathSpecRegistryImpl.java`
- Test: `core/model/src/test/java/it/unibz/inf/ontop/dbschema/PathSpecRegistryImplTest.java`

- [ ] **Step 1: 写失败测试**

```java
package it.unibz.inf.ontop.dbschema;

import it.unibz.inf.ontop.dbschema.impl.PathSpecRegistryImpl;
import org.junit.Test;
import java.util.Optional;
import static org.junit.Assert.*;

public class PathSpecRegistryImplTest {
    @Test
    public void upsertIncrementsVersionAndReplaces() {
        PathSpecRegistry reg = new PathSpecRegistryImpl();
        long v0 = reg.version();
        PathSpec.Self spec = new PathSpec.Self("p", "t", "a", "b", "id", "pref", Optional.empty());
        reg.upsert(spec);
        assertEquals(v0 + 1, reg.version());
        assertSame(spec, reg.get("p").orElseThrow());
    }

    @Test
    public void remove() {
        PathSpecRegistry reg = new PathSpecRegistryImpl();
        reg.upsert(new PathSpec.Self("p", "t", "a", "b", "id", "pref", Optional.empty()));
        reg.remove("p");
        assertTrue(reg.get("p").isEmpty());
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
mvn -q -pl core/model -am -Dtest=PathSpecRegistryImplTest -DfailIfNoTests=false test
```

- [ ] **Step 3: 实现接口与实现**

`PathSpecRegistry.java`:
```java
package it.unibz.inf.ontop.dbschema;

import java.util.Optional;

public interface PathSpecRegistry {
    void upsert(PathSpec spec);
    void remove(String predicate);
    Optional<PathSpec> get(String predicate);
    long version();
}
```

`impl/PathSpecRegistryImpl.java`:
```java
package it.unibz.inf.ontop.dbschema.impl;

import com.google.common.collect.ImmutableMap;
import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PathSpecRegistryImpl implements PathSpecRegistry {
    private final AtomicReference<ImmutableMap<String, PathSpec>> snapshot =
            new AtomicReference<>(ImmutableMap.of());
    private final AtomicLong version = new AtomicLong();

    @Override
    public void upsert(PathSpec spec) {
        snapshot.updateAndGet(m -> ImmutableMap.<String, PathSpec>builder()
                .putAll(m).put(spec.predicate(), spec).build());
        version.incrementAndGet();
    }

    @Override
    public void remove(String predicate) {
        snapshot.updateAndGet(m -> ImmutableMap.<String, PathSpec>builder()
                .putAll(m).remove(predicate).build());
        version.incrementAndGet();
    }

    @Override
    public Optional<PathSpec> get(String predicate) {
        return Optional.ofNullable(snapshot.get().get(predicate));
    }

    @Override
    public long version() {
        return version.get();
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run:
```bash
mvn -q -pl core/model -am -Dtest=PathSpecRegistryImplTest -DfailIfNoTests=false test
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/model/src/main/java/it/unibz/inf/ontop/dbschema/PathSpecRegistry.java \
        core/model/src/main/java/it/unibz/inf/ontop/dbschema/impl/PathSpecRegistryImpl.java \
        core/model/src/test/java/it/unibz/inf/ontop/dbschema/PathSpecRegistryImplTest.java
git commit -m "feat(path): add PathSpecRegistry with versioned snapshots"
```

### Task 1.4：`PathQuerySupport` 与翻译层分支（⚠ 原 `getBlackBoxView` 方案作废，按以下 1.4a–1.4d 执行）

#### Task 1.4a：`PathRelationDefinition`（core/model）

```java
package it.unibz.inf.ontop.dbschema.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.dbschema.*;

public class PathRelationDefinition extends AbstractRelationDefinition {
    public PathRelationDefinition(String pathSql, RelationDefinition.AttributeListBuilder builder) {
        super("(" + pathSql + ")", builder);
    }
    @Override public ImmutableList<UniqueConstraint> getUniqueConstraints() { return ImmutableList.of(); }
    @Override public ImmutableList<FunctionalDependency> getOtherFunctionalDependencies() { return ImmutableList.of(); }
    @Override public ImmutableList<ForeignKeyConstraint> getForeignKeys() { return ImmutableList.of(); }
}
```

测试：构造一个 builder（node_iri/depth/predecessor），断言 `getAtomPredicate().getName()` 以 `(SELECT` 开头且以 `)` 结尾。

#### Task 1.4b：`PathQuerySupport` / `PathQuerySupportImpl` / `PathQueryContext`（core/kg-query）

```java
public interface PathQuerySupport {
    boolean isEnabled();
    Optional<PathSpec> getSpec(String predicateIri);
    RelationDefinition toRelation(String pathSql);
}
```

```java
public class PathQuerySupportImpl implements PathQuerySupport {
    private final DBParameters dbParameters;
    private final PathSpecRegistry registry;

    public PathQuerySupportImpl(DBParameters dbParameters, PathSpecRegistry registry) {
        this.dbParameters = dbParameters;
        this.registry = registry;
    }

    @Override public boolean isEnabled() { return true; }
    @Override public Optional<PathSpec> getSpec(String iri) { return registry.get(iri); }

    @Override public RelationDefinition toRelation(String pathSql) {
        RelationDefinition.AttributeListBuilder b = AbstractRelationDefinition.attributeListBuilder();
        QuotedIDFactory ids = dbParameters.getQuotedIDFactory();
        DBTypeFactory types = dbParameters.getDBTypeFactory();
        DBTermType text = types.getAbstractRootDBType();
        b.addAttribute(ids.createAttributeID("node_iri"), text, null, true);
        b.addAttribute(ids.createAttributeID("depth"), text, null, false);
        b.addAttribute(ids.createAttributeID("predecessor"), text, null, true);
        return new PathRelationDefinition(pathSql, b);
    }
}
```

```java
public final class PathQueryContext {
    private static final ThreadLocal<PathQuerySupport> CURRENT = new ThreadLocal<>();
    private PathQueryContext() {}
    public static void set(PathQuerySupport support) { CURRENT.set(support); }
    public static PathQuerySupport current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
```

#### Task 1.4c：`QuestQueryProcessor` 接线（engine）

在 `reformulateIntoNativeQuery` 里 `inputQuery.translate(...)` 前：

```java
PathQuerySupport support = new PathQuerySupportImpl(
        obdaSpecification.getDBParameters(), pathSpecRegistry);
PathQueryContext.set(support);
try {
    IQ convertedIQ = inputQuery.translate(inputQueryTranslator);
    ...
} finally {
    PathQueryContext.clear();
}
```

`PathSpecRegistry` 需通过 Guice 注入 `PathSpecRegistryImpl`：在创建 `QuestQueryProcessor` 的模块（搜索 `bind(QuestQueryProcessor.class)` / `ReformulationFactory` 所在模块）中绑定。

缓存命中（`QuestQueryProcessor.reformulateIntoNativeQuery` 开头的 early return）不会调用 translator，无需设置上下文；`set` / `finally clear` 只包裹 `inputQuery.translate(...)`。

#### Task 1.4d：`RDF4JTupleExprTranslator` 路径分支

在 `translate(ArbitraryLengthPath)` 开头，识别 `path:maxDepth` 与常量起点，调用 `HierarchySqlBuilder` 与 `PathQueryContext.current().toRelation(sql)`，用 `createExtensionalDataNode` 注入；`path:` 元数据三元组翻译为 `TrueNode`。具体按实际 TupleExpr 调整（见设计文档 7.2）。

#### 历史方案（已作废，勿照做）

以下为原 `getBlackBoxView` 方案。

**Files:**
- Create: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/PathQuerySupport.java`
- Modify: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JTupleExprTranslator.java:274-328`
- Modify: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JQueryTranslatorImpl.java:66-90,274`
- Test: `core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/path/PathQuerySupportTest.java`

- [ ] **Step 1: 定义 `PathQuerySupport`**

```java
package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.RelationDefinition;

import java.util.Optional;

public interface PathQuerySupport {
    boolean isEnabled();
    Optional<PathSpec> getSpec(String predicateIri);
    RelationDefinition toRelation(String pathSql) throws Exception;
}
```

- [ ] **Step 2: 写失败测试（用 stub 验证分支被调用）**

```java
package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import org.junit.Test;
import java.util.Optional;
import static org.junit.Assert.*;

public class PathQuerySupportTest {
    @Test
    public void stubReturnsSpec() {
        PathQuerySupport support = new PathQuerySupport() {
            @Override public boolean isEnabled() { return true; }
            @Override public Optional<PathSpec> getSpec(String iri) {
                return Optional.of(new PathSpec.Self(iri, "t", "a", "b", "id", "pref", Optional.empty()));
            }
            @Override public RelationDefinition toRelation(String sql) { throw new UnsupportedOperationException(); }
        };
        assertTrue(support.getSpec("p").isPresent());
    }
}
```

- [ ] **Step 3: 在 `RDF4JTupleExprTranslator` 增加字段与构造参数**

在类字段区加：
```java
private final PathQuerySupport pathQuerySupport;
```
构造器参数末尾加 `PathQuerySupport pathQuerySupport`，并 `this.pathQuerySupport = pathQuerySupport;`。为兼容旧调用，另加一个旧构造重载，委托新构造并传 `null`。

- [ ] **Step 4: 替换 `translate(ArbitraryLengthPath)`**

在现有方法开头（识别 `subClassOf` 之前）插入：
```java
if (pathQuerySupport != null && pathQuerySupport.isEnabled()) {
    var pathNode = arbitraryLengthPath.getPathExpression();
    var propertyIri = extractPropertyIri(pathNode);   // 见 Step 5
    if (propertyIri.isPresent() && arbitraryLengthPath.getSubjectVar().getValue() instanceof IRIConstant) {
        var spec = pathQuerySupport.getSpec(propertyIri.get());
        if (spec.isPresent()) {
            try {
                String startIri = ((IRIConstant) arbitraryLengthPath.getSubjectVar().getValue()).getIRI().stringValue();
                String sql = new HierarchySqlBuilder().build(
                        spec.get(), new SeedSource.Constant(startIri), 1, maxDepthFromSiblings,
                        spec.get().traversalFilter());
                RelationDefinition rel = pathQuerySupport.toRelation(sql);
                var objectVar = (Variable) termFactory.getVariable(arbitraryLengthPath.getObjectVar().getName());
                var args = ImmutableMap.of(0, objectVar, 1, termFactory.getVariable("depth"),
                        2, termFactory.getVariable("predecessor"));
                return createTranslationResult(
                        iqFactory.createExtensionalDataNode(rel, args), ImmutableSet.of());
            } catch (Exception e) {
                throw new OntopUnsupportedKGQueryException("Path translation failed: " + e.getMessage());
            }
        }
    }
}
```
> `maxDepthFromSiblings`、`extractPropertyIri` 由 Step 5 补齐。`HierarchySqlBuilder` 的最终 SELECT 只输出 3 列（node_iri, depth, predecessor），join_key 仅在内部派生表使用，因此 `argumentMap` 用 0/1/2 即可。

- [ ] **Step 5: 补齐辅助方法**

```java
private Optional<String> extractPropertyIri(TupleExpr pathExpression) {
    if (pathExpression instanceof StatementPattern sp
            && sp.getPredicateVar().getValue() instanceof IRIConstant c) {
        return Optional.of(c.getIRI().stringValue());
    }
    return Optional.empty();
}
```

> `maxDepthFromSiblings`：在 `translateJoinLikeNode` 收集兄弟 `path:maxDepth`（见 Task 1.5 的收集逻辑）；Phase 1 可先只支持 `BooleanLiteral true` 的 `path:traversalFilter` 与 `path:maxDepth` 常量。

- [ ] **Step 6: 运行，确认测试通过**

Run:
```bash
mvn -q -pl core/kg-query -am -Dtest=PathQuerySupportTest -DfailIfNoTests=false test
```
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/path/PathQuerySupport.java \
        core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JTupleExprTranslator.java \
        core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/path/PathQuerySupportTest.java
git commit -m "feat(path): wire path translation branch"
```

> Phase 1 的 `path:maxDepth` / `path:minDepth` / `path:depth` / `path:predecessor` 收集逻辑属于 Task 1.5；Task 1.4 先打通"常量起点 + 无 depth 输出"的最小路径。

### Task 1.5：收集 `path:` 元数据并绑定输出变量

**Files:**
- Modify: `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JTupleExprTranslator.java:274-328`
- Test: `core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/path/PathMetadataTest.java`

- [ ] **Step 1: 写失败测试**

```java
// 构造 Join(ArbitraryLengthPath, StatementPattern(path:maxDepth, 9),
//          StatementPattern(path:depth, ?d))，断言翻译结果含 depth 变量。
// 使用 RDF4J SPARQLParser 解析字符串后取 TupleExpr，直接调用 translator.getTree。
```
（测试代码略，按实际 `RDF4JTupleExprTranslator` 构造签名补齐。）

- [ ] **Step 2: 在 `translateJoinLikeNode` 收集元数据**

```java
private Optional<Integer> findDepth(Join join, VariableOrGroundTerm subject, IRI predicate, int defaultValue) {
    return join.getChildren().stream()
            .filter(c -> c instanceof StatementPattern)
            .map(c -> (StatementPattern) c)
            .filter(sp -> sp.getPredicateVar().getValue().equals(predicate)
                    && sp.getSubjectVar().getValue().equals(subject))
            .map(sp -> ((Literal) sp.getObjectVar().getValue()).getLabel())
            .map(Integer::parseInt)
            .findFirst()
            .or(() -> Optional.of(defaultValue));
}
```

- [ ] **Step 3: 运行并提交**

Run:
```bash
mvn -q -pl core/kg-query -am -Dtest=PathMetadataTest -DfailIfNoTests=false test
git add core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JTupleExprTranslator.java
git commit -m "feat(path): collect path:maxDepth/minDepth/depth/predecessor"
```

### Task 1.6：H2 端到端测试

**Files:**
- Create: `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/HierarchicalPathTest.java`
- Create: `binding/rdf4j/src/test/resources/hierarchical-path/obda.obda`
- Create: `binding/rdf4j/src/test/resources/hierarchical-path/create.sql`

- [ ] **Step 1: 建 H2 表与数据**

`create.sql`:
```sql
CREATE TABLE bom (
  bom_item_id VARCHAR(32) PRIMARY KEY,
  material_code VARCHAR(32),
  parent_material_code VARCHAR(32),
  num INT
);
INSERT INTO bom VALUES ('A','A','P1',100);
INSERT INTO bom VALUES ('P1','P1','P2',200);
INSERT INTO bom VALUES ('P2','P2',NULL,300);
```

- [ ] **Step 2: 写 OBDA 映射**

`obda.obda`:
```
[PrefixDeclaration]
:    http://example.org/bom/
path: http://ontop.org/path#

[MappingDeclaration] @collection [[
mappingId bom-nodes
source SELECT bom_item_id, material_code, parent_material_code, num FROM bom
target :bom_item/{bom_item_id} a :BomItem ;
       :bomItemId {bom_item_id} ;
       :num {num}^^xsd:integer ;
       :parent_bom_item :bom_item/{parent_material_code} .
]]
```

- [ ] **Step 3: 写测试**

```java
@Test
public void constantStartTraversesTwoLevels() {
    // 加载 H2 + OBDA，注册 PathSpec:
    // new PathSpec.Self("http://example.org/bom/parent_bom_item",
    //     "bom", "material_code", "parent_material_code", "bom_item_id",
    //     "http://example.org/bom/bom_item/", Optional.empty());
    String sparql = """
        PREFIX : <http://example.org/bom/>
        PREFIX path: <http://ontop.org/path#>
        SELECT ?depth ?ancestor WHERE {
          :bom_item/A :parent_bom_item* ?ancestor .
          :bom_item/A path:maxDepth 2 .
          ?ancestor path:depth ?depth .
        } ORDER BY ?depth
        """;
    // assert 返回 depth=1(P1), depth=2(P2)
}
```

- [ ] **Step 4: 运行**

Run:
```bash
mvn -q -pl binding/rdf4j -am -Dtest=HierarchicalPathTest -DfailIfNoTests=false test
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/HierarchicalPathTest.java \
        binding/rdf4j/src/test/resources/hierarchical-path/
git commit -m "test(path): add H2 end-to-end test for hierarchical path"
```

---

## 自审清单（写完后执行）

- [ ] 每个 spec 的 Phase 1 要求都有对应 Task：PathSpec ✓、Registry ✓、SeedSource ✓、SQL 生成 ✓、翻译分支 ✓、depth/predecessor（Task 1.5）✓、H2 测试 ✓。
- [ ] 无 TBD / "similar to" / 无代码步骤。
- [ ] 类型一致：`build(spec, seedSource, minDepth, maxDepth, traversalFilter)` 在 Task 1.2/1.4 一致；`PathSpec.Self` 字段顺序在所有任务一致。
- [ ] `argumentMap` 下标与 4 列契约（node_iri, join_key, depth, predecessor）对齐——Task 1.4 Step 4 已标注需调整。

## 执行交接

计划已保存到 `docs/superpowers/plans/2026-11-11-hierarchical-path-static.md`。两种执行方式：

1. **Subagent-Driven（推荐）**：每个 Task 派一个 fresh subagent，任务间评审。
2. **Inline Execution**：本会话按 executing-plans 批量执行，带检查点。

选哪种？
