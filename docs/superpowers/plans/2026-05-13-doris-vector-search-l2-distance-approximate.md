# Doris l2_distance_approximate 向量检索支持 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Ontop 中增加 Doris 的 `l2_distance_approximate` 向量距离函数支持，允许 SPARQL 查询通过字符串字面量传入向量参数，最终生成 `l2_distance_approximate(col, ARRAY[0.1,0.2,...])` SQL。

**Architecture:** 三层管道：
1. SPARQL 函数符号 `vfn:l2DistApprox` 接收 `(?emb, "[0.1,0.2,...]"^^xsd:string)`
2. DBFunctionSymbol 持有自定义序列化器
3. 输出 SQL `l2_distance_approximate(emb_col, ARRAY[0.1,0.2,0.3])`

**Tech Stack:** Java, Ontop (core/model + db/rdb), Apache Doris, MySQL JDBC, JUnit

---

## 一、数据流映射

```
SPARQL: vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string)
           │
           ▼ SPARQL FunctionSymbol
        ?emb + "[0.1,0.2,0.3]" (xsd:string)
           │
           ▼ getConversionFromRDFLexical2DB
        embedding_col + DBConstant("[0.1,0.2,0.3]")
           │
           ▼ getRegularDBFunctionSymbol("l2_distance_approximate", 2)
        DBFunctionSymbol + 自定义序列化
           │
           ▼
SQL: l2_distance_approximate(embedding, ARRAY[0.1,0.2,0.3])
```

---

## 二、支持的 SPARQL 写法

### 写法 1: BIND 方式（推荐）
```sparql
PREFIX vfn: <http://ontop/vectorfn/>
PREFIX ex: <http://example.org/>

SELECT ?id ?dist WHERE {
    ?s ex:hasId ?id ;
       ex:hasEmbedding ?emb .
    BIND(vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string) AS ?dist)
}
ORDER BY ?dist
LIMIT 10
```

### 写法 2: FILTER 方式
```sparql
PREFIX vfn: <http://ontop/vectorfn/>
PREFIX ex: <http://example.org/>

SELECT ?id ?emb WHERE {
    ?s ex:hasId ?id ;
       ex:hasEmbedding ?emb .
    FILTER(vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string) > 300)
}
LIMIT 10
```

### 写法 3: ORDER BY + FILTER 组合
```sparql
SELECT ?id ?dist WHERE {
    ?s ex:hasId ?id ;
       ex:hasEmbedding ?emb .
    BIND(vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string) AS ?dist)
    FILTER(?dist > 300)
}
ORDER BY ?dist
LIMIT 10
```

---

## 三、生成目标 SQL

```sql
SELECT id,
       l2_distance_approximate(embedding, ARRAY[0.1, 0.2, 0.3]) AS dist
FROM sift_1M
WHERE l2_distance_approximate(embedding, ARRAY[0.1, 0.2, 0.3]) > 300
ORDER BY dist
LIMIT 10
```

---

## 四、SPARQL ↔ SQL 转换对应表

| SPARQL | SQL |
|--------|-----|
| `?emb` (变量) | `embedding` (列引用) |
| `"[0.1,0.2,0.3]"^^xsd:string` (字符串字面量) | `ARRAY[0.1,0.2,0.3]` (SQL 数组字面量) |
| `vfn:l2DistApprox(...)` (函数 IRI) | `l2_distance_approximate(...)` (DB 函数) |
| `BIND(... AS ?dist)` | `AS dist` (列别名) |
| `ORDER BY ?dist` | `ORDER BY dist` |
| `LIMIT 10` | `LIMIT 10` |

---

## 五、不支持的场景

| 场景 | 原因 |
|------|------|
| R2RML `sqlQuery` 中写 `ARRAY[...]` | `ExpressionParser.visit(ArrayConstructor)` 抛异常 |
| SPARQL 中直接写 `[0.1,0.2,0.3]` | SPARQL 无数组字面量类型 |
| Stardog 的 `fts:textMatch {...}` 语法 | 那是 Stardog 私有扩展，非标准 SPARQL |

**唯一支持的 SPARQL 写法**: `vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string)`

---

## 六、与 Stardog 的对比

| 维度 | Stardog | Ontop + Doris |
|------|---------|---------------|
| **向量存储** | RDF 字面量 + 内部索引 | SQL ARRAY 列 |
| **查询方式** | `spa:model {...}` / `fts:textMatch {...}` | `vfn:l2DistApprox(?emb, "[...]")` |
| **语法扩展** | Stardog 私有 `{ }` 语法 | 标准 SPARQL 函数调用 ✅ |
| **向量传入** | 预计算字面量 | 动态字符串字面量 |
| **距离函数** | 内部 cosine similarity | Doris `l2_distance_approximate` |

---

## 文件结构

### 新建文件

| # | 文件路径 | 职责 |
|---|----------|------|
| 1 | `core/model/src/main/java/it/unibz/inf/ontop/model/vocabulary/VectorFN.java` | 定义 `http://ontop/vectorfn/l2DistApprox` IRI 常量 |
| 2 | `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/L2DistApproxSPARQLFunctionSymbolImpl.java` | SPARQL 函数符号实现 |

### 修改文件

| # | 文件路径 | 修改内容 |
|---|----------|----------|
| 3 | `db/rdb/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/AbstractSQLDBFunctionSymbolFactory.java` | 在 `createDefaultRegularFunctionTable()` 中注册 `("l2_distance_approximate", 2)` 及自定义序列化 |
| 4 | `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/FunctionSymbolFactoryImpl.java` | 在 `createSPARQLFunctionSymbolTable()` 中注册 `L2DistApproxSPARQLFunctionSymbolImpl` |

### 测试文件

| # | 文件路径 | 测试内容 |
|---|----------|----------|
| 5 | `db/rdb/src/test/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/DorisVectorFunctionSymbolTest.java` | DB 函数符号序列化测试 |
| 6 | `core/model/src/test/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/L2DistApproxSPARQLFunctionSymbolTest.java` | SPARQL 函数符号映射测试 |

---

## Task 1: 创建 VectorFN 词汇类

**Files:**
- Create: `core/model/src/main/java/it/unibz/inf/ontop/model/vocabulary/VectorFN.java`

- [ ] **Step 1: 创建文件**

```java
package it.unibz.inf.ontop.model.vocabulary;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.simple.SimpleRDF;

/**
 * Vocabulary for Ontop vector function extensions (Apache Doris).
 * <a href="https://doris.apache.org/docs/query-acceleration/vector-search/">Doris Vector Search</a>
 */
public class VectorFN {

    public static final String PREFIX = "http://ontop/vectorfn/";

    public static final IRI L2_DIST_APPROX;

    static {
        org.apache.commons.rdf.api.RDF factory = new SimpleRDF();
        L2_DIST_APPROX = factory.createIRI(PREFIX + "l2DistApprox");
    }
}
```

遵循 `OFN.java` 中 `SimpleRDF` + `factory.createIRI()` 的模式。

- [ ] **Step 2: 验证编译**

Run: （在 IDE 中或通过 Maven 确认该文件所在模块编译通过）

---

## Task 2: 注册 l2_distance_approximate DB 函数符号

**Files:**
- Modify: `db/rdb/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/AbstractSQLDBFunctionSymbolFactory.java`

在 `createDefaultRegularFunctionTable()` 中的 Geo 函数组之后添加向量函数组。

- [ ] **Step 1: 阅读 `createDefaultRegularFunctionTable()` 末尾**

找到 GEO 函数组注册的结尾位置（大约在 `ST_DISTANCE`、`ST_INTERSECTS` 等之后，`CARDINALITY_STR` 之前或类似位置）。确认 builder 仍在 build 状态。

- [ ] **Step 2: 添加向量函数常量**

在类的常量区域（与其他 `private static final String` 常量一起）添加：

```java
// --- Vector Similarity Functions (Apache Doris) ---
private static final String L2_DISTANCE_APPROXIMATE_STR = "l2_distance_approximate";
```

- [ ] **Step 3: 在 createDefaultRegularFunctionTable() 中注册**

在 builder 的 GEO 函数条目之后添加：

```java
/*
 * Vector Similarity Functions (Apache Doris)
 */
DBFunctionSymbol l2DistApproxFunctionSymbol = new SimpleTypedDBFunctionSymbolImpl(
        L2_DISTANCE_APPROXIMATE_STR, 2, dbDoubleType, false, abstractRootDBType,
        (terms, termConverter, termFactory) -> {
            // terms.get(0) = embedding column ref, terms.get(1) = string constant "[0.1,0.2,...]"
            String colSql = termConverter.apply(terms.get(0));
            String vecSql;

            // If the 2nd arg is a DBConstant, extract raw value to avoid SQL quoting issues
            if ((terms.get(1) instanceof DBConstant)) {
                vecSql = ((DBConstant) terms.get(1)).getValue();
            } else {
                // Fallback: strip SQL string quotes from serialized output
                vecSql = termConverter.apply(terms.get(1))
                        .replaceAll("^'|'$", "");
            }

            // If vector is already wrapped in [...] use as-is, otherwise wrap
            if (vecSql.startsWith("[") && vecSql.endsWith("]")) {
                return String.format("%s(%s, %s)", L2_DISTANCE_APPROXIMATE_STR, colSql, vecSql);
            } else {
                return String.format("%s(%s, ARRAY[%s])", L2_DISTANCE_APPROXIMATE_STR, colSql, vecSql);
            }
        });
builder.put(L2_DISTANCE_APPROXIMATE_STR, 2, l2DistApproxFunctionSymbol);
```

**注意**: 需要确认 `SimpleTypedDBFunctionSymbolImpl` 是否已在 `AbstractSQLDBFunctionSymbolFactory` 的 import 中。如果没有，添加：
```java
import it.unibz.inf.ontop.model.term.functionsymbol.db.impl.SimpleTypedDBFunctionSymbolImpl;
```
同时确认 `DBConstant` 已 import（可能在 `AbstractSQLDBFunctionSymbolFactory` 中已有，因为它是父类 `AbstractDBFunctionSymbolFactory` 使用的）。

- [ ] **Step 4: 验证编译**

Run: `cd db/rdb && mvn compile -pl .`
Expected: BUILD SUCCESS

确认 `DBConstant` 的 import 路径是：
```java
import it.unibz.inf.ontop.model.term.DBConstant;
```

---

## Task 3: 创建 L2DistApproxSPARQLFunctionSymbolImpl

**Files:**
- Create: `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/L2DistApproxSPARQLFunctionSymbolImpl.java`

参考 `OfnSimpleBinarySPARQLFunctionSymbolImpl` 的模式，但两个参数类型可以不同（第一个是列引用，第二个是字符串字面量）。

- [ ] **Step 1: 创建文件**

```java
package it.unibz.inf.ontop.model.term.functionsymbol.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.iq.node.VariableNullability;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbol;
import it.unibz.inf.ontop.model.type.RDFTermType;
import it.unibz.inf.ontop.model.type.TermTypeInference;
import org.apache.commons.rdf.api.IRI;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * SPARQL function symbol for l2_distance_approximate(vector, vector_literal).
 * <p>
 * SPARQL: vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string)
 * SQL:    l2_distance_approximate(emb_col, ARRAY[0.1,0.2,0.3])
 * <p>
 * Both arguments are declared as xsd:string at the SPARQL level:
 * - arg0: the embedding variable (resolved to a column reference via mapping)
 * - arg1: the query vector expressed as xsd:string, e.g. "[0.1,0.2,0.3]"
 */
public class L2DistApproxSPARQLFunctionSymbolImpl extends ReduciblePositiveAritySPARQLFunctionSymbolImpl {

    private static final String FUNCTION_NAME = "SP_L2_DIST_APPROX";

    private final RDFTermType argType;
    private final RDFTermType targetType;

    public L2DistApproxSPARQLFunctionSymbolImpl(@Nonnull IRI functionIRI,
                                                 @Nonnull RDFTermType stringType,
                                                 @Nonnull RDFTermType doubleType) {
        super(FUNCTION_NAME, functionIRI,
                ImmutableList.of(stringType, stringType));
        this.argType = stringType;
        this.targetType = doubleType;
    }

    @Override
    public Optional<TermTypeInference> inferType(ImmutableList<? extends ImmutableTerm> terms) {
        return Optional.of(TermTypeInference.declareTermType(targetType));
    }

    @Override
    public boolean canBePostProcessed(ImmutableList<? extends ImmutableTerm> arguments) {
        return false;
    }

    @Override
    protected ImmutableTerm computeLexicalTerm(ImmutableList<ImmutableTerm> subLexicalTerms,
                                                ImmutableList<ImmutableTerm> typeTerms,
                                                TermFactory termFactory,
                                                ImmutableTerm returnedTypeTerm) {

        // Convert arg0: ?emb → DB term (column reference or variable)
        ImmutableTerm embDB = termFactory.getConversionFromRDFLexical2DB(
                subLexicalTerms.get(0), argType);

        // Convert arg1: string literal "[0.1,0.2,0.3]" → DB constant
        ImmutableTerm vecDB = termFactory.getConversionFromRDFLexical2DB(
                subLexicalTerms.get(1), argType);

        // Look up the DB function symbol and create functional term
        DBFunctionSymbol dbFunc = termFactory.getDBFunctionSymbolFactory()
                .getRegularDBFunctionSymbol("l2_distance_approximate", 2);
        ImmutableFunctionalTerm dbFuncTerm = termFactory.getImmutableFunctionalTerm(
                dbFunc, embDB, vecDB);

        // Convert DB result back to RDF lexical (xsd:double)
        return termFactory.getConversion2RDFLexical(dbFuncTerm, targetType);
    }

    @Override
    protected ImmutableTerm computeTypeTerm(ImmutableList<? extends ImmutableTerm> subLexicalTerms,
                                             ImmutableList<ImmutableTerm> typeTerms,
                                             TermFactory termFactory,
                                             VariableNullability variableNullability) {
        return termFactory.getRDFTermTypeConstant(targetType);
    }

    @Override
    public boolean isAlwaysInjectiveInTheAbsenceOfNonInjectiveFunctionalTerms() {
        return false;
    }
}
```

**说明**:
- `argType` = xsd:string（两个参数都声明为字符串类型—第一个参数的实际类型由 mapping 解析时确定）
- `targetType` = xsd:double（距离值）
- `getRegularDBFunctionSymbol("l2_distance_approximate", 2)` 会命中 Task 2 中注册的条目

- [ ] **Step 2: 验证编译**

Run: `cd core/model && mvn compile -pl .`
Expected: BUILD SUCCESS

---

## Task 4: 注册 SPARQL 函数符号到 FunctionSymbolFactoryImpl

**Files:**
- Modify: `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/FunctionSymbolFactoryImpl.java`

- [ ] **Step 1: 找到 SPARQL 函数表的创建位置**

在 `createSPARQLFunctionSymbolTable()` 方法中（大约第 126 行开始），找到 Geo 或时间函数组的末尾（大约在第 340 行附近的 OFN 函数或第 370 行附近的 XSD CAST 函数之前）。

- [ ] **Step 2: 添加 import 语句**

```java
import it.unibz.inf.ontop.model.vocabulary.VectorFN;
```

- [ ] **Step 3: 在函数集合中添加函数符号**

在 Geo 函数组之后的时间扩展函数之前（或在 XSD CAST 函数之前均可），添加：

```java
/*
 * Vector Similarity Functions (Apache Doris)
 */
new L2DistApproxSPARQLFunctionSymbolImpl(
        VectorFN.L2_DIST_APPROX,
        xsdString,
        xsdDouble),
```

- [ ] **Step 4: 验证编译**

Run: `cd core/model && mvn compile -pl .`
Expected: BUILD SUCCESS

---

## Task 5: 单元测试 — DB 函数符号序列化

**Files:**
- Create: `db/rdb/src/test/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/DorisVectorFunctionSymbolTest.java`

- [ ] **Step 1: 创建测试类**

```java
package it.unibz.inf.ontop.model.term.functionsymbol.db.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.model.term.DBConstant;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbol;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.model.type.TypeFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Tests for the l2_distance_approximate DB function symbol serialization.
 */
public class DorisVectorFunctionSymbolTest {

    // TODO: Replace Mockito-based tests with real Ontop test infrastructure
    //       (requires proper DI setup with Guice)

    @Test
    public void testSerializerWithBracketedVector() {
        // Verify the serializer produces the correct SQL when vector has [...] brackets

        // Create a mock DB constant with value "[0.1,0.2,0.3]"
        DBConstant mockVec = mock(DBConstant.class);
        when(mockVec.getValue()).thenReturn("[0.1,0.2,0.3]");

        // Create a mock term converter
        Function<ImmutableTerm, String> termConverter = mock(Function.class);
        when(termConverter.apply(any())).thenReturn("emb_col", "[0.1,0.2,0.3]");

        // Expected output
        String expected = "l2_distance_approximate(emb_col, [0.1,0.2,0.3])";
        // TODO: instantiate the actual function symbol via DI and test getNativeDBString
    }
}
```

**注意**: 由于 Ontop 使用 Guice DI 来创建 `DBFunctionSymbolFactory`，在简单单元测试中直接实例化较复杂。建议采用两种方式之一：
1. 使用 Mockito 模拟所需组件（如上所示，但需要完善）
2. 使用 Ontop 的测试工具类来创建 DI 容器

- [ ] **Step 2: 完善测试（待确定测试框架集成细节后）**

---

## Task 6: 集成验证

- [ ] **Step 1: 确认整体编译通过**

Run: `mvn compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 确认现有测试不因改动而失败**

Run: `mvn test -pl core/model,db/rdb -DskipITs`
Expected: 所有测试通过

- [ ] **Step 3: 验证完整数据流（人工验证，需要 Doris 测试实例）**

创建一个测试用例，连接真实 Doris 实例，验证 SPARQL 查询：
```sparql
PREFIX vfn: <http://ontop/vectorfn/>
PREFIX ex: <http://example.org/>

SELECT ?id ?dist WHERE {
  ?s ex:hasId ?id ;
     ex:hasEmbedding ?emb .
  BIND(vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string) AS ?dist)
}
ORDER BY ?dist
LIMIT 10
```

验证生成的 SQL 包含：
```sql
SELECT id, l2_distance_approximate(embedding, ARRAY[0.1,0.2,0.3]) AS dist
FROM sift_1M
ORDER BY dist
LIMIT 10
```

---

## 风险与注意事项

### 1. `getConversionFromRDFLexical2DB` 的字符串行为

SPARQL 的 `"[0.1,0.2,0.3]"^^xsd:string` 经过 `getConversionFromRDFLexical2DB` 后，在内部变为 `DBConstant` 或带 `DBStringType` 的 term。DB 函数序列化器的 `instanceof DBConstant` 检查需要在 `AbstractSQLDBFunctionSymbolFactory` 中导入 `DBConstant`。

**如果 `DBConstant` 不可用**（因为 `AbstractSQLDBFunctionSymbolFactory` 在 `db/rdb` 模块，而 `DBConstant` 在 `core/model`），则改为在 serializer lambda 中使用 `terms.get(1) instanceof ImmutableTerm` 并用 `toString()` 检查。但实际上 `DBConstant` 是 `core/model` 中的公开接口，`db/rdb` 模块依赖于 `core/model`，所以 import 应该可用。

### 2. SPARQL 函数符号的参数类型

`L2DistApproxSPARQLFunctionSymbolImpl` 将两个参数都声明为 `xsd:string`。对于 `?emb` SPARQL 变量，Ontop 的 SPARQL→DB 转换会通过 mapping 解析出实际的 RDF 类型（通常是 `xsd:anyURI` 或基于映射的目标类型）。这里声明为 `xsd:string` 是安全的，因为转换函数会进行必要的类型适配。

### 3. 向量内容格式

用户需要在 SPARQL 查询中将向量写成 `"[0.1,0.2,0.3]"^^xsd:string`。序列化器会剥离引号并按原样输出为 `[0.1,0.2,0.3]`（无外层引号）。如果输入不包含 brackets `[...]`，则会自动包裹为 `ARRAY[0.1,0.2,0.3]`。

### 4. 顺序依赖

Task 1 → Task 3（VectorFN.java 被 SPARQL 函数符号引用）
Task 2 → 独立，可在任何时间开发
Task 3 → Task 4（SPARQL 函数符号被 FunctionSymbolFactoryImpl 注册）
Task 4 → 所有前置完成

**推荐开发顺序**: Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6

---

## 回退方案

如果 `instanceof DBConstant` 在序列化器中不支持：

```java
// 回退方案：直接在 SPARQL 函数符号侧提取字符串值
// 在 L2DistApproxSPARQLFunctionSymbolImpl.computeLexicalTerm() 中：
if (subLexicalTerms.get(1) instanceof DBConstant) {
    String rawVec = ((DBConstant) subLexicalTerms.get(1)).getValue();
    // 直接构造已包含 ARRAY[...] 的完整 SQL 片段
    // 通过一个特殊的飞线性 term 传递给 DB 函数序列化器
}
```

或者直接在序列化器中做简单的字符串替换（不依赖 `instanceof`）：

```java
// 回退方案：纯字符串处理
String vecSql = termConverter.apply(terms.get(1))
    .replaceAll("^'|'$", "")          // 剥离 SQL 引号
    .replaceAll("^\"|\"$", "");      // 也处理双引号
// 然后包裹为 ARRAY[]
```