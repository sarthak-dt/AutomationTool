package com.stellar.platform.testkit.boundary;

import com.stellar.platform.dbc.decode.DecodeStatus;
import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.mf4.block.MdfBlockCandidateValidator;
import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockHeaderReader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.decode.MdfDtBoundaryRequest;
import com.stellar.platform.parser.mf4.decode.MdfDtBoundaryResolution;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.decode.MdfUnfinalizedDtBoundaryResolver;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceNode;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryDiagnosticCode;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryResult;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One-off OLD vs NEW unfinalized-DT boundary comparison for an external MF4.
 * Paths via system properties (never hard-coded in production code):
 * -Dstellar.mf4.boundary.mf4=...
 * -Dstellar.mf4.boundary.dbc=...
 */
public final class UnfinalizedDtBoundaryRealFileProbe {

    public static void main(String[] args) throws Exception {
        Path mf4 = Path.of(System.getProperty("stellar.mf4.boundary.mf4", ""));
        Path dbc = Path.of(System.getProperty("stellar.mf4.boundary.dbc", ""));
        if (!Files.isRegularFile(mf4)) {
            System.err.println("Missing MF4: set -Dstellar.mf4.boundary.mf4=");
            System.exit(2);
        }

        long oldStart;
        long oldEnd;
        long newStart;
        long newEnd;
        long falseMarkers;
        long acceptedBoundaries;
        long rawHashCandidates;
        String prematureRejectReason = "";

        try (MdfFileAccess access = MdfFileAccess.open(mf4)) {
            MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();
            var prepared = decoder.decodeAll(mf4, ReaderContext.ofBatchSize(256));
            // locate DT via schemas
            MdfRecordSchema schema = prepared.schemaResult().recordSchemas().stream()
                    .filter(s -> s.dataSource() != null)
                    .findFirst()
                    .orElseThrow();
            MdfDataSourceNode dtNode = schema.dataSource().orderedNodes().stream()
                    .filter(n -> n.blockType() == MdfBlockType.DT)
                    .findFirst()
                    .orElseThrow();
            MdfBlockHeader dtHeader = MdfBlockHeaderReader.read(access, dtNode.address());
            long payloadStart = dtHeader.blockEnd();
            boolean unusable = dtHeader.declaredDataLength() <= 0L;

            oldStart = payloadStart;
            oldEnd = simulateOldFindNext(access, payloadStart);

            boolean allowEof = MdfIdentificationReader.read(access).hasUnfinalizedFlags() || unusable;
            MdfDtBoundaryResolution resolution = MdfUnfinalizedDtBoundaryResolver.resolveUnfinalizedDtEnd(
                    access,
                    payloadStart,
                    unusable,
                    MdfDtBoundaryRequest.forRecovery(allowEof, null)
            );
            newStart = payloadStart;
            newEnd = resolution.endExclusive();
            falseMarkers = resolution.falseMarkerCandidates();
            acceptedBoundaries = resolution.acceptedBoundaryOffset() >= 0L ? 1L : 0L;

            // Count raw ## candidates between payloadStart and min(oldEnd+8, fileSize) for report
            rawHashCandidates = countRawHash(access, payloadStart, Math.min(access.fileSize(), Math.max(oldEnd, newEnd)));

            // Explain why the OLD premature boundary fails structural validation (if it claimed ##)
            if (oldEnd < access.fileSize() && oldEnd + 2 <= access.fileSize()) {
                byte[] magic = access.readFully(oldEnd, Math.min(4, (int) (access.fileSize() - oldEnd)), "oldBound");
                if (magic.length >= 2 && magic[0] == '#' && magic[1] == '#') {
                    prematureRejectReason = explainReject(access, oldEnd);
                } else {
                    prematureRejectReason = "OLD end was probe-cap (not a ## marker)";
                }
            }

            System.out.println("=== UNFINALIZED DT BOUNDARY REAL-FILE COMPARISON ===");
            System.out.println("fileSize=" + access.fileSize());
            System.out.println("dtAddress=" + dtNode.address());
            System.out.println("declaredDataLength=" + dtHeader.declaredDataLength());
            System.out.println();
            System.out.println("OLD recovery start/end: " + oldStart + " / " + oldEnd);
            System.out.println("NEW recovery start/end: " + newStart + " / " + newEnd);
            System.out.println("OLD bytes region: " + Math.max(0, oldEnd - oldStart));
            System.out.println("NEW bytes region: " + Math.max(0, newEnd - newStart));
            System.out.println("raw ## candidates in scanned region: " + rawHashCandidates);
            System.out.println("false markers rejected: " + falseMarkers);
            System.out.println("accepted valid MDF boundaries: " + acceptedBoundaries);
            System.out.println("premature OLD candidate rejection: " + prematureRejectReason);
            System.out.println("usedEofFallback=" + resolution.usedEofFallback());

            // NEW decode metrics
            List<CanonicalCanFrame> frames = prepared.frames();
            long bytesInspected = prepared.recoveryResults().stream().mapToLong(MdfRecoveryResult::bytesInspected).sum();
            long recordsInspected = prepared.recoveryResults().stream().mapToLong(MdfRecoveryResult::recordsInspected).sum();
            Set<Long> ids = new HashSet<>();
            for (CanonicalCanFrame f : frames) {
                ids.add(f.canId());
            }
            long firstTs = frames.stream().mapToLong(CanonicalCanFrame::timestampNanos).min().orElse(-1);
            long lastTs = frames.stream().mapToLong(CanonicalCanFrame::timestampNanos).max().orElse(-1);

            System.out.println();
            System.out.println("NEW frames=" + frames.size()
                    + " uniqueCanIds=" + ids.size()
                    + " bytesInspected=" + bytesInspected
                    + " recordsInspected=" + recordsInspected);
            System.out.println("NEW firstTsNanos=" + firstTs + " lastTsNanos=" + lastTs);
            System.out.println("NEW lastTsSeconds=" + (lastTs < 0 ? -1 : lastTs / 1_000_000_000.0));

            long falseDiag = prepared.recoveryResults().stream()
                    .flatMap(r -> r.diagnostics().stream())
                    .filter(d -> d.code() == MdfRecoveryDiagnosticCode.FALSE_MDF_BLOCK_MARKER_IGNORED)
                    .count();
            System.out.println("FALSE_MDF_BLOCK_MARKER_IGNORED diagnostics=" + falseDiag);

            // Representative frames after old endpoint
            List<CanonicalCanFrame> after = frames.stream()
                    .filter(f -> f.source().sourceByteOffset() >= oldEnd)
                    .sorted(Comparator.comparingLong(f -> f.source().sourceByteOffset()))
                    .limit(8)
                    .toList();
            System.out.println();
            System.out.println("--- CAN frames after OLD endpoint (sample) ---");
            for (CanonicalCanFrame f : after) {
                System.out.printf(Locale.ROOT,
                        "ts=%d id=0x%X dlc=%d payload=%s offset=%d%n",
                        f.timestampNanos(), f.canId(), f.dlc(),
                        toHex(f.data()), f.source().sourceByteOffset());
            }
            boolean tsBeyond = frames.stream().anyMatch(f -> f.timestampNanos() > 7_030_000_000L);
            System.out.println("timestampsBeyond~7.03s=" + tsBeyond);

            // DBC
            if (Files.isRegularFile(dbc)) {
                DbcDatabase database = new DbcParser().parse(dbc);
                DefaultDbcDecoder dec = new DefaultDbcDecoder();
                long matched = 0;
                long signals = 0;
                List<CanonicalSignal> beyond = new ArrayList<>();
                for (CanonicalCanFrame f : frames) {
                    DecodingResult r = dec.decode(f, database);
                    if (r.status() != DecodeStatus.UNMATCHED && r.status() != DecodeStatus.MALFORMED
                            && r.matchedMessage() != null) {
                        matched++;
                        signals += r.signals().size();
                        if (f.timestampNanos() > 7_030_000_000L) {
                            beyond.addAll(r.signals());
                        }
                    }
                }
                System.out.println();
                System.out.println("NEW DBC matchedFrames=" + matched + " signalsEmitted=" + signals);
                System.out.println("--- DBC signals after ~7.03s (sample) ---");
                beyond.stream().limit(12).forEach(s ->
                        System.out.printf(Locale.ROOT, "ts=%d name=%s value=%s%n",
                                s.timestampNanos(), s.signalName(), s.physicalValue()));
            } else {
                System.out.println("DBC path missing; skipped DBC section");
            }

            // Approximate OLD frame count: frames with offset < oldEnd
            long oldApproxFrames = frames.stream().filter(f -> f.source().sourceByteOffset() < oldEnd).count();
            long oldApproxLastTs = frames.stream()
                    .filter(f -> f.source().sourceByteOffset() < oldEnd)
                    .mapToLong(CanonicalCanFrame::timestampNanos)
                    .max().orElse(-1);
            System.out.println();
            System.out.println("OLD approx (subset of NEW with offset < oldEnd): frames=" + oldApproxFrames
                    + " lastTsNanos=" + oldApproxLastTs
                    + " lastTsSeconds=" + (oldApproxLastTs < 0 ? -1 : oldApproxLastTs / 1_000_000_000.0));
            if (Files.isRegularFile(dbc)) {
                DbcDatabase database = new DbcParser().parse(dbc);
                DefaultDbcDecoder dec = new DefaultDbcDecoder();
                long oldMatched = 0;
                for (CanonicalCanFrame f : frames) {
                    if (f.source().sourceByteOffset() >= oldEnd) {
                        continue;
                    }
                    DecodingResult r = dec.decode(f, database);
                    if (r.matchedMessage() != null && r.status() != DecodeStatus.UNMATCHED
                            && r.status() != DecodeStatus.MALFORMED) {
                        oldMatched++;
                    }
                }
                System.out.println("OLD approx DBC matchedFrames=" + oldMatched);
            }
        }
    }

    private static long simulateOldFindNext(MdfFileAccess access, long from) throws Exception {
        long fileSize = access.fileSize();
        long at = Math.max(0L, from);
        long limit = Math.min(fileSize, at + 1_048_576L);
        for (long p = at; p + 4L <= limit; p++) {
            byte[] magic = access.readFully(p, 4, "old-magic");
            if (magic[0] == '#' && magic[1] == '#') {
                return p;
            }
        }
        return limit;
    }

    private static long countRawHash(MdfFileAccess access, long from, long to) throws Exception {
        long n = 0;
        long end = Math.min(access.fileSize(), to);
        for (long p = from; p + 1 < end; p++) {
            byte[] b = access.readFully(p, 2, "c");
            if (b[0] == '#' && b[1] == '#') {
                n++;
            }
        }
        return n;
    }

    private static String explainReject(MdfFileAccess access, long offset) {
        StringBuilder sb = new StringBuilder();
        sb.append("offset=").append(offset);
        sb.append(" aligned8=").append((offset % 8) == 0);
        if ((offset % 8) != 0) {
            sb.append(" (fails ASAM 8-byte alignment)");
        }
        var opt = MdfBlockCandidateValidator.validateBlockCandidate(access, offset);
        if (opt.isPresent()) {
            sb.append(" unexpectedly VALID as ").append(opt.get().rawId());
            return sb.toString();
        }
        try {
            byte[] id = access.readFully(offset, 4, "id");
            sb.append(" idBytes=");
            for (byte b : id) {
                sb.append(String.format(Locale.ROOT, "%02X", b));
            }
            MdfBlockHeaderReader.read(access, offset);
            sb.append(" headerReaderSucceededButValidatorRejected");
        } catch (Exception e) {
            sb.append(" headerValidationFailed: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }
}
