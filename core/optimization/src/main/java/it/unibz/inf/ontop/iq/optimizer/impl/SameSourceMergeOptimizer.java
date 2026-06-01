package it.unibz.inf.ontop.iq.optimizer.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.impl.IQTreeTools;
import it.unibz.inf.ontop.iq.optimizer.IQOptimizer;
import it.unibz.inf.ontop.iq.transform.IQTreeVariableGeneratorTransformer;
import it.unibz.inf.ontop.iq.transform.impl.DefaultRecursiveIQTreeVisitingTransformer;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.VariableGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SameSourceMergeOptimizer extends AbstractIQOptimizer implements IQOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SameSourceMergeOptimizer.class);

    private final IQTreeTools iqTreeTools;
    private final TermFactory termFactory;
    private final SubstitutionFactory substitutionFactory;

    @Inject
    private SameSourceMergeOptimizer(IntermediateQueryFactory iqFactory,
                                     IQTreeTools iqTreeTools,
                                     TermFactory termFactory,
                                     SubstitutionFactory substitutionFactory) {
        super(iqFactory);
        this.iqTreeTools = iqTreeTools;
        this.termFactory = termFactory;
        this.substitutionFactory = substitutionFactory;
    }

    @Override
    protected IQTreeVariableGeneratorTransformer getTransformer() {
        return IQTreeVariableGeneratorTransformer.of(
                vg -> new SameSourceMergeTransformer(vg, iqFactory, iqTreeTools, termFactory, substitutionFactory));
    }

    private static class SameSourceMergeTransformer
            extends DefaultRecursiveIQTreeVisitingTransformer {

        private SameSourceMergeTransformer(VariableGenerator vg,
                                           IntermediateQueryFactory iqFactory,
                                           IQTreeTools iqTreeTools,
                                           TermFactory termFactory,
                                           SubstitutionFactory substitutionFactory) {
            super(iqFactory);
        }
    }
}
