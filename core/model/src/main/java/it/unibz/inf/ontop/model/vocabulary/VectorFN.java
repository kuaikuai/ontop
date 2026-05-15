package it.unibz.inf.ontop.model.vocabulary;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.simple.SimpleRDF;

/**
 * Vocabulary for Ontop vector function extensions (Apache Doris).
 * <a href="https://doris.apache.org/docs/query-acceleration/vector-search/">Doris Vector Search</a>
 */
public class VectorFN {

    public static final String PREFIX = "http://ontop/vectorfn/";

    public static final IRI L2_DIST_APPROX;

    static {
        org.apache.commons.rdf.api.RDF factory = new SimpleRDF();
        L2_DIST_APPROX = factory.createIRI(PREFIX + "l2DistApprox");
    }
}