package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectSearchEmptyCopyTest {

    @Test
    void catalogEmptyKeepsNoSavedProjectsCopy() {
        assertEquals("No saved projects yet.", ProjectSearchEmptyCopy.title(false, false, false));
        assertEquals("No saved projects yet.", ProjectSearchEmptyCopy.title(false, true, false));
    }

    @Test
    void searchWithProjectsAndNoMatchUsesSearchCopy() {
        assertEquals("No projects match your search.", ProjectSearchEmptyCopy.title(true, true, false));
        assertEquals("Try a different project name.", ProjectSearchEmptyCopy.body(true, true, false));
    }
}
