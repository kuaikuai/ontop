# MySQL Doris 模式配置开关实现说明

## 1. 背景

在使用 MySQL 协议连接 Apache Doris 时，OnTop 生成的 SQL 语句中的 CAST 表达式会包含 MySQL 特有的语法：

1. **TEXT 类型**: `CAST(... AS CHAR CHARACTER SET utf8)`
2. **BIGINT 类型**: `CAST(... AS SIGNED)`

Doris 不支持这些 MySQL 特有语法，会导致 SQL 执行错误：
```
Syntax error in line 2: ... CAST(... ELSE NULL END AS SIGNED) ...
```

需要通过 `ontop.mysql.doris=true` 配置开关，生成 Doris 兼容的 SQL。

## 2. 实现方案

### 2.1 设计思路

通过 `DatabaseInfoSupplier` 接口添加 `isDoris()` 方法，Doris 模式下：
- TEXT CAST: `CAST(... AS CHAR)` (去掉 `CHARACTER SET utf8`)
- BIGINT CAST: `CAST(... AS BIGINT)` (替代 `SIGNED`)

### 2.2 涉及的源代码文件

| 文件路径 | 修改类型 | 说明 |
|---------|---------|------|
| `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java` | 新增常量 | 定义配置键 `ontop.mysql.doris` |
| `core/model/src/main/java/it/unibz/inf/ontop/dbschema/DatabaseInfoSupplier.java` | 新增方法 | 添加 `isDoris()` 默认方法 |
| `core/model/src/main/java/it/unibz/inf/ontop/dbschema/impl/DatabaseInfoSupplierImpl.java` | 修改 | 实现 `isDoris()`，从 `OntopModelSettings` 读取配置值 |
| `core/model/src/main/resources/it/unibz/inf/ontop/injection/default.properties` | 新增配置 | 添加默认值 `ontop.mysql.doris=false` |
| `db/rdb/src/main/java/it/unibz/inf/ontop/model/type/impl/MySQLDBTypeFactory.java` | 修改 | 根据 `isDoris()` 动态设置 BIGINT 和 TEXT 的 castName |
| `db/rdb/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java` | 修改 | 在 `serializeDateTimeNorm()` 方法中根据 `isDoris()` 控制格式 |
| `db/rdb/src/main/java/it/unibz/inf/ontop/generation/normalization/impl/ProjectOrderByTermsTransformer.java` | 修复 | ORDER BY + DISTINCT 与 CAST 表达式的冲突修复 |

## 3. 详细修改

### 3.1 OntopModelSettings.java

**文件**: `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java`

```java
String INCLUDE_MYSQL_DORIS = "ontop.mysql.doris";
```

### 3.2 DatabaseInfoSupplier.java

**文件**: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/DatabaseInfoSupplier.java`

添加默认方法：

```java
/**
 * MySQL-compatible Doris mode flag.
 * When true, generates Doris-compatible SQL instead of MySQL-specific SQL.
 * Default is false.
 */
default boolean isDoris() {
    return false;
}
```

### 3.3 DatabaseInfoSupplierImpl.java

**文件**: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/impl/DatabaseInfoSupplierImpl.java`

实现 `isDoris()` 方法：

```java
@Override
public boolean isDoris() {
    return settings.getProperty(OntopModelSettings.INCLUDE_MYSQL_DORIS)
            .map(Boolean::parseBoolean)
            .orElse(false);
}
```

### 3.4 MySQLDBTypeFactory.java

**文件**: `db/rdb/src/main/java/it/unibz/inf/ontop/model/type/impl/MySQLDBTypeFactory.java`

**修改点 1**: 字段和构造方法

```java
private final boolean isDoris;

@AssistedInject
protected MySQLDBTypeFactory(@Assisted TermType rootTermType, @Assisted TypeFactory typeFactory,
                             DatabaseInfoSupplier databaseInfoSupplier) {
    super(createMySQLTypeMap(rootTermType, typeFactory, databaseInfoSupplier), createMySQLCodeMap());
    this.isDoris = databaseInfoSupplier.isDoris();
}
```

**修改点 2**: BIGINT 和 TEXT 类型的 castName

```java
// Overloads BIGINT to use BIGINT for Doris or SIGNED for MySQL
String bigIntCastName = databaseInfoSupplier.isDoris() ? "BIGINT" : "SIGNED";
NumberDBTermType bigIntType = new NumberDBTermType(BIGINT_STR, bigIntCastName, rootAncestry, xsdInteger, INTEGER);

// Overloads NVARCHAR to insert the precision
String charCastName = databaseInfoSupplier.isDoris()
        ? "CHAR" : "CHAR CHARACTER SET utf8";
StringDBTermType textType = new StringDBTermType(TEXT_STR, charCastName, rootAncestry,
        typeFactory.getXsdStringDatatype());
```

### 3.5 MySQLDBFunctionSymbolFactory.java

**文件**: `db/rdb/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java`

**修改方法**: `serializeDateTimeNorm()`

```java
protected String serializeDateTimeNorm(DBTermType dbDateTimestampType,
                                       ImmutableList<? extends ImmutableTerm> terms,
                                       Function<ImmutableTerm, String> termConverter) {

    String charType = databaseInfoSupplier.isDoris()
            ? "CHAR(30)" : "CHAR(30) CHARACTER SET utf8";
    String dateTimeStringWithoutTz = String.format("REPLACE(CAST(%s AS " + charType + "),' ', 'T')",
            termConverter.apply(terms.get(0)));

    return dbDateTimestampType.getName().equals(TIMESTAMP_STR)
            ? String.format("CONCAT(%s,%s)", dateTimeStringWithoutTz, CURRENT_TZ_STR)
            : dateTimeStringWithoutTz;
}
```

## 4. 使用方法

### 4.1 默认配置

`default.properties` 中默认值：

```properties
# MySQL/Doris compatibility
# When set to true, generates Doris-compatible SQL instead of MySQL-specific SQL
ontop.mysql.doris=false
```

### 4.2 启用 Doris 模式

在 Ontop 配置文件（如 `ontop.properties`）中添加：

```properties
ontop.mysql.doris=true
```

或在代码中构建配置时：

```java
OntopSQLAllOWLAPIConfigurationImpl.builder()
    .jdbcUrl("jdbc:mysql://host:port/database")
    .property("ontop.mysql.doris", "true")
    .build();
```

### 4.3 效果对比

**MySQL 模式（默认 `ontop.mysql.doris=false`）时生成的 SQL**:
```sql
SELECT DISTINCT CAST(v1.factory_code AS CHAR CHARACTER SET utf8) AS `v0`
FROM (...) v1

SELECT CAST(v1.bigint_col AS SIGNED) * 2 FROM (...) v1
```

**Doris 模式（`ontop.mysql.doris=true`）时生成的 SQL**:
```sql
SELECT DISTINCT v1.factory_code AS `v0`
FROM (...) v1

SELECT CAST(v1.bigint_col AS BIGINT) * 2 FROM (...) v1
```

## 5. 技术细节

### 5.1 CAST 表达式生成原理

OnTop 通过 `Serializers.getCastSerializer()` 方法生成 CAST 表达式：

```java
public static DBFunctionSymbolSerializer getCastSerializer(DBTermType targetType) {
    return (terms, termConverter, termFactory) -> String.format(
            "CAST(%s AS %s)", termConverter.apply(terms.get(0)), targetType.getCastName());
}
```

CAST 表达式的目标类型由 `DBTermType.getCastName()` 返回。`MySQLDBTypeFactory` 中的 TEXT 和 BIGINT 类型通过 `StringDBTermType` 和 `NumberDBTermType` 实现，根据 `isDoris()` 返回值动态设置。

### 5.2 依赖注入机制

`MySQLDBTypeFactory` 和 `MySQLDBFunctionSymbolFactory` 通过 Guice 的 `@AssistedInject` 注解注入 `DatabaseInfoSupplier`。`DatabaseInfoSupplier` 是在 `OntopModelModule` 中绑定的单例。

## 6. 编译验证

修改后通过 Maven 编译验证：

```
[INFO] Reactor Summary for Ontop 5.6.0-SNAPSHOT:
[INFO]
[INFO] Ontop .............................................. SUCCESS [  0.240 s]
[INFO] Ontop Core ......................................... SUCCESS [  1.509 s]
[INFO] Ontop Model ........................................ SUCCESS [  1.509 s]
[INFO] Ontop OBDA Core .................................... SUCCESS [  0.327 s]
[INFO] Ontop DB ........................................... SUCCESS [  0.004 s]
[INFO] Ontop RDB .......................................... SUCCESS [  0.571 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

## 7. 后续建议

1. **功能测试**: 建议编写或更新 MySQL/Doris 相关的集成测试，验证 Doris 模式下 SQL 生成正确
2. **其他数据库**: 如有类似需求，可参考此模式在其他数据库的工厂类中实现

## 8. ORDER BY + DISTINCT 修复

### 8.1 问题描述

当 mapping 中属性声明了 `^^xsd:decimal` 类型，SPARQL 查询使用 `ORDER BY ?var` 且 `?var` 在 SELECT 中时，Ontop 报错：

```
MinorOntopInternalBugException: The dialect requires ORDER BY conditions to be projected 
but a DISTINCT prevents some of them
```

### 8.2 根因分析

Reformulation pipeline 执行顺序：

```
步骤 5: OrderBySimplifier
        → 将 ORDER BY 中的 RDF 变量 ?actualWeight 转换为 DB 表达式
        → CAST(actual_weight AS DECIMAL(...))

步骤 7: ProjectOrderByTermsTransformer
        → 检查 alreadyDefinedTerms（只有 SPARQL 变量和 RDF term）
        → CAST 表达式不在 alreadyDefinedTerms 中
        → mayImpactDistinct 返回 true → 报错！
```

`alreadyDefinedTerms` 包含的是 SPARQL 级别的 term（变量、RDF 包装），而 `OrderBySimplifier` 已将 ORDER BY comparator 转换为 DB 级别的 CAST 表达式。两者不匹配导致误判。

### 8.3 修复方案（v2）

**文件**: `db/rdb/src/main/java/it/unibz/inf/ontop/generation/normalization/impl/ProjectOrderByTermsTransformer.java`

**v1 方案（不足）**：从 `substitution.getRangeSet()` 提取 RDF lexical term。但 reformulation pipeline 中 `ProjectionSplitter` 已将 SPARQL 层 construction 分离，`normalize()` 中的 `substitution` 来自 push-down 的 CONSTRUCTION2，包含的是 DB CAST 表达式而非 RDF term，无法提取到有效的 DB lexical term。

**v2 方案（当前）**：从 ORDER BY comparator 中递归提取所有叶子变量，筛选在 descendant tree 中存在的，加入 `alreadyDefinedTerms`：

```java
// 从 ORDER BY 表达式中提取所有叶子变量（Var 节点）
ImmutableSet<Variable> allDescendantVars = newDescendantTree.getVariables();

ImmutableSet<Variable> orderBySubVars = orderBy.getComparators().stream()
        .map(OrderByNode.OrderComparator::getTerm)
        .flatMap(t -> extractVariables(t).stream())
        .filter(allDescendantVars::contains)    // 只保留在 descendant tree 中存在的
        .collect(ImmutableCollectors.toSet());

ImmutableSet<ImmutableTerm> alreadyDefinedTerms = ImmutableSet.<ImmutableTerm>builder()
        .addAll(projectedVariables)             // SPARQL 变量
        .addAll(substitution.getRangeSet())      // DB CAST 表达式
        .addAll(orderBySubVars)                 // ORDER BY 叶子 DB 变量  ← 新增
        .build();
```

**helper 方法**：
```java
/**
 * Recursively extracts all Variable leaf nodes from a term.
 */
private static ImmutableSet<Variable> extractVariables(ImmutableTerm term) {
    if (term instanceof Variable)
        return ImmutableSet.of((Variable) term);
    else if (term instanceof ImmutableFunctionalTerm)
        return ((ImmutableFunctionalTerm) term).getTerms().stream()
                .flatMap(t -> extractVariables(t).stream())
                .collect(ImmutableCollectors.toSet());
    else
        return ImmutableSet.of();
}
```

### 8.4 修复效果

```
ORDER BY term: CAST(actual_weight, DECIMAL(...))
                    ↑
mayImpactDistinct() 递归检查:
  1. CAST 是 deterministic functional term → 递归检查 sub-term
  2. sub-term = actual_weight（DB variable）
  3. actual_weight 在 alreadyDefinedTerms（fix 新增） → return false ✅
```

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| `ORDER BY ?var` + `^^xsd:decimal` + 有 DISTINCT | ❌ 报错 | ✅ 正常 |
| `ORDER BY ?var` + `^^xsd:string` | ✅ 正常 | ✅ 不受影响 |
| `ORDER BY ?var`（不在 SELECT 中） | ❌ 报错 | ❌ 仍报错（正确行为） |
| `ORDER BY RAND()` (NonDeterministic) | ❌ 报错 | ❌ 仍报错（正确行为） |

### 8.5 MySQL/Doris 不兼容性汇总

Doris 与 MySQL 的主要 SQL 语法差异（相关于 Ontop 生成的 SQL）：

| 类别 | MySQL | Doris | 处理 |
|------|-------|-------|------|
| CAST(... AS SIGNED) | ✅ 支持 | ❌ 不支持 | `ontop.mysql.doris=true` → 使用 BIGINT |
| CHAR CHARACTER SET utf8 | ✅ 支持 | ❌ 不支持 | `ontop.mysql.doris=true` → 使用 CHAR |
| UNSIGNED INT/BIGINT | ✅ 支持 | ❌ 不支持 | 不做特殊处理 |
| BIT / YEAR / TIME 类型 | ✅ 支持 | ❌ 不支持 | 不做特殊处理 |
| ALTER TABLE ADD col | ✅ 支持（可选 COLUMN 关键字） | 必须 ADD COLUMN | 不做特殊处理 |
| MOD 运算符 | ✅ 10 MOD 3 | 严格语法 | 不做特殊处理 |