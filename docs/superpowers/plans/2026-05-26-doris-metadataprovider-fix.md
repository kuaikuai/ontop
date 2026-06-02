# Doris Metadata Provider Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `Relation IDs mismatch` error when Ontop connects to Doris with `ontop.mysql.doris=true` and the table has an index.

**Architecture:** Override `equalRelationIDs()` in `MySQLDBMetadataProvider` to compare only table names when `isDoris()` is true. This bypasses Doris JDBC driver's inconsistent catalog/schema reporting in `getIndexInfo`.

**Tech Stack:** Java, Guice AssistedInject, JDBC metadata

**Reference spec:** `docs/superpowers/specs/2026-05-26-doris-metadataprovider-fix-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `db/rdb/src/main/java/.../dbschema/impl/MySQLDBMetadataProvider.java` | Modify | Add `isDoris` field, override `equalRelationIDs()` |
| `.../test/.../dbschema/impl/MySQLDBMetadataProviderTest.java` | Create | Unit tests for the override |
| `docs/superpowers/plans/2026-05-26-doris-metadataprovider-fix.md` | Create | This plan |

---

### Task 1: Modify MySQLDBMetadataProvider

**Files:**
- Modify: `db/rdb/src/main/java/it/unibz/inf/ontop/dbschema/impl/MySQLDBMetadataProvider.java`

- [ ] **Step 1: Read the current file**

```bash
cat db/rdb/src/main/java/.../MySQLDBMetadataProvider.java
```

Expected: View the existing 41-line file.

- [ ] **Step 2: Add `isDoris` field and initialize in constructor**

Add after the class declaration:

```java
private final boolean isDoris;
```

In the constructor, after the `super(...)` call, add:

```java
this.isDoris = coreSingletons.getDatabaseInfoSupplier().isDoris();
```

The `CoreSingletons` parameter is already available in the constructor (injected as a non-assisted dependency).

- [ ] **Step 3: Override `equalRelationIDs()`**

Add after the constructor:

```java
@Override
protected boolean equalRelationIDs(RelationID extractedId, RelationID givenId) {
    if (isDoris) {
        return extractedId.getTableOnlyID().equals(givenId.getTableOnlyID());
    }
    return super.equalRelationIDs(extractedId, givenId);
}
```

- [ ] **Step 4: Add required import(s)**

Ensure `it.unibz.inf.ontop.dbschema.RelationID` is imported. (Check if `RelationID` is already imported transitively — the file already imports `it.unibz.inf.ontop.dbschema.RelationID`.)

- [ ] **Step 5: Run the project build to verify compilation**

```bash
cd ontop
mvn compile -pl db/rdb -am -q
```

Expected: BUILD SUCCESS — no compilation errors.

- [ ] **Step 6: Commit**

```bash
git add db/rdb/src/main/java/.../MySQLDBMetadataProvider.java
git commit -m "fix: override equalRelationIDs in MySQLDBMetadataProvider for Doris compatibility

Doris JDBC driver returns inconsistent TABLE_CAT/TABLE_SCHEM in getIndexInfo
vs getColumns, causing RelationID mismatch errors. When ontop.mysql.doris=true,
compare only table names to bypass the extra catalog component.
"
```

---

### Task 2: Write and Run Tests

**Files:**
- Create: `db/rdb/src/test/java/it/unibz/inf/ontop/dbschema/impl/MySQLDBMetadataProviderTest.java`

- [ ] **Step 1: Write the unit test class**

Create a test class that verifies `equalRelationIDs` behavior:

```java
package it.unibz.inf.ontop.dbschema.impl;

import it.unibz.inf.ontop.dbschema.RelationID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MySQLDBMetadataProviderTest {

    private final MySQLDBMetadataProvider provider = /* create via test injector */;

    @Test
    void testEqualRelationIDs_matchingTableNames() {
        // Two IDs with same table name, different schemas
        RelationID id1 = /* id with table=test_items, schema=dwd */;
        RelationID id2 = /* id with table=test_items only */;
        assertTrue(provider.equalRelationIDs(id1, id2));
    }

    @Test
    void testEqualRelationIDs_differentTableNames() {
        // Two IDs with different table names
        RelationID id1 = /* id with table=test_items */;
        RelationID id2 = /* id with table=other_table */;
        assertFalse(provider.equalRelationIDs(id1, id2));
    }
}
```

**Note on testability:** `equalRelationIDs` is `protected`, so the test must either:
- Be in the same package (`it.unibz.inf.ontop.dbschema.impl`), OR
- Use reflection, OR
- Create a package-level helper

Recommendation: Put the test in `db/rdb/src/test/java/it/unibz/inf/ontop/dbschema/impl/` to access the protected method directly.

- [ ] **Step 2: Run the test**

```bash
cd ontop
mvn test -pl db/rdb -Dtest=MySQLDBMetadataProviderTest -am
```

Expected: Tests pass.

- [ ] **Step 3: Commit**

```bash
git add db/rdb/src/test/java/.../MySQLDBMetadataProviderTest.java
git commit -m "test: add unit tests for MySQLDBMetadataProvider.equalRelationIDs"
```

---

### Task 3: Verify Integration

- [ ] **Step 1: Run the full MySQL-related test suite**

```bash
cd ontop
mvn test -pl db/rdb -am -q
```

Expected: All MySQL-related tests pass (no regressions from the `isDoris=false` path).

- [ ] **Step 2: Manual verification (if Doris instance available)**

Start Ontop endpoint with:
```properties
jdbc.driver=com.mysql.cj.jdbc.Driver
jdbc.url=jdbc:mysql://doris-host:9030/dwd
ontop.mysql.doris=true
ontop.mapping.file=test.obda
ontop.template.file=test.ttl
```

Verify the endpoint starts without `MetadataExtractionException` even when the table has an index.
