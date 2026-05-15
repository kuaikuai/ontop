package it.unibz.inf.ontop.model.term.functionsymbol.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.iq.node.VariableNullability;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbol;
import it.unibz.inf.ontop.model.type.RDFTermType;
import it.unibz.inf.ontop.model.type.TermTypeInference;
import org.apache.commons.rdf.api.IRI;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * SPARQL function symbol for l2_distance_approximate(vector, vector_literal).
 * <p>
 * SPARQL: vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string)
 * SQL:    l2_distance_approximate(emb_col, ARRAY[0.1,0.2,0.3])
 * <p>
 * Both arguments are declared as xsd:string at the SPARQL level:
 * - arg0: the embedding variable (resolved to a column reference via mapping)
 * - arg1: the query vector expressed as xsd:string, e.g. "[0.1,0.2,0.3]"
 */
public class L2DistApproxSPARQLFunctionSymbolImpl extends ReduciblePositiveAritySPARQLFunctionSymbolImpl {

    private static final String FUNCTION_NAME = "SP_L2_DIST_APPROX";

    private final RDFTermType argType;
    private final RDFTermType targetType;

    public L2DistApproxSPARQLFunctionSymbolImpl(@Nonnull IRI functionIRI,
                                                 @Nonnull RDFTermType stringType,
                                                 @Nonnull RDFTermType doubleType) {
        super(FUNCTION_NAME, functionIRI,
                ImmutableList.of(stringType, stringType));
        this.argType = stringType;
        this.targetType = doubleType;
    }

    @Override
    public Optional<TermTypeInference> inferType(ImmutableList<? extends ImmutableTerm> terms) {
        return Optional.of(TermTypeInference.declareTermType(targetType));
    }

    @Override
    public boolean canBePostProcessed(ImmutableList<? extends ImmutableTerm> arguments) {
        return false;
    }

    @Override
    protected ImmutableTerm computeLexicalTerm(ImmutableList<ImmutableTerm> subLexicalTerms,
                                                ImmutableList<ImmutableTerm> typeTerms,
                                                TermFactory termFactory,
                                                ImmutableTerm returnedTypeTerm) {

        // Convert arg0: ?emb → DB term (column reference or variable)
        ImmutableTerm embDB = termFactory.getConversionFromRDFLexical2DB(
                subLexicalTerms.get(0), argType);

        // Convert arg1: string literal "[0.1,0.2,0.3]" → DB constant
        ImmutableTerm vecDB = termFactory.getConversionFromRDFLexical2DB(
                subLexicalTerms.get(1), argType);

        // Look up the DB function symbol and create functional term
        DBFunctionSymbol dbFunc = termFactory.getDBFunctionSymbolFactory()
                .getRegularDBFunctionSymbol("L2_DISTANCE_APPROXIMATE", 2);
        ImmutableFunctionalTerm dbFuncTerm = termFactory.getImmutableFunctionalTerm(
                dbFunc, embDB, vecDB);

        // Convert DB result back to RDF lexical (xsd:double)
        return termFactory.getConversion2RDFLexical(dbFuncTerm, targetType);
    }

    @Override
    protected ImmutableTerm computeTypeTerm(ImmutableList<? extends ImmutableTerm> subLexicalTerms,
                                             ImmutableList<ImmutableTerm> typeTerms,
                                             TermFactory termFactory,
                                             VariableNullability variableNullability) {
        return termFactory.getRDFTermTypeConstant(targetType);
    }

    @Override
    public boolean isAlwaysInjectiveInTheAbsenceOfNonInjectiveFunctionalTerms() {
        return false;
    }
}