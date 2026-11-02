package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Copies investigation screenshots into a report folder, skipping an image already generated automatically. */
public final class ReportEvidenceAssets {

    private ReportEvidenceAssets() {
    }

    public static List<EngineeringReportModel.Figure> copy(
            EngineeringInvestigation investigation,
            Path artifacts,
            Path assetsDir,
            Set<String> skipHashes,
            CancellationToken token
    ) throws IOException {
        List<EngineeringReportModel.Figure> figures = new ArrayList<>();
        if (investigation == null) {
            return figures;
        }
        List<EngineeringEvidenceItem> items = new ArrayList<>(investigation.evidence());
        items.sort(Comparator.comparingInt(EngineeringEvidenceItem::sortOrder)
                .thenComparing(item -> item.createdAt() == null ? "" : item.createdAt().toString()));
        Files.createDirectories(assetsDir);
        int number = 1;
        int file = 1;
        for (EngineeringEvidenceItem item : items) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            if (!imageKind(item.kind())) {
                continue;
            }
            Path png = InvestigationStoragePaths.snapshot(
                    artifacts, investigation.investigationId(), item.referenceId());
            String caption = caption(item);
            if (!Files.isRegularFile(png)) {
                figures.add(new EngineeringReportModel.Figure(number++,
                        caption + " Snapshot file is missing. It was not regenerated.", "", ""));
                continue;
            }
            String hash = sha256(png);
            if (hash != null && skipHashes != null && skipHashes.contains(hash)) {
                continue;
            }
            String name = String.format(Locale.ROOT, "evidence-%03d.png", file++);
            Files.copy(png, assetsDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            figures.add(new EngineeringReportModel.Figure(number++, caption, "assets/" + name, ""));
        }
        return List.copyOf(figures);
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            return null;
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            return null;
        }
    }

    static boolean imageKind(EvidenceKind kind) {
        return kind == EvidenceKind.GRAPH_SNAPSHOT || kind == EvidenceKind.ROUTE_MAP;
    }

    static String caption(EngineeringEvidenceItem item) {
        String text = item.caption() == null || item.caption().isBlank() ? item.summary() : item.caption();
        if (text == null || text.isBlank() || text.equalsIgnoreCase(item.evidenceId())
                || text.equalsIgnoreCase(item.referenceId())) {
            return switch (item.kind()) {
                case ROUTE_MAP -> "Route Map";
                case DIAGNOSTIC_FINDING -> "Diagnostics — Captured Evidence";
                default -> "Captured Evidence";
            };
        }
        return text;
    }
}
