package it.unibz.inf.ontop.query.translation.path;

import com.google.common.collect.ImmutableList;

public interface SeedSource {

    final class Constant implements SeedSource {
        private final String iri;

        public Constant(String iri) {
            this.iri = iri;
        }

        public String iri() {
            return iri;
        }
    }

    final class Values implements SeedSource {
        private final ImmutableList<String> iris;

        public Values(ImmutableList<String> iris) {
            this.iris = iris;
        }

        public ImmutableList<String> iris() {
            return iris;
        }
    }

    final class TempTable implements SeedSource {
        private final String table;

        public TempTable(String table) {
            this.table = table;
        }

        public String table() {
            return table;
        }
    }
}
