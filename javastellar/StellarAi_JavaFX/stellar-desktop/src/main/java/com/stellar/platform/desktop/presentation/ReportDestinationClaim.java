package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.report.ReportNameAllocator;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Reserves the next HTML report filename in one folder.
 * The empty file is created before report content is written so two generations
 * cannot choose the same name.
 */
public final class ReportDestinationClaim {

    private static final Object LOCK = new Object();

    private ReportDestinationClaim() {
    }

    public static Path claim(Path directory, String displayName, Collection<String> reservedNames) throws IOException {
        if (directory == null) {
            throw new IOException("Choose a destination for the report.");
        }
        Files.createDirectories(directory);
        synchronized (LOCK) {
            for (int attempt = 0; attempt < 10_000; attempt++) {
                LinkedHashSet<String> names = new LinkedHashSet<>();
                if (reservedNames != null) {
                    names.addAll(reservedNames);
                }
                try (var listed = Files.list(directory)) {
                    listed.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .forEach(names::add);
                }
                Path dest = directory.resolve(ReportNameAllocator.allocate(displayName, names));
                try {
                    Files.createFile(dest);
                    return dest;
                } catch (FileAlreadyExistsException occupied) {
                    // Another writer reserved this name. Choose the next suffix.
                }
            }
        }
        throw new IOException("A report with this name already exists.");
    }

    public static Path claimPdf(Path directory, String displayName, Collection<String> reservedNames) throws IOException {
        if (directory == null) {
            throw new IOException("Choose a destination for the report.");
        }
        Files.createDirectories(directory);
        synchronized (LOCK) {
            for (int attempt = 0; attempt < 10_000; attempt++) {
                LinkedHashSet<String> names = new LinkedHashSet<>();
                if (reservedNames != null) {
                    names.addAll(reservedNames);
                }
                try (var listed = Files.list(directory)) {
                    listed.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .forEach(names::add);
                }
                Path dest = directory.resolve(ReportNameAllocator.allocatePdf(displayName, names));
                try {
                    Files.createFile(dest);
                    return dest;
                } catch (FileAlreadyExistsException occupied) {
                    // Another writer reserved this name. Choose the next suffix.
                }
            }
        }
        throw new IOException("A report with this name already exists.");
    }
}
