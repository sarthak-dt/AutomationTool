package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Loads the MapTiler (or other) tile API key from the environment, then an external file.
 * The key is never written to project SQLite or logged in full.
 */
public final class MapSecretStore {

    public static final String ENV_API_KEY = "STELLARAI_MAP_API_KEY";
    public static final String FILE_NAME = "map-secrets.properties";
    public static final String PROPERTY_API_KEY = "apiKey";

    private static final Pattern QUERY_KEY = Pattern.compile("(?i)([?&](?:key|api[_-]?key)=)[^&\\s'\"]+");
    private static final Pattern PROP_KEY = Pattern.compile("(?i)((?:api)?key\\s*[=:]\\s*)\\S+");

    private final Path secretsFile;
    private final Supplier<String> envApiKey;

    public MapSecretStore(Path secretsFile, Supplier<String> envApiKey) {
        this.secretsFile = secretsFile;
        this.envApiKey = envApiKey == null ? () -> null : envApiKey;
    }

    /** Resolves {@code StellarAIAnalysis/config} on each read once Analysis Storage is configured. */
    public static MapSecretStore production() {
        return new MapSecretStore(null, () -> System.getenv(ENV_API_KEY));
    }

    public static MapSecretStore empty() {
        return new MapSecretStore(Path.of("unused-map-secrets.properties"), () -> null);
    }

    public Path secretsFile() {
        Path file = liveFile();
        if (file == null) {
            throw new IllegalStateException("Analysis Storage is not configured");
        }
        return file;
    }

    private Path liveFile() {
        if (secretsFile != null) {
            return secretsFile;
        }
        if (!AnalysisStorageRoots.isConfigured()) {
            return null;
        }
        return AnalysisStorageRoots.uiPreferencesFile(FILE_NAME);
    }

    public Optional<String> apiKey() {
        String env = trimToNull(envApiKey.get());
        if (env != null) {
            return Optional.of(env);
        }
        return fileApiKey();
    }

    public boolean configured() {
        return apiKey().isPresent();
    }

    private Optional<String> fileApiKey() {
        Path file = liveFile();
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            return Optional.empty();
        }
        String value = trimToNull(properties.getProperty(PROPERTY_API_KEY));
        if (value == null) {
            value = trimToNull(properties.getProperty("stellarai.map.api-key"));
        }
        return Optional.ofNullable(value);
    }

    public static String redactForLog(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String redacted = QUERY_KEY.matcher(text).replaceAll("$1***");
        return PROP_KEY.matcher(redacted).replaceAll("$1***");
    }

    public static String maskConfigured(boolean present) {
        return present ? "configured" : "not configured";
    }

    /** Writes only the key file for tests; production UI does not offer a key field. */
    public void writeApiKeyForTest(String key) throws IOException {
        Path file = liveFile();
        if (file == null) {
            throw new IOException("Analysis Storage is not configured");
        }
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        if (key != null && !key.isBlank()) {
            properties.setProperty(PROPERTY_API_KEY, key.trim());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "StellarAI map secrets — do not commit");
        }
    }

    @Override
    public String toString() {
        Path file = liveFile();
        return "MapSecretStore{file=" + (file == null ? FILE_NAME : file.getFileName())
                + ", apiKey=" + maskConfigured(configured()) + "}";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean looksLikeKeyQuery(String url) {
        if (url == null) {
            return false;
        }
        return url.toLowerCase(Locale.ROOT).contains("key=");
    }
}
