package it.unibz.inf.ontop.dbschema;

import java.util.Optional;

/**
 * Carries information that is not yet available at the moment where the dependency-injection framework is initialized.
 */
public interface DatabaseInfoSupplier {

    Optional<String> getDatabaseVersion();

    /**
     * Can only be set once
     */
    void setDatabaseVersion(String version) throws IllegalStateException;

    /**
     * MySQL-compatible Doris mode flag.
     * When true, generates Doris-compatible SQL instead of MySQL-specific SQL.
     * Default is false.
     */
    default boolean isDoris() {
        return false;
    }

    /**
     * Doris UNIQUE-model key columns as unique constraints flag.
     * When true (together with isDoris()), the key columns of Doris UNIQUE-model tables
     * are declared as unique constraints during metadata extraction.
     * Default is false.
     */
    default boolean isDorisUniqueModelKeyConstraints() {
        return false;
    }
}