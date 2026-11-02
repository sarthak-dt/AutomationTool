package com.stellar.platform.desktop;

import javafx.application.Application;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StellarDesktopLauncherTest {

    @Test
    void launcherIsClasspathEntryPointAndDoesNotExtendJavaFxApplication() throws Exception {
        assertFalse(Application.class.isAssignableFrom(StellarDesktopLauncher.class),
                "jpackage main class must not extend javafx.application.Application");
        Method main = StellarDesktopLauncher.class.getMethod("main", String[].class);
        assertTrue(Modifier.isStatic(main.getModifiers()));
        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());
    }
}
