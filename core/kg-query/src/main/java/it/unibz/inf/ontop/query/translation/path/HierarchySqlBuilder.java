package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpec.TraversalFilter;

import java.util.Optional;

public class HierarchySqlBuilder {

    public String build(PathSpec spec, SeedSource seed, int minDepth, int maxDepth,
                        Optional<TraversalFilter> filter) {
        if (!(spec instanceof PathSpec.Self))
            throw new IllegalArgumentException("Phase 1 supports self relations only");
        if (maxDepth < minDepth || minDepth < 1)
            throw new IllegalArgumentException("require 1 <= minDepth <= maxDepth");

        PathSpec.Self self = (PathSpec.Self) spec;
        String level0 = "SELECT "
                + iri(self, self.nodeKeyColumn()) + " AS node_iri, "
                + self.joinKeyColumn() + " AS join_key, 0 AS depth, "
                + iri(self, self.nodeKeyColumn()) + " AS predecessor "
                + "FROM " + self.table() + " "
                + "WHERE " + self.nodeKeyColumn() + " = " + nodeKeyLiteral(seed);

        StringBuilder sb = new StringBuilder("SELECT node_iri, depth, predecessor FROM (\n");
        sb.append(level0).append("\n");

        String filterSql = filter.flatMap(TraversalFilter::where)
                .map(w -> " WHERE " + w)
                .orElse("");

        String previous = level0;
        for (int k = 1; k <= maxDepth; k++) {
            String body = "SELECT "
                    + iri(self, "p." + self.nodeKeyColumn()) + " AS node_iri, "
                    + "p." + self.joinKeyColumn() + " AS join_key, "
                    + k + " AS depth, l.node_iri AS predecessor "
                    + "FROM " + self.table() + " p "
                    + "JOIN (" + previous + ") l ON p." + self.parentJoinKeyColumn() + " = l.join_key"
                    + filterSql;
            sb.append("UNION ALL\n").append(body).append("\n");
            previous = body;
        }
        sb.append(") path_rows WHERE depth >= ").append(minDepth);
        return sb.toString();
    }

    private String iri(PathSpec.Self self, String nodeKeyExpression) {
        return "CONCAT('" + self.iriPrefix() + "', " + nodeKeyExpression + ")";
    }

    private String nodeKeyLiteral(SeedSource seed) {
        if (!(seed instanceof SeedSource.Constant))
            throw new IllegalArgumentException("Phase 1 supports constant seed only");
        String iri = ((SeedSource.Constant) seed).iri();
        String key = iri.substring(iri.lastIndexOf('/') + 1);
        return "'" + key.replace("'", "''") + "'";
    }
}
