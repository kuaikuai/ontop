# 层级路径静态化 — Phase 0 前置验证结论

**Date**: 2026-11-11
**Plan**: `docs/superpowers/plans/2026-11-11-hierarchical-path-static.md`

## P0-1 翻译层能拿到 MetadataLookup —— 失败

证据：

- `core/obda/.../spec/OBDASpecification.java:11-15` 只暴露 `getSaturatedMapping()`、`getDBParameters()`（及 TBox），**不含** `MetadataLookup` / `MetadataProvider`。
- `engine/reformulation/.../QuestQueryProcessor.java:50-76` 构造只接收 `OBDASpecification`；`engine` 模块内搜索 `MetadataLookup` 无结果。
- `MetadataProvider` 仅在 mapping 解析期（`SQLMappingExtractor`）使用，构建完成后不保留。

结论：查询/翻译阶段没有 `MetadataLookup`，无法注入。

## P0-2 运行时 `getBlackBoxView` 可用 —— 失败

- `getBlackBoxView` 的唯一调用点是 mapping source 解析兜底：`SelectQueryParser.java:25-33`。
- `ImmutableMetadataLookup.java:32-34` 直接抛 `UnsupportedOperationException`。
- 查询期没有 `MetadataProvider` 实例。

结论：`getBlackBoxView` 不能用于查询期动态构造关系。

## P0-3 `createExtensionalDataNode` 可用 —— 成立

- `CoreSingletons.getIQFactory()` 返回 `IntermediateQueryFactory`。
- `IntermediateQueryFactory.java:60` 提供 `createExtensionalDataNode(RelationDefinition, ...)`。

## P0-4 目标 DB 的派生表/CTE 形态 —— 待目标库验证

- Phase 1 采用嵌套派生表，不依赖子查询内 `WITH`。
- H2 支持；MySQL/Doris 需在实际环境确认（不影响 Phase 1 的派生表形态）。

## P0-5 IRI 逐字节一致 —— 待实际映射验证

- 需用同一 id 比较路径 `node_iri` 与属性映射 IRI，Phase 1 集成测试覆盖。

## 结论与调整

采用计划 Task 0.1 预设的 fallback：**自定义 `PathRelationDefinition`，不使用 `getBlackBoxView`**。

- 新增 `PathRelationDefinition extends AbstractRelationDefinition`，携带 `pathSql`，固定三列 `node_iri`、`depth`、`predecessor`（列定义不依赖 DB metadata）。
- `DefaultSelectFromWhereSerializer.visit(SQLTable)` 增加分支：识别 `PathRelationDefinition`，输出 `(<pathSql>) alias`（或提成 CTE）。
- 翻译层直接 `new PathRelationDefinition(pathSql)` + `createExtensionalDataNode`。
- 计划 Task 1.4 的 `metadata.getBlackBoxView(pathSql)` 改为 `new PathRelationDefinition(pathSql)`。
- 设计文档第 8 节（依赖 MetadataLookup）与参考文件需同步改为自定义 RelationDefinition。

## 影响范围

| 原假设 | 调整 |
|---|---|
| 注入 `MetadataLookup` | 不需要；改用自定义 relation |
| `getBlackBoxView` | 移除 |
| serializer 复用 BlackBoxView 分支 | 新增 `PathRelationDefinition` 分支（逻辑相同） |
| P0-1/P0-2 验证 | 已完成（失败，已 fallback） |
