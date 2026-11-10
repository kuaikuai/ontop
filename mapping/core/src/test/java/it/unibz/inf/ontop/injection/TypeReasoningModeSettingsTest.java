package it.unibz.inf.ontop.injection;

import it.unibz.inf.ontop.exception.InvalidOntopConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TypeReasoningModeSettingsTest {

    private static OntopMappingSettings settings(Properties properties) {
        return OntopMappingConfiguration.defaultBuilder()
                .enableTestMode()
                .properties(properties)
                .build()
                .getSettings();
    }

    @Test
    public void testDefaultIsFull() {
        assertEquals(OntopMappingSettings.TypeReasoningMode.FULL,
                settings(new Properties()).getTypeReasoningMode());
    }

    @Test
    public void testExact() {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, "exact");
        assertEquals(OntopMappingSettings.TypeReasoningMode.EXACT,
                settings(properties).getTypeReasoningMode());
    }

    @Test
    public void testFull() {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, "full");
        assertEquals(OntopMappingSettings.TypeReasoningMode.FULL,
                settings(properties).getTypeReasoningMode());
    }

    @Test
    public void testInvalidValueIsRejected() {
        Properties properties = new Properties();
        properties.setProperty(OntopMappingSettings.TYPE_REASONING_MODE, "bogus");
        assertThrows(InvalidOntopConfigurationException.class,
                () -> settings(properties).getTypeReasoningMode());
    }
}
