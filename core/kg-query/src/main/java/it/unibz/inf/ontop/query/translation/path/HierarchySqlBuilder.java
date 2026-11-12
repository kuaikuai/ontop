package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpec.TraversalFilter;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class HierarchySqlBuilder {

    public String build(PathSpec spec, SeedSource seed, int minDepth, int maxDepth,
                        Optional<TraversalFilter> filter) {
        if (!(spec instanceof PathSpec.Self))
            throw new IllegalArgumentException("Phase 1 supports self relations only");
        if (maxDepth < minDepth || minDepth < 1)
            throw new IllegalArgumentException("require 1 <= minDepth <= maxDepth");

        PathSpec.Self self = (PathSpec.Self) spec;
        List<String> extraColumns = self.nodeColumns().values().stream()
                .distinct()
                .collect(Collectors.toList());
        String extraSelect = extraColumns.isEmpty()
                ? ""
                : ", " + String.join(", ", extraColumns);
        String extraParentSelect = extraColumns.isEmpty()
                ? ""
                : extraColumns.stream().map(c -> ", p." + c).collect(Collectors.joining());

        StringBuilder sb = new StringBuilder("WITH\n");

        sb.append("level_0 AS (\n  SELECT ")
                .append(self.nodeKeyColumn()).append(" AS node_iri, ")
                .append(self.parentJoinKeyColumn()).append(" AS next_key, '0' AS depth, ")
                .append(self.nodeKeyColumn()).append(" AS predecessor")
                .append(extraSelect)
                .append(" FROM ").append(self.table()).append(" ")
                .append("WHERE ").append(self.nodeKeyColumn()).append(" = ").append(nodeKeyLiteral(self, seed))
                .append("\n)");

        String filterSql = filter.flatMap(TraversalFilter::where)
                .map(w -> " WHERE " + w)
                .orElse("");

        for (int k = 1; k <= maxDepth; k++) {
            sb.append(",\nlevel_").append(k).append(" AS (\n  SELECT ")
                    .append("p.").append(self.nodeKeyColumn()).append(" AS node_iri, ")
                    .append("p.").append(self.parentJoinKeyColumn()).append(" AS next_key, ")
                    .append("'").append(k).append("' AS depth, l.node_iri AS predecessor")
                    .append(extraParentSelect)
                    .append(" FROM ").append(self.table()).append(" p ")
                    .append("JOIN level_").append(k - 1)
                    .append(" l ON p.").append(self.joinKeyColumn()).append(" = l.next_key")
                    .append(filterSql)
                    .append("\n)");
        }

        String projectedColumns = "node_iri, depth, predecessor" + extraSelect;
        sb.append("\nSELECT ").append(projectedColumns)
                .append(" FROM level_").append(minDepth);
        for (int k = minDepth + 1; k <= maxDepth; k++) {
            sb.append("\nUNION ALL SELECT ").append(projectedColumns)
                    .append(" FROM level_").append(k);
        }
        return sb.toString();
    }

    private String nodeKeyLiteral(PathSpec.Self self, SeedSource seed) {
        if (!(seed instanceof SeedSource.Constant))
            throw new IllegalArgumentException("Phase 1 supports constant seed only");
        String iri = ((SeedSource.Constant) seed).iri();
        String key = iri.substring(iri.lastIndexOf('/') + 1);
        return isNumericType(self.nodeKeyType())
                ? key
                : "'" + key.replace("'", "''") + "'";
    }

    private boolean isNumericType(String type) {
        if (type == null)
            return false;
        String upper = type.toUpperCase();
        return upper.contains("INT") || upper.contains("DEC") || upper.contains("NUM")
                || upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("REAL");
    }
}
