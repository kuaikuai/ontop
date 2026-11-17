package it.unibz.inf.ontop.dbschema;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public interface PathSpec {

    String predicate();

    Optional<TraversalFilter> traversalFilter();

    final class Self implements PathSpec {
        private final String predicate;
        private final String table;
        private final String joinKeyColumn;
        private final String parentJoinKeyColumn;
        private final String nodeKeyColumn;
        private final String nodeKeyType;
        private final String iriPrefix;
        private final Map<String, String> nodeColumns;
        private final Map<String, String> nodeColumnTypes;
        private final Optional<TraversalFilter> traversalFilter;

        public Self(String predicate, String table, String joinKeyColumn,
                    String parentJoinKeyColumn, String nodeKeyColumn, String nodeKeyType,
                    String iriPrefix, Map<String, String> nodeColumns,
                    Map<String, String> nodeColumnTypes,
                    Optional<TraversalFilter> traversalFilter) {
            this.predicate = predicate;
            this.table = table;
            this.joinKeyColumn = joinKeyColumn;
            this.parentJoinKeyColumn = parentJoinKeyColumn;
            this.nodeKeyColumn = nodeKeyColumn;
            this.nodeKeyType = nodeKeyType;
            this.iriPrefix = iriPrefix;
            this.nodeColumns = nodeColumns;
            this.nodeColumnTypes = nodeColumnTypes;
            this.traversalFilter = traversalFilter;
        }

        /**
         * Legacy constructor (no datatype information): kept for PathSpecConfigLoader,
         * where properties get the default xsd:string typing.
         */
        public Self(String predicate, String table, String joinKeyColumn,
                    String parentJoinKeyColumn, String nodeKeyColumn, String nodeKeyType,
                    String iriPrefix, Map<String, String> nodeColumns,
                    Optional<TraversalFilter> traversalFilter) {
            this(predicate, table, joinKeyColumn, parentJoinKeyColumn, nodeKeyColumn, nodeKeyType,
                    iriPrefix, nodeColumns, Collections.emptyMap(), traversalFilter);
        }

        @Override
        public String predicate() {
            return predicate;
        }

        @Override
        public Optional<TraversalFilter> traversalFilter() {
            return traversalFilter;
        }

        public String table() {
            return table;
        }

        public String joinKeyColumn() {
            return joinKeyColumn;
        }

        public String parentJoinKeyColumn() {
            return parentJoinKeyColumn;
        }

        public String nodeKeyColumn() {
            return nodeKeyColumn;
        }

        public String nodeKeyType() {
            return nodeKeyType;
        }

        public String iriPrefix() {
            return iriPrefix;
        }

        public Map<String, String> nodeColumns() {
            return nodeColumns;
        }

        /** property IRI -> declared RDF datatype IRI (xsd:string when unknown). */
        public Map<String, String> nodeColumnTypes() {
            return nodeColumnTypes;
        }
    }

    final class Endpoint {
        private final String table;
        private final String alias;
        private final String joinKeyColumn;
        private final String nodeKeyColumn;
        private final String iriTemplate;

        public Endpoint(String table, String alias, String joinKeyColumn,
                        String nodeKeyColumn, String iriTemplate) {
            this.table = table;
            this.alias = alias;
            this.joinKeyColumn = joinKeyColumn;
            this.nodeKeyColumn = nodeKeyColumn;
            this.iriTemplate = iriTemplate;
        }

        public String table() {
            return table;
        }

        public String alias() {
            return alias;
        }

        public String joinKeyColumn() {
            return joinKeyColumn;
        }

        public String nodeKeyColumn() {
            return nodeKeyColumn;
        }

        public String iriTemplate() {
            return iriTemplate;
        }
    }

    final class TwoTable implements PathSpec {
        private final String predicate;
        private final Endpoint src;
        private final Endpoint dst;
        private final String on;
        private final Optional<TraversalFilter> traversalFilter;

        public TwoTable(String predicate, Endpoint src, Endpoint dst, String on,
                        Optional<TraversalFilter> traversalFilter) {
            this.predicate = predicate;
            this.src = src;
            this.dst = dst;
            this.on = on;
            this.traversalFilter = traversalFilter;
        }

        @Override
        public String predicate() {
            return predicate;
        }

        @Override
        public Optional<TraversalFilter> traversalFilter() {
            return traversalFilter;
        }

        public Endpoint src() {
            return src;
        }

        public Endpoint dst() {
            return dst;
        }

        public String on() {
            return on;
        }
    }

    final class TraversalFilter {
        private final Optional<String> join;
        private final Optional<String> where;
        private final boolean applyToStart;

        public TraversalFilter(Optional<String> join, Optional<String> where, boolean applyToStart) {
            this.join = join;
            this.where = where;
            this.applyToStart = applyToStart;
        }

        public Optional<String> join() {
            return join;
        }

        public Optional<String> where() {
            return where;
        }

        public boolean applyToStart() {
            return applyToStart;
        }
    }
}
