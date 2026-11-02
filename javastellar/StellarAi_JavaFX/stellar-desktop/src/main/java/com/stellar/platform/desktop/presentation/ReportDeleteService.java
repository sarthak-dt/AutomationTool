package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.metadata.InvestigationReportRow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deletes one generated report file, then its metadata row.
 * A missing file still allows the metadata row to be removed.
 * A failed file delete leaves the metadata row in place.
 */
public final class ReportDeleteService {

    @FunctionalInterface
    public interface ReportRecordRemover {
        void remove(InvestigationReportRow row);
    }

    public void delete(InvestigationReportRow row, ReportRecordRemover remover) throws IOException {
        if (row == null) {
            return;
        }
        if (row.destination() != null && !row.destination().isBlank()) {
            Path dest = Path.of(row.destination());
            if (Files.exists(dest)) {
                if (Files.isDirectory(dest)) {
                    throw new IOException("The report could not be deleted.");
                }
                Files.delete(dest);
            }
        }
        remover.remove(row);
    }
}
