# Doris ARRAY 列 SPARQL 查询支持 — 设计方案

**Date**: 2026-05-26
**Status**: Design Approved
**Author**: Sisyphus

## 1. 问题

Apache Doris 支持 `ARRAY<VARCHAR>`、`ARRAY<CHAR>`、`ARRAY<STRING>` 等字符串元素类型的数组列（如 `dims ARRAY<VARCHAR(64)>`）。
在 Ontop 中对这类列进行 SPARQL 查询时，存在以下问题：

1. **类型映射问题**：JDBC `getColumns()` 返回 `TYPE_NAME` 为 `"ARRAY<VARCHAR(64)>"`、`"ARRAY<CHAR(10)>"` 或 `"ARRAY<STRING>"`。`DefaultSQLDBTypeFactory` 中没有注册 `ARRAY` 类型，回退为未知类型，无法进行字符串操作。
2. **函数映射问题**：SPARQL `CONTAINS(?dims, "a")` 期望生成 `INSTR(dims, 'a') > 0`，但 Doris 的 ARRAY 列需要 `array_contains(dims, 'a')`。
3. **区分问题**：Doris 模式下仍有普通 VARCHAR 列，`CONTAINS` 对它们必须使用 `INSTR`，不能一刀切替换为 `array_contains`。

### 1.1 目标

- 允许 SPARQL `CONTAINS(?dims, "a")` 直接查询 Doris 字符串元素类型的 ARRAY 列（`ARRAY<VARCHAR>`、`ARRAY<CHAR>`、`ARRAY<STRING>`）
- 翻译为 `array_contains(dims, 'a')` SQL
- 对普通 VARCHAR/CHAR/STRING 列的 `CONTAINS` 仍使用 `INSTR(col, 'val') > 0`
- 不引入自定义 SPARQL 函数，用户无需感知底层列类型差异
- 仅影响 `ontop.mysql.doris=true` 模式，MySQL 正常模式不受影响

## 2. 设计方案

### 2.1 核心思路

**不将 ARRAY 抹平为 VARCHAR**。而是在 MySQLDBTypeFactory 的 TypeMap 中将 `ARRAY` 注册为类型名独立的 `StringDBTermType`。三种字符串元素类型的 ARRAY（`ARRAY<VARCHAR>`、`ARRAY<CHAR>`、`ARRAY<STRING>`）统一映射为类型名 `"ARRAY"`。

```
MySQLDBTypeFactory TypeMap (Doris 模式):
  "VARCHAR"  → StringDBTermType(name="VARCHAR")  -- 普通 VARCHAR 列
  "CHAR"     → StringDBTermType(name="CHAR")     -- 普通 CHAR 列
  "ARRAY"    → StringDBTermType(name="ARRAY")    -- ARRAY<VARCHAR/CHAR/STRING> 统一
```

这样：
- `ARRAY<VARCHAR>`、`ARRAY<CHAR>`、`ARRAY<STRING>` 都进入 `"ARRAY"` 条目
- 两种类型都是字符串行为（`xsd:string` 映射, `CONTAINS` 可用）
- 但类型名不同（`ARRAY` vs `VARCHAR`/`CHAR`）→ `serializeContains` 可以通过 term 的类型名判断走哪个分支
- ARRAY 类型信息不丢失

### 2.2 类型判断逻辑

```
serializeContains(terms, ...)
    │
    ├─ isDoris && term[0].type.name.startsWith("ARRAY")
    │     → array_contains(col, val)
    │
    └─ 其他 (VARCHAR, TEXT, isDoris=false, ...)
          → INSTR(col, val) > 0
```

### 2.3 getDBTermType 匹配分析

JDBC `TYPE_NAME="ARRAY<VARCHAR(64)>"` 经过 `getDBTermType()` 调用链：

```
getDBTermType("ARRAY<VARCHAR(64)>")
  → preprocessTypeName("ARRAY<VARCHAR(64)>")     // 去除 (64) 参数
  → normalized = "ARRAY<VARCHAR>"
  → sqlTypeMap.get("ARRAY<VARCHAR>")
```

**注意**：`sqlTypeMap` 的 key 是 `"ARRAY"`，而 normalized 结果是 `"ARRAY<VARCHAR>"`。**不能直接命中**。

所以 `preprocessTypeName` 仍需一个小改动：将 `"ARRAY<VARCHAR>"` → `"ARRAY"`，但不改成 `"VARCHAR"`（保留类型信息）。

```
preprocessTypeName("ARRAY<VARCHAR(64)>")
  → 正则去除 "(64)" → "ARRAY<VARCHAR>"
  → 检测到以 "ARRAY<" 开头 → 返回 "ARRAY"   (不是 "VARCHAR"！)
  → sqlTypeMap.get("ARRAY") → StringDBTermType(name="ARRAY") ✅
```

## 3. 修改的文件

| # | 文件 | 修改类型 | 说明 |
|---|------|---------|------|
| 1 | `db/rdb/src/main/java/.../type/impl/MySQLDBTypeFactory.java` | 修改 | TypeMap 添加 `ARRAY` 类型；`preprocessTypeName` 将 `ARRAY<...>` → `ARRAY` |
| 2 | `db/rdb/src/main/java/.../functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java` | 修改 | `serializeContains` 中通过类型名判断走 `array_contains` 或 `INSTR` |

## 4. 详细改动

### 4.1 MySQLDBTypeFactory.java

#### 4.1.1 TypeMap 注册 ARRAY 类型

在 `createMySQLTypeMap()` 方法中，添加 Doris ARRAY 类型：

```java
protected static Map<String, DBTermType> createMySQLTypeMap(TermType rootTermType, TypeFactory typeFactory,
                                                             DatabaseInfoSupplier databaseInfoSupplier) {
    // ... 现有代码 ...
    // 已有: map.put(JSON_STR, new JsonDBTermTypeImpl(JSON_STR, rootAncestry));

    if (databaseInfoSupplier.isDoris()) {
        // Doris 字符串元素 ARRAY 类型注册为字符串类型。
        // ARRAY<VARCHAR>, ARRAY<CHAR>, ARRAY<STRING> 统一映射为 name="ARRAY"
        // 保留类型名 "ARRAY" 以便 serializeContains 可以识别。
        RDFDatatype xsdString = typeFactory.getXsdStringDatatype();
        StringDBTermType arrayType = new StringDBTermType(
                "ARRAY", rootAncestry, xsdString);
        map.put("ARRAY", arrayType);
    }

    return map;
}
```

#### 4.1.2 preprocessTypeName 精简 ARRAY<...> → ARRAY

不覆盖已有 case，在 `default` 或兜底分支中添加 ARRAY 检测：

```java
@Override
protected String preprocessTypeName(String typeName) {
    String capitalizedTypeName = typeName.toUpperCase();
    switch (capitalizedTypeName) {
        case TINY_INT_ONE_STR:
            return BOOLEAN_STR;
        case BIT_ONE_STR:
            return capitalizedTypeName;
        default:
            // Doris: ARRAY<VARCHAR(64)>, ARRAY<CHAR(10)>, ARRAY<STRING> → ARRAY
            if (isDoris && capitalizedTypeName.startsWith("ARRAY")) {
                // 去除泛型参数: "ARRAY<VARCHAR>" → "ARRAY"
                int genericStart = capitalizedTypeName.indexOf('<');
                if (genericStart > 0) {
                    return "ARRAY";
                }
            }
            return super.preprocessTypeName(capitalizedTypeName);
    }
}

@Override
protected String preprocessTypeName(String typeName, int columnSize) {
    String capitalizedTypeName = typeName.toUpperCase();
    switch (capitalizedTypeName) {
        case TINYINT_STR:
            if (columnSize == 1)
                return BOOLEAN_STR;
            break;
        case BIT_STR:
            if (columnSize == 1)
                return capitalizedTypeName + "(1)";
            break;
    }
    // Doris: ARRAY<VARCHAR(64)>, ARRAY<CHAR(10)>, ARRAY<STRING> → ARRAY
    if (isDoris && capitalizedTypeName.startsWith("ARRAY")) {
        int genericStart = capitalizedTypeName.indexOf('<');
        if (genericStart > 0) {
            return "ARRAY";
        }
    }
    return super.preprocessTypeName(capitalizedTypeName);
}
```

**关键区别**：以前的设计返回 `"VARCHAR"`（丢失类型信息），现在返回 `"ARRAY"`（保留类型信息）。`ARRAY<VARCHAR>`、`ARRAY<CHAR>`、`ARRAY<STRING>` 都归一为 `"ARRAY"`。

### 4.2 MySQLDBFunctionSymbolFactory.java

#### 4.2.1 serializeContains 添加类型判断

```java
@Override
protected String serializeContains(ImmutableList<? extends ImmutableTerm> terms,
                                    Function<ImmutableTerm, String> termConverter, TermFactory termFactory) {
    if (databaseInfoSupplier.isDoris() && isArrayColumn(terms.get(0))) {
        // Doris ARRAY<VARCHAR> 列 → array_contains(col, val)
        return String.format("array_contains(%s, %s)",
                termConverter.apply(terms.get(0)),
                termConverter.apply(terms.get(1)));
    }
    // 默认路径 → INSTR(col, val) > 0
    return String.format("INSTR(%s,%s) > 0",
            termConverter.apply(terms.get(0)),
            termConverter.apply(terms.get(1)));
}
```

#### 4.2.2 类型判断辅助方法

```java
/**
 * 判断 term 是否来自 Doris ARRAY<VARCHAR> 类型列。
 * 类型名为 "ARRAY" 的 StringDBTermType（在 MySQLDBTypeFactory 中注册）。
 */
private boolean isArrayColumn(ImmutableTerm term) {
    // 尝试从 term 推断类型名
    Optional<String> typeName = Optional.empty();
    
    if (term instanceof DBConstant) {
        typeName = Optional.of(((DBConstant) term).getType().getName());
    } else if (term instanceof ImmutableFunctionalTerm) {
        typeName = ((ImmutableFunctionalTerm) term).inferType()
                .flatMap(TermTypeInference::getTermType)
                .map(DBTermType.class::cast)
                .map(DBTermType::getName);
    }
    
    return typeName.map(n -> n.toUpperCase().startsWith("ARRAY"))
            .orElse(false);
}
```

**需要的 import**：
```java
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBBooleanFunctionSymbol;
import it.unibz.inf.ontop.model.term.DBConstant;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.type.TermTypeInference;
```

## 5. 数据流

```
Doris 表: dims ARRAY<VARCHAR(64)>  (或 ARRAY<CHAR(10)>, ARRAY<STRING>)

JDBC getColumns() → TYPE_NAME="ARRAY<VARCHAR(64)>"  (或 "ARRAY<CHAR(10)>", "ARRAY<STRING>")
    ↓
getDBTermType(typeName)
    → preprocessTypeName(typeName)
    → 正则去除 (size) → "ARRAY<VARCHAR>"  (或 "ARRAY<CHAR>", "ARRAY<STRING>")
    → startsWith("ARRAY") && indexOf('<')>0 → "ARRAY"  (统一)
    → sqlTypeMap.get("ARRAY") → StringDBTermType(name="ARRAY") ✅
    ↓
DBTermType = StringDBTermType(name="ARRAY", castName="ARRAY")
(sparql: 行为如同 xsd:string, CONTAINS 可用, 但类型名保留为 ARRAY)
    ↓

OBDA 映射:
    ex:Item{id} ex:dims {dims}^^xsd:string .
    ↓

SPARQL 查询:
    FILTER(CONTAINS(?dims, "a"))
    ↓

ContainsSPARQLFunctionSymbolImpl → getDBContains → serializeContains
    ↓

serializeContains(terms=[dims_col, 'a'], ...)
    → isDoris=true
    → isArrayColumn(terms[0]): term 类型名为 "ARRAY" → true
    → array_contains(dims, 'a') ✅
    ↓

SQL:
    SELECT id FROM items WHERE array_contains(dims, 'a')
```

## 6. 使用示例

### 6.1 OBDA 映射

```ttl
[PrefixDeclaration]
ex:     http://example.org/
xsd:    http://www.w3.org/2001/XMLSchema#

[MappingDeclaration] @collection [[
mappingId   dims-mapping
target      ex:Item{id} ex:dims {dims}^^xsd:string .
source      SELECT id, dims FROM items
]]
```

### 6.2 ARRAY<VARCHAR> 列查询

```sparql
PREFIX ex: <http://example.org/>

SELECT ?id WHERE {
    ?s ex:id ?id ;
       ex:dims ?dims .
    FILTER(CONTAINS(?dims, "a"))
}
```

生成 SQL：
```sql
SELECT id FROM items WHERE array_contains(dims, 'a')
```

### 6.3 普通 VARCHAR 列查询（不受影响）

```sparql
SELECT ?id WHERE {
    ?s ex:id ?id ;
       ex:name ?name .
    FILTER(CONTAINS(?name, "abc"))
}
```

生成 SQL：
```sql
SELECT id FROM items WHERE INSTR(name, 'abc') > 0
```

### 6.4 混合查询

```sparql
SELECT ?id WHERE {
    ?s ex:id ?id ;
       ex:dims ?dims ;
       ex:name ?name .
    FILTER(CONTAINS(?dims, "a") && CONTAINS(?name, "abc"))
}
```

生成 SQL：
```sql
SELECT id FROM items
WHERE array_contains(dims, 'a')
  AND INSTR(name, 'abc') > 0
```

## 7. OBDA 类型声明

| 列类型 | OBDA 声明 | 内部类型名 | CONTAINS 生成的 SQL |
|--------|----------|-----------|-------------------|
| `ARRAY<VARCHAR>` | `{dims}^^xsd:string` | `ARRAY` | `array_contains(dims, val)` |
| `ARRAY<CHAR>` | `{dims}^^xsd:string` | `ARRAY` | `array_contains(dims, val)` |
| `ARRAY<STRING>` | `{dims}^^xsd:string` | `ARRAY` | `array_contains(dims, val)` |
| `VARCHAR` | `{name}^^xsd:string` | `VARCHAR` | `INSTR(name, val) > 0` |

**内部区分机制**：类型名不同（`ARRAY` vs `VARCHAR`），`isArrayColumn()` 自动识别。

## 8. 不支持的场景

| 场景 | 原因 |
|------|------|
| `ARRAY<INT>` / `ARRAY<DOUBLE>` 等非字符串元素 | `ARRAY` 注册为 `StringDBTermType`，只对字符串元素类型有意义 |
| `REGEX(?dims, "pattern")` | 正则不适用于 ARRAY 类型 |
| `STRSTARTS(?dims, "a")` | 前缀匹配不适用于 ARRAY |
| `REPLACE(?dims, "a", "b")` | 字符串替换不适用于 ARRAY |

## 9. 风险与缓解措施

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| `ImmutableFunctionalTerm.inferType()` 返回空 | `isArrayColumn` 返回 false，回退到 INSTR | 安全回退，不会报错。ARRAY 列用 INSTR 语义不对但 SQL 可执行。|
| JDBC TYPE_NAME 不含 `<...>`（仅 "ARRAY"） | `preprocessTypeName` 不进入 `<` 分支，直接返回 `"ARRAY"` → 命中 map ✅ | 无风险 |
| `StringDBTermType` castName 为 `"ARRAY"` | 如果 Ontop 在某些路径生成 `CAST(col AS ARRAY)`，Doris 可能不支持 | DBTermType 的 `getCastName()` 默认等于 `getName()` 即 `"ARRAY"`。Doris 不支持 `CAST(... AS ARRAY)`。但使用 `CONTAINS` 路径不会触发 CAST。如果有其他路径触发 CAST，需要重写 castName。|
| MySQL 正常模式 | 所有改动用 `isDoris` flag 控制 | 无影响 |

## 10. 测试

### 10.1 单元测试

1. **MySQLDBTypeFactoryTest**: `isDoris=true` 时 `getDBTermType("ARRAY<VARCHAR(64)>")` 返回 name=`"ARRAY"` 的 StringDBTermType
2. **MySQLDBTypeFactoryTest**: `isDoris=false` 时 `getDBTermType("ARRAY<VARCHAR>")` 不做特殊处理
3. **MySQLDBFunctionSymbolFactoryTest**: `isArrayColumn` 对类型名为 `"ARRAY"` 的 term 返回 true
4. **MySQLDBFunctionSymbolFactoryTest**: `serializeContains` 对 ARRAY term + Doris 模式生成 `array_contains(col, val)`
5. **MySQLDBFunctionSymbolFactoryTest**: `serializeContains` 对 VARCHAR term + Doris 模式生成 `INSTR(col, val) > 0`
6. **MySQLDBFunctionSymbolFactoryTest**: `serializeContains` 对 MySQL 模式（isDoris=false）生成 `INSTR(col, val) > 0`

### 10.2 集成测试

1. Doris `ARRAY<VARCHAR>` 列执行 `CONTAINS(?dims, "a")` → SQL `array_contains(dims, 'a')`
2. Doris 普通 `VARCHAR` 列执行 `CONTAINS(?name, "abc")` → SQL `INSTR(name, 'abc') > 0`
3. 混合查询中两种列各自正确翻译

### 10.3 回归测试

1. `mvn test -pl db/rdb` — 现有 MySQL 测试全部通过
2. `mvn test -pl core/model` — 核心模型测试不受影响

## 11. 激活方式

```properties
ontop.mysql.doris=true
```

OBDA 映射：
```ttl
target  ex:Item{id} ex:dims {dims}^^xsd:string .
```

