package com.stellar.platform.server.decoder;

import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoderSessionRecoveryTest {

    @Test
    void deadOwnerIsRemovedAndLiveOwnerIsKept() throws Exception {
        Path root = Files.createTempDirectory("decoder-recovery-");
        Path dead = root.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Path live = root.resolve("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        Files.createDirectories(dead);
        Files.createDirectories(live);
        Files.writeString(dead.resolve(DecoderSessionRecovery.OWNER_FILE), """
                schema=1
                pid=2147483647
                startedAt=1
                """);
        Files.writeString(live.resolve(DecoderSessionRecovery.OWNER_FILE),
                "schema=1\npid=" + ProcessHandle.current().pid()
                        + "\nstartedAt=" + ManagementFactory.getRuntimeMXBean().getStartTime() + "\n");
        Files.writeString(root.resolve("notes.txt"), "keep");
        Files.createDirectories(root.resolve("customer-project"));

        DecoderSessionRecovery.Result result = DecoderSessionRecovery.recover(
                root, Duration.ofHours(24), Instant.now());

        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(live));
        assertTrue(Files.exists(root.resolve("notes.txt")));
        assertTrue(Files.exists(root.resolve("customer-project")));
        assertTrue(result.removed() >= 1);
        assertTrue(result.keptLive() >= 1);
    }

    @Test
    void recentUnownedSessionIsKeptAndOldUnownedStoreIsRemoved() throws Exception {
        Path root = Files.createTempDirectory("decoder-recovery-age-");
        Path recent = root.resolve("cccccccccccccccccccccccccccccccc");
        Path old = root.resolve("dddddddddddddddddddddddddddddddd");
        Path project = root.resolve("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        Files.createDirectories(recent);
        Files.createDirectories(old);
        Files.createDirectories(project);
        Files.writeString(recent.resolve(GraphSignalSessionStore.DECODER_GENERATION_FILE), "3");
        Files.writeString(old.resolve(GraphSignalSessionStore.DECODER_GENERATION_FILE), "3");
        Files.writeString(project.resolve(GraphSignalSessionStore.DECODER_GENERATION_FILE), "3");
        Files.writeString(project.resolve("stellar.db"), "not a database");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(project, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        DecoderSessionRecovery.recover(root, Duration.ofHours(24), Instant.now());

        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(project));
    }

    @Test
    void linkOutsideTheSessionDirectoryIsNotFollowed() throws Exception {
        Path root = Files.createTempDirectory("decoder-recovery-link-");
        Path target = Files.createTempDirectory("decoder-recovery-target-");
        Files.writeString(target.resolve("keep.txt"), "keep");
        Path link = root.resolve("ffffffffffffffffffffffffffffffff");
        boolean created = false;
        try {
            Files.createSymbolicLink(link, target);
            created = true;
        } catch (Exception ignored) {
            Process process = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            created = process.waitFor() == 0 && Files.exists(link);
        }
        Assumptions.assumeTrue(created, "neither a symlink nor a directory junction could be created");
        Files.setLastModifiedTime(link, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        DecoderSessionRecovery.recover(root, Duration.ZERO, Instant.now());

        assertTrue(Files.exists(target.resolve("keep.txt")));
        assertTrue(Files.exists(link));
    }

    @Test
    void reusedPidWithADifferentStartTimeIsRemoved() throws Exception {
        Path root = Files.createTempDirectory("decoder-recovery-reuse-");
        Path stale = root.resolve("00112233445566778899aabbccddeeff");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve(DecoderSessionRecovery.OWNER_FILE),
                "schema=1\npid=" + ProcessHandle.current().pid() + "\nstartedAt=1\n");

        DecoderSessionRecovery.recover(root, Duration.ofHours(24), Instant.now());

        assertFalse(Files.exists(stale));
    }
}
