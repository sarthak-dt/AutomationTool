package com.stellar.platform.desktop.ui.home;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Workspace UI preferences (favourites). Application-level file, not project engineering metadata.
 */
public final class WorkspaceUiPreferencesStore {

    private static final Logger LOG = Logger.getLogger(WorkspaceUiPreferencesStore.class.getName());
    private static final String FILE_NAME = "workspace-ui.properties";
    private static final String FAVORITES_KEY = "favorite.project.ids";

    private final Path settingsFile;

    public WorkspaceUiPreferencesStore() {
        this(null);
    }

    public WorkspaceUiPreferencesStore(Path settingsFile) {
        this.settingsFile = settingsFile == null ? null : settingsFile.toAbsolutePath().normalize();
    }

    private Path resolveFile() {
        if (settingsFile != null) {
            return settingsFile;
        }
        if (!AnalysisStorageRoots.isConfigured()) {
            return null;
        }
        return AnalysisStorageRoots.uiPreferencesFile(FILE_NAME);
    }

    public Set<String> favoriteProjectIds() {
        return Set.copyOf(loadFavorites());
    }

    public boolean isFavorite(String projectId) {
        return projectId != null && loadFavorites().contains(projectId);
    }

    public boolean toggleFavorite(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        Set<String> favorites = loadFavorites();
        if (!favorites.add(projectId)) {
            favorites.remove(projectId);
        }
        saveFavorites(favorites);
        return favorites.contains(projectId);
    }

    private Set<String> loadFavorites() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Path file = resolveFile();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ex) {
            LOG.warning(() -> "Cannot read workspace UI preferences: " + ex.getMessage());
            return out;
        }
        String raw = props.getProperty(FAVORITES_KEY, "");
        if (raw == null || raw.isBlank()) {
            return out;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .forEach(out::add);
        return out;
    }

    private void saveFavorites(Set<String> favorites) {
        Path file = resolveFile();
        if (file == null) {
            LOG.warning("Workspace UI preferences were not saved because Analysis Storage is not configured");
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Properties props = new Properties();
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            props.setProperty(FAVORITES_KEY, favorites.stream().collect(Collectors.joining(",")));
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "StellarAI Workspace UI preferences");
            }
        } catch (IOException ex) {
            LOG.warning(() -> "Cannot save workspace UI preferences: " + ex.getMessage());
        }
    }
}
