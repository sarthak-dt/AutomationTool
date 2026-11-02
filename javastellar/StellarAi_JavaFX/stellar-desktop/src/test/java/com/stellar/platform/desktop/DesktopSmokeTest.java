package com.stellar.platform.desktop;

import com.stellar.platform.processing.ParserRegistry;
import javafx.application.Application;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSmokeTest {

    @Test
    void classpathExposesParserPluginsViaServiceLoader() {
        ParserRegistry registry = ParserRegistry.load();
        assertTrue(registry.providers().size() >= 3,
                "Desktop classpath should include MF4, ASC, and BLF parser providers");
    }

    @Test
    void applicationClassExtendsJavaFxApplication() {
        assertTrue(Application.class.isAssignableFrom(StellarDesktopApp.class));
    }
}
