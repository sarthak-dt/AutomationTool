package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;

import java.util.List;

/** Engineering Analysis bookmarks — persists the existing BookmarkRow workflow. */
public interface EngineeringBookmarkRepository {
    default void upsertBookmark(EngineeringBookmarkMetadata bookmark) {
    }

    default void deleteBookmark(String projectId, String bookmarkId) {
    }

    default List<EngineeringBookmarkMetadata> listBookmarks(String projectId) {
        return List.of();
    }

    default void deleteBookmarksForProject(String projectId) {
    }
}
