package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Append-only audit trail with durable ordering. */
class AuditLogPersistenceTest {

    private static final String PROJECT = "proj-audit";

    @TempDir
    Path temp;

    private Path db;

    @BeforeEach
    void createProject() {
        db = temp.resolve("audit.db");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProject(new ProjectMetadata(
                    PROJECT, "Audited", Instant.now(), Instant.now(), "SOURCES", null));
        }
    }

    private SqliteProjectMetadataStore open() {
        return new SqliteProjectMetadataStore(db);
    }

    @Test
    void eventsKeepWriteOrderEvenWithinTheSameInstant() {
        Instant sameMoment = Instant.parse("2026-09-08T12:00:00Z");
        try (SqliteProjectMetadataStore store = open()) {
            for (AuditEventType type : List.of(
                    AuditEventType.PROJECT_CREATED,
                    AuditEventType.SOURCE_ADDED,
                    AuditEventType.PROCESSING_STARTED,
                    AuditEventType.PROCESSING_COMPLETED,
                    AuditEventType.PROJECT_SAVED)) {
                store.appendAuditEvent(new AuditEventMetadata(
                        0L, PROJECT, type, "entity", type.name(), "", sameMoment));
            }
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            // listRecent returns newest first; reverse to compare against write order.
            List<AuditEventType> chronological = new ArrayList<>(
                    reopened.listRecentAuditEvents(PROJECT, 50).stream()
                            .map(AuditEventMetadata::eventType)
                            .toList());
            Collections.reverse(chronological);
            assertEquals(List.of(
                    AuditEventType.PROJECT_CREATED,
                    AuditEventType.SOURCE_ADDED,
                    AuditEventType.PROCESSING_STARTED,
                    AuditEventType.PROCESSING_COMPLETED,
                    AuditEventType.PROJECT_SAVED), chronological);
        }
    }

    @Test
    void appendReturnsMonotonicallyIncreasingIds() {
        try (SqliteProjectMetadataStore store = open()) {
            long first = store.appendAuditEvent(
                    AuditEventMetadata.of(PROJECT, AuditEventType.PROJECT_OPENED, "", "opened"));
            long second = store.appendAuditEvent(
                    AuditEventMetadata.of(PROJECT, AuditEventType.PROJECT_SAVED, "", "saved"));
            assertTrue(second > first, "audit ids must increase: " + first + " then " + second);
        }
    }

    @Test
    void eventsSurviveReopenWithDetail() {
        try (SqliteProjectMetadataStore store = open()) {
            store.appendAuditEvent(AuditEventMetadata.of(
                    PROJECT, AuditEventType.WORKER_CRASHED, "worker-1",
                    "Worker stopped unexpectedly", "exit=139"));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            AuditEventMetadata event = reopened.listRecentAuditEvents(PROJECT, 10).getFirst();
            assertSame(AuditEventType.WORKER_CRASHED, event.eventType());
            assertEquals("worker-1", event.entityId());
            assertEquals("Worker stopped unexpectedly", event.summary());
            assertEquals("exit=139", event.detail());
            assertTrue(event.auditId() > 0L);
        }
    }

    @Test
    void limitBoundsTheReturnedHistory() {
        try (SqliteProjectMetadataStore store = open()) {
            for (int i = 0; i < 25; i++) {
                store.appendAuditEvent(AuditEventMetadata.of(
                        PROJECT, AuditEventType.PROCESSING_CHECKPOINTED, "job-" + i, "checkpoint " + i));
            }
            assertEquals(10, store.listRecentAuditEvents(PROJECT, 10).size());
            assertEquals(0, store.listRecentAuditEvents(PROJECT, 0).size());
        }
    }

    @Test
    void unknownEventTypeFromANewerBuildReadsAsUnknown() {
        assertSame(AuditEventType.UNKNOWN, AuditEventType.parse("SOMETHING_NEW"));
        assertSame(AuditEventType.UNKNOWN, AuditEventType.parse(null));
    }
}
