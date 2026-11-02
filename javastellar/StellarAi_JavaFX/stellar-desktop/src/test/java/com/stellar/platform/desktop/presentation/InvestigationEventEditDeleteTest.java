package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Manual Inspector events: edit and delete reuse engineering bookmarks and survive reopen. */
class InvestigationEventEditDeleteTest {

    @TempDir Path tempDir;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void editKeepsIdentityAndDeleteRemovesOnlyThatEventAfterReopen() {
        openService();
        service.newProject("Events Alpha");
        service.persistInvestigationEvent("Brake noise", "Issue", "pad", 595_092_000_000L, "VehicleSpeed");
        service.persistInvestigationEvent("Look", "Observation", "", 8_411_000_000L, "EngineRPM");
        service.persistEngineeringBookmark("VehicleSpeed", "Transition", "Large relative step", 1_000_000_000L);
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();

        var issue = service.listEngineeringBookmarks().stream()
                .filter(b -> "Brake noise".equals(InvestigationEventCodec.titleOf(b.observation())))
                .findFirst().orElseThrow();
        var look = service.listEngineeringBookmarks().stream()
                .filter(b -> "Look".equals(InvestigationEventCodec.titleOf(b.observation())))
                .findFirst().orElseThrow();
        var generated = service.listEngineeringBookmarks().stream()
                .filter(b -> "Transition".equals(b.eventType()))
                .findFirst().orElseThrow();

        assertFalse(service.updateInvestigationEvent(issue.bookmarkId(), "Hijack", "NotACategory", "no"));
        assertEquals("Brake noise", InvestigationEventCodec.titleOf(
                service.findEngineeringBookmark(issue.bookmarkId()).orElseThrow().observation()));
        assertFalse(service.updateInvestigationEvent(generated.bookmarkId(), "Edited", "Issue", ""));
        assertEquals("Large relative step", service.findEngineeringBookmark(generated.bookmarkId()).orElseThrow().observation());
        assertFalse(service.updateInvestigationEvent("missing-event", "Edited", "Issue", ""));
        assertFalse(service.deleteInvestigationEvent(generated.bookmarkId()));
        assertFalse(service.deleteInvestigationEvent("missing-event"));

        assertTrue(service.updateInvestigationEvent(issue.bookmarkId(), "Edited brake", "Braking", "updated note"));
        var edited = service.findEngineeringBookmark(issue.bookmarkId()).orElseThrow();
        assertEquals(issue.bookmarkId(), edited.bookmarkId());
        assertEquals(issue.projectId(), edited.projectId());
        assertEquals(issue.sourceId(), edited.sourceId());
        assertEquals("VehicleSpeed", edited.signalName());
        assertEquals(595_092_000_000L, edited.timestampNanos());
        assertEquals("Braking", edited.eventType());
        assertEquals("Edited brake", InvestigationEventCodec.titleOf(edited.observation()));
        assertEquals("updated note", InvestigationEventCodec.noteOf(edited.observation()));
        assertEquals(3, service.listEngineeringBookmarks().size());

        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals("Edited brake", InvestigationEventCodec.titleOf(
                service.findEngineeringBookmark(issue.bookmarkId()).orElseThrow().observation()));
        assertEquals(3, service.listEngineeringBookmarks().size());

        assertTrue(service.deleteInvestigationEvent(issue.bookmarkId()));
        assertTrue(service.findEngineeringBookmark(issue.bookmarkId()).isEmpty());
        assertEquals("Look", InvestigationEventCodec.titleOf(
                service.findEngineeringBookmark(look.bookmarkId()).orElseThrow().observation()));
        assertEquals("Transition", service.findEngineeringBookmark(generated.bookmarkId()).orElseThrow().eventType());
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        List<String> titles = service.listEngineeringBookmarks().stream()
                .map(b -> InvestigationEventCodec.titleOf(b.observation()))
                .toList();
        assertEquals(List.of("Large relative step", "Look"), titles);
        assertTrue(service.findEngineeringBookmark(issue.bookmarkId()).isEmpty());
    }

    @Test
    void editAndDeleteDoNotCrossProjects() {
        openService();
        service.newProject("Events Alpha");
        service.persistInvestigationEvent("Alpha event", "Issue", "", 10L, "Speed");
        assertTrue(service.saveProject().saved());
        String alphaId = service.currentProjectId();
        String alphaEvent = service.listEngineeringBookmarks().getFirst().bookmarkId();

        service.newProject("Events Beta");
        service.persistInvestigationEvent("Beta event", "Observation", "", 20L, "Rpm");
        assertTrue(service.saveProject().saved());
        String betaEvent = service.listEngineeringBookmarks().getFirst().bookmarkId();

        assertFalse(service.updateInvestigationEvent(alphaEvent, "Hijack", "Issue", "no"));
        assertFalse(service.deleteInvestigationEvent(alphaEvent));
        assertEquals("Beta event", InvestigationEventCodec.titleOf(
                service.listEngineeringBookmarks().getFirst().observation()));

        service.closeProject();
        assertTrue(service.openSavedProject(alphaId).opened());
        assertEquals("Alpha event", InvestigationEventCodec.titleOf(
                service.findEngineeringBookmark(alphaEvent).orElseThrow().observation()));
        assertTrue(service.findEngineeringBookmark(betaEvent).isEmpty());
        assertFalse(service.deleteInvestigationEvent(betaEvent));
        assertEquals(1, service.listEngineeringBookmarks().size());
    }

    private void openService() {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        Path db = tempDir.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }
}
