package com.stellar.platform.desktop.support;

import com.stellar.platform.analysis.investigation.PathSanitizer;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Local diagnostics support ZIP. Distinct from an Investigation Package.
 * Never includes raw MF4/DBC, project SQLite, waveform samples, or IPC tokens.
 */
public final class DiagnosticsSupportBundle {

    public static final int MAX_LOG_BYTES = 512 * 1024;

    private DiagnosticsSupportBundle() {
    }

    public record Snapshot(
            String applicationVersion,
            int schemaVersion,
            String javaVersion,
            String osName,
            String osArch,
            String analysisStorageStatus,
            String workerSummary,
            List<String> processingErrors,
            List<String> recentAudit,
            Path logFile
    ) {
        public Snapshot {
            applicationVersion = applicationVersion == null ? DesktopVersion.displayVersion() : applicationVersion;
            javaVersion = javaVersion == null ? System.getProperty("java.version", "") : javaVersion;
            osName = osName == null ? System.getProperty("os.name", "") : osName;
            osArch = osArch == null ? System.getProperty("os.arch", "") : osArch;
            analysisStorageStatus = analysisStorageStatus == null ? "" : analysisStorageStatus;
            workerSummary = workerSummary == null ? "" : workerSummary;
            processingErrors = processingErrors == null ? List.of() : List.copyOf(processingErrors);
            recentAudit = recentAudit == null ? List.of() : List.copyOf(recentAudit);
        }
    }

    public static Path write(Path destination, Snapshot snapshot, CancellationToken token) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination");
        }
        Path staging = destination.resolveSibling(destination.getFileName() + ".staging");
        Files.deleteIfExists(staging);
        Snapshot snap = snapshot == null
                ? new Snapshot(DesktopVersion.displayVersion(), SqliteProjectMetadataStore.currentSchemaVersion(),
                null, null, null, "", "", List.of(), List.of(), null)
                : snapshot;
        try (OutputStream raw = Files.newOutputStream(staging);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "manifest.json", manifest(snap), token);
            put(zip, "configuration.txt", configuration(snap), token);
            put(zip, "worker.txt", snap.workerSummary(), token);
            put(zip, "processing-errors.txt", join(snap.processingErrors()), token);
            put(zip, "audit-summary.txt", join(snap.recentAudit()), token);
            if (snap.logFile() != null && Files.isRegularFile(snap.logFile())) {
                putTruncated(zip, "logs/stellar-desktop.log", snap.logFile(), token);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException ex) {
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    private static String manifest(Snapshot snap) {
        return "{\"applicationVersion\":\"" + json(snap.applicationVersion())
                + "\",\"schemaVersion\":" + snap.schemaVersion()
                + ",\"javaVersion\":\"" + json(snap.javaVersion())
                + "\",\"os\":\"" + json(snap.osName() + " " + snap.osArch())
                + "\",\"containsRawMf4\":false,\"containsRawDbc\":false,\"containsSqlite\":false"
                + ",\"containsIpcToken\":false}";
    }

    private static String configuration(Snapshot snap) {
        return "applicationVersion=" + snap.applicationVersion()
                + "\nschemaVersion=" + snap.schemaVersion()
                + "\njavaVersion=" + snap.javaVersion()
                + "\nos=" + snap.osName() + " " + snap.osArch()
                + "\nanalysisStorage=" + PathSanitizer.sanitize(snap.analysisStorageStatus())
                + "\n";
    }

    private static String join(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> sanitized = new ArrayList<>();
        for (String line : lines) {
            sanitized.add(PathSanitizer.sanitize(line == null ? "" : line));
        }
        return String.join("\n", sanitized) + "\n";
    }

    private static void put(ZipOutputStream zip, String name, String text, CancellationToken token)
            throws IOException {
        throwIfCancelled(token);
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putTruncated(ZipOutputStream zip, String name, Path file, CancellationToken token)
            throws IOException {
        throwIfCancelled(token);
        byte[] all = Files.readAllBytes(file);
        int len = Math.min(all.length, MAX_LOG_BYTES);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(all, Math.max(0, all.length - len), len);
        zip.closeEntry();
    }

    private static void throwIfCancelled(CancellationToken token) {
        if (token != null && token.isCancellationRequested()) {
            throw new IllegalStateException("Support bundle cancelled");
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
