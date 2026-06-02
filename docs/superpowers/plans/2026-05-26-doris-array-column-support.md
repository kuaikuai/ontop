# Doris ARRAY 列 SPARQL CONTAINS 支持 — 实施计划

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修改 Ontop 以支持 `CONTAINS(?dims, "a")` 在 Doris 模式下生成 `array_contains(dims, 'a')` SQL。ARRAY<VARCHAR/CHAR/STRING> 列使用 `array_contains`，普通 VARCHAR 列使用 `INSTR`。

**改动文件:** 2 个，零新增文件

---

## Task 1: MySQLDBTypeFactory — TypeMap 注册 ARRAY 类型

**文件:** `db/rdb/src/main/java/.../model/type/impl/MySQLDBTypeFactory.java`

- [ ] **Step 1.1: 在 createMySQLTypeMap() 中添加 ARRAY 条目**

在 `map.put(JSON_STR, ...)` 之后、`return map` 之前添加：

```java
if (databaseInfoSupplier.isDoris()) {
    RDFDatatype xsdString = typeFactory.getXsdStringDatatype();
    StringDBTermType arrayType = new StringDBTermType(
            "ARRAY", rootAncestry, xsdString);
    map.put("ARRAY", arrayType);
}
```

**验证:** 编译通，`isDoris=true` 时 TypeMap 包含 name="ARRAY" 的 StringDBTermType

- [ ] **Step 1.2: 重写 preprocessTypeName(String typeName)**

在现有 `preprocessTypeName(String)` 的 `default:` 分支中添加 ARRAY 检测：

```java
default:
    // Doris: ARRAY<VARCHAR(64)>, ARRAY<CHAR(10)>, ARRAY<STRING> → ARRAY
    if (isDoris && capitalizedTypeName.startsWith("ARRAY")) {
        int genericStart = capitalizedTypeName.indexOf('<');
        if (genericStart > 0) {
            return "ARRAY";
        }
    }
    return super.preprocessTypeName(capitalizedTypeName);
```

- [ ] **Step 1.3: 重写 preprocessTypeName(String typeName, int columnSize)**

在 switch 之后添加相同逻辑：

```java
// Doris: ARRAY<VARCHAR(64)>, ARRAY<CHAR(10)>, ARRAY<STRING> → ARRAY
if (isDoris && capitalizedTypeName.startsWith("ARRAY")) {
    int genericStart = capitalizedTypeName.indexOf('<');
    if (genericStart > 0) {
        return "ARRAY";
    }
}
```

- [ ] **Step 1.4: 验证编译**

```
mvn compile -pl db/rdb -DskipTests
```

Expected: BUILD SUCCESS

---

## Task 2: MySQLDBFunctionSymbolFactory — serializeContains 类型判断

**文件:** `db/rdb/src/main/java/.../functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java`

- [ ] **Step 2.1: 阅读 DBContainsFunctionSymbolImpl 确认 serialzeContains 签名**

读取 `AbstractDBFunctionSymbolFactory.getDBContains()` 和 `DBContainsFunctionSymbolImpl` 确认 `getNativeDBString` 的 terms 参数类型，确保 `isArrayColumn` 能正确获取 term 类型。

- [ ] **Step 2.2: 添加 import 语句**

```java
import it.unibz.inf.ontop.model.term.DBConstant;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.type.TermTypeInference;
```

(检查是否已有部分 import，避免重复)

- [ ] **Step 2.3: 添加 isArrayColumn 辅助方法**

```java
/**
 * 判断 term 是否来自 Doris ARRAY<VARCHAR/CHAR/STRING> 类型列。
 * 类型名为 "ARRAY" 的 StringDBTermType（在 MySQLDBTypeFactory 中注册）。
 */
private boolean isArrayColumn(ImmutableTerm term) {
    // 尝试从 term 推断类型名
    String typeName = null;
    
    if (term instanceof DBConstant) {
        typeName = ((DBConstant) term).getType().getName();
    } else {
        typeName = term.inferType()
                .flatMap(TermTypeInference::getTermType)
                .map(DBTermType.class::cast)
                .map(DBTermType::getName)
                .orElse(null);
    }
    
    return typeName != null && typeName.toUpperCase().startsWith("ARRAY");
}
```

- [ ] **Step 2.4: 修改 serializeContains**

```java
@Override
protected String serializeContains(ImmutableList<? extends ImmutableTerm> terms,
                                    Function<ImmutableTerm, String> termConverter, TermFactory termFactory) {
    if (databaseInfoSupplier.isDoris() && isArrayColumn(terms.get(0))) {
        return String.format("array_contains(%s, %s)",
                termConverter.apply(terms.get(0)),
                termConverter.apply(terms.get(1)));
    }
    return String.format("INSTR(%s,%s) > 0",
            termConverter.apply(terms.get(0)),
            termConverter.apply(terms.get(1)));
}
```

- [ ] **Step 2.5: 验证编译**

```
mvn compile -pl db/rdb -DskipTests
```

Expected: BUILD SUCCESS

---

## Task 3: 全量编译验证

- [ ] **Step 3.1: 全量编译**

```
mvn compile -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 3.2: 运行现有单元测试确认回归**

```
mvn test -pl core/model,db/rdb -DskipITs
```

Expected: 全部通过

---

## Task 4: 单元测试（可选，建议但非必须）

如果已有 `MySQLDBTypeFactoryTest` 或 `MySQLDBFunctionSymbolFactoryTest` 测试类，添加以下测试：

### 4.1 MySQLDBTypeFactory 测试

| 测试用例 | 输入 | 期望结果 |
|---------|------|---------|
| `isDoris=true` 时 TypeMap 含 ARRAY | `getDBTermType("ARRAY")` | name=`"ARRAY"` 的 StringDBTermType |
| `isDoris=true` ARRAY<VARCHAR(64)> 解析 | `getDBTermType("ARRAY<VARCHAR(64)>")` | 同上 |
| `isDoris=true` ARRAY<CHAR(10)> 解析 | `getDBTermType("ARRAY<CHAR(10)>")` | 同上 |
| `isDoris=true` ARRAY<STRING> 解析 | `getDBTermType("ARRAY<STRING>")` | 同上 |
| `isDoris=false` ARRAY 不处理 | `getDBTermType("ARRAY")` | 非 StringDBTermType（或原始行为） |

### 4.2 MySQLDBFunctionSymbolFactory 测试

| 测试用例 | 输入 | 期望结果 |
|---------|------|---------|
| `isArrayColumn` true | typeName="ARRAY" 的 term | true |
| `isArrayColumn` false | typeName="VARCHAR" 的 term | false |
| serializeContains Doris + ARRAY | `isDoris=true`, ARRAY term | `array_contains(col, 'val')` |
| serializeContains Doris + VARCHAR | `isDoris=true`, VARCHAR term | `INSTR(col, 'val') > 0` |
| serializeContains MySQL | `isDoris=false` | `INSTR(col, 'val') > 0` |

---

## 文件修改清单

| 文件 | Step | 改动类型 |
|------|------|---------|
| `db/rdb/src/main/java/.../type/impl/MySQLDBTypeFactory.java` | 1.1-1.3 | 修改 |
| `db/rdb/src/main/java/.../functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java` | 2.1-2.4 | 修改 |
