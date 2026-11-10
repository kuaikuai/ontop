# Exact Type Reasoning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a global `ontop.reasoning.type` switch (`full` default / `exact`) so that `rdf:type` queries in `exact` mode ignore class mappings derived by TBox saturation, while keeping literal mappings, facts, rules, and property (sub-property) reasoning intact.

**Architecture:** The switch is read by `OntopMappingSettings`. `TMappingSaturatorImpl` filters its `saturated` map by dropping entries whose `MappingAssertionIndex.isClass()` is true when `exact` is active, before concatenating with the untouched `original` assertions. No changes to `MappingAssertion`, `DefaultMappingTransformer`, or `MappingImpl`.

**Tech Stack:** Java 11, Maven, Guice, Guava, JUnit 5 (mapping/core), JUnit 4 (binding/rdf4j), SLF4J, H2 (tests).

---

## File Structure

**Modified**
- `mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java` — add `TypeReasoningMode` enum, `getTypeReasoningMode()`, `TYPE_REASONING_MODE` key.
- `mapping/core/src/main/resources/it/unibz/inf/ontop/injection/mapping-default.properties` — default `ontop.reasoning.type = full`.
- `mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java` — parse/validate the property.
- `mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java` — inject settings, drop inferred class entries in exact mode, log diagnostics.

**Created (tests)**
- `mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java`
- `mapping/core/src/test/java/it/unibz/inf/ontop/spec/mapping/TMappingSaturationExactModeTest.java`
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningFullTest.java`
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningExactTest.java`

**Created (resources)**
- `binding/rdf4j/src/test/resources/exact-type-reasoning/data.sql`
- `binding/rdf4j/src/test/resources/exact-type-reasoning/mapping.obda`
- `binding/rdf4j/src/test/resources/exact-type-reasoning/ontology.ttl`
- `binding/rdf4j/src/test/resources/exact-type-reasoning/full.properties`
- `binding/rdf4j/src/test/resources/exact-type-reasoning/exact.properties`

**Note:** Two integration test classes (full/exact) are used because `AbstractRDF4JTest` keeps a single static repository per class; each class initializes once with a different properties file.

---

## Task 1: Configuration key `ontop.reasoning.type`

**Files:**
- Create: `mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java`
- Modify: `mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java`
- Modify: `mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java`
- Modify: `mapping/core/src/main/resources/it/unibz/inf/ontop/injection/mapping-default.properties`

- [ ] **Step 1: Write the failing test**

Create `mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java`:

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
        assertEquals(OntopMappingSettings.TypeReasoningMode.FULL,
                settings(new Properties()).getTypeReasoningMode());
    }

    @Test
    public void testExact() {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, "exact");
        assertEquals(OntopMappingSettings.TypeReasoningMode.EXACT,
                settings(properties).getTypeReasoningMode());
    }

    @Test
    public void testFull() {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, "full");
        assertEquals(OntopMappingSettings.TypeReasoningMode.FULL,
                settings(properties).getTypeReasoningMode());
    }

    @Test
    public void testInvalidValueIsRejected() {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, "bogus");
        assertThrows(InvalidOntopConfigurationException.class,
                () -> settings(properties).getTypeReasoningMode());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run:

```bash
mvn test -pl mapping/core -Dtest=TypeReasoningModeSettingsTest
```

Expected: FAIL at compile time (`OntopMappingSettings.TypeReasoningMode` and `getTypeReasoningMode()` do not exist yet).

> If Maven cannot resolve `mapping/core` dependencies, run once: `mvn -pl mapping/core -am -DskipTests install`.

- [ ] **Step 3: Add the enum, method, and key to the interface**

Edit `mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java`. Add the enum and method right after the interface declaration:

```java
public interface OntopMappingSettings extends OntopKGQuerySettings {

    enum TypeReasoningMode { FULL, EXACT }

    TypeReasoningMode getTypeReasoningMode();

    boolean isOntologyAnnotationQueryingEnabled();
```

Add the key inside the `// Keys` section, next to the other keys:

```java
    String TYPE_REASONING_MODE = "ontop.reasoning.type";
```

- [ ] **Step 4: Add the default value**

Append to `mapping/core/src/main/resources/it/unibz/inf/ontop/injection/mapping-default.properties` (after the existing `ontop.wrapMappingValuesNodesInLenses` line):

```properties

# Controls the source of rdf:type class mappings.
# full : all class mappings, including those derived by TBox saturation
# exact: only non-derived class mappings (excludes TBox saturation)
ontop.reasoning.type = full
```

- [ ] **Step 5: Implement parsing and validation**

Edit `mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java`. Add this method before the closing brace:

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

`InvalidOntopConfigurationException` is already imported in this file, and `getRequiredProperty` is inherited from `OntopModelSettingsImpl` in the same package.

- [ ] **Step 6: Run the test to confirm it passes**

Run:

```bash
mvn test -pl mapping/core -Dtest=TypeReasoningModeSettingsTest
```

Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add mapping/core/src/main/java/it/unibz/inf/ontop/injection/OntopMappingSettings.java
git add mapping/core/src/main/java/it/unibz/inf/ontop/injection/impl/OntopMappingSettingsImpl.java
git add mapping/core/src/main/resources/it/unibz/inf/ontop/injection/mapping-default.properties
git add mapping/core/src/test/java/it/unibz/inf/ontop/injection/TypeReasoningModeSettingsTest.java
git commit -m "feat: add ontop.reasoning.type switch (full|exact)"
```

---

## Task 2: Exact filtering in `TMappingSaturatorImpl`

**Files:**
- Create: `mapping/core/src/test/java/it/unibz/inf/ontop/spec/mapping/TMappingSaturationExactModeTest.java`
- Modify: `mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java`

- [ ] **Step 1: Write the failing test**

Create `mapping/core/src/test/java/it/unibz/inf/ontop/spec/mapping/TMappingSaturationExactModeTest.java`:

```java
package it.unibz.inf.ontop.spec.mapping;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Injector;
import it.unibz.inf.ontop.dbschema.NamedRelationDefinition;
import it.unibz.inf.ontop.dbschema.impl.OfflineMetadataProviderBuilder;
import it.unibz.inf.ontop.injection.OntopMappingConfiguration;
import it.unibz.inf.ontop.injection.OntopMappingSettings;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.node.ConstructionNode;
import it.unibz.inf.ontop.iq.node.ExtensionalDataNode;
import it.unibz.inf.ontop.iq.node.UnionNode;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import it.unibz.inf.ontop.model.atom.RDFAtomPredicate;
import it.unibz.inf.ontop.model.template.Template;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.vocabulary.RDF;
import it.unibz.inf.ontop.spec.mapping.transformer.MappingSaturator;
import it.unibz.inf.ontop.spec.ontology.OClass;
import it.unibz.inf.ontop.spec.ontology.ObjectPropertyExpression;
import it.unibz.inf.ontop.spec.ontology.Ontology;
import it.unibz.inf.ontop.spec.ontology.OntologyBuilder;
import it.unibz.inf.ontop.spec.ontology.impl.OntologyBuilderImpl;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import org.apache.commons.rdf.api.IRI;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static it.unibz.inf.ontop.utils.MappingTestingTools.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TMappingSaturationExactModeTest {

    private static final NamedRelationDefinition T1;
    private static final NamedRelationDefinition T2;

    private static final Variable X = TERM_FACTORY.getVariable("x");
    private static final Variable S = TERM_FACTORY.getVariable("s");
    private static final Variable P = TERM_FACTORY.getVariable("p");
    private static final Variable O = TERM_FACTORY.getVariable("o");

    private static final ImmutableList<Template.Component> TEMPLATE_B =
            Template.builder().string("http://example.org/b/").placeholder().build();
    private static final ImmutableList<Template.Component> TEMPLATE_A =
            Template.builder().string("http://example.org/a/").placeholder().build();
    private static final ImmutableList<Template.Component> TEMPLATE_S =
            Template.builder().string("http://example.org/s/").placeholder().build();
    private static final ImmutableList<Template.Component> TEMPLATE_O =
            Template.builder().string("http://example.org/o/").placeholder().build();

    private static final IRI CLASS_A = RDF_FACTORY.createIRI("http://example.org/ontology#A");
    private static final IRI CLASS_B = RDF_FACTORY.createIRI("http://example.org/ontology#B");
    private static final IRI PROP_P = RDF_FACTORY.createIRI("http://example.org/ontology#p");
    private static final IRI PROP_Q = RDF_FACTORY.createIRI("http://example.org/ontology#q");

    static {
        OfflineMetadataProviderBuilder builder = createMetadataProviderBuilder();
        T1 = builder.createDatabaseRelation("t1", "id",
                builder.getDBTypeFactory().getDBLargeIntegerType(), false);
        T2 = builder.createDatabaseRelation("t2", "id",
                builder.getDBTypeFactory().getDBLargeIntegerType(), false);
    }

    @Test
    public void testExactKeepsOnlyLiteralClassMappings() throws Exception {
        OntologyBuilder builder = OntologyBuilderImpl.builder(RDF_FACTORY, TERM_FACTORY);
        OClass a = builder.declareClass(CLASS_A);
        OClass b = builder.declareClass(CLASS_B);
        builder.addSubClassOfAxiom(b, a);
        Ontology ontology = builder.build();

        IQ bMapping = classMapping(TEMPLATE_B, CLASS_B, T1);
        IQ aMapping = classMapping(TEMPLATE_A, CLASS_A, T2);
        ImmutableList<MappingAssertion> mapping = ImmutableList.of(
                new MappingAssertion(bMapping, null),
                new MappingAssertion(aMapping, null));

        RDFAtomPredicate predicate = (RDFAtomPredicate) aMapping.getProjectionAtom().getPredicate();

        ImmutableMap<MappingAssertionIndex, IQ> result = createSaturator("exact")
                .saturate(mapping, ontology.tbox()).stream()
                .collect(ImmutableCollectors.toMap(MappingAssertion::getIndex, MappingAssertion::getQuery));

        IQ classA = result.get(MappingAssertionIndex.ofClass(predicate, CLASS_A));
        IQ classB = result.get(MappingAssertionIndex.ofClass(predicate, CLASS_B));

        assertNotNull(classA, "literal a:A mapping must be kept in exact mode");
        assertNotNull(classB, "literal a:B mapping must be kept in exact mode");
        assertFalse(classA.getTree().getChildren().get(0).getRootNode() instanceof UnionNode,
                "exact a:A must not contain the B-derived mapping");
        assertFalse(classB.getTree().getChildren().get(0).getRootNode() instanceof UnionNode,
                "exact a:B must stay a single mapping");
    }

    @Test
    public void testFullMergesDerivedClassMapping() throws Exception {
        OntologyBuilder builder = OntologyBuilderImpl.builder(RDF_FACTORY, TERM_FACTORY);
        OClass a = builder.declareClass(CLASS_A);
        OClass b = builder.declareClass(CLASS_B);
        builder.addSubClassOfAxiom(b, a);
        Ontology ontology = builder.build();

        IQ bMapping = classMapping(TEMPLATE_B, CLASS_B, T1);
        IQ aMapping = classMapping(TEMPLATE_A, CLASS_A, T2);
        ImmutableList<MappingAssertion> mapping = ImmutableList.of(
                new MappingAssertion(bMapping, null),
                new MappingAssertion(aMapping, null));

        RDFAtomPredicate predicate = (RDFAtomPredicate) aMapping.getProjectionAtom().getPredicate();

        ImmutableMap<MappingAssertionIndex, IQ> result = createSaturator("full")
                .saturate(mapping, ontology.tbox()).stream()
                .collect(ImmutableCollectors.toMap(MappingAssertion::getIndex, MappingAssertion::getQuery));

        IQ classA = result.get(MappingAssertionIndex.ofClass(predicate, CLASS_A));
        assertNotNull(classA);
        assertTrue(classA.getTree().getChildren().get(0).getRootNode() instanceof UnionNode,
                "full a:A must merge the B-derived mapping");
    }

    @Test
    public void testExactKeepsPropertySubPropertySaturation() throws Exception {
        OntologyBuilder builder = OntologyBuilderImpl.builder(RDF_FACTORY, TERM_FACTORY);
        ObjectPropertyExpression p = builder.declareObjectProperty(PROP_P);
        ObjectPropertyExpression q = builder.declareObjectProperty(PROP_Q);
        builder.addSubPropertyOfAxiom(p, q);
        Ontology ontology = builder.build();

        IQ pMapping = objectPropertyMapping(TEMPLATE_S, TEMPLATE_O, PROP_P, T1);
        ImmutableList<MappingAssertion> mapping = ImmutableList.of(new MappingAssertion(pMapping, null));

        RDFAtomPredicate predicate = (RDFAtomPredicate) pMapping.getProjectionAtom().getPredicate();

        ImmutableMap<MappingAssertionIndex, IQ> result = createSaturator("exact")
                .saturate(mapping, ontology.tbox()).stream()
                .collect(ImmutableCollectors.toMap(MappingAssertion::getIndex, MappingAssertion::getQuery));

        assertNotNull(result.get(MappingAssertionIndex.ofProperty(predicate, PROP_P)),
                "literal p mapping must be kept");
        assertNotNull(result.get(MappingAssertionIndex.ofProperty(predicate, PROP_Q)),
                "sub-property saturation must remain enabled in exact mode");
    }

    private static MappingSaturator createSaturator(String mode) {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, mode);
        Injector injector = OntopMappingConfiguration.defaultBuilder()
                .enableTestMode()
                .properties(properties)
                .build()
                .getInjector();
        return injector.getInstance(MappingSaturator.class);
    }

    private static IQ classMapping(ImmutableList<Template.Component> template, IRI classIri,
                                   NamedRelationDefinition table) {
        DistinctVariableOnlyDataAtom atom = ATOM_FACTORY.getDistinctTripleAtom(S, P, O);
        ConstructionNode root = IQ_FACTORY.createConstructionNode(
                ImmutableSet.of(S, P, O),
                SUBSTITUTION_FACTORY.getSubstitution(
                        S, TERM_FACTORY.getIRIFunctionalTerm(template, ImmutableList.of(X)),
                        P, TERM_FACTORY.getConstantIRI(RDF.TYPE),
                        O, TERM_FACTORY.getConstantIRI(classIri)));
        ExtensionalDataNode data = IQ_FACTORY.createExtensionalDataNode(table, ImmutableMap.of(0, X));
        return IQ_FACTORY.createIQ(atom, IQ_FACTORY.createUnaryIQTree(root, data));
    }

    private static IQ objectPropertyMapping(ImmutableList<Template.Component> subjectTemplate,
                                            ImmutableList<Template.Component> objectTemplate,
                                            IRI propertyIri, NamedRelationDefinition table) {
        DistinctVariableOnlyDataAtom atom = ATOM_FACTORY.getDistinctTripleAtom(S, P, O);
        ConstructionNode root = IQ_FACTORY.createConstructionNode(
                ImmutableSet.of(S, P, O),
                SUBSTITUTION_FACTORY.getSubstitution(
                        S, TERM_FACTORY.getIRIFunctionalTerm(subjectTemplate, ImmutableList.of(X)),
                        P, TERM_FACTORY.getConstantIRI(propertyIri),
                        O, TERM_FACTORY.getIRIFunctionalTerm(objectTemplate, ImmutableList.of(X))));
        ExtensionalDataNode data = IQ_FACTORY.createExtensionalDataNode(table, ImmutableMap.of(0, X));
        return IQ_FACTORY.createIQ(atom, IQ_FACTORY.createUnaryIQTree(root, data));
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run:

```bash
mvn test -pl mapping/core -Dtest=TMappingSaturationExactModeTest
```

Expected: `testExactKeepsOnlyLiteralClassMappings` FAILS (in `exact` the A mapping is still a union, because filtering is not implemented). The other two tests may already pass.

- [ ] **Step 3: Inject settings into `TMappingSaturatorImpl`**

Edit `mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java`.

Add imports (after the existing `it.unibz.inf.ontop.evaluator.TermNullabilityEvaluator` import area):

```java
import it.unibz.inf.ontop.injection.OntopMappingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add a logger field next to the class fields:

```java
    private static final Logger LOGGER = LoggerFactory.getLogger(TMappingSaturatorImpl.class);
```

Add the settings field:

```java
    private final OntopMappingSettings settings;
```

Add the constructor parameter and assignment:

```java
    @Inject
	private TMappingSaturatorImpl(TMappingExclusionConfig tMappingExclusionConfig,
                                  MappingCQCOptimizer mappingCqcOptimizer,
                                  UnionBasedQueryMerger queryMerger,
                                  CoreSingletons coreSingletons,
                                  TermNullabilityEvaluator termNullabilityEvaluator,
                                  OntopMappingSettings settings) {
        this.tMappingExclusionConfig = tMappingExclusionConfig;
		this.termFactory = coreSingletons.getTermFactory();
        this.mappingCqcOptimizer = mappingCqcOptimizer;
        this.queryMerger = queryMerger;
        this.coreSingletons = coreSingletons;
        this.substitutionFactory = coreSingletons.getSubstitutionFactory();
        this.iqTreeTools = coreSingletons.getIQTreeTools();
        this.termNullabilityEvaluator = termNullabilityEvaluator;
        this.settings = settings;
    }
```

- [ ] **Step 4: Replace the return block with the exact-aware version**

In `saturate(...)`, replace the existing final return block:

```java
        return Stream.concat(
                saturated.values().stream(),
                original.asMap().entrySet().stream()
                        .filter(e -> !saturated.containsKey(e.getKey()))
                        .map(e -> e.getValue().stream()
                                        .collect(toMappingAssertion(cqc)))
                        .map(Optional::get))
                .collect(ImmutableCollectors.toList());
```

with:

```java
        ImmutableMap<MappingAssertionIndex, MappingAssertion> effectiveSaturated =
                settings.getTypeReasoningMode() == OntopMappingSettings.TypeReasoningMode.EXACT
                        ? dropInferredClassMappings(saturated)
                        : saturated;

        return Stream.concat(
                effectiveSaturated.values().stream(),
                original.asMap().entrySet().stream()
                        .filter(e -> !effectiveSaturated.containsKey(e.getKey()))
                        .map(e -> e.getValue().stream()
                                        .collect(toMappingAssertion(cqc)))
                        .map(Optional::get))
                .collect(ImmutableCollectors.toList());
```

- [ ] **Step 5: Add the filtering helper**

Add this private static method before the closing brace of `TMappingSaturatorImpl`:

```java
    private static ImmutableMap<MappingAssertionIndex, MappingAssertion> dropInferredClassMappings(
            ImmutableMap<MappingAssertionIndex, MappingAssertion> saturated) {
        long dropped = saturated.keySet().stream()
                .filter(MappingAssertionIndex::isClass)
                .count();
        LOGGER.info("Exact type reasoning enabled: dropped {} inferred class mappings", dropped);
        saturated.keySet().stream()
                .filter(MappingAssertionIndex::isClass)
                .forEach(i -> LOGGER.debug("Exact type reasoning: dropped {}", i));
        return saturated.entrySet().stream()
                .filter(e -> !e.getKey().isClass())
                .collect(ImmutableCollectors.toMap());
    }
```

- [ ] **Step 6: Run the test to confirm it passes**

Run:

```bash
mvn test -pl mapping/core -Dtest=TMappingSaturationExactModeTest
```

Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add mapping/core/src/main/java/it/unibz/inf/ontop/spec/mapping/transformer/impl/TMappingSaturatorImpl.java
git add mapping/core/src/test/java/it/unibz/inf/ontop/spec/mapping/TMappingSaturationExactModeTest.java
git commit -m "feat: drop inferred class mappings in exact reasoning mode"
```

---

## Task 3: End-to-end full vs exact tests

**Files:**
- Create: `binding/rdf4j/src/test/resources/exact-type-reasoning/data.sql`
- Create: `binding/rdf4j/src/test/resources/exact-type-reasoning/mapping.obda`
- Create: `binding/rdf4j/src/test/resources/exact-type-reasoning/ontology.ttl`
- Create: `binding/rdf4j/src/test/resources/exact-type-reasoning/full.properties`
- Create: `binding/rdf4j/src/test/resources/exact-type-reasoning/exact.properties`
- Create: `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningFullTest.java`
- Create: `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningExactTest.java`

- [ ] **Step 1: Create the SQL fixture**

Create `binding/rdf4j/src/test/resources/exact-type-reasoning/data.sql`:

```sql
create table t1 (
    id integer primary key
);

create table t2 (
    id integer primary key
);

insert into t1 (id) values (1), (2);
insert into t2 (id) values (3), (4);
```

- [ ] **Step 2: Create the OBDA mapping**

Create `binding/rdf4j/src/test/resources/exact-type-reasoning/mapping.obda`:

```
[PrefixDeclaration]
: http://example.org/ontology#
rdfs: http://www.w3.org/2000/01/rdf-schema#

[MappingDeclaration] @collection [[
mappingId	bMapping
target		:b/{id} a :B .
source		select id from t1

mappingId	aMapping
target		:a/{id} a :A .
source		select id from t2
]]
```

- [ ] **Step 3: Create the ontology**

Create `binding/rdf4j/src/test/resources/exact-type-reasoning/ontology.ttl`:

```
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix : <http://example.org/ontology#> .

:A a rdfs:Class .

:B a rdfs:Class ;
    rdfs:subClassOf :A .
```

- [ ] **Step 4: Create the properties files**

Create `binding/rdf4j/src/test/resources/exact-type-reasoning/full.properties`:

```
ontop.reasoning.type=full
```

Create `binding/rdf4j/src/test/resources/exact-type-reasoning/exact.properties`:

```
ontop.reasoning.type=exact
```

- [ ] **Step 5: Write the full-mode test**

Create `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningFullTest.java`:

```java
package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningFullTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning/data.sql",
                "/exact-type-reasoning/mapping.obda",
                "/exact-type-reasoning/ontology.ttl",
                "/exact-type-reasoning/full.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testFullIncludesDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/3",
                "http://example.org/ontology#a/4",
                "http://example.org/ontology#b/1",
                "http://example.org/ontology#b/2"));
    }
}
```

- [ ] **Step 6: Run the full-mode test**

Run:

```bash
mvn test -pl binding/rdf4j -Dtest=ExactTypeReasoningFullTest
```

Expected: PASS. If dependencies are missing, run once: `mvn -pl binding/rdf4j -am -DskipTests install`.

- [ ] **Step 7: Write the exact-mode test**

Create `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningExactTest.java`:

```java
package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningExactTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning/data.sql",
                "/exact-type-reasoning/mapping.obda",
                "/exact-type-reasoning/ontology.ttl",
                "/exact-type-reasoning/exact.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testExactExcludesDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/3",
                "http://example.org/ontology#a/4"));
    }
}
```

- [ ] **Step 8: Run the exact-mode test**

Run:

```bash
mvn test -pl binding/rdf4j -Dtest=ExactTypeReasoningExactTest
```

Expected: PASS (only `a/3` and `a/4`).

- [ ] **Step 9: Commit**

```bash
git add binding/rdf4j/src/test/resources/exact-type-reasoning
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningFullTest.java
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningExactTest.java
git commit -m "test: add end-to-end full/exact type reasoning tests"
```

---

## Task 4: Full verification and regression

**Files:** none (verification only)

- [ ] **Step 1: Run all new mapping/core tests together**

Run:

```bash
mvn test -pl mapping/core -Dtest=TypeReasoningModeSettingsTest,TMappingSaturationExactModeTest
```

Expected: PASS (7 tests total).

- [ ] **Step 2: Run existing saturation regressions**

Run:

```bash
mvn test -pl mapping/core -Dtest=MappingSaturationTest,PunningTest
```

Expected: PASS. These use the default `full` mode and must be unchanged.

- [ ] **Step 3: Run the integration tests together**

Run:

```bash
mvn test -pl binding/rdf4j -Dtest=ExactTypeReasoningFullTest,ExactTypeReasoningExactTest
```

Expected: PASS (2 tests).

- [ ] **Step 4: Compile the whole affected modules**

Run:

```bash
mvn -pl mapping/core,binding/rdf4j -am -DskipTests install
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Review the final diff**

Run:

```bash
git status
git diff --stat
```

Expected: only the files listed in "File Structure" are modified/added. No unrelated changes staged.

- [ ] **Step 6: Commit any remaining changes (only if Step 5 shows uncommitted intended files)**

```bash
git add mapping/core binding/rdf4j docs/superpowers/plans/2026-11-10-exact-type-reasoning.md
git commit -m "docs: add exact type reasoning implementation plan"
```

---

## Task 5: Multi-domain / property-domain regression (design §5.2)

**Files (all new):**
- `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/data.sql`
- `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/mapping.obda`
- `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/ontology.ttl`
- `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/full.properties`
- `binding/rdf4j/src/test/resources/exact-type-reasoning-domain/exact.properties`
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainFullTest.java`
- `binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExactTest.java`

Purpose: reproduce the original symptom shape — a data property `:code` with `rdfs:domain :A`, mapped on two different tables with different subject templates, so `full` produces a `UNION` of the literal `a:A` table and the domain-derived table, while `exact` leaves a single table. This is the §2.4 diagnostic gate.

Fixtures:
- `data.sql`: `t1(id, code)` values `(1,'c1'),(2,'c2')`; `t2(id, code)` values `(3,'c3'),(4,'c4')`.
- `mapping.obda`: t1 → `:a/{id} a :A ; :code {code} .`; t2 → `:b/{id} :code {code} .`.
- `ontology.ttl`: `:A a rdfs:Class .` and `:code a owl:DatatypeProperty ; rdfs:domain :A .`.
- `full.properties`: `ontop.reasoning.type=full`; `exact.properties`: `ontop.reasoning.type=exact`.

Expected:
- full: `?v a :A` → `{a/1, a/2, b/3, b/4}` and reformulated SQL contains `union`.
- exact: `?v a :A` → `{a/1, a/2}` and reformulated SQL contains no `union`.

Verified result: both pass. This confirms the original union is caused by TBox saturation (domain-derived class mappings), not by the rewrite layer; §2.5 is not required for this scenario.

Commit:
```bash
git add binding/rdf4j/src/test/resources/exact-type-reasoning-domain
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainFullTest.java
git add binding/rdf4j/src/test/java/it/unibz/inf/ontop/rdf4j/repository/ExactTypeReasoningDomainExactTest.java
git commit -m "test: add multi-domain regression for exact type reasoning"
```

---

## Self-Review Notes

- **Spec coverage:** §2.1 → Task 1; §2.2/§2.3/§2.4 → Task 2; §2.4 diagnostics → Task 2 Step 5; §5.1 unit → Tasks 1–2; §5.2 integration → Tasks 3 & 5; §5.3 commands → Tasks 1–4.
- **No `MappingAssertion.inferred` field** is introduced, matching v1.1 design.
- **Facts/rules preservation** follows from not touching `DefaultMappingTransformer` and only filtering `saturated` class entries; `original` retains literal/fact class assertions because `effectiveSaturated.containsKey` is false for them.
- **Property sub-property saturation** is explicitly regression-tested in `testExactKeepsPropertySubPropertySaturation`.
- **Type consistency:** `TypeReasoningMode` is referenced as `OntopMappingSettings.TypeReasoningMode` everywhere; `dropInferredClassMappings` signature matches the `saturated` map type `ImmutableMap<MappingAssertionIndex, MappingAssertion>`.
