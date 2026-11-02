package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.analysis.MdfConsistencyIssue;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationAssessment;
import com.stellar.platform.parser.mf4.analysis.MdfRecoveryCandidate;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.block.MdfLinkIssue;
import com.stellar.platform.parser.mf4.block.MdfLinkIssueKind;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.graph.MdfBlockNode;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MF4 risk-elimination inspection facade:
 * identify → discover blocks → extract schema metadata → analyse → report.
 */
public final class Mf4Inspector {

    public MdfInspectionReport inspect(Path path) throws ParserException {
        return inspect(path, CancellationToken.NONE);
    }

    public MdfInspectionReport inspect(Path path, CancellationToken cancellation) throws ParserException {
        Objects.requireNonNull(path, "path");
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;

        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            MdfIdentification identification = MdfIdentificationReader.read(access);
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, token);
            MdfMetadataSummary metadata = MdfSchemaExtractor.extract(access, graph, token);
            MdfConsistencyAnalyser.AnalysisResult analysis =
                    MdfConsistencyAnalyser.analyse(identification, graph, metadata);

            return buildReport(path, identification, graph, metadata, analysis);
        }
    }

    private static MdfInspectionReport buildReport(
            Path path,
            MdfIdentification identification,
            MdfBlockGraph graph,
            MdfMetadataSummary metadata,
            MdfConsistencyAnalyser.AnalysisResult analysis
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MdfBlockType type : MdfBlockType.values()) {
            if (type == MdfBlockType.UNKNOWN) {
                continue;
            }
            long count = graph.countByType(type);
            if (count > 0) {
                counts.put(type.name(), count);
            }
        }
        long unknownCount = graph.countByType(MdfBlockType.UNKNOWN);
        if (unknownCount > 0) {
            counts.put(MdfBlockType.UNKNOWN.name(), unknownCount);
        }

        List<String> unknownBlocks = graph.unknownNodes().stream()
                .sorted(Comparator.comparingLong(MdfBlockNode::address))
                .map(n -> n.header().rawId() + "@" + n.address())
                .toList();

        List<MdfLinkIssue> broken = graph.linkIssues().stream()
                .filter(i -> i.kind() == MdfLinkIssueKind.OUTSIDE_FILE
                        || i.kind() == MdfLinkIssueKind.BROKEN_UNREADABLE
                        || i.kind() == MdfLinkIssueKind.PREMATURE_EOF
                        || i.kind() == MdfLinkIssueKind.IMPOSSIBLE_LENGTH
                        || i.kind() == MdfLinkIssueKind.ARITHMETIC_OVERFLOW)
                .toList();

        MdfFinalizationAssessment fin = analysis.finalization();
        List<MdfConsistencyIssue> issues = new ArrayList<>(analysis.issues());
        List<MdfRecoveryCandidate> recovery = analysis.recoveryCandidates();

        return new MdfInspectionReport(
                path.getFileName().toString(),
                path.toAbsolutePath().toString(),
                identification.physicalFileSize(),
                identification.version(),
                fin.status(),
                counts,
                metadata.dataGroups().size(),
                metadata.channelGroups().size(),
                metadata.channels().size(),
                graph.countByType(MdfBlockType.DT),
                graph.countByType(MdfBlockType.DZ),
                graph.countByType(MdfBlockType.DL),
                fin.declaredCycleCountTotal(),
                fin.possibleRecordCount(),
                fin.physicalDataRegionLength(),
                fin.physicalDataRegionLength() > 0,
                fin.recoveryCandidate(),
                unknownBlocks,
                broken,
                issues,
                recovery,
                metadata.unsupportedStructures(),
                fin.reasons(),
                graph.cancelled()
        );
    }

    public static boolean isProbablyMf4(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mf4") || name.endsWith(".mdf")) {
            return true;
        }
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            return MdfIdentificationReader.looksLikeMdf(access);
        } catch (ParserException e) {
            return false;
        }
    }
}
