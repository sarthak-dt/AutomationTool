package com.stellar.platform.storage.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Application-level Analysis Storage preference (not project-local SQLite).
 *
 * <p>The durable file is {@code ~/.stellarai/config/analysis-storage.properties}.
 * A packaged install also keeps binaries under {@code %LOCALAPPDATA%\StellarAI}.
 * Startup reads the user-profile file first so an upgrade still finds the
 * previously selected root. A selection that exists only beside the binaries
 * is copied into that user-profile file. Project databases and artifacts are
 * left where they are. An explicit {@code stellarai.home} or test isolation
 * uses only that home and does not read the operator profile.
 */
public final class AnalysisStorageSettingsStore {

    private static final Logger LOG = Logger.getLogger(AnalysisStorageSettingsStore.class.getName());
    static final String FILE_NAME = "analysis-storage.properties";
    private static final String KEY_ROOT = "analysis.storage.root";

    private final Path settingsFile;
    private final Path fallbackFile;

    public AnalysisStorageSettingsStore() {
        this(resolveDefault());
    }

    public AnalysisStorageSettingsStore(Path settingsFile) {
        this(settingsFile, null);
    }

    private AnalysisStorageSettingsStore(Path settingsFile, Path fallbackFile) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile").toAbsolutePath().normalize();
        Path fallback = fallbackFile == null ? null : fallbackFile.toAbsolutePath().normalize();
        this.fallbackFile = fallback != null && fallback.equals(this.settingsFile) ? null : fallback;
    }

    private AnalysisStorageSettingsStore(AnalysisStorageSettingsStore resolved) {
        this.settingsFile = resolved.settingsFile;
        this.fallbackFile = resolved.fallbackFile;
    }

    /**
     * Settings file for a packaged or developer launch.
     * Isolated homes stay inside {@link com.stellar.platform.storage.StellarAiPaths#homeRoot()}.
     */
    public static AnalysisStorageSettingsStore resolveDefault() {
        return resolve(
                com.stellar.platform.storage.StellarAiPaths.homeRoot().resolve("config").resolve(FILE_NAME),
                legacySettingsFile(),
                homeOverridden());
    }

    /**
     * Chooses the durable settings file. {@code legacySettings} is the
     * user-profile location. {@code currentHomeSettings} is the file under the
     * active application home, which for a packaged launch is the install directory.
     */
    public static AnalysisStorageSettingsStore resolve(
            Path currentHomeSettings,
            Path legacySettings,
            boolean homeOverridden
    ) {
        Path current = currentHomeSettings.toAbsolutePath().normalize();
        Path legacy = legacySettings.toAbsolutePath().normalize();
        if (homeOverridden || current.equals(legacy)) {
            return new AnalysisStorageSettingsStore(current, null);
        }
        if (containsRoot(legacy)) {
            return new AnalysisStorageSettingsStore(legacy, null);
        }
        if (containsRoot(current)) {
            return new AnalysisStorageSettingsStore(legacy, current);
        }
        return new AnalysisStorageSettingsStore(legacy, null);
    }

    public static Path legacySettingsFile() {
        return Path.of(System.getProperty("user.home"), ".stellarai", "config", FILE_NAME)
                .toAbsolutePath()
                .normalize();
    }

    private static boolean homeOverridden() {
        if (!System.getProperty("stellarai.home", "").trim().isEmpty()) {
            return true;
        }
        return AnalysisStorageIsolation.enabled();
    }

    public Path settingsFile() {
        return settingsFile;
    }

    public Optional<Path> loadUserSelectedRoot() {
        Optional<Path> selected = readRoot(settingsFile);
        if (selected.isPresent()) {
            return selected;
        }
        if (fallbackFile == null) {
            return Optional.empty();
        }
        Optional<Path> recovered = readRoot(fallbackFile);
        if (recovered.isEmpty()) {
            return Optional.empty();
        }
        try {
            saveUserSelectedRoot(recovered.get());
        } catch (RuntimeException ex) {
            LOG.warning(() -> "Analysis Storage root was restored for this session; "
                    + "the user-profile setting was not written: " + ex.getMessage());
        }
        return recovered;
    }

    private Optional<Path> readRoot(Path file) {
        if (AnalysisStorageIsolation.enabled() && AnalysisStorageIsolation.isOperatorWorkspace(file)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ex) {
            LOG.warning(() -> "Cannot read Analysis Storage settings: " + ex.getMessage());
            return Optional.empty();
        }
        String value = props.getProperty(KEY_ROOT, "").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value).toAbsolutePath().normalize());
    }

    private static boolean containsRoot(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ex) {
            return false;
        }
        return !props.getProperty(KEY_ROOT, "").trim().isEmpty();
    }

    public void saveUserSelectedRoot(Path userSelectedRoot) {
        Objects.requireNonNull(userSelectedRoot, "userSelectedRoot");
        Path absolute = userSelectedRoot.toAbsolutePath().normalize();
        AnalysisStorageIsolation.refuseOperatorWorkspace(settingsFile);
        AnalysisStorageIsolation.refuseOperatorWorkspace(absolute);
        try {
            Files.createDirectories(settingsFile.getParent());
            Properties props = new Properties();
            if (Files.isRegularFile(settingsFile)) {
                try (InputStream in = Files.newInputStream(settingsFile)) {
                    props.load(in);
                }
            }
            props.setProperty(KEY_ROOT, absolute.toString());
            try (OutputStream out = Files.newOutputStream(settingsFile)) {
                props.store(out, "StellarAI Analysis Storage (application setting)");
            }
            LOG.info(() -> "Analysis Storage configured: " + absolute);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot save Analysis Storage setting", ex);
        }
    }
}
