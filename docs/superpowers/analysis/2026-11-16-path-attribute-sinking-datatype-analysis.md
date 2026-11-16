# 路径属性下沉与 datatype：问题、修复与遗留

日期：2026-11-16
状态：阶段一已修复并合入；阶段二与 datatype 改造已尝试并回退（附根因）
相关提交：`2a143912d9`（阶段一）

## 1. 现象

BOM 路径查询（`?n0 :parent_bom_item+ ?n1` + `path:maxDepth`）在内层子查询之外对 `?n0` / `?n1` 取属性时出现两类问题：

1. 对路径**起点变量** `?n0` 取 OPTIONAL 属性 → `InvalidIntermediateQueryException`（线上表现为 `EMPTY`，`resultCount: 0`）。
2. 对路径**节点变量** `?n1` 取**独立** OPTIONAL 属性 → 属性值全部为 `NULL`（`CONSTRUCT` 里 `n1_p0/NULL` …）。

## 2. 阶段一：sink 跨 projection scope 误吞（已修复）

### 诊断

在 reformulation 的 6 个阶段（converted / rewritten / simplified / unfolded / optimized / planned）逐段检查 IQ：

```
pathCol=true n1_p0=true n1_p5=true proj=[... 全部变量 ...] empty=false
```

`path_col_*` 与 `n1_p*` **全程存在**，因此「下沉列被优化器裁剪」的假设不成立。

### 根因

`isSinkProperty` 只按「谓词 IRI + subject 变量名」判定是否吞掉属性 pattern，**不区分 pattern 所在的 projection 层级**：

- 内层 `?n1 :bom_version ?v1_1`：与路径同 scope → 下沉到 CTE，正确；
- 外层 `OPTIONAL { ?n1 :material_code ?n1_p0 }`：subject 同名 `n1` → **也被吞**（返回 `TrueNode`）。该变量按 SPARQL 语义应由外层求值，被吞后既不在内层投影里输出，也没有回表 → `NULL`。

### 修复

引入 `pathTerminalScopes` / `pathStartScopes`（记录路径所在的 `Projection`），`isSinkProperty` 只在**同一 projection scope** 内生效：

```java
private static QueryModelNode enclosingProjection(QueryModelNode node) {
    QueryModelNode current = node;
    while (current != null) {
        if (current instanceof Projection) return current;
        current = current.getParentNode();
    }
    return null;
}
```

修复后外层 OPTIONAL 的属性改为正常翻译，生成回表 LEFT JOIN：

```sql
V11."BOM_VERSION" AS "BOM_VERSION1m49",
V11."BOM_ITEM_ID" AS "BOM_ITEM_ID1m57",
...
LEFT OUTER JOIN "PARENT_BOM" V11 ON ...
```

### 性能提示

- 独立 OPTIONAL × N → N 次 LEFT JOIN；
- 合并成一个 OPTIONAL 块 → 1 次 LEFT JOIN，且属性从 CTE 取值（已验证 `path_col_*` 出现）。

## 3. 阶段二：起点属性下沉（已回退）

### 目标

让 `?n0` 的属性也从路径 relation 取值，从而不再需要「起点主体的常量 IRI 改写」。

### 实现

- `HierarchySqlBuilder` 每层额外携带起点行属性列（`start_col_<index>`，index = `4 + nodeColumns.size() + i`）；
- `PathQuerySupportImpl.toRelation` 追加对应列；
- `translatePath` 收集起点属性（`sinkStartPropertyVariables` + `pathStartScopes`）并绑定到 `start_col_*`。

### 结果与回退原因

string 资源（`parent-bom-item-string`）6 项全过；但 `parent-bom-item`（`bom_item_id`/`bom_version` 为 **integer**）4 项回归变成 `EMPTY`。

原因：下沉时 `RDFLiteral` 统一按 `xsd:string` 包装，而该资源属性实际是 `xsd:integer`，`FILTER(?bid = 2)`（数值）类型不匹配 → type error → `FILTER` 视为 false → 空集。

结论：**起点属性下沉必须以「按属性真实 datatype 下沉」为前提**，因此回退，等待 datatype 支持。

## 4. datatype 改造（已回退）

### 尝试

- `PathSpec.Self` 增加 `nodeColumnTypes: Map<propertyIRI, datatypeIRI>`（保留旧构造器，默认空）；
- deriver 提取 datatype；
- `translatePath` 按 datatype 构造 `RDFLiteral`。

### 失败的根因（架构层面）

`QuestQueryProcessor` 只拿得到 `OBDASpecification.getSaturatedMapping()`。在 saturated mapping 中：

- 属性的 RDF 包装 `RDF(lexical, xsd:string)` **已被优化掉**，object 就是裸 `Variable`；
- 因此唯一可用的类型来源是 `SingleTermTypeExtractor`，它返回的是 **DB 类型**（integer/decimal），把 OBDA 显式声明的 `^^xsd:string` **覆盖**；
- 结果：`parent-bom-item-string`（声明 string）被下沉成 integer/decimal → `FILTER(?v = "1")`（字符串）失败 → `EMPTY`。

即：**OBDA 的 datatype 声明在 saturation 之后不可达**。

### 结论

`extractSingleTermType` 不是可靠来源（它反映 DB 类型，而非 SPARQL 声明）。

## 5. 对当前线上场景的影响

真实 OBDA 中所有属性均显式声明 `^^xsd:string`（含 `bom_item_id`、`quantity`）。现有实现「统一下沉为 `xsd:string`」**与其声明一致**，因此：

- 下沉后的属性与回表时 datatype 一致；
- 字符串字面量比较（`"3517912884"`、`"1"`）正确；
- 阶段一的 scope 修复 + 合并 OPTIONAL 方案可继续使用。

## 6. 后续方案（未实施）

要真正支持混合 datatype，需要让「属性 IRI → datatype」在查询期可达，两条路：

1. 给 `OBDASpecification` 增加 `getMapping()`（未饱和），deriver 用它取 datatype、用 saturated mapping 取结构；
2. 或新增 `DatatypeRegistry`，在 `MappingDatatypeFiller` 之后填充，随 `OBDASpecification` 传给 `QuestQueryProcessor`。

涉及模块：`core/obda`、`mapping/core`、`engine/reformulation`。完成后可重启阶段二（起点属性下沉）。

## 7. 当前可用写法

```sparql
SELECT DISTINCT ?n0 ?n1 ?n2 ?n1_p1 ?n1_p5 ?n2_p1 WHERE {
  { SELECT DISTINCT ?n0 ?n1 ?n2 WHERE {
      ?n0 a cls:bom_item ;
          prop:parent_bom_item+ ?n1 ;
          prop:bom_item_id ?v0 ;
          path:maxDepth 2 .
      FILTER(?v0 = "3517912884")
      ?n1 prop:has_component ?n2 ;
          prop:bom_version ?v1 .
      FILTER(?v1 = "1")
    } LIMIT 200 }

  OPTIONAL { <cls:bom_item/3517912884> prop:material_code ?n0_p0 . }   # 起点用常量 IRI

  OPTIONAL {                                                            # 节点属性合并为 1 块
    ?n1 prop:material_code ?n1_p0 ;
        prop:bom_item_id ?n1_p1 ;
        prop:bom_code ?n1_p2 ;
        prop:unit ?n1_p3 ;
        prop:process_code ?n1_p4 ;
        prop:bom_version ?n1_p5 .
  }

  OPTIONAL { ?n2 prop:color ?n2_p1 . }
}
```

要点：

1. 起点 `?n0` 的属性用**常量 IRI** 作主体（避免跨 scope 的 IRI 反解）；
2. `?n1` 的属性合并成**一个** OPTIONAL 块（1 次 join，且走 CTE）；
3. 始终带 `LIMIT`；
4. 字符串属性用字符串字面量比较。
