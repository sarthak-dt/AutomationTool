package com.stellar.platform.server.decoder;

import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Removes decoder-validation sessions left behind by a dead process.
 * It only considers direct children of the configured session directory.
 */
final class DecoderSessionRecovery {

    static final String OWNER_FILE = "decoder-validation-owner";
    private static final String SCHEMA = "1";
    private static final long START_TOLERANCE_MILLIS = 15_000L;

    private DecoderSessionRecovery() {
    }

    record Result(int removed, int keptLive, int skipped) {
    }

    static void writeOwner(Path sessionRoot) throws IOException {
        if (Files.isSymbolicLink(sessionRoot) || !Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Decoder session root is not a real directory.");
        }
        Path owner = sessionRoot.resolve(OWNER_FILE);
        Path parent = owner.toAbsolutePath().normalize().getParent();
        Path root = sessionRoot.toAbsolutePath().normalize();
        if (parent == null || !parent.equals(root)) {
            throw new IOException("Decoder session owner path escaped the session.");
        }
        String body = "schema=" + SCHEMA + "\n"
                + "pid=" + ProcessHandle.current().pid() + "\n"
                + "startedAt=" + ManagementFactory.getRuntimeMXBean().getStartTime() + "\n";
        Files.writeString(owner, body, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    static Result recover(Path sessionDirectory, Duration unownedMinAge, Instant now) {
        if (sessionDirectory == null || !Files.isDirectory(sessionDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(sessionDirectory)) {
            return new Result(0, 0, 0);
        }
        Path sessionReal;
        try {
            sessionReal = sessionDirectory.toRealPath();
        } catch (IOException ex) {
            return new Result(0, 0, 0);
        }
        int removed = 0;
        int keptLive = 0;
        int skipped = 0;
        try (Stream<Path> children = Files.list(sessionDirectory)) {
            for (Path child : children.toList()) {
                Decision decision = classify(sessionReal, child, unownedMinAge, now);
                if (decision == Decision.REMOVE && DecoderSnapshots.deleteTree(child)) {
                    removed++;
                } else if (decision == Decision.KEEP_LIVE) {
                    keptLive++;
                } else {
                    skipped++;
                }
            }
        } catch (IOException ex) {
            skipped++;
        }
        return new Result(removed, keptLive, skipped);
    }

    private enum Decision {
        REMOVE,
        KEEP_LIVE,
        SKIP
    }

    private static Decision classify(Path sessionReal, Path child, Duration unownedMinAge, Instant now) {
        if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            return Decision.SKIP;
        }
        Path childReal;
        try {
            childReal = child.toRealPath();
        } catch (IOException ex) {
            return Decision.SKIP;
        }
        if (!childReal.startsWith(sessionReal) || childReal.equals(sessionReal)) {
            return Decision.SKIP;
        }
        if (containsSymlink(child)) {
            return Decision.SKIP;
        }
        Optional<Owner> owner = readOwner(child.resolve(OWNER_FILE));
        if (owner.isPresent()) {
            return ownerAlive(owner.get()) ? Decision.KEEP_LIVE : Decision.REMOVE;
        }
        if (!isSessionName(child.getFileName().toString()) || containsDatabase(child)) {
            return Decision.SKIP;
        }
        if (!olderThan(child, unownedMinAge, now)) {
            return Decision.SKIP;
        }
        if (isEmpty(child) || hasCurrentGeneration(child)) {
            return Decision.REMOVE;
        }
        return Decision.SKIP;
    }

    private static boolean ownerAlive(Owner owner) {
        Optional<ProcessHandle> handle = ProcessHandle.of(owner.pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        if (owner.pid == ProcessHandle.current().pid()) {
            return owner.startedAt == ManagementFactory.getRuntimeMXBean().getStartTime();
        }
        Optional<Instant> started = handle.get().info().startInstant();
        if (started.isEmpty()) {
            return true;
        }
        return Math.abs(started.get().toEpochMilli() - owner.startedAt) <= START_TOLERANCE_MILLIS;
    }

    private static Optional<Owner> readOwner(Path ownerFile) {
        if (Files.isSymbolicLink(ownerFile) || !Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            String schema = null;
            Long pid = null;
            Long startedAt = null;
            for (String line : Files.readAllLines(ownerFile, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    return Optional.empty();
                }
                String key = line.substring(0, split);
                String value = line.substring(split + 1);
                switch (key) {
                    case "schema" -> schema = value;
                    case "pid" -> pid = Long.valueOf(value);
                    case "startedAt" -> startedAt = Long.valueOf(value);
                    default -> {
                        return Optional.empty();
                    }
                }
            }
            if (!SCHEMA.equals(schema) || pid == null || pid <= 0 || startedAt == null || startedAt <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Owner(pid, startedAt));
        } catch (RuntimeException | IOException ex) {
            return Optional.empty();
        }
    }

    private static boolean isSessionName(String name) {
        return name != null && name.matches("[0-9a-f]{32}");
    }

    private static boolean containsDatabase(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.anyMatch(path -> "stellar.db".equals(path.getFileName().toString()));
        } catch (IOException ex) {
            return true;
        }
    }

    private static boolean containsSymlink(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.anyMatch(Files::isSymbolicLink);
        } catch (IOException ex) {
            return true;
        }
    }

    private static boolean isEmpty(Path directory) {
        try (Stream<Path> list = Files.list(directory)) {
            return list.findAny().isEmpty();
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean hasCurrentGeneration(Path directory) {
        Path marker = directory.resolve(GraphSignalSessionStore.DECODER_GENERATION_FILE);
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return GraphSignalSessionStore.DECODER_GENERATION.equals(Files.readString(marker).trim());
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean olderThan(Path directory, Duration minimum, Instant now) {
        try {
            Instant modified = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant();
            return !modified.isAfter(now.minus(minimum));
        } catch (IOException ex) {
            return false;
        }
    }

    private record Owner(long pid, long startedAt) {
    }
}
