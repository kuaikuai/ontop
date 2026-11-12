package it.unibz.inf.ontop.dbschema;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.OntopModelTestingTools;
import it.unibz.inf.ontop.model.template.Template;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.db.IRIStringTemplateFunctionSymbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathIRITemplateTest {

    @Test
    public void iriTemplateKeepsPrefix() {
        TermFactory termFactory = OntopModelTestingTools.CORE_SINGLETONS.getTermFactory();
        ImmutableList<Template.Component> template = Template.builder()
                .string("http://example.org/ontology#bom_item/")
                .placeholder()
                .build();

        ImmutableFunctionalTerm term = termFactory.getIRIFunctionalTerm(
                template, ImmutableList.of(termFactory.getVariable("v")));

        ImmutableFunctionalTerm lexical = (ImmutableFunctionalTerm) term.getTerms().get(0);
        assertTrue(lexical.getFunctionSymbol() instanceof IRIStringTemplateFunctionSymbol);

        IRIStringTemplateFunctionSymbol fs = (IRIStringTemplateFunctionSymbol) lexical.getFunctionSymbol();
        assertTrue(fs.getTemplateComponents().stream()
                .anyMatch(c -> !c.isColumn()
                        && c.getComponent().equals("http://example.org/ontology#bom_item/")));
    }
}
