package it.unibz.inf.ontop.iq.optimizer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import org.junit.Test;

import static it.unibz.inf.ontop.OptimizationTestingTools.*;
import static org.junit.Assert.*;

public class SameSourceMergeOptimizerTest {

    public static final RelationDefinition T1_AR5;

    static {
        OfflineMetadataProviderBuilder3 builder = createMetadataProviderBuilder();
        T1_AR5 = builder.createRelationWithStringAttributes(1, 5, true);
    }

}
