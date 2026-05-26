# Doris Metadata Provider Fix

**Date**: 2026-05-26
**Status**: Design Approved
**Author**: Sisyphus

## Problem

When connecting Ontop to Apache Doris with `ontop.mysql.doris=true`, the
`MySQLDBMetadataProvider` (used because Doris uses the MySQL JDBC driver) fails
during metadata extraction with:

```
Relation IDs mismatch: for relation `dwd`.`test_items`,
the JDBC method getIndexInfo returns `dwd`.`dwd.test_items`
```

### Root Cause

Doris JDBC driver (MySQL protocol compatible) returns inconsistent catalog/schema
values across different JDBC metadata methods:

| JDBC Method   | TABLE_CAT | TABLE_SCHEM | TABLE_NAME  | RelationID (rendered)      |
|---------------|-----------|-------------|-------------|----------------------------|
| `getColumns`  | `null`    | `dwd`       | `test_items`| ``dwd`.`test_items`` (2-part) |
| `getIndexInfo`| `dwd`     | `dwd`       | `test_items`| ``dwd`.`dwd`.`test_items`` (3-part) |

The `extractRelationID()` method in `AbstractDBMetadataProvider` calls
`equalRelationIDs()` to compare the extracted RelationID with the expected one.
The default implementation uses `equals()`, which does a full component-wise
comparison. The extra catalog component causes the mismatch.

### Impact

- `insertUniqueAttributes()` fails, throwing `MetadataExtractionException`
- The Ontop endpoint fails to start with `MappingIOException`
- Error occurs whenever the table has any index (which exposes it via
  `getIndexInfo`)

## Solution: Minimal Change to MySQLDBMetadataProvider

### Approach

Override `equalRelationIDs()` in `MySQLDBMetadataProvider` to compare only the
table name when Doris mode is active (`isDoris() == true`).

### Justification

- Single file change — low risk, easy to review
- Existing `isDoris()` flag already handles MySQL/Doris differences in
  `MySQLDBTypeFactory` and `MySQLDBFunctionSymbolFactory`
- `DatabaseInfoSupplier` is injectable via `CoreSingletons`
- No new classes, no new properties, no configuration changes

### File Changed

**`db/rdb/src/main/java/it/unibz/inf/ontop/dbschema/impl/MySQLDBMetadataProvider.java`**

### Changes

1. **Add `isDoris` field** — read from `coreSingletons.getDatabaseInfoSupplier().isDoris()` in constructor
2. **Override `equalRelationIDs()`** — when `isDoris` is true, compare only
   `RelationID.getTableOnlyID()` instead of full component-wise comparison

### Call Flow

```
insertUniqueAttributes()
  → getIndexInfoResultSet(id)          // Queries JDBC metadata
  → while (rs.next())
    → extractRelationID(id, rs, ...)   // Line 313 AbstractDBMetadataProvider
      → getRelationID(rs, ...)         // Creates RelationID from JDBC row
      → equalRelationIDs(givenId, extractedId)  // ⭐ OVERRIDE HERE
        → if (isDoris)
           return extractedId.getTableOnlyID().equals(givenId.getTableOnlyID())
        → else
           return super.equalRelationIDs(...)   // Full comparison for MySQL
```

### Pseudo-code

```java
private final boolean isDoris;

@AssistedInject
MySQLDBMetadataProvider(@Assisted Connection connection, CoreSingletons coreSignletons)
        throws MetadataExtractionException {
    // NOTE: preserve the existing 4-argument super() call unchanged;
    //       only add the this.isDoris = ... line after it.
    super(connection,
            metadata -> metadata.storesMixedCaseIdentifiers()
                    ? new MySQLCaseSensitiveTableNamesQuotedIDFactory()
                    : new MySQLCaseNotSensitiveTableNamesQuotedIDFactory(),
            coreSingletons,
            c -> new String[] { c.getCatalog(), "DUMMY" });
    this.isDoris = coreSingletons.getDatabaseInfoSupplier().isDoris();
}

@Override
protected boolean equalRelationIDs(RelationID extractedId, RelationID givenId) {
    if (isDoris) {
        return extractedId.getTableOnlyID().equals(givenId.getTableOnlyID());
    }
    return super.equalRelationIDs(extractedId, givenId);
}
```

### Activation

No configuration changes needed. The fix activates automatically when:

```properties
ontop.mysql.doris=true
jdbc.driver=com.mysql.cj.jdbc.Driver
```

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Table-only comparison is too lenient | Could match wrong tables if table names collide across schemas | Only activated when `isDoris() == true`. MySQL path uses strict comparison. |
| `isDoris()` flag not available in constructor | Compilation error | `CoreSingletons` is injected as a non-assisted dependency; `getDatabaseInfoSupplier().isDoris()` is available |
| RelationID equality semantics change | Other code relying on full comparison | Only affects the `insertUniqueAttributes` path via `extractRelationID`. All other uses of `RelationID` are unchanged. |
| `extractRelationID()` return value used downstream | The relaxed comparison returns an `extractedId` with inconsistent catalog; if future code uses this returned value, issues could arise | Currently `extractRelationID()` return value is only used for the equality assertion in `insertUniqueAttributes()` — the actual constraint data comes from raw `ResultSet` columns. If the calling code changes, this constraint must be re-examined. |

## Testing

1. **Unit test**: Verify `equalRelationIDs` returns true for IDs with matching
   table names but different schemas when `isDoris=true`
2. **Integration test**: Connect Ontop to Doris with an indexed table, verify
   endpoint starts without `MetadataExtractionException`
3. **Regression test**: Verify MySQL connections (without Doris flag) still use
   strict RelationID comparison

## Verification

- Startup with `ontop.mysql.doris=true` and an indexed Doris table no longer
  throws `MetadataExtractionException`
- Unique constraints are still extracted (only the comparison is relaxed)
- All existing MySQL tests continue to pass
