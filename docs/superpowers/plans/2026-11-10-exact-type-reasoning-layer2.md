# Exact Type Reasoning — Rewrite-Layer (§2.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In `exact` mode, stop the query rewriter from letting `rdfs:domain`/`rdfs:range` (and other class axioms) eliminate explicit `rdf:type` atoms, so `?x a :A ; :p ?y` no longer degrades into a multi-table union of `:p`.

**Architecture:** Move `TypeReasoningMode` to the shared `OntopModelSettings`. In `DummyRewriter.setTBox`, skip building class ABox dependencies (Sigma) when `exact`, so `removeRedundantAtoms` can never delete `rdf:type` atoms. Property (sub-property) dependencies stay. `TreeWitnessRewriter` inherits the gate.

**Tech Stack:** Java 11, Maven, Guice, Guava, JUnit 5 (mapping/core, core/model), JUnit 4 (binding/rdf4j), H2.

**Workspace:** Work directly in `F:\git\ontop` on branch `version5`. Do NOT create/switch branches. The tree has UNRELATED uncommitted changes — always stage only the explicit files listed per task (never `git add -A` / `git add .`).

---

## File Structure

**Modified**
- `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java` — add `TypeReasoningMode`, `getTypeReasoningMode()`, `TYPE_REASONING_MODE`.
- `core/model/src/main/java/it/unibz/inf/ontop/injection/impl/OntopModelSettingsImpl.java` — parse + default `FULL`.
- `mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java` — remove duplicate enum/method/key.
- `mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java` — remove duplicate override.
- `mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java` — reference `OntopModelSettings.TypeReasoningMode`.
- `engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/DummyRewriter.java` — inject settings; skip class DAG deps in exact.
- `engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/TreeWitnessRewriter.java` — pass settings to `super`.
- `mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java` — reference `OntopModelSettings`.
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExactTest.java` — finalize regression (remove debug print, add SQL assertion).
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainFullTest.java` — keep property-atom case.

**Created**
- `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/existential.properties`
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExistentialTest.java`

---

## Task 1: Relocate `TypeReasoningMode` to `OntopModelSettings`

**Files:**
- Modify: `mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java`
- Modify: `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java`
- Modify: `core/model/src/main/java/it/unibz/inf/ontop/injection/impl/OntopModelSettingsImpl.java`
- Modify: `mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java`
- Modify: `mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java`
- Modify: `mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java`

- [ ] **Step 1: Point the config test at `OntopModelSettings`**

Replace the whole file `mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java` with:

```java
package it.unibz.inf.ontop.injection;

import it.unibz.inf.ontop.exception.InvalidOntopConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TypeReasoningModeSettingsTest {

    private static OntopMappingSettings settings(Properties properties) {
        return OntopMappingConfiguration.defaultBuilder()
                .enableTestMode()
                .properties(properties)
                .build()
                .getSettings();
    }

    @Test
    public void testDefaultIsFull() {
        assertEquals(OntopModelSettings.TypeReasoningMode.FULL,
                settings(new Properties()).getTypeReasoningMode());
    }

    @Test
    public void testExact() {
        Properties properties = new Properties();
        properties.setProperty(OntopModelSettings.TYPE_REASONING_MODE, "exact");
        assertEquals(OntopModelSettings.TypeReasoningMode.EXACT,
                settings(properties).getTypeReasoningMode());
    }

    @Test
    public void testFull() {
        Properties properties = new Properties();
        properties.setProperty(OntopModelSettings.TYPE_REASONING_MODE, "full");
        assertEquals(OntopModelSettings.TypeReasoningMode.FULL,
                settings(properties).getTypeReasoningMode());
    }

    @Test
    public void testInvalidValueIsRejected() {
        Properties properties = new Properties();
        properties.setProperty(OntopModelSettings.TYPE_REASONING_MODE, "bogus");
        assertThrows(InvalidOntopConfigurationException.class,
                () -> settings(properties).getTypeReasoningMode());
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run:

```bash
mvn test -pl mapping/core -Dtest=TypeReasoningModeSettingsTest
```

Expected: FAIL at compile time (`OntopModelSettings.TypeReasoningMode` / `TYPE_REASONING_MODE` do not exist).

- [ ] **Step 3: Add enum, accessor, and key to `OntopModelSettings`**

In `core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java`, add the enum and accessor after `public interface OntopModelSettings {`:

```java
    enum TypeReasoningMode { FULL, EXACT }

    TypeReasoningMode getTypeReasoningMode();
```

Add the key inside the `// Keys` section:

```java
    String TYPE_REASONING_MODE = "ontop.reasoning.type";
```

- [ ] **Step 4: Implement parsing in `OntopModelSettingsImpl`**

In `core/model/src/main/java/it/unibz/inf/ontop/injection/impl/OntopModelSettingsImpl.java`, add the following methods right before `Optional<Boolean> getBoolean(String key)`:

```java
    @Override
    public TypeReasoningMode getTypeReasoningMode() {
        return getProperty(TYPE_REASONING_MODE)
                .map(String::trim)
                .map(OntopModelSettingsImpl::parseTypeReasoningMode)
                .orElse(TypeReasoningMode.FULL);
    }

    private static TypeReasoningMode parseTypeReasoningMode(String value) {
        if ("full".equalsIgnoreCase(value))
            return TypeReasoningMode.FULL;
        if ("exact".equalsIgnoreCase(value))
            return TypeReasoningMode.EXACT;
        throw new InvalidOntopConfigurationException(
                "Invalid value for " + TYPE_REASONING_MODE + ": '" + value + "' (expected 'full' or 'exact')");
    }
```

`InvalidOntopConfigurationException` and `getProperty` are already available in this file/superclass.

- [ ] **Step 5: Remove the duplicate definition from the mapping module**

In `mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java`, remove:

```java
    enum TypeReasoningMode { FULL, EXACT }

    TypeReasoningMode getTypeReasoningMode();
```

and remove the key constant:

```java
    String TYPE_REASONING_MODE = "ontop.reasoning.type";
```

In `mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java`, remove the whole override method:

```java
    @Override
    public TypeReasoningMode getTypeReasoningMode() {
        String value = getRequiredProperty(TYPE_REASONING_MODE).trim();
        if ("full".equalsIgnoreCase(value))
            return TypeReasoningMode.FULL;
        if ("exact".equalsIgnoreCase(value))
            return TypeReasoningMode.EXACT;
        throw new InvalidOntopConfigurationException(
                "Invalid value for " + TYPE_REASONING_MODE + ": '" + value + "' (expected 'full' or 'exact')");
    }
```

- [ ] **Step 6: Update `TMappingSaturatorImpl` reference**

In `mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java`:

Add the import next to the existing `OntopMappingSettings` import:

```java
import it.unibz.inf.ontop.injection.OntopModelSettings;
```

Change:

```java
                settings.getTypeReasoningMode() == OntopMappingSettings.TypeReasoningMode.EXACT
```

to:

```java
                settings.getTypeReasoningMode() == OntopModelSettings.TypeReasoningMode.EXACT
```

- [ ] **Step 7: Rebuild `core/model` and run the affected tests**

Run:

```bash
mvn -pl core/model -DskipTests install
mvn test -pl mapping/core "-Dtest=TypeReasoningModeSettingsTest,TMappingSaturationExactModeTest"
```

Expected: `core/model` BUILD SUCCESS; tests PASS (4 + 3 = 7 tests).

- [ ] **Step 8: Commit**

```bash
git add core/model/src/main/java/it/unibz/inf/ontop/injection/OntopModelSettings.java
git add core/model/src/main/java/it/unibz/inf/ontop/injection/impl/OntopModelSettingsImpl.java
git add mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java
git add mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java
git add mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java
git add mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java
git commit -m "refactor: move type reasoning mode to OntopModelSettings"
```

---

## Task 2: Gate class ABox dependencies in `DummyRewriter`

**Files:**
- Modify: `engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/DummyRewriter.java`
- Modify: `engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/TreeWitnessRewriter.java`
- Modify: `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExactTest.java`
- Modify: `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainFullTest.java`
- Create: `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/existential.properties`
- Create: `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExistentialTest.java`

- [ ] **Step 1: Finalize the exact-mode regression test**

Replace the whole file `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExactTest.java` with:

```java
package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertFalse;

public class ExactTypeReasoningDomainExactTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-domain/data.sql",
                "/exact-type-reasoning-domain/mapping.obda",
                "/exact-type-reasoning-domain/ontology.ttl",
                "/exact-type-reasoning-domain/exact.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testExactDropsDomainDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2"));

        String sql = reformulateIntoNativeQuery(query).toLowerCase();
        assertFalse("exact mode should drop the domain-derived class mapping, leaving a single table:\n" + sql,
                sql.contains("union"));
    }

    @Test
    public void testExactWithPropertyAtom() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> ; <http://example.org/ontology#code> ?c }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2"));

        String sql = reformulateIntoNativeQuery(query).toLowerCase();
        assertFalse("exact mode must not include the domain-derived table:\n" + sql,
                sql.contains("ontology#b/"));
    }
}
```

- [ ] **Step 2: Confirm the full-mode counterpart stays as expected**

Open `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainFullTest.java`. It must already contain `testFullWithPropertyAtom` expecting all four values (`a/1,a/2,b/3,b/4`). If it does not, add it:

```java
    @Test
    public void testFullWithPropertyAtom() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> ; <http://example.org/ontology#code> ?c }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2",
                "http://example.org/ontology#b/3",
                "http://example.org/ontology#b/4"));
    }
```

- [ ] **Step 3: Create the existential-enabled properties**

Create `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/existential.properties`:

```
ontop.reasoning.type=exact
ontop.existentialReasoning=true
```

- [ ] **Step 4: Create the existential-path test**

Create `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExistentialTest.java`:

```java
package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningDomainExistentialTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-domain/data.sql",
                "/exact-type-reasoning-domain/mapping.obda",
                "/exact-type-reasoning-domain/ontology.ttl",
                "/exact-type-reasoning-domain/existential.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testExactWithPropertyAtomUnderExistentialReasoning() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> ; <http://example.org/ontology#code> ?c }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2"));
    }
}
```

- [ ] **Step 5: Run the exact regression and confirm it fails**

Run:

```bash
mvn -pl binding/rdf4j -am -DskipTests install
mvn test -pl binding/rdf4j -Dtest=ExactTypeReasoningDomainExactTest
```

Expected: `testExactWithPropertyAtom` FAILS with actual `[a/1, a/2, b/3, b/4]` (the gate is not implemented yet). `testExactDropsDomainDerivedTypes` still passes.

- [ ] **Step 6: Inject settings and gate class dependencies in `DummyRewriter`**

In `engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/DummyRewriter.java`:

Add import (next to `it.unibz.inf.ontop.injection.IntermediateQueryFactory`):

```java
import it.unibz.inf.ontop.injection.OntopModelSettings;
```

Add field after `private LinearInclusionDependencies<RDFAtomPredicate> sigma;`:

```java
    private final boolean exactTypeReasoning;
```

Replace the constructor:

```java
    @Inject
    protected DummyRewriter(CoreSingletons coreSingletons) {
        this.iqFactory = coreSingletons.getIQFactory();
        this.atomFactory = coreSingletons.getAtomFactory();
        this.termFactory = coreSingletons.getTermFactory();
        this.homomorphismFactory = coreSingletons.getHomomorphismFactory();
        this.iqTreeTools = coreSingletons.getIQTreeTools();
    }
```

with:

```java
    @Inject
    protected DummyRewriter(CoreSingletons coreSingletons, OntopModelSettings settings) {
        this.iqFactory = coreSingletons.getIQFactory();
        this.atomFactory = coreSingletons.getAtomFactory();
        this.termFactory = coreSingletons.getTermFactory();
        this.homomorphismFactory = coreSingletons.getHomomorphismFactory();
        this.iqTreeTools = coreSingletons.getIQTreeTools();
        this.exactTypeReasoning = settings.getTypeReasoningMode() == OntopModelSettings.TypeReasoningMode.EXACT;
    }
```

In `setTBox`, replace the class DAG traversal:

```java
        // the head will have no existential variables
        traverseDAG(reasoner.classesDAG(), c -> (c instanceof OClass), c -> getAtom(c, x, () -> y), builder);
```

with:

```java
        if (!exactTypeReasoning) {
            // the head will have no existential variables
            traverseDAG(reasoner.classesDAG(), c -> (c instanceof OClass), c -> getAtom(c, x, () -> y), builder);
        }
```

- [ ] **Step 7: Pass settings through `TreeWitnessRewriter`**

In `engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/TreeWitnessRewriter.java`:

Add import:

```java
import it.unibz.inf.ontop.injection.OntopModelSettings;
```

Replace:

```java
    @Inject
	private TreeWitnessRewriter(CoreSingletons coreSingletons) {
        super(coreSingletons);
        this.substitutionFactory = coreSingletons.getSubstitutionFactory();
    }
```

with:

```java
    @Inject
	private TreeWitnessRewriter(CoreSingletons coreSingletons, OntopModelSettings settings) {
        super(coreSingletons, settings);
        this.substitutionFactory = coreSingletons.getSubstitutionFactory();
    }
```

- [ ] **Step 8: Rebuild and run the regression tests**

Run:

```bash
mvn -pl binding/rdf4j -am -DskipTests install
mvn test -pl binding/rdf4j "-Dtest=ExactTypeReasoningDomainExactTest,ExactTypeReasoningDomainFullTest,ExactTypeReasoningDomainExistentialTest"
```

Expected: PASS (2 + 2 + 1 = 5 tests). The exact property-atom case now returns only `a/1,a/2`; full still returns four values; existential path also returns only `a/1,a/2`.

If the existential test fails, stop and report `DONE_WITH_CONCERNS` with the actual values and generated SQL — do not weaken assertions.

- [ ] **Step 9: Commit**

```bash
git add engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/DummyRewriter.java
git add engine/reformulation/core/src/main/java/it/unibz/inf/ontop/answering/reformulation/rewriting/impl/TreeWitnessRewriter.java
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExactTest.java
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainFullTest.java
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExistentialTest.java
git add binding/rdf4j/src/test/resources/exact-type-reasoning-domain/existential.properties
git commit -m "feat: ignore domain/range class inference for rdf:type in exact mode"
```

---

## Task 3: Full verification and regression

**Files:** none (verification only)

- [ ] **Step 1: Run all exact/reasoning unit tests**

Run:

```bash
mvn test -pl mapping/core "-Dtest=TypeReasoningModeSettingsTest,TMappingSaturationExactModeTest,MappingSaturationTest,PunningTest"
```

Expected: PASS (4 + 3 + 1 + 1 = 9 tests).

- [ ] **Step 2: Run all exact integration tests plus the full-mode domain regression**

Run:

```bash
mvn test -pl binding/rdf4j "-Dtest=ExactTypeReasoningFullTest,ExactTypeReasoningExactTest,ExactTypeReasoningDomainFullTest,ExactTypeReasoningDomainExactTest,ExactTypeReasoningDomainExistentialTest,DomainSimplificationTest"
```

Expected: PASS (1 + 1 + 2 + 2 + 1 + 1 = 8 tests).

- [ ] **Step 3: Compile the affected modules**

Run:

```bash
mvn -pl core/model,mapping/core,engine/reformulation/core,binding/rdf4j -DskipTests install
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Review the final diff**

Run:

```bash
git status
git diff --stat 8ca9bea06c..HEAD
```

Expected: only the files in “File Structure” changed. Unrelated working-tree files (SameSourceMerge, analysis docs) remain untouched.

- [ ] **Step 5: Commit the plan document**

```bash
git add docs/superpowers/plans/2026-11-10-exact-type-reasoning-layer2.md
git commit -m "docs: add implementation plan for exact rewrite-layer gate"
```

---

## Self-Review Notes

- **Spec coverage:** config relocation → Task 1; `DummyRewriter` gate + `TreeWitnessRewriter` → Task 2 Steps 6–7; regression tests (property atom, existential) → Task 2 Steps 1–4; full-mode/regression verification → Task 3.
- **No TBox rebuild**, no change to `TMappingSaturatorImpl` logic (only enum reference) — matches the approved design.
- **Type consistency:** `TypeReasoningMode` referenced as `OntopModelSettings.TypeReasoningMode` everywhere after Task 1; `DummyRewriter` stores `boolean exactTypeReasoning`.
- **Full mode unchanged:** gate is `if (!exactTypeReasoning)`, so `full` builds exactly the previous Sigma.
- **Strict semantics:** skipping the whole class DAG prevents any atom from eliminating `rdf:type`, covering domain, range, and named subclass.
