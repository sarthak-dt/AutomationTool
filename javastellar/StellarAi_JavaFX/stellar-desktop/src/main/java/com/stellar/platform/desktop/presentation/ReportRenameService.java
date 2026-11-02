package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.report.ReportNameAllocator;
import com.stellar.platform.storage.metadata.InvestigationReportRow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

/**
 * Renames one generated HTML report. Metadata is updated only after the file move.
 * A metadata failure moves the file back.
 */
public final class ReportRenameService {

    @FunctionalInterface
    public interface ReportRecordWriter {
        void write(InvestigationReportRow row);
    }

    public InvestigationReportRow rename(
            InvestigationReportRow row,
            String requested,
            Collection<String> namesInDirectory,
            ReportRecordWriter writer
    ) throws IOException {
        if (row == null || row.destination() == null || row.destination().isBlank()) {
            throw new IOException("File missing");
        }
        Path from = Path.of(row.destination());
        if (!Files.isRegularFile(from)) {
            throw new IOException("File missing");
        }
        String currentFile = from.getFileName().toString();
        if (ReportNameAllocator.nameTaken(requested, namesInDirectory, currentFile)) {
            throw new IllegalArgumentException(ReportNameAllocator.NAME_EXISTS);
        }
        String display = ReportNameAllocator.requireDisplayName(requested);
        Path to = from.resolveSibling(display + ".html");
        boolean moved = false;
        if (!sameFile(from, to)) {
            move(from, to);
            moved = true;
        }
        InvestigationReportRow updated = new InvestigationReportRow(
                row.reportId(),
                row.investigationId(),
                row.projectId(),
                display,
                row.format(),
                row.status(),
                to.toString(),
                row.message(),
                row.createdAt(),
                row.finishedAt());
        try {
            writer.write(updated);
        } catch (RuntimeException ex) {
            if (moved) {
                try {
                    move(to, from);
                } catch (IOException restore) {
                    ex.addSuppressed(restore);
                }
            }
            throw ex;
        }
        return updated;
    }

    private static boolean sameFile(Path left, Path right) {
        return left.toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(right.toAbsolutePath().normalize().toString());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomic) {
            try {
                Files.move(from, to);
            } catch (IOException fallback) {
                fallback.addSuppressed(atomic);
                throw fallback;
            }
        }
    }
}
