package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Loads path definitions from a properties file named by the
 * {@code ontop.pathSpecFile} system property.
 *
 * Format:
 * <pre>
 * path.0.predicate=...
 * path.0.table=...
 * path.0.joinKeyColumn=...
 * path.0.parentJoinKeyColumn=...
 * path.0.nodeKeyColumn=...
 * path.0.iriPrefix=...
 * path.1.predicate=...
 * ...
 * </pre>
 */
public final class PathSpecConfigLoader {

    public static final String FILE_PROPERTY = "ontop.pathSpecFile";

    private PathSpecConfigLoader() {
    }

    public static void loadIfConfigured(PathSpecRegistry registry) {
        String path = System.getProperty(FILE_PROPERTY);
        if (path == null || path.trim().isEmpty())
            return;
        load(registry, new File(path.trim()));
    }

    public static void load(PathSpecRegistry registry, File file) {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read path spec config: " + file, e);
        }

        int count = 0;
        for (int i = 0; ; i++) {
            String prefix = "path." + i + ".";
            String predicate = properties.getProperty(prefix + "predicate");
            if (predicate == null)
                break;
            registry.upsert(new PathSpec.Self(
                    predicate,
                    required(properties, prefix + "table"),
                    required(properties, prefix + "joinKeyColumn"),
                    required(properties, prefix + "parentJoinKeyColumn"),
                    required(properties, prefix + "nodeKeyColumn"),
                    properties.getProperty(prefix + "nodeKeyType", "VARCHAR"),
                    required(properties, prefix + "iriPrefix"),
                    java.util.Collections.emptyMap(),
                    Optional.empty()));
            count++;
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null)
            throw new IllegalArgumentException("Missing path spec property: " + key);
        return value;
    }
}
