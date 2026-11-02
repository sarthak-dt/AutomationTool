package com.stellar.platform.analysis.investigation;

import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.parser.spi.CancellationToken;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Investigation package ZIP. Excludes raw MF4/DBC and project SQLite by construction.
 */
public final class InvestigationPackageExporter {

    private InvestigationPackageExporter() {
    }

    public static Path write(
            Path destination,
            EngineeringInvestigation investigation,
            EngineeringReportModel model,
            List<Path> snapshotFiles,
            List<NamedText> extraExports,
            CancellationToken token
    ) throws IOException {
        return write(destination, investigation, model, snapshotFiles, extraExports, List.of(), token);
    }

    public static Path write(
            Path destination,
            EngineeringInvestigation investigation,
            EngineeringReportModel model,
            List<Path> snapshotFiles,
            List<NamedText> extraExports,
            List<Path> reportAssets,
            CancellationToken token
    ) throws IOException {
        Path staging = InvestigationStoragePaths.packageStaging(destination);
        Files.deleteIfExists(staging);
        try (OutputStream raw = Files.newOutputStream(staging);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "manifest.json", manifest(investigation), token);
            put(zip, "metadata/investigation.json", investigationJson(investigation), token);
            put(zip, "report/report.html", EngineeringHtmlReportRenderer.render(model), token);
            if (snapshotFiles != null) {
                int i = 1;
                for (Path png : snapshotFiles) {
                    throwIfCancelled(token);
                    if (png != null && Files.isRegularFile(png)) {
                        putFile(zip, "evidence/graph-" + String.format("%03d", i) + ".png", png);
                        i++;
                    }
                }
            }
            if (extraExports != null) {
                for (NamedText extra : extraExports) {
                    put(zip, "exports/" + extra.name(), extra.text(), token);
                }
            }
            if (reportAssets != null) {
                for (Path asset : reportAssets) {
                    throwIfCancelled(token);
                    if (asset != null && Files.isRegularFile(asset) && asset.getFileName() != null) {
                        putFile(zip, "report/assets/" + asset.getFileName(), asset);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
        Files.createDirectories(destination.getParent() == null ? Path.of(".") : destination.getParent());
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException ex) {
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    public record NamedText(String name, String text) {
    }

    private static String manifest(EngineeringInvestigation inv) {
        return "{\"investigationId\":\"" + json(inv.investigationId())
                + "\",\"name\":\"" + json(inv.name())
                + "\",\"status\":\"" + inv.status()
                + "\",\"health\":\"" + inv.health()
                + "\",\"containsRawMf4\":false,\"containsRawDbc\":false,\"containsSqlite\":false}";
    }

    private static String investigationJson(EngineeringInvestigation inv) {
        return "{\"investigationId\":\"" + json(inv.investigationId())
                + "\",\"name\":\"" + json(inv.name())
                + "\",\"description\":\"" + json(inv.description())
                + "\",\"conclusion\":\"" + json(inv.conclusion())
                + "\",\"status\":\"" + inv.status()
                + "\",\"evidenceCount\":" + inv.evidence().size() + "}";
    }

    private static void put(ZipOutputStream zip, String name, String text, CancellationToken token) throws IOException {
        throwIfCancelled(token);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static void throwIfCancelled(CancellationToken token) {
        if (token != null && token.isCancellationRequested()) {
            throw new com.stellar.platform.analysis.source.AnalysisCancelledException();
        }
    }

    private static String json(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
