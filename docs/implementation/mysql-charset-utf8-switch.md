# MySQL CHARACTER SET UTF8 配置开关实现说明

## 1. 背景

在使用 MySQL 协议时，OnTop 生成的 SQL 语句中的 CAST 表达式会包含 `CHARACTER SET utf8`，例如：

```sql
SELECT DISTINCT CAST(v1.factory_code AS CHAR CHARACTER SET utf8) AS `v0`
FROM (...) v1
WHERE v1.factory_code IS NOT NULL
LIMIT 10
```

某些业务场景下需要关闭此行为，生成简化的 SQL：

```sql
SELECT DISTINCT v1.factory_code AS `v0`
FROM (...) v1
WHERE v1.factory_code IS NOT NULL
LIMIT 10
```

## 2. 实现方案

### 2.1 设计思路

通过 `DatabaseInfoSupplier` 接口添加一个默认方法 `isIncludeCharacterSetUtf8()`，MySQL 相关的工厂类通过注入该接口来获取配置值，决定是否在 CAST 表达式中包含 `CHARACTER SET utf8`。

- **默认行为**：包含 `CHARACTER SET utf8`（向后兼容）
- **关闭方式**：设置 `ontop.mysql.includeCharacterSetUtf8=false`

### 2.2 涉及的源代码文件

| 文件路径 | 修改类型 | 说明 |
|---------|---------|------|
| `core/model/src/main/java/it/unibz/inf/ontop/dbschema/DatabaseInfoSupplier.java` | 新增方法 | 添加 `isIncludeCharacterSetUtf8()` 默认方法 |
| `core/model/src/main/java/it/unibz/inf/ontop/dbschema/impl/DatabaseInfoSupplierImpl.java` | 修改 | 实现 `isIncludeCharacterSetUtf8()`，从 `OntopModelSettings` 读取配置值 |
| `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java` | 新增常量 | 定义配置键 `ontop.mysql.includeCharacterSetUtf8` |
| `db/rdb/src/main/java/it/unibz/inf/ontop/model/type/impl/MySQLDBTypeFactory.java` | 修改 | 注入 `DatabaseInfoSupplier`，根据开关动态设置 TEXT 类型的 castName |
| `db/rdb/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java` | 修改 | 在 `serializeDateTimeNorm()` 方法中根据开关控制 datetime 转字符串时的 CAST 格式 |

## 3. 详细修改

### 3.1 DatabaseInfoSupplier.java

**文件**: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/DatabaseInfoSupplier.java`

在接口中添加默认方法：

```java
/**
 * MySQL-specific flag for CAST to CHAR with CHARACTER SET utf8.
 * Default is true for backward compatibility.
 */
default boolean isIncludeCharacterSetUtf8() {
    return true;
}
```

**说明**: 使用 `default` 修饰符确保向后兼容，所有现有的 `DatabaseInfoSupplier` 实现类无需修改。

### 3.2 MySQLDBTypeFactory.java

**文件**: `db/rdb/src/main/java/it/unibz/inf/ontop/model/type/impl/MySQLDBTypeFactory.java`

**修改点 1**: 添加 `DatabaseInfoSupplier` 导入和字段

```java
import it.unibz.inf.ontop.dbschema.DatabaseInfoSupplier;

public class MySQLDBTypeFactory extends DefaultSQLDBTypeFactory {

    private final boolean includeCharSetUtf8;

    @AssistedInject
    protected MySQLDBTypeFactory(@Assisted TermType rootTermType, @Assisted TypeFactory typeFactory,
                                 DatabaseInfoSupplier databaseInfoSupplier) {
        super(createMySQLTypeMap(rootTermType, typeFactory, databaseInfoSupplier), createMySQLCodeMap());
        this.includeCharSetUtf8 = databaseInfoSupplier.isIncludeCharacterSetUtf8();
    }
```

**修改点 2**: 动态设置 TEXT 类型的 castName

```java
protected static Map<String, DBTermType> createMySQLTypeMap(TermType rootTermType, TypeFactory typeFactory,
                                                             DatabaseInfoSupplier databaseInfoSupplier) {
    // ...

    // Overloads NVARCHAR to insert the precision
    String charCastName = databaseInfoSupplier.isIncludeCharacterSetUtf8()
            ? "CHAR CHARACTER SET utf8" : "CHAR";
    StringDBTermType textType = new StringDBTermType(TEXT_STR, charCastName, rootAncestry,
            typeFactory.getXsdStringDatatype());

    // ...
}
```

**说明**: `MySQLDBTypeFactory` 在创建 TEXT 类型时，原本硬编码使用 `"CHAR CHARACTER SET utf8"` 作为 castName。现在根据 `databaseInfoSupplier.isIncludeCharacterSetUtf8()` 的返回值动态决定使用 `"CHAR CHARACTER SET utf8"` 还是 `"CHAR"`。

### 3.3 MySQLDBFunctionSymbolFactory.java

**文件**: `db/rdb/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/MySQLDBFunctionSymbolFactory.java`

**修改方法**: `serializeDateTimeNorm()`

```java
protected String serializeDateTimeNorm(DBTermType dbDateTimestampType,
                                       ImmutableList<? extends ImmutableTerm> terms,
                                       Function<ImmutableTerm, String> termConverter) {

    String charType = databaseInfoSupplier.isIncludeCharacterSetUtf8()
            ? "CHAR(30) CHARACTER SET utf8" : "CHAR(30)";
    String dateTimeStringWithoutTz = String.format("REPLACE(CAST(%s AS " + charType + "),' ', 'T')",
            termConverter.apply(terms.get(0)));

    return dbDateTimestampType.getName().equals(TIMESTAMP_STR)
            ? String.format("CONCAT(%s,%s)", dateTimeStringWithoutTz, CURRENT_TZ_STR)
            : dateTimeStringWithoutTz;
}
```

**说明**: 该方法用于将 DATETIME/TIMESTAMP 类型转换为字符串格式。原本硬编码使用 `CHAR(30) CHARACTER SET utf8`，现在根据开关动态选择。

### 3.4 DatabaseInfoSupplierImpl.java

**文件**: `core/model/src/main/java/it/unibz/inf/ontop/dbschema/impl/DatabaseInfoSupplierImpl.java`

实现 `isIncludeCharacterSetUtf8()` 方法，从 `OntopModelSettings` 读取配置值：

```java
@Override
public boolean isIncludeCharacterSetUtf8() {
    return settings.getProperty(OntopModelSettings.INCLUDE_MYSQL_CHARSET_UTF8)
            .map(Boolean::parseBoolean)
            .orElse(true);
}
```

**说明**: 如果未设置 `ontop.mysql.includeCharacterSetUtf8`，默认返回 `true`（包含 CHARACTER SET utf8），确保向后兼容。

### 3.5 OntopModelSettings.java

**文件**: `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java`

定义配置键常量：

```java
String INCLUDE_MYSQL_CHARSET_UTF8 = "ontop.mysql.includeCharacterSetUtf8";
```

**说明**: 所有 MySQL 相关配置统一使用 `ontop.mysql.` 前缀，便于管理。

## 4. 使用方法

### 4.1 默认行为

不配置任何参数时，默认包含 `CHARACTER SET utf8`，确保向后兼容。

### 4.2 关闭 CHARACTER SET UTF8

在 Ontop 配置文件（如 `ontop.properties`）中添加：

```properties
ontop.mysql.includeCharacterSetUtf8=false
```

或在代码中构建配置时：

```java
OntopSQLAllOWLAPIConfigurationImpl.builder()
    .jdbcUrl("jdbc:mysql://host:port/database")
    .property("ontop.mysql.includeCharacterSetUtf8", "false")
    .build();
```

### 4.3 效果对比

**开关开启（默认）时生成的 SQL**:
```sql
SELECT DISTINCT CAST(v1.factory_code AS CHAR CHARACTER SET utf8) AS `v0`
FROM (...) v1
```

**开关关闭时生成的 SQL**:
```sql
SELECT DISTINCT v1.factory_code AS `v0`
FROM (...) v1
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

CAST 表达式的目标类型由 `DBTermType.getCastName()` 返回。`MySQLDBTypeFactory` 中的 TEXT 类型通过 `StringDBTermType` 实现，其 `getCastName()` 返回 `"CHAR CHARACTER SET utf8"` 或 `"CHAR"`。

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

## 7. 测试状态

**当前状态**: 无针对此开关的自动化测试。

建议后续补充集成测试，验证开关关闭后：
- CAST 表达式中不包含 `CHARACTER SET utf8`
- DATETIME/TIMESTAMP 格式化 SQL 不包含 `CHARACTER SET utf8`

## 8. 后续建议

1. **功能测试**: 建议编写或更新 MySQL 相关的集成测试，验证开关关闭后 SQL 生成正确
2. **文档更新**: 在 OnTop 用户文档中添加此配置项的说明
3. **其他数据库**: 如有类似需求，可参考此模式在其他数据库的工厂类中实现
