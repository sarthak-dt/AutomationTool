package com.stellar.platform.desktop;

/**
 * Classpath entry point for native Windows packaging ({@code jpackage}).
 *
 * <p>JavaFX refuses to start when the JVM main class itself extends
 * {@link javafx.application.Application} and JavaFX is on the classpath rather
 * than the module path. That is exactly how {@code jpackage} launches a
 * non-modular application, and it produces:
 * {@code JavaFX runtime components are missing}.
 *
 * <p>This launcher does not extend {@code Application}. It delegates to
 * {@link StellarDesktopApp#main(String[])}, which then calls
 * {@code Application.launch}. The JavaFX Maven plugin continues to use
 * {@code StellarDesktopApp} directly.
 */
public final class StellarDesktopLauncher {

    private StellarDesktopLauncher() {
    }

    public static void main(String[] args) {
        DesktopLogging.configureOnce();
        StellarDesktopApp.main(args);
    }
}
