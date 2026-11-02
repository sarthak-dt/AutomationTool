package com.stellar.platform.desktop.ui.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceUiPreferencesStoreTest {

    @TempDir Path temp;

    @Test
    void favouriteStateSurvivesReload() {
        Path file = temp.resolve("workspace-ui.properties");
        WorkspaceUiPreferencesStore store = new WorkspaceUiPreferencesStore(file);
        assertFalse(store.isFavorite("lavasa"));

        assertTrue(store.toggleFavorite("lavasa"));
        assertTrue(store.isFavorite("lavasa"));
        assertFalse(store.toggleFavorite("lavasa"));
        assertFalse(store.isFavorite("lavasa"));
        assertTrue(store.toggleFavorite("lavasa"));

        WorkspaceUiPreferencesStore reloaded = new WorkspaceUiPreferencesStore(file);
        assertTrue(reloaded.isFavorite("lavasa"));
        assertFalse(reloaded.isFavorite("sample222"));
    }
}
