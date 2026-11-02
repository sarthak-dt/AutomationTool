package com.stellar.platform.desktop;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Resolves a displayable application version for packaged and Maven launches.
 *
 * <p>Precedence: {@code stellar.desktop.version} (product label),
 * {@code jpackage.app-version} (numeric Windows installer version),
 * {@code /stellar-version.properties}, JAR manifest, then {@code 1.0.4}.</p>
 */
public final class DesktopVersion {

    public static final String RESOURCE = "/stellar-version.properties";
    public static final String FALLBACK = "1.0.4";

    private DesktopVersion() {
    }

    public static String displayVersion() {
        String prop = System.getProperty("stellar.desktop.version", "").trim();
        if (!prop.isEmpty()) {
            return prop;
        }
        String jpackage = System.getProperty("jpackage.app-version", "").trim();
        if (!jpackage.isEmpty()) {
            return jpackage;
        }
        return readResourceVersion()
                .or(DesktopVersion::readManifestImplementationVersion)
                .or(() -> Optional.ofNullable(DesktopVersion.class.getPackage())
                        .map(Package::getImplementationVersion)
                        .filter(v -> v != null && !v.isBlank()))
                .orElse(FALLBACK);
    }

    public static String windowTitle() {
        return "StellarAI Automotive Engineering Platform  ·  " + displayVersion();
    }

    static Optional<String> readResourceVersion() {
        try (InputStream in = DesktopVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("application.version", "").trim();
            return version.isEmpty() ? Optional.empty() : Optional.of(version);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> readManifestImplementationVersion() {
        try (InputStream in = DesktopVersion.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return Optional.empty();
            }
            Manifest manifest = new Manifest(in);
            Attributes attrs = manifest.getMainAttributes();
            String version = attrs.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (version == null || version.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(version.trim());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
