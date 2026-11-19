# 生产 Doris 31 个 LEFT JOIN 不合并：定位过程、根因与解决措施

- 日期：2026-11-19
- 范围：Ontop（version5 fork）+ 生产 Doris（MySQL 协议，ods 库）
- 状态：已解决，修复已提交并推送（`3b3f449cc8` / `679a113c99` / `dd9d024ffe`）
- 涉及查询形态：层级路径查询（`parent_bom_item+`、`path:maxDepth 2`）+ 31 个属性 OPTIONAL 全部位于 subselect 之外

---

## 1. 问题现象

生产 SPARQL 查询经 Ontop 改写后生成异常 SQL：

| 指标 | 数值 |
|---|---|
| LEFT OUTER JOIN | **32** |
| `IS NOT NULL` 运行时防护 | 42 |
| SQL 长度 | 8163 字符 |
| 生产执行结果 | **丢行**（实际返回 26/38，丢失 12 行） |
| 执行性能 | 慢（多表重复自 join） |

31 个属性 OPTIONAL 每个都通过键列把同一张表反复 LEFT JOIN 回主查询，健康基线（H2 wide 测试）同一形态只有 3~4 个不可合并的结构性 LJ。

## 2. 定位过程

### 2.1 第一轮：证伪"类型转换触发防护"假设

最初的怀疑方向是 Doris 方言的类型转换（XXToTEXT）触发额外防护。对照实验（同一查询、生产 Doris）：

| 配置 | LEFT JOIN | ToTEXT |
|---|---|---|
| typed（全 `^^xsd:string`） | 32 | 0 |
| bare | 32 | 0 |
| inferDefaultDatatype=false | 32 | 0 |
| ontop.mysql.doris=false | 32 | 0 |

四种配置全部 32 LJ 且 ToTEXT=0 —— **证伪**。类型转换与 Doris 特判都不是原因。

### 2.2 第二轮：H2 复现锁定唯一变量

在 H2 上建全可空列表复现查询：同样得到 32 LJ。
**唯一有效变量是列可空性/约束元数据**，与数据库产品无关。

### 2.3 根因确认：约束元数据缺失

Doris 通过 JDBC 元数据不暴露主键/唯一约束（且本 fork 的 Doris 特判跳过了 index 提取），导致 Ontop：

1. 认为 `bom_item_id = <常量>` 可能匹配**多行**，MergeLJOptimizer 的行集包含检查失败，31 个属性 join 无法合并；
2. 对每个可空映射属性列生成 `col IS NOT NULL` 运行时防护（42 个）。

防护本身语义正确（保护 OPTIONAL 的 unbound 语义），但使每个 join 变成独立 LEFT JOIN。

#### 2.3.1 追问验证：不跳过 index 提取能否解决？（实测：不能）

针对“如果不跳过 index 提取是否就能解决”的追问，在生产 Doris 上实测了全部候选 JDBC 通道（`getPrimaryKeys` / `getIndexInfo` / `SHOW INDEX` / `information_schema.statistics` / `getBestRowIdentifier`）：

| 通道 | bom 表（31 join 主表） | material 表 |
|---|---|---|
| `getPrimaryKeys` | **0 行** | **0 行** |
| `getIndexInfo` / `SHOW INDEX` | **0 行**（一个索引都没有） | 2 行，均为 **INVERTED** 倒排索引 |
| `information_schema.statistics` | 0 行 | 0 行 |
| `getBestRowIdentifier` | 0 行 | — |

三层原因决定了“不跳过”不可行：

1. **bom 表无数据可提**：`SHOW INDEX` 返回 0 行，9 个 bom 属性的合并没有任何信息源；
2. **material 表语义不对口**：返回的两行是倒排索引（`idx_mat_id` / `idx_mat_material_code`，为全文检索/点查加速而建），且 `Non_unique`、`Seq_in_index` 等字段全是**空字符串**——连接“是否唯一”的标志都没有，Ontop 拿到的最多是“唯一性未知的非唯一索引”，不能（也不应）转成 UniqueConstraint；倒排索引恰好建在 UNI 键列 `id` 上，不代表 index 通道暴露了模型键的唯一性；
3. **不跳过会启动崩溃而非仅仅无效**：空字符串的 `ORDINAL_POSITION` 使 MySQL connector 在 `rs.next()` 解码时抛 `NumberFormatException`（本实测在 material 表上复现了该异常）。这正是当年 `AbstractDBMetadataProvider.insertIntegrityConstraints` 加 `isDoris` 跳过 `insertUniqueAttributes` 的原因（代码注释在案）——不跳过则**任何 Doris mapping 都无法完成启动期元数据提取**。`insertPrimaryKey`（`getPrimaryKeys`）从未被跳过，它一直在跑且拿到 0 行。

**结论**：UNI 模型键的唯一性只存在于 `information_schema.table_options`（`TABLE_MODEL='UNI'` + `TABLE_MODEL_KEY`），这是唯一“有货且语义正确”的通道——最终方案（2.8 的代码开关）选它不是权宜，而是探测后的唯一可行路径。

### 2.4 解法验证 A：dbMetadataFile JSON（改写器侧声明）

用 `.dbMetadataFile()` 注入 JSON 声明 uniqueConstraints + 全列 isNullable:false：
**32 LJ → 3 LJ，42 防护 → 3，SQL 8163 → 4242 字符。**
剩余 3 个 LJ 为结构性不可合并（provenance 种子分支 ON 1=1 + 2 个 CTE 输出 join-back），与健康基线一致。

此环节踩了三个坑（均有独立教训价值）：

1. **java @argfile 反斜杠转义**：双引号字符串内 `\` 是转义符，`f:\git\...` 被吃掉导致 classpath 失效报"找不到主类"；修复为 argfile 内全部用正斜杠。
2. **JSON 缺 `idFactoryType: "MYSQL-D"` 静默回退**：缺省退化为 STANDARD（双引号）QuotedIDFactory，反引号表名匹配失败后**静默回退 JDBC 元数据**——表象是"文件给了但没效果"。排查靠探针实验（删 JSON 列仍成功）+ 源码链路（JsonSerializedMetadataProvider → JsonMetadata.createQuotedIDFactory）。
3. **改源码未重编译**：`.class` 时间戳（14:50）早于 `.java`（14:52），args[7] 支持在最后一次编译之后才加入，字节码忽略该参数。教训：排查配置问题前先比对源/字节码时间戳。

### 2.5 NULL 容忍评估 + 意外发现：现状查询丢行

用户提出：真实数据大量含 NULL，`isNullable:false` 是"信任声明"，能否容忍？

**NULL 普查**（只读直连生产 Doris）：

- bom 表 9,239,273 行，`bom_item_id` 严格唯一；`process_code` 54% NULL（5,026,823 行）、`plant_code` 144 行 NULL
- material 表 1,197,533 行，`id` 严格唯一；`color` 100% NULL、`density` 99.4%、`spec_thickness` 98%、`category_code` 42.5%、`procurement_type` 2.7%

**A/B 真实执行**（merged=JSON 声明 3-LJ vs guarded=现状 32-LJ）：

| 配置 | 返回行数 |
|---|---|
| merged（3 LJ） | **38**（= 手工 SQL 验证的真实基数，38 n1 × 1 n2，LIMIT 1000 无截断） |
| guarded（32 LJ） | **26** |

丢失的 12 行全部是 n1 的 `process_code` **非空**的行；两次重放稳定复现。26 个共同行上所有 NULL 列两臂绑定逐字节一致（unbound）。

**结论**：NULL 容忍成立（`isNullable:false` 只移除冗余防护，NULL 到达时变量 unbound，与 OPTIONAL 语义等价）；同时**重大意外发现：现状生产查询不仅慢，还在丢行**——32 层 LEFT JOIN 嵌套在 Doris 上执行出错，dbMetadataFile 方案同时修复性能与正确性。丢行的 Doris 内部根因未深挖（见遗留事项）。

### 2.6 变量分离：唯一约束才是合并的决定因素

用户设想"全局开关把所有字段当非空"。变量分离实验（生产 Doris，2×2 闭合）：

| 配置 | LEFT JOIN | IS NOT NULL 防护 | 合并？ |
|---|---|---|---|
| 现状（无声明） | 32 | 42 | ✗ |
| 仅普通字段非空、键原样 | 32 | 12 | ✗ |
| 全部字段非空、无唯一约束 | 32 | 2 | ✗ |
| **仅键唯一约束、列保持可空** | **3** | 15 | **✓** |
| 键唯一 + 全列非空 | 3 | 3 | ✓ |

**isNullable 只决定运行时防护的数量；合并由键唯一性约束（函数依赖）驱动。**
"全字段非空"开关即使实现也毫无合并效果。据此验证了更轻量的 constraint 文件（`basicImplicitConstraintFile`，2 行文本）同样达到 3 LJ。

### 2.7 Doris UNI 模型发现：唯一性是模型层承诺

从 `information_schema.table_options` 可查到：

```
ods.dp_mpo_mopp_bom_detial_info_dd:   TABLE_MODEL=UNI  KEY=bom_item_id
ods.dp_mpo_mopp_material_base_info_dd: TABLE_MODEL=UNI  KEY=id
```

两张映射表都是 **UNIQUE（UNI）模型**——Doris 在模型层保证 Key 列整表唯一（重复写入被 merge），不依赖数据纪律。库中混有 DUP 模型表（如 equipment_dd），其键列**不**保证唯一。这把 constraint 声明的性质从"信任声明"升级为"模型事实"。

### 2.8 最终方案：代码开关

实现 `ontop.mysql.doris.uniqueModelKeyConstraints`（4 个文件，102 行）：

- `OntopModelSettings`：属性键定义
- `DatabaseInfoSupplier/Impl`：default 方法 + 配置读取
- `MySQLDBMetadataProvider.insertIntegrityConstraints` 覆写：开关开启时按 relation 查询 `information_schema.table_options`，`TABLE_MODEL='UNI'` 则把 `TABLE_MODEL_KEY`（支持逗号分隔联合键）声明为 UniqueConstraint；非 UNI（DUP 等）跳过

**生产 A/B 验证**（同一构建，开关为唯一变量）：

| | off（现状） | on |
|---|---|---|
| LEFT JOIN | 32 | **3** |
| IS NOT NULL 防护 | 42 | 15（列保持真实可空元数据） |
| SQL 长度 | 8163 | 4807 |
| 执行行数 | 28（丢 12 行） | **40（完整）** |

开关 on 的执行结果与 dbMetadataFile JSON 基线**逐字节一致**（40 行、0 重复、0 差异）。

### 2.9 插曲：ecj 坏 stub 与 MergeLJOptimizer 修复

验证过程中运行时突发 `Unresolved compilation problem`。排查发现：IDE（Eclipse JDT 增量编译）在通配符捕获的 Builder 类型上无法解析 `transform` 双参重载（隐式 lambda），**不报错而是把坏 stub 写进 target/classes**；javac 对同一源码正常。修复：源码层给 lambda 加显式参数类型（注意 `InjectiveSubstitution<Variable>` 的 T=Variable，第二 lambda 首参必须写 `Variable`），两个编译器均通过；IDE 后台会持续写回坏 stub，必须源码级修复而非重编覆盖。

## 3. 问题原因（最终结论）

1. **直接根因**：Doris 不通过 JDBC 元数据 API（PK/index 通道均为空或语义不符，见 2.3.1 实测）暴露主键/唯一约束，Ontop 无法获得键唯一性函数依赖 → MergeLJOptimizer 无法证明“键值确定唯一一行” → 31 个属性 join 全部保持独立 LEFT JOIN。
2. **次要因素**：Doris 元数据中非键列全部 nullable → 每属性附加 `IS NOT NULL` 防护（42 个），影响 SQL 体积但不影响合并。
3. **意外发现（独立问题）**：32 层 LEFT JOIN 嵌套的 SQL 在 Doris 上执行丢行（26/38，丢失行均为某非可空过滤列非空的行），现状查询结果**错误**。3-LJ 形态无此问题。

## 4. 解决措施

### 4.1 已交付（commit 均已推送 origin/version5）

| commit | 内容 |
|---|---|
| `3b3f449cc8` | feat(db): UNI 模型键约束代码开关（核心修复） |
| `679a113c99` | fix(optimization): MergeLJOptimizer ecj 重载歧义修复（零语义变更，JDK 11 兼容已实证） |
| `dd9d024ffe` | test(path): 生产形态回归测试（31 OPTIONAL 全在 subselect 外） |

**生产启用方式**（发布定制 jar 后，`ontop.properties` 加一行，前提已有 `ontop.mysql.doris=true`）：

```properties
ontop.mysql.doris.uniqueModelKeyConstraints=true
```

### 4.2 三种方案对比（均为可行解，开关为最终推荐）

| 方案 | 落地成本 | 覆盖面 | 维护成本 |
|---|---|---|---|
| constraint 文件（`schema.表:键列`） | 零代码，立即 | 仅文件点名的表 | 新表手工追加 |
| dbMetadataFile JSON | 零代码 | 点名表 + 全列元数据 | 逐列维护，有 idFactoryType 坑 |
| **代码开关** | 随 jar 发布 | **所有 UNI 表含未来新表，自动** | 无文件维护 |

### 4.3 风险与运维边界

- **语义安全**：开关只声明模型层事实（UNI 唯一性）；列可空元数据保持真实，NULL → unbound 语义正确（A/B 逐字节验证）；IRI 模板只用键列，键列已验证无 NULL。
- **回滚**：属性置 `false` 即恢复现状，无需回滚 jar。
- **失败模式**：开关开启时若账号无 `information_schema.table_options` 读权限或 Doris 升级改掉该视图 → 启动失败（fail-fast，非静默）。当前生产账号已验证可读。
- **运维告警建议**：对映射表的 `TABLE_MODEL='UNI'` 做变更监控——若被重建为 DUP，唯一性消失、合并重新失效且无报错；键唯一性巡检（`COUNT(*) = COUNT(DISTINCT key)`）可降级为辅助校验。
- **only-Doris**：非 Doris 的 MySQL 部署完全不受影响（开关默认 false 且需 isDoris 双条件）。

## 5. 遗留事项

1. **Doris 丢行根因未深挖**：32 层 LEFT JOIN 嵌套 SQL 在 Doris 上返回 26/38 行，两次重放稳定复现，最小化复现 SQL 尚未提取。建议向 Doris 社区/平台反馈；开关上线后该形态不再产生，风险自然消除。
2. `analysis/doris-ab/` 实验产物（含生产凭据脚本）留在本地未入库，不应提交。

## 6. 实验产物索引（本地 `analysis/doris-ab/`）

- `DorisAB.java`：实验驱动（reformulate + exec 双模式，参数化 mapping/metadata/constraint/seed）
- `doris-db-metadata.json`、`doris.constraint`：两份声明基线
- `sql-ukswitch*.txt` / `sql-exec-*.txt`：开关 on/off 的 SQL 与执行结果
- `census*.jsh/.log`：NULL 普查与基数验证
- `probe-jdbc-index.jsh/.log`、`probe-show-index.jsh/.log`：JDBC 约束通道探测（2.3.1 的证据）
- 完整 A/B 结果对比：`abdiff-full.txt`（12 行丢失明细）
