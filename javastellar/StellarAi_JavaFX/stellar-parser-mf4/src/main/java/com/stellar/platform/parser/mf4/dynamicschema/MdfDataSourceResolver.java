package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.schema.MdfDataBlockMetadata;
import com.stellar.platform.parser.mf4.schema.MdfDgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves DG data roots into ordered DT/DZ/DL/HL chains without reading payloads.
 */
public final class MdfDataSourceResolver {

    private MdfDataSourceResolver() {
    }

    public record Resolution(MdfDataSourceSchema schema, List<MdfSchemaDiagnostic> diagnostics) {
    }

    public static Resolution resolve(
            MdfDgMetadata dg,
            MdfBlockGraph graph,
            MdfMetadataSummary metadata
    ) {
        List<MdfSchemaDiagnostic> diagnostics = new ArrayList<>();
        long root = dg.dataBlockLink();
        if (root == 0L) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.MISSING_DATA_ROOT,
                    MdfIssueSeverity.FATAL,
                    "DG has no data-block root link",
                    dg.address(),
                    null,
                    null
            ));
            return new Resolution(new MdfDataSourceSchema(
                    dg.address(), 0L, MdfBlockType.UNKNOWN, 0L, 0L, false, List.of()
            ), diagnostics);
        }

        List<MdfDataSourceNode> nodes = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        boolean healthy = true;
        long declared = 0L;
        long readable = 0L;
        MdfBlockType rootType = MdfBlockType.UNKNOWN;

        List<Long> queue = new ArrayList<>();
        queue.add(root);
        int index = 0;
        while (index < queue.size()) {
            long address = queue.get(index++);
            if (!visited.add(address)) {
                healthy = false;
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.CYCLIC_DATA_SOURCE_CHAIN,
                        MdfIssueSeverity.FATAL,
                        "Cyclic data-source chain at " + address,
                        dg.address(),
                        null,
                        null
                ));
                continue;
            }

            Optional<MdfDataBlockMetadata> meta = metadata.dataBlocks().stream()
                    .filter(d -> d.address() == address)
                    .findFirst();
            if (meta.isEmpty()) {
                if (graph.nodeAt(address).isEmpty()) {
                    healthy = false;
                    diagnostics.add(new MdfSchemaDiagnostic(
                            MdfSchemaDiagnosticCode.BROKEN_DATA_SOURCE_CHAIN,
                            MdfIssueSeverity.FATAL,
                            "Missing data-source block at " + address,
                            dg.address(),
                            null,
                            null
                    ));
                    continue;
                }
                MdfBlockType type = graph.nodeAt(address).get().type();
                if (!isDataSourceType(type)) {
                    healthy = false;
                    diagnostics.add(new MdfSchemaDiagnostic(
                            MdfSchemaDiagnosticCode.UNKNOWN_SOURCE_TYPE,
                            MdfIssueSeverity.WARNING,
                            "Unsupported/unknown data-source type " + type + " at " + address,
                            dg.address(),
                            null,
                            null
                    ));
                }
                if (nodes.isEmpty()) {
                    rootType = type;
                }
                nodes.add(new MdfDataSourceNode(type, address, 0L, 0L, null, null, null, null));
                continue;
            }

            MdfDataBlockMetadata d = meta.get();
            if (nodes.isEmpty()) {
                rootType = d.blockType();
            }
            if (!isDataSourceType(d.blockType())) {
                healthy = false;
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.UNKNOWN_SOURCE_TYPE,
                        MdfIssueSeverity.WARNING,
                        "Unsupported data-source type " + d.blockType() + " at " + address,
                        dg.address(),
                        null,
                        null
                ));
            }

            nodes.add(new MdfDataSourceNode(
                    d.blockType(),
                    d.address(),
                    d.declaredPayloadLength(),
                    d.physicallyReadableLength(),
                    d.nextDlLink(),
                    d.dzZipType() != null ? d.dzZipType() : d.hlZipType(),
                    d.dzOriginalUncompressedLength(),
                    d.dzCompressedLength()
            ));
            declared += Math.max(0L, d.declaredPayloadLength());
            readable += Math.max(0L, d.physicallyReadableLength());

            if (d.blockType() == MdfBlockType.DL) {
                if (d.nextDlLink() != null && d.nextDlLink() != 0L) {
                    queue.add(d.nextDlLink());
                }
                graph.nodeAt(address).ifPresent(node -> {
                    for (int i = 1; i < node.outgoingLinks().size(); i++) {
                        long link = node.outgoingLinks().get(i);
                        if (link != 0L) {
                            queue.add(link);
                        }
                    }
                });
            } else if (d.blockType() == MdfBlockType.HL) {
                if (d.hlFirstDlLink() != null && d.hlFirstDlLink() != 0L) {
                    queue.add(d.hlFirstDlLink());
                } else {
                    healthy = false;
                    diagnostics.add(new MdfSchemaDiagnostic(
                            MdfSchemaDiagnosticCode.BROKEN_DATA_SOURCE_CHAIN,
                            MdfIssueSeverity.WARNING,
                            "HL block missing first DL link at " + address,
                            dg.address(),
                            null,
                            null
                    ));
                }
            }
        }

        return new Resolution(new MdfDataSourceSchema(
                dg.address(),
                root,
                rootType,
                declared,
                readable,
                healthy && !nodes.isEmpty(),
                nodes
        ), diagnostics);
    }

    private static boolean isDataSourceType(MdfBlockType type) {
        return type == MdfBlockType.DT
                || type == MdfBlockType.DZ
                || type == MdfBlockType.DL
                || type == MdfBlockType.HL
                || type == MdfBlockType.SD;
    }
}
