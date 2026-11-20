# SPARQL 大列表 IN 改写触发 StackOverflowError：平衡 OR 折叠修复设计

日期：2026-11-20
状态：**已实施（Implemented）** —— 代码改动与 4 个单测均已落地并实测通过；剩余待办：`test/sparql-compliance` 全量 + 生产实跑（见 5.4 DoD）
关联：无

## 1. 问题

### 1.1 表象

生产环境 Ontop v5（Apache Doris 定制分支，后端 Doris，MySQL 协议，`ontop.mysql.doris=true`）执行形如下述的 SPARQL 查询时抛出 `java.lang.StackOverflowError`：

```sparql
?n0 a <.../class/mould> .
?n1 a <.../class/plant> .
?n0 <.../property/mould_of_plant> ?n1 .
?n1 <.../property/plant_code> ?v1_0 .
FILTER(?v1_0 IN ("6320" ...))   # 645 个字面量
```

外层再套 10 个 `OPTIONAL`。列表规模达到几百项时，查询在翻译/规范化阶段即爆栈，无法生成 SQL；小列表（如 20 项）则安全。

> **已实测记录**：645 项的 `FILTER(... IN (...))` 已实测抛出 `StackOverflowError`；生产另有 1001 项规模（6320–7320）的查询，该规模改用 `VALUES` 路径后实测不再爆栈（`IN` 路径未单独实测）。因此「临界点位于几百项」是推断值，需在实施前按 5.3 节的深度探针实测确认。

### 1.2 根因（调用链，附代码位置）

SPARQL `FILTER(?v IN (x1, x2, ..., xN))` 在 Ontop 内部被改写为 `(?v = x1) || (?v = x2) || ... || (?v = xN)`，其 `||` 用**二元**函数符号两两组合。当前实现采用 `Stream.reduce` 做**左深折叠**，产生深度为 **N** 的左深二叉树（本文深度口径 = 根到最深叶子的**函数项节点数**，叶子 `EQ` 计 1 层；等价说法是「N-1 层二元 OR + 1 层叶子」）；后续 `simplify` / `distinct` / `toString` 三处递归遍历整棵树，每一项都加深一层栈，最终压爆 JVM 栈。

**逐层调用链（已逐行核对）：**

| 层 | 位置 | 作用 |
|---|---|---|
| 1. 改写触发点 | `core/kg-query/.../query/translation/impl/RDF4JValueExprTranslator.java` 第 302-314 行 `getTerm(NAryValueOperator)` 的 `ListMemberOperator` 分支 | 把 `IN` 改写为链式 `LOGICAL_OR` |
| 2. 左深折叠 | 同上，第 306-313 行 `Stream.reduce` | N 个元素 → 深度 N 的左深二叉树（OR 层数 N-1） |
| 3. 二元 OR 符号 | `core/model/.../term/functionsymbol/impl/LogicalOrSPARQLFunctionSymbolImpl.java`（继承 `AbstractBinaryBooleanOperatorSPARQLFunctionSymbol`） | `SPARQL.LOGICAL_OR` 是**二元**符号，无法直接构造 N 元 OR |
| 4. 递归爆栈 | `core/model/.../term/functionsymbol/impl/FunctionSymbolImpl.java` 第 84-98 行，其中第 87-91 行对每个子项递归 `t.simplify(variableNullability)` | 项树深度 N → 递归帧数 ≈ N |
| 5. 第二层递归（压垮的最后一根稻草） | `core/model/.../term/impl/ImmutableFunctionalTermImpl.java`：第 126-128 行 `hashCode()` = `toString().hashCode()`；第 117-123 行 `equals()` = `toString().equals(...)`；第 96-111 行 `toString()` 递归遍历全部子项（第 102-104 行 `terms.stream().map(ImmutableTerm::toString)`） | `distinct()` 的哈希去重会把整棵子树再递归一遍 |
| 6. 触发哈希去重 | `core/model/.../term/impl/TermFactoryImpl.java` 第 201-211 行 `getConjunction(Stream)`，其中第 205 行 `.distinct()` | 惰性 distinct 在 `.collect()` 处真正执行哈希去重 |
| 7. **为什么没有防护** | 见 [第 7 章](#7-设计动因为什么-ontop-原始代码里没有做树平衡) | Ontop 的规模控制全部落在「元数」维度（`MAX_ARITY=8`），而本路径每个节点都是二元（arity=2）→ **永远触发不了那道闸门** |

当前改写实现（第 306-313 行）：

```java
ImmutableTerm firstArgument = extendedTerms.get(0).get();
ImmutableTerm orTerm = extendedTerms.stream()
        .map(ExistsMapAnnotatedObject::get)
        .skip(1)
        .map(t -> getFunctionalTerm(SPARQL.EQ, firstArgument, t))
        .reduce((e1, e2) -> getFunctionalTerm(SPARQL.LOGICAL_OR, e1, e2))
        .orElseThrow(() -> new MinorOntopInternalBugException("Cannot happen because there are at least 2 terms"));
return ExistsMapAnnotatedObject.of(extendedTerms, terms -> orTerm);
```

`Stream.reduce` 的累加器 `(e1, e2) -> OR(e1, e2)` 是左结合，产生 `OR(OR(OR(EQ1,EQ2),EQ3),...,EQn)` 的链式左深树。

**已观测生产栈（自顶向下 = 由内到外）：**

最内层（栈顶）：

```
AbstractDBStrictEqNeqFunctionSymbol.combineExpressions
  (core/model/.../term/functionsymbol/db/impl/AbstractDBStrictEqNeqFunctionSymbol.java:148-152)
  → TermFactoryImpl.getConjunction:206
  → HashSet.contains
  → ImmutableFunctionalTermImpl.hashCode:127
  → toString:104
  → DBConstantImpl.toString:38
```

栈底主体：上千层重复的

```
FunctionSymbolImpl.simplify:89/91
  → ImmutableFunctionalTermImpl.simplify:142
```

> 说明：栈帧显示 `getConjunction:206`（`.collect(...)` 行）而非 `:205`（`.distinct()` 行），二者并不矛盾——`.distinct()` 是惰性中间操作，真正的 `HashSet.contains` 在 206 行终端操作 `.collect()` 触发时才执行。

### 1.3 触发规模与临界点

- **安全规模**：20 项列表正常。
- **触发规模**：约**几百项**即触发 `StackOverflowError`（**已实测：645 项爆栈**；1001 项为生产实际规模，但其 `IN` 路径未单独实测，`VALUES` 路径实测未爆栈）。
- **深度关系**：左深折叠使项树深度 = **N**（本文口径：函数项节点数；OR 层数 = N-1）；`simplify`（第 4 层）、`distinct`→`hashCode`→`toString`（第 5 层）各自独立地、逐层地递归遍历整棵树。因此总递归帧数与 N 成线性正比关系，即**深度 ≈ k·N**（k 为常数因子）。
- **实测标度**：见 [第 6 章](#6-实测数据2026-11-20-取证)。结论：左深树所需栈**∝ N 线性增长**（100→136k、300→448k、645→768k、1001→>1MB），默认 1MB 线程栈下**临界 N ≈ 1000–1050**；平衡树所需栈**恒定不随 N 增长**。生产在 645 项即爆栈，是因为整条查询（10 个 `OPTIONAL` → `IF_ELSE_NULL` 嵌套 + 多个 LEFT JOIN）叠加的基线栈占用把临界点压低。

## 2. 目标与非目标

**目标：**

1. 消除大列表 `IN` 改写导致的 `StackOverflowError`，使 645 项、1001 项乃至更大列表均可正常翻译、规范化并生成 SQL。
2. 保证改动后生成的 SQL 与改动前**逐字节一致**（仅消除中间态的递归深度，不改变任何可观测语义）。
3. 最小化改动面：单文件、单方法。

**非目标：**

1. 不改变 SPARQL `IN` / `||` 的既有语义（Kleene 三值逻辑下的 error 语义保持不变）。
2. 不优化最终 SQL 形态（不新增/移除 `IN` 谓词、不改 OR 分支顺序）。
3. 不引入新的方言相关 normalizer 或 `VALUES` 改写（相关方向见第 4 章，仅作评估，不纳入本期）。
4. 不提升 `simplify` / `toString` / `equals` 本身的递归实现（这些是公共基础能力，改动面大、风险高）。

## 3. 设计

### 3.1 核心思路：左深折叠 → 平衡折叠

把 `ListMemberOperator` 分支里对 `LOGICAL_OR` 的**左深折叠**（`Stream.reduce`）替换为**平衡折叠**（两两配对、逐层合并），使项树深度从 O(N) 降为 O(log N)。1001 项时深度从 1000 降到约 10，从根本上消解递归爆栈。

因为 `LOGICAL_OR` 是二元符号，无法一步构造 N 元 OR，所以仍须两两组合；但组合的**树形**（左深 vs 平衡）对最终语义与最终 SQL 均无影响（见 3.3、3.4）。

**为什么这个改动方向成立（第 7 章的考古结论）**：Ontop 既有代码里没有任何「主动平衡 / 限制树深」的先例，其规模控制全部落在「元数」维度（`MAX_ARITY=8`），而本路径每个节点都是二元（arity=2）→ 永远触发不了那道闸门，属未被覆盖的盲区。本改动**只调整中间态树形、不动语义、不动最终产物**，是相对上游风格的最小偏离；第 6 章已实测「最终 SQL 逐字节不变」。

### 3.2 唯一改动点（文件 / 方法 / 行号 + 改前改后代码）

- **文件**：`core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JValueExprTranslator.java`
- **方法**：`private ExistsMapAnnotatedObject<ImmutableTerm> getTerm(NAryValueOperator expr)` 的 `ListMemberOperator` 分支（第 302-314 行）
- **新增 import**（该文件当前未导入）：`java.util.ArrayList`、`java.util.List`、`java.util.stream.Collectors`

**改前（第 302-314 行）：**

```java
if (expr instanceof ListMemberOperator) {
    if (extendedTerms.size() < 2)
        throw new MinorOntopInternalBugException("Was not expecting a ListMemberOperator from RDF4J with less than 2 terms");

    ImmutableTerm firstArgument = extendedTerms.get(0).get();
    ImmutableTerm orTerm = extendedTerms.stream()
            .map(ExistsMapAnnotatedObject::get)
            .skip(1)
            .map(t -> getFunctionalTerm(SPARQL.EQ, firstArgument, t))
            .reduce((e1, e2) -> getFunctionalTerm(SPARQL.LOGICAL_OR, e1, e2))
            .orElseThrow(() -> new MinorOntopInternalBugException("Cannot happen because there are at least 2 terms"));
    return ExistsMapAnnotatedObject.of(extendedTerms, terms -> orTerm);
}
```

**改后（已落地的实现）：**

```java
if (expr instanceof ListMemberOperator) {
    if (extendedTerms.size() < 2)
        throw new MinorOntopInternalBugException("Was not expecting a ListMemberOperator from RDF4J with less than 2 terms");

    ImmutableTerm firstArgument = extendedTerms.get(0).get();
    ImmutableTerm orTerm = getBalancedDisjunction(extendedTerms.stream()
            .map(ExistsMapAnnotatedObject::get)
            .skip(1)
            .map(t -> getFunctionalTerm(SPARQL.EQ, firstArgument, t))
            .collect(ImmutableCollectors.toList()));
    return ExistsMapAnnotatedObject.of(extendedTerms, terms -> orTerm);
}
throw new RuntimeException("Unreachable: all subclasses covered");
}

private ImmutableTerm getBalancedDisjunction(ImmutableList<? extends ImmutableTerm> terms) {
    return RDF4JValueExprTranslator.<ImmutableTerm>balancedFold(terms,
            (t1, t2) -> getFunctionalTerm(SPARQL.LOGICAL_OR, t1, t2));
}

/** 平衡折叠：包内可见，便于不启动 Ontop 注入器即可单测。 */
static <T> T balancedFold(ImmutableList<? extends T> items, BiFunction<T, T, T> combiner) {
    List<T> level = new ArrayList<>(items);
    while (level.size() > 1) {
        List<T> nextLevel = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            nextLevel.add(i + 1 < level.size()
                    ? combiner.apply(level.get(i), level.get(i + 1))
                    : level.get(i));
        }
        level = nextLevel;
    }
    return level.get(0);
}
```

改动量：**主文件 +19 / -6 行**（含 `getBalancedDisjunction` 与 `balancedFold` 两个方法、3 个 import）。

> 实现要点：
> 1. 等值项用 `ImmutableCollectors.toList()` 收集（复用该文件既有依赖，无需新增 `Collectors`），保持原有顺序；
> 2. 折叠逻辑抽成 `static <T> T balancedFold(...)`，**不依赖 Ontop 注入器** → 可在 `core/kg-query` 里用纯 Java 对象单测（该模块无法在测试期构建 `CoreSingletons` 注入器，见 5.1）；
> 3. 奇数项时最后一项原样进入下一轮；循环终止后只剩 1 个元素，即 OR 树根；
> 4. `balancedFold` 为包内可见（package-private），不扩大 public API。

### 3.3 语义等价性论证

1. **SPARQL `||` 是 Kleene 三值逻辑下的逻辑或，满足结合律与交换律。** SPARQL 1.1 §17.2.2 的 `||` 真值表（含 `error`，记 E）：
   - `T || x = T`（含 `T || E = T`）
   - `F || F = F`，`F || E = E`，`E || E = E`
   - 交换对称成立。

   该三值结构（Kleene 强三值逻辑 K3）的 OR/AND 运算满足结合律与交换律，因此平衡化（改变括号结合方式）与左深化在结果上完全等价。

2. **`||` 不做短路求值，且 Ontop 此处仅构造项树、不求值。** SPARQL 规范中 `||` 无短路语义；Ontop 的 `getTerm` 只构造 `ImmutableFunctionalTerm` 树，真正求值在后续 lowering/simplify 阶段对整棵树统一进行，树形不影响求值结果。

3. 因此平衡化与左深化在**语义上等价**，唯一的差异是中间态项树的形状（递归深度）。

### 3.4 最终 SQL 保持不变的论证

平衡化只改变 SPARQL 层 `SP_OR` 树的形状，不改变叶子（`EQ` 等值项）的内容与顺序。降级到 DB 层时：

1. `LogicalOrSPARQLFunctionSymbolImpl.computeExpression`（`core/model/.../term/functionsymbol/impl/LogicalOrSPARQLFunctionSymbolImpl.java` 第 15-19 行）调用 `termFactory.getDisjunction(subExpressions)`，把二元 SPARQL OR 降级为 DB OR。
2. `DefaultDBOrFunctionSymbol.computeNewDisjunction`（`core/model/.../term/functionsymbol/db/impl/DefaultDBOrFunctionSymbol.java` 第 58-88 行，**第 76-79 行**）会把嵌套的 DB_OR **拍平为 n 元 DB_OR**：

   ```java
   .flatMap(t -> (t.getFunctionSymbol() instanceof DBOrFunctionSymbol)
           ? t.getTerms().stream()
               .map(s -> (ImmutableExpression)s)
           : Stream.of(t))
   ```

   该拍平是**自底向上逐层吸收**的：`simplify` 先化简最内层 OR（得到一个 n₁ 元 DB_OR），父层化简时再把这个 DB_OR 子项展开并入自身，层层递推。平衡折叠与左深折叠在每一层展开后得到的叶子序列都保持 `EQ1, EQ2, ..., EQn` 的原序（平衡折叠每轮只配对相邻元素，故其**中序叶子序列与左深树完全一致**），因此层层吸收后得到**同一个** n 元 DB_OR 项。
3. 后续序列化输出该 n 元 DB_OR 的 SQL 文本，与树形无关。

**必须注意的一个反直觉事实**（实测发现，见第 6 章）：`SPARQL.LOGICAL_OR` 在 `simplify()` 阶段**并不会自行降级**为 DB_OR——`AbstractBinaryBooleanOperatorSPARQLFunctionSymbol.buildTermAfterEvaluation`（`core/model/.../term/functionsymbol/impl/AbstractBinaryBooleanOperatorSPARQLFunctionSymbol.java` 第 32-53 行）要求**所有子项都是「RDF 函数项或常量」**（第 35-36 行），而 `SPARQL.EQ(v1_0, "6320")` 的子项不满足该条件，于是第 52 行直接 `return termFactory.getImmutableFunctionalTerm(this, newTerms)`，**原样保留 SP_OR 树**（实测：N=645 时 `simplify()` 后仍是 `SP_OR/arity=2`，**OR 层数 644**）。真正的降级发生在**变量被 DB 定义替换之后**（生产路径），此时产出的是嵌套二元 DB_OR，再由第 2 步逐层拍平。

结论：**平衡树与左深树拍平后是同一个项 → 序列化输出必然相同**。本次改动纯粹消除中间态递归深度，不改变任何可观测语义，回归范围天然收窄到 SPARQL 合规测试。

> **证据强度（请勿过度解读）**：第 6.4 节实测证实的是「**项级** `toString()` 逐字节相同（N = 3…1001 全部 `finalEq = true`）」；由此到「**端到端 SQL 文本逐字节相同**」还差一步序列化确定性推理，且实测树形是**人工构造的 DB 层模拟**而非端到端。**端到端 SQL diff 为空**必须在 5.3 的生产验证中实测确认。

### 3.5 明确不改动的部分（边界）

- **不改** `FunctionSymbolImpl.simplify`（第 84-98 行）的递归实现。
- **不改** `ImmutableFunctionalTermImpl` 的 `toString` / `equals` / `hashCode`（第 96-128 行）——这是 `distinct()` 依赖的公共等价性实现，改动面大、风险高。
- **不改** `TermFactoryImpl.getConjunction` 的 `.distinct()`（第 205 行）——它是惰性去重的正确行为。
- **不改** `LogicalOrSPARQLFunctionSymbolImpl` / `DefaultDBOrFunctionSymbol`——其拍平逻辑已保证 SQL 不变。
- **不改** 任何方言 normalizer、mapping 或序列化器。

## 4. 替代方案与取舍

### 4.1 方案二：改写为 SPARQL VALUES

把 `FILTER(?v IN (...))` 换成同 group 内的 `VALUES ?v { "6320" ... }`。**已实测可行，可立刻止血**（不再 SOE）。

**代价（已实测/已核实）：**

- Ontop 的 `VALUES` 路径在 MySQL/Doris 方言下，被 `db/rdb/.../generation/normalization/impl/MySQLExtraNormalizer.java`（第 15-17 行）挂上的 `ConvertValuesToUnionNormalizer`（同目录）转换，生成 **N 支 `UNION ALL`**（每支带 `FROM (SELECT 1) tdummy`），实测 1001 项即 1001 支。
- 过滤条件退化成 `JOIN + 等值条件`，**丢掉了 Doris 的 `OrToIn` 谓词下推红利**（Doris Nereids 的 `OrToIn` 规则见 apache/doris PR #21326、#46468），执行计划反而更差。
- 另：SPARQL `VALUES` 在 `RDF4JTupleExprTranslator.translate(BindingSetAssignment)`（第 802-837 行）被翻译成 UNION of Construction/True；`UnionNormalizerImpl`（`core/model/.../iq/node/normalization/impl/UnionNormalizerImpl.java` 第 88-129 行）可能再把它合并回单个 `ValuesNode`；SQL 序列化时 `DefaultSelectFromWhereSerializer.visit(SQLValuesExpression)`（`db/rdb/.../generation/serializer/impl/DefaultSelectFromWhereSerializer.java` 第 416-429 行）本可输出 `(VALUES (...),(...)) AS t(c)` 表构造器（第 405 行生成的元组形式为 ` (..)`，无 `ROW` 关键字）。

**结论**：可作为**紧急止血**手段（改动在映射/查询层，不触及 Ontop 代码），但 SQL 形态退化为 UNION，长期性能差于原生 `IN` 谓词下推。

### 4.2 方案三：Doris 侧直出 (VALUES ...) 表构造器（可选叠加）

Doris 的 Nereids 语法原生支持表构造器：

- `DorisParser.g4`：`queryPrimary: ... | inlineTable #valuesTable`，`inlineTable: VALUES rowConstructor (COMMA rowConstructor)*`；
- 其语法正是 `VALUES (..)` **无 ROW 关键字**，与 Ontop 第 405 行生成的形态一致；而 MySQL 8.0.19+ 要求 `VALUES ROW(..)`，故**不能对 MySQL 放开**。

**落点**：为 Doris 准备一个不含 `ConvertValuesToUnionNormalizer` 的专用 `DialectExtraNormalizer`，让 `ValuesNode` 直接序列化为 `(VALUES ...) AS t(c)` 表构造器。

**前置条件（需在实施前确认）**：目标 Doris 版本使用 Nereids 优化器（3.x 为唯一优化器，2.1+ 为默认）——需先确认生产 Doris 版本。

**补充**：Doris 侧有 `MergeOneRowRelationIntoUnion` 规则会把常量 `UNION ALL` 分支折叠进 `LogicalUnion.constantExprsList`，因此即使不放开 `ConvertValuesToUnionNormalizer` 也不会生成 N 个 fragment——但仍不如原生 `IN` 谓词。

**结论**：与方案二正交、可叠加，但收益有限且引入方言分叉与版本前提，本期**不纳入**。

### 4.3 方案四：过滤条件下推到映射 source SQL

用户的 OBDA `source` 是裸 SQL（`mapping/sql/native/.../spec/mapping/parser/impl/OntopNativeMappingParser.java` 第 221-225 行把 source 字符串直接当 SQL 交给数据库执行），可在 source 里写 `WHERE plant_code IN (...)` 让过滤在 DB 侧完成。

**代价**：条件写死在映射里，丧失动态性（列表值变化需改映射）。

**结论**：仅适用于固定列表的静态场景，不通用。

### 4.4 方案对比表

| 维度 | 方案一（平衡折叠，本设计） | 方案二（SPARQL VALUES） | 方案三（Doris 直出 VALUES 表构造器） | 方案四（下推到 source SQL） |
|---|---|---|---|---|
| 代码量 | +12/-5 行，单文件单方法 | 仅改映射/查询（Ontop 零改动） | 新增一个 `DialectExtraNormalizer` + 方言绑定 | 仅改映射 |
| 语义风险 | 无（Kleene 三值 `||` 满足结合律） | 低（VALUES 语义等价） | 低（表构造器语义等价，但需版本前提） | 中（动态性丧失、写死条件） |
| 回归范围 | SPARQL 合规测试（SQL 逐字节不变） | 全量（SQL 形态剧变 → UNION） | 全量（方言分叉） | 映射相关场景 |
| 最终 SQL 形态 | 与现状**完全一致**（原生 `IN`/`OR` 谓词） | N 支 `UNION ALL`（性能退化） | `(VALUES ...) AS t(c)`（需 Nereids） | `WHERE plant_code IN (...)`（写死在 source） |
| 对 Doris 执行计划的影响 | 无（保留 `OrToIn` 谓词下推） | 差（丢 `OrToIn`，JOIN + 等值） | 一般（表构造器 join，或可被折叠） | 好（DB 侧直接过滤） |
| 估算工时 | 0.5 人日（含单测） | 0（止血，改映射） | 2-3 人日（需版本确认 + 回归） | 0.5 人日（按查询逐个改） |

**结论**：方案一改动最小、语义与 SQL 均不变、回归面最窄，为**首选**；方案二可作为改动上线前的**紧急止血**。

## 5. 测试策略

### 5.1 单元测试（已实现）

`core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/impl/RDF4JValueExprTranslatorListMemberTest.java`，共 4 个用例，**实测 4/4 通过**：

| 用例 | 断言 |
|---|---|
| `foldIsBalancedInsteadOfLeftDeep` | N ∈ {2, 3, 4, 20, 200, 1001}：折叠高度 = ⌈log₂N⌉（节点数深度 = ⌈log₂N⌉+1）；N ≥ 4 时严格小于左深的 N−1 |
| `foldPreservesTheOrderOfTheItems` | N = 1001：中序叶子序列与输入完全一致（保证最终 SQL 的 OR 分支顺序不变） |
| `foldAppliesTheCombinerExactlyOncePerPair` | N ∈ {1, 2, 3, 20, 1001}：combiner 恰好调用 N−1 次（与左深折叠的次数一致，即不改变项数） |
| `singleItemIsReturnedAsIs` | N = 1：直接返回该元素，不引入任何 OR |

#### 5.1.1 为什么单测只覆盖"折叠算法"而非"项级"（限制说明）

在 `core/kg-query` 的测试 classpath 下，`OntopModelConfiguration.defaultBuilder()...build()` 产生的注入器**缺少 `CoreSingletons`、`DBFunctionSymbolFactory`、`IntermediateQueryFactory`、`SingleTermTypeExtractor` 等绑定**（实测 `Guice/MissingImplementation`），且 `OntopModelModule` 的构造器为 `protected` / 需 `OntopModelSettings` —— 该模块**没有可用的注入器测试设施**（仓库内它仅有一个不依赖注入器的 `HierarchySqlBuilderTest`）。

因此测试策略为：
- **本单测**覆盖本次唯一改动点——折叠算法（树深 / 顺序 / 调用次数 / 单元素），用纯 Java 对象 `Node` 观测树形，零依赖；
- **项级 `simplify()` 不抛 SOE** 与**端到端 SQL 不变**，由第 6 章的独立诊断程序（仓库外，已实测 `finalEq = true`）与 5.3 的生产实跑覆盖。

### 5.2 SPARQL 合规回归

跑 `test/sparql-compliance`（`MemorySPARQL11QueryTest`、`MemorySPARQLOntopQueryTest`）全量——这是最耗时、也是最关键的一环，验证 `IN` / `||` 的 error 语义与结果集均无回归。

### 5.3 生产场景验证

- 645 项与 1001 项的 `FILTER(?v IN (...))` 各跑一次，确认：
  1. 无 `StackOverflowError`；
  2. 生成的 SQL 与改动前**逐字节一致**（diff 为空）。
- 第 6 章的隔离实测已给出**参照基线**：DB 层 OR 链在默认 1MB 栈下 **N = 1001 通过、N = 1050 溢出**；生产在 645 项即溢出，说明整条查询的基线栈占用约相当于把临界点从 ~1000 压到 ~645。改动后应观察到：同一查询的 OR 链深度从 645 降至 11，且 SQL 不变。
- 建议在 `ImmutableFunctionalTermImpl.simplify`（第 141-143 行）加 `ThreadLocal` 深度探针作为确诊/回归观测手段（打印最大递归深度与当时的函数符号名），在排查期启用。

### 5.4 验收标准（DoD）、回滚与兼容性

**完成判定（须全部满足）**：

1. `mvn -o -pl core/kg-query -am test` 通过（**注**：`HierarchySqlBuilderTest.minDepthStartsFromTheRequestedLevel` 为**既有失败**，与本改动无关，已在基线（stash 掉本改动）复现；本改动的 4 个新用例全部通过）；
2. `test/sparql-compliance` 全量通过（`MemorySPARQL11QueryTest`、`MemorySPARQLOntopQueryTest`）；
3. 新增单测（`RDF4JValueExprTranslatorListMemberTest`，4 用例）通过：折叠高度 = ⌈log₂N⌉、中序顺序不变、combiner 调用 N−1 次、N=1 直接返回；
4. 生产查询（645 项与 1001 项）实跑无 `StackOverflowError`；
5. **端到端 SQL 与改动前逐字节 diff 为空** —— 这是本改动的核心不变量，**不可跳过**（第 6.4 节的实测只到「项级」，端到端须在此步确认）。

**回滚**：改动仅涉及单文件 `RDF4JValueExprTranslator.java`，`git revert` 该 commit 即可；回滚后重跑第 3、5 项确认行为回到改动前。

**兼容性影响**：无。不新增/修改配置项，不改 public API，不触及 SQL 生成器与方言层；仅影响 SPARQL→IQ 翻译阶段中间态项树的**形状**。

## 6. 实测数据（2026-11-20 取证）

> **方法**：在**仓库之外**（系统临时目录）编译一个独立诊断程序，直接调用仓库已编译的类，**未修改仓库任何文件**。
> 诊断程序做两件事：
> - **A（SPARQL 层，真实生产路径）**：调用 `RDF4JValueExprTranslator.getTerm(ListMemberOperator)`（第 302-314 行），得到生产实际产生的 OR 树；
> - **B（DB 层，生产 SOE 所在层）**：复现变量替换后的 DB 级等值/析取树（叶子 `DB_EQ_2(col, '6320')`，OR 节点为二元 `DB_OR`）。
> 对两种树分别用**左深折叠**与**平衡折叠**构造，测量项树深度、`simplify()` 是否 `StackOverflowError`、以及 `simplify()` 后的最终项是否逐字节相同。
> 依赖 classpath 取自仓库已有的 `analysis/doris-ab/ws-cp.txt`（workspace classes）与 `analysis/doris-ab/cp.txt`（m2 依赖）。

### 6.1 项树深度

| N | 左深深度 | 平衡深度 |
|---|---|---|
| 3 | 3 | 3 |
| 20 | 20 | 6 |
| 100 | 100 | 8 |
| 300 | 300 | 10 |
| 645 | 645 | 11 |
| 1001 | 1001 | 11 |

> **深度口径（全文统一）**：本章及全文所称「深度」= 根到最深叶子的**函数项节点数**，叶子 `EQ` 计 1 层。因此左深 = **N**，平衡 = **⌈log₂N⌉+1**；若改按「OR 层数（边数）」表述，则分别为 **N-1** 与 **⌈log₂N⌉**。第 6.5 节的数字均为「OR 层数」。

→ **N = 1001 时深度由 1001 降至 11**（两个数量级）。

### 6.2 栈需求（DB 层，`-Xss` 扫描，测「能跑通的最小栈」）

| N | 左深最小栈 | 平衡最小栈 |
|---|---|---|
| 100 | 136k | 136k |
| 300 | **448k** | 136k |
| 645 | **768k** | 136k |
| 1001 | **> 1MB** | 136k |

- 左深：**∝ N 线性增长**，斜率 ≈ 0.8k/N；
- 平衡：**恒定不随 N 增长**（N = 3000 仍全部 OK）。

### 6.3 默认 1MB 线程栈下的临界点

| N | 左深 | 平衡 |
|---|---|---|
| 1001 | OK | OK |
| 1050 | **SOE** | OK |
| 1200 / 1500 / 2000 / 3000 | **SOE** | OK |

→ **单独这条 OR 链在默认栈下的临界 N ≈ 1000–1050**。生产在 645 项即爆栈，是因为整条查询（10 个 `OPTIONAL` → `IF_ELSE_NULL` 嵌套 + 多个 LEFT JOIN）叠加的**基线栈占用**把临界点压低——这解释了「645 项就爆」而非「1000 项才爆」。

### 6.4 「SQL 逐字节一致」的实测验证

| N | 左深 simplify 后 | 平衡 simplify 后 | 最终项逐字节相同 | 结果形态 |
|---|---|---|---|---|
| 3 | OK | OK | **true** | `OR3/arity=3/depth=2` |
| 20 | OK | OK | **true** | `OR20/arity=20/depth=2` |
| 100 | OK | OK | **true** | `OR100/arity=100/depth=2` |
| 300 | OK | OK | **true** | `OR300/arity=300/depth=2` |
| 645 | OK | OK | **true** | `OR645/arity=645/depth=2` |
| 1001 | OK | OK | **true** | `OR1001/arity=1001/depth=2` |

**两种树形在 DB 层化简后得到完全相同的 n 元 `DB_OR`**（元数相同、`toString()` 长度相同、内容逐字节相同）→ 3.4 节的论证成立，**最终 SQL 不会因本改动而变化**。

### 6.5 一条反直觉实测

对 SPARQL 层 OR 树单独调用 `simplify()`，结果**仍是 `SP_OR`（元数 2）**，不会降级为 DB_OR，也就不会被拍平（下表 `depth` 列为 **OR 层数**，即 N-1）：

| N | simplify 后形态 |
|---|---|
| 20 | `SP_OR/arity=2/depth=19` |
| 100 | `SP_OR/arity=2/depth=99` |
| 645 | `SP_OR/arity=2/depth=644` |

原因见 3.4 节「必须注意的一个反直觉事实」：降级要求子项均为 RDF 函数项/常量，而 `SPARQL.EQ(v1_0, "6320")` 不满足。**推论**：本改动的价值在于**从源头把项树深度降下来**，从而同时压低 `simplify` / `toString` / `hashCode` / `flattenAND` / `flattenOR` 这若干条 O(深度) 递归路径的栈压力；它不依赖 DB 层是否拍平。

### 6.6 实测的边界与局限（必须在实施前知悉）

1. **未在生产查询上端到端复现 SOE**：诊断程序只驱动了 OR 树本身，未包含生产查询的 10 个 `OPTIONAL` / `IF_ELSE_NULL` 嵌套，因此默认栈下 N ≤ 1001 不复现。生产基线栈占用需另行测定。
2. **诊断程序自身存在 O(N) 递归辅助函数**（`depth()`），在 `-Xss ≤ 120k` 时会先于被测代码溢出——该现象为**探针假象**，不是 Ontop 的结论。低于 136k 的栈数据不可用。
3. **DB 层树是「按生产形状人工构造」的**（`DB_EQ_2(col, '6320')` + 二元 `DB_OR`），而非从真实 IQ 树中提取；生产中 `?v1_0` 会被替换为 `RDF(VARCHARToTEXT(...), ...)` 形式（见 reformulated SQL 的 CONSTRUCT 段），会再多一层 RDF 包装，**栈需求只会更高、不会更低**。

## 7. 设计动因：为什么 Ontop 原始代码里没有做树平衡

> 本章回答「为什么不修」而不是「怎么修」。结论先行：**从证据看，这更可能不是刻意设计，而是「历史沿革 + 未预料规模 + 规模控制哲学只管元数不管树深」三者叠加（推断）；更关键的是——这条路径恰好绕过了作者唯一的规模闸门（`MAX_ARITY=8`），成为一个盲区（此项有代码证据）。**

### 7.1 起点：2021-09-29，为「给 SPARQL 补上 IN」而写

| 证据 | 内容 |
|---|---|
| 引入 commit | `31b0ad5844`（2021-09-29，Benjamin Cogrel），标题直白：*"SPARQL IN is now converted into a disjunction."* |
| 改动前 | `test/sparql-compliance/.../MemorySPARQL11QueryTest.java` 中 `in01`、`in02` 两条合规用例上方写着 `//not supported in SPARQL transformation` → **Ontop 当时根本不支持 SPARQL `IN`** |
| 改动后 | 合规测试解除排除；新增回归用例 `MirrorViewPersonTest.testFullNameIn`，其 IN 列表**只有 2 项** |
| 原始实现形态 | ```java<br>SPARQLFunctionSymbol eq = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.EQ, 2);<br>SPARQLFunctionSymbol or = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LOGICAL_OR, 2);<br>return argTerms.stream().skip(1)<br>        .map(t -> termFactory.getImmutableFunctionalTerm(eq, firstArgument, t))<br>        .reduce((e1, e2) -> termFactory.getImmutableFunctionalTerm(or, e1, e2))<br>``` |

**后续两次改动都没碰折叠算法**：
- `9d289b1a11`（2023-02-06，kontchakov）*"second step of splitting RDF4JQueryTranslatorImpl"*：`RDF4JValueExprTranslator.java` +391 行 / `RDF4JTupleExprTranslator.java` -390 行 / `RDF4JQueryTranslatorImpl.java` -15 行 → **纯代码搬迁**；
- `8cd0ae2ce90`（2025-10-29，Roman Kontchakov）：去重重构，折叠逻辑未变。

→ **形态自 2021 年起稳定存在 5 年，从未被重新审视。**

### 7.2 为什么是「二元链」而不是 n 元 `DB_OR`：结构性必然，但上游其实给了 n 元

- **RDF4J 给的原始形状是 n 元**：`ListMemberOperator`（`In`/`NotIn` 的超类）继承 `NAryValueOperator`，`getArguments()` 返回 `[被测值, elem1, …, elemN]`。Jena 侧同理：`E_OneOf`（`E_OneOfBase.evalOneOf` 用 for 循环求值，**不物化 OR 树**）。**n 元能力是上游白送的，左深链不是 API 形状的必然结果。**
- **但 Ontop 这一层只有二元 SPARQL 符号**：`FunctionSymbolFactoryImpl:180` 中 `new LogicalOrSPARQLFunctionSymbolImpl(xsdBoolean)` 是**单实例注册、不带 arity 参数**；对比同文件 `ReplaceSPARQLFunctionSymbolImpl(3, …)` / `(4, …)` 的按元数注册惯例，可见「`||` 二元」是刻意建模（SPARQL 规范里 `||` 本就二元）。
- **且此时还不能跳到 DB 层**：`SPARQL.EQ` 是 SPARQL 层的「=」（带 error 语义），不是 DB 严格等值；n 元 `DB_OR` 只存在于 DB 层（`getDBOr(arity)`）。

⇒ 只能先折成二元 SPARQL OR 链，等 SPARQL→DB 化简时由 `LogicalOrSPARQLFunctionSymbolImpl.computeExpression → getDisjunction(flattenOR)` 拍平成单一 n 元 `DB_OR`。**左深是这一约束下最省事的写法**（`Stream.reduce` 的二元累加器天然左结合，零成本默认结果），而非刻意选择。

### 7.3 作者的规模控制哲学，与它留下的盲区

Ontop 控制复杂度的**全部**手段都落在「元数」维度：

| 机制 | 位置 | 性质 |
|---|---|---|
| `MAX_ARITY = 8` | `DisjunctionOfEqualitiesMergingSimplifierImpl:26`，闸门在 `:192` / `:275` / `:443` / `:446` | 限**元数** |
| `getDBAnd` / `getDBOr` | `AbstractSQLDBFunctionSymbolFactory:1099-1110` | 仅校验 `arity >= 2`，**无上限** |
| `getStrictDBIn` | `AbstractDBFunctionSymbolFactory:890` | 按 arity 无限缓存，**无上限** |
| `computeNewDisjunction` | `DefaultDBOrFunctionSymbol:76-79` | **事后**把嵌套 OR 拍平成 n 元扁平项 |
| `ontop.maxNbChildrenLiftingDBFS = 10` | 官方配置项 | 限**子节点数** |

**致命盲区**：`MAX_ARITY` 拦不住这条路径——**每个 `LOGICAL_OR` 仍是二元（arity = 2），永远触发不了元数闸门**。

**更讽刺的是**，`MAX_ARITY` 的封顶方式恰恰是「再折一层二叉」：

```java
// DisjunctionOfEqualitiesMergingSimplifierImpl:195 / :197
return termFactory.getConjunction((ImmutableExpression) next, (ImmutableExpression) current);
return termFactory.getDisjunction((ImmutableExpression) next, (ImmutableExpression) current);
```

→ **作者应对「表达式太大」的既定反应就是「折成二叉链」，而这正是制造深度 O(N) 树的机制本身。**

**（推断）**作者的心智模型可能是：**「左深树总会在 DB 层化简时被 flatten 成 n 元扁平项，最终 SQL 不变」**。第 6 章的实测（`finalEq = true`）**恰好证实了这个默认是对的**——只是**拍平之前的 `simplify` / `toString` / `hashCode` 递归先踩爆了栈**。

### 7.4 全仓普查：左深是团队的**通用写法**，不是孤例

| 位置 | 形态 |
|---|---|
| `core/kg-query/.../RDF4JValueExprTranslator.java:311` | `.reduce((e1, e2) -> getFunctionalTerm(SPARQL.LOGICAL_OR, e1, e2))` |
| `db/rdb/.../spec/sqlparser/BasicSelectQueryParser.java:211` | `.reduce(termFactory::getConjunction)`（JOIN ON） |
| `db/rdb/.../spec/sqlparser/RAExpressionOperations.java:100` | `.reduce(termFactory::getConjunction)`（JOIN USING） |
| `db/rdb/.../dbschema/impl/json/JsonBasicOrJoinLens.java:164` | `.reduce(termFactory::getConjunction)`（JSON lens 父表过滤） |
| `core/optimization/.../DisjunctionOfEqualitiesMergingSimplifierImpl.java:186-202` | 带 `MAX_ARITY=8` 元数封顶的左深折叠 |

**全部无平衡、无树深上限。** Ontop 现有代码里**没有任何「主动平衡 / 限制树深」的先例**——没有 `MAX_DEPTH`、没有深度计数、没有 SOE 防护。唯一沾边的是 `845ff74919`（2025-05-10）*"replaced recursion with iteration in FilterNormalizerImpl"*，但它针对的是 **IQ 树归一化**的递归，不是项树。

> 对照：VALUES 路径为何安全——`RDF4JTupleExprTranslator:823-833` 构造的是**宽而扁**的 UNION（每分支深度 O(1)）；IN 路径是**窄而深**的左深链。**同样 1001 项，差别只在树形。**

### 7.5 上游现状（截至 v5.6.0）

| 事项 | 状态 |
|---|---|
| **相邻路径已修** | PR **#942**（kontchakov，2026-06-19 合入 v5.6.0）：*"Fixes the out of memory and **stack overflow** bugs caused by checking possible solution mapping compatibility conditions with **large VALUES nodes**"*。改动落在 **unfolding / InnerJoin 的 solution-mapping 兼容性检查**（`SecondPhaseQueryMergingTransformer` + `InnerJoinNodeImpl`，改为基于 `Sets.intersection` 的迭代式兼容性检查，避免递归）——**与本路径（`RDF4JValueExprTranslator` 的左深 OR 链）无关**。 |
| **本路径** | **未查到任何对应 issue / PR** → 上游仍是缺口，属「按需修」 |
| **#755 的哲学原文** | *"we added the constant `MAX_ARITY` … we do not perform any further cross-multiplying. This prevents the terms from ever growing too large"* → 再次印证「限元数」取向 |
| **#755 评审中的反向信号** | 评审者 kontchakov 建议：*"it perhaps makes sense to **delay unpacking the INs until the SQL serializer**"* → 上游其实更倾向**保留 n 元 IN** |
| **业界同 bug 模式** | CockroachDB **#133068**（*"the current implementation builds an OrExpr tree with depth equal to the length of the IN list… causes the stack to overflow"*，修法是**根本不建深 OR 树**）、OpenSearch **#1469**、Cassandra CASSANDRA-6567 → **都不是「平衡」，而是「不建 / 限制 / 扁平化」** |

### 7.6 对本设计的意义

1. **这是上游缺口，值得提 issue**：Ontop v5 至今无对应 issue；本修复 12 行、语义与最终 SQL 均不变（已实测）。
2. **本方案（平衡折叠）是上游风格的最小偏离**——不改语义、不改最终 SQL、只改中间态树形。
3. **若上游不接受平衡**（因其哲学是「不建深树」），还有一条**更贴合上游方向**的替代路线：**在翻译层直接构造 n 元项 / 把 n 元 IN 保留到 SQL serializer 再拆**——正是 kontchakov 在 #755 中提出的思路；该路线还能顺带解决「最终 SQL 只有 OR 链、没有原生 `IN`」的问题（与本文档 4.2 节呼应）。
4. **业界主流修法同样是「不建深树」**——若不愿改上游，方案三（Doris 侧直出原生 `IN`）是同一思路在 SQL 层的版本。

### 7.7 一处更正：与 Jena 无关

**「从 Jena 时代沿袭而来」不成立。** Ontop 的查询翻译栈一直是 **Sesame → RDF4J**（`ea0fc3a7f1 "sesame -> rdf4j"`）；**Jena 只用于 R2RML 序列化与 Protege 输出，且早已删除**——`c7868f39af`（2020-08-17）*"remove JENA and reimplement R2RML serialization using RDF4J"*、`9a1ce78b1a "Jena removed"*。
→ 本段的左深链是 **RDF4J 时代原生写的**，不是 Jena 遗产。

## 8. 风险与缓解

| 风险 | 说明 | 缓解 |
|---|---|---|
| `\|\|` 结合律假设不成立 | 若某种 error 语义下结合律失效 → 结果集变化 | Kleene 三值逻辑下结合律成立；SPARQL 合规测试覆盖 `\|\|` 与 `IN` 的 error 语义 |
| SQL 文本中 OR 顺序变化 | 平衡化后 OR 分支顺序理论上可能改变 | 仅影响文本顺序，语义等价；且平衡折叠**每轮只配对相邻元素**，故中序叶子顺序与左深树一致，拍平后顺序不变（已在 6.4 实测中由「最终项逐字节相同」间接验证） |
| 回归面扩大 | 改动点在所有 SPARQL `IN`/`\|\|` 的公共路径 | 必须跑 `test/sparql-compliance` 全量；改动仅影响项树形状，不影响叶子内容与顺序（已实测：最终项逐字节相同） |
| 动到 `toString` 缓存语义 | `ImmutableFunctionalTermImpl.string` 是**非 `volatile`** 的惰性缓存 | 本改动**不触及**该缓存；该可见性是既有性质（多线程下可能重复计算，但字符串结果幂等），与本改动无关 |
| 生产基线栈占用未测定 | 隔离实测无法复现生产的 645 项 SOE（第 6.6 节局限 1），生产临界点是由「隔离临界 N ≈ 1000」叠加「查询基线栈占用」推算的 | 实施前用 5.3 节的深度探针在生产实测基线占用，再据此预估可支撑的列表规模上限 |
| 深度探针本身有开销 | `ThreadLocal` 计数进入热路径 | 探针仅在排查期启用，正式提交前移除或用开关控制 |
| 上游未修复、可能被拒 | Ontop v5 至今无对应 issue；上游哲学是「限元数 / 不建深树」而非「平衡」 | 见 7.6 节：若上游不接受平衡，可改走「保留 n 元 IN 到 SQL serializer 再拆」（kontchakov 在 PR #755 中的建议）；本 fork 先落地平衡折叠亦可独立受益 |

## 9. 参考文件

| 文件 | 用途 |
|---|---|
| `core/kg-query/src/main/java/it/unibz/inf/ontop/query/translation/impl/RDF4JValueExprTranslator.java` | 触发点（第 302-314 行）与唯一改动点（`getBalancedDisjunction` + `balancedFold`） |
| `core/kg-query/src/test/java/it/unibz/inf/ontop/query/translation/impl/RDF4JValueExprTranslatorListMemberTest.java` | 本次新增的 4 个回归用例（折叠高度/顺序/调用次数/单元素），不依赖注入器 |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/FunctionSymbolImpl.java` | 第 84-98 行 `simplify` 递归 |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/impl/LogicalOrSPARQLFunctionSymbolImpl.java` | 第 15-19 行，SPARQL OR → DB OR 降级 |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/impl/ImmutableFunctionalTermImpl.java` | 第 96-128 行 `toString`/`equals`/`hashCode`，第 141-143 行 `simplify` |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/impl/TermFactoryImpl.java` | 第 201-211 行 `getConjunction` 的 `.distinct()`（第 205 行） |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/DefaultDBOrFunctionSymbol.java` | 第 58-88 行 `computeNewDisjunction` 拍平嵌套 OR（第 76-79 行） |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/functionsymbol/db/impl/AbstractDBStrictEqNeqFunctionSymbol.java` | 第 148-152 行 `combineExpressions`（栈顶） |
| `core/model/src/main/java/it/unibz/inf/ontop/model/term/impl/DBConstantImpl.java` | 第 38 行 `toString`（栈顶） |
| `core/optimization/src/main/java/it/unibz/inf/ontop/iq/optimizer/impl/DisjunctionOfEqualitiesMergingSimplifierImpl.java` | `MAX_ARITY = 8`（第 26 行），OR 合并为 IN 的上限 |
| `db/rdb/src/main/java/it/unibz/inf/ontop/generation/normalization/impl/MySQLExtraNormalizer.java` | 第 15-17 行，挂 `ConvertValuesToUnionNormalizer` |
| `db/rdb/src/main/java/it/unibz/inf/ontop/generation/normalization/impl/ConvertValuesToUnionNormalizer.java` | `transformValues` → N 支 UNION |
| `db/rdb/src/main/java/it/unibz/inf/ontop/generation/serializer/impl/DefaultSelectFromWhereSerializer.java` | 第 405、416-429 行，VALUES 表构造器序列化 |
| `core/model/src/main/java/it/unibz/inf/ontop/iq/node/normalization/impl/UnionNormalizerImpl.java` | 第 88-129 行，UNION 合并回 ValuesNode |
| 上游对照：apache/doris PR #21326（OrToIn）、#46468（infer in-predicate from or-predicate） | 说明 Doris 侧 `OrToIn` 红利来源 |
| 上游对照：ontop/ontop PR #942（large VALUES node 的 OOM/SOE 修复）、PR #755（引入 `DisjunctionOfEqualitiesMergingSimplifierImpl` 与 `MAX_ARITY`） | 关联历史问题与优化器出处（第 7 章） |
| git：`31b0ad5844`（2021-09-29，Benjamin Cogrel，*"SPARQL IN is now converted into a disjunction."*） | 本路径的诞生语境（第 7.1 节） |
| git：`9d289b1a11`（2023-02-06，翻译器拆分）、`8cd0ae2ce90`（2025-10-29，去重重构） | 折叠逻辑自 2021 年起未变（第 7.1 节） |
| git：`c7868f39af`（2020-08-17，移除 Jena）、`ea0fc3a7f1`（Sesame→RDF4J） | 更正「Jena 沿袭」误解（第 7.7 节） |
| git：`845ff74919`（2025-05-10，*"replaced recursion with iteration in FilterNormalizerImpl"*） | Ontop 唯一的「递归改迭代」先例，作用域是 IQ 树归一化而非项树（第 7.4 节） |
| 诊断程序（**位于仓库之外**，系统临时目录 `...\Temp\opencode\OrFoldProbe2.java`） | 第 6 章实测取证用；编译期依赖仓库已有 classpath（`analysis/doris-ab/ws-cp.txt` + `cp.txt`），**不修改仓库任何文件**；如需复现请以本文第 6 章的方法描述重建 |

## 10. 不纳入范围

- 不修改任何 `.java` 源码（本文档仅给出可落地的代码片段，实施为后续独立步骤）。
- 不引入 Doris 专用 `DialectExtraNormalizer` 或放开 `VALUES` 表构造器（方案三）。
- 不改写为 SPARQL `VALUES`（方案二，仅作紧急止血备选）。
- 不把过滤条件下推到映射 source SQL（方案四）。
- 不重构 `simplify` / `toString` / `equals` / `hashCode` 的递归实现。
- 不修改任何 OBDA 映射文件。
