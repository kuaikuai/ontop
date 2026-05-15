package it.unibz.inf.ontop.dbschema.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unibz.inf.ontop.dbschema.DatabaseInfoSupplier;
import it.unibz.inf.ontop.injection.OntopModelSettings;

import javax.annotation.Nullable;
import java.util.Optional;

@Singleton
public class DatabaseInfoSupplierImpl implements DatabaseInfoSupplier {

    private final OntopModelSettings settings;

    @Inject
    protected DatabaseInfoSupplierImpl(OntopModelSettings settings) {
        this.settings = settings;
    }

    @Nullable
    private String dbVersion;

    @Override
    public Optional<String> getDatabaseVersion() {
        return Optional.ofNullable(dbVersion);
    }

    @Override
    public synchronized void setDatabaseVersion(String version) throws IllegalStateException {
        // No strict requirement to re-set the database version more than once
        // In settings such as incomplete serialized metadata (when metadata is extracted on-demand by an external
        // program) the database version setter may be called twice.
        if (this.dbVersion == null)
            this.dbVersion = version;
    }

    @Override
    public boolean isDoris() {
        return settings.getProperty(OntopModelSettings.INCLUDE_MYSQL_DORIS)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }
}
