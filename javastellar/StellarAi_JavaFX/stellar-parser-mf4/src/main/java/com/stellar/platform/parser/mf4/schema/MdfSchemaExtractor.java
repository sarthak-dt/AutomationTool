package com.stellar.platform.parser.mf4.schema;

import com.stellar.platform.parser.mf4.block.MdfBlockHeader;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockNode;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts DG/CG/CN and data-block schema metadata without decoding records.
 */
public final class MdfSchemaExtractor {

    private MdfSchemaExtractor() {
    }

    public static MdfMetadataSummary extract(
            MdfFileAccess access,
            MdfBlockGraph graph,
            CancellationToken cancellation
    ) throws ParserException {
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        List<MdfDgMetadata> dgs = new ArrayList<>();
        List<MdfCgMetadata> cgs = new ArrayList<>();
        List<MdfCnMetadata> cns = new ArrayList<>();
        List<MdfCaMetadata> cas = new ArrayList<>();
        List<MdfDataBlockMetadata> dataBlocks = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        Set<Long> seenData = new HashSet<>();
        Set<Long> seenCa = new HashSet<>();

        Optional<MdfBlockNode> hd = graph.nodeAt(MdfIdentification.HD_BLOCK_OFFSET);
        if (hd.isEmpty()) {
            unsupported.add("HD block missing at offset 64");
            return new MdfMetadataSummary(dgs, cgs, cns, cas, dataBlocks, unsupported);
        }

        long dgLink = linkOrZero(hd.get(), 0);
        Set<Long> visitedDg = new HashSet<>();
        while (dgLink != 0L) {
            if (token.isCancellationRequested()) {
                break;
            }
            if (!visitedDg.add(dgLink)) {
                unsupported.add("Cyclic DG chain at " + dgLink);
                break;
            }
            Optional<MdfBlockNode> dgNode = graph.nodeAt(dgLink);
            if (dgNode.isEmpty() || dgNode.get().type() != MdfBlockType.DG) {
                unsupported.add("Expected DG at " + dgLink);
                break;
            }
            MdfDgMetadata dg = readDg(access, dgNode.get());
            dgs.add(dg);

            long cgLink = dg.firstCgLink();
            Set<Long> visitedCg = new HashSet<>();
            while (cgLink != 0L) {
                if (token.isCancellationRequested()) {
                    break;
                }
                if (!visitedCg.add(cgLink)) {
                    unsupported.add("Cyclic CG chain at " + cgLink);
                    break;
                }
                Optional<MdfBlockNode> cgNode = graph.nodeAt(cgLink);
                if (cgNode.isEmpty() || cgNode.get().type() != MdfBlockType.CG) {
                    unsupported.add("Expected CG at " + cgLink);
                    break;
                }
                MdfCgMetadata cg = readCg(access, graph, cgNode.get(), dg.address());
                cgs.add(cg);

                long cnLink = cg.firstCnLink();
                Set<Long> visitedCn = new HashSet<>();
                while (cnLink != 0L) {
                    if (token.isCancellationRequested()) {
                        break;
                    }
                    if (!visitedCn.add(cnLink)) {
                        unsupported.add("Cyclic CN chain at " + cnLink);
                        break;
                    }
                    Optional<MdfBlockNode> cnNode = graph.nodeAt(cnLink);
                    if (cnNode.isEmpty() || cnNode.get().type() != MdfBlockType.CN) {
                        unsupported.add("Expected CN at " + cnLink);
                        break;
                    }
                    MdfCnMetadata top = readCn(access, graph, cnNode.get(), cg.address(), 0L);
                    cns.add(top);
                    collectCompositionMembers(
                            access, graph, top, cg.address(), cns, cas, seenCa, visitedCn, unsupported, token
                    );
                    cnLink = top.nextCnLink();
                }
                cgLink = cg.nextCgLink();
            }

            if (dg.dataBlockLink() != 0L) {
                collectDataBlocks(access, graph, dg.dataBlockLink(), dataBlocks, seenData, unsupported, token);
            }
            dgLink = dg.nextDgLink();
        }

        // Also capture orphan reachable data blocks discovered in the graph
        for (MdfBlockNode node : graph.nodes()) {
            if (token.isCancellationRequested()) {
                break;
            }
            if (isDataRelated(node.type()) && seenData.add(node.address())) {
                dataBlocks.add(readDataBlock(access, node));
            }
        }

        for (MdfBlockNode unknown : graph.unknownNodes()) {
            unsupported.add("Unknown readable block id='" + unknown.header().rawId()
                    + "' at " + unknown.address());
        }

        return new MdfMetadataSummary(dgs, cgs, cns, cas, dataBlocks, unsupported);
    }

    private static boolean isDataRelated(MdfBlockType type) {
        return type == MdfBlockType.DT
                || type == MdfBlockType.DZ
                || type == MdfBlockType.DL
                || type == MdfBlockType.HL
                || type == MdfBlockType.SD;
    }

    private static void collectDataBlocks(
            MdfFileAccess access,
            MdfBlockGraph graph,
            long address,
            List<MdfDataBlockMetadata> out,
            Set<Long> seen,
            List<String> unsupported,
            CancellationToken token
    ) throws ParserException {
        if (address == 0L || !seen.add(address) || token.isCancellationRequested()) {
            return;
        }
        Optional<MdfBlockNode> node = graph.nodeAt(address);
        if (node.isEmpty()) {
            unsupported.add("Missing linked data block at " + address);
            return;
        }
        MdfDataBlockMetadata meta = readDataBlock(access, node.get());
        out.add(meta);

        if (node.get().type() == MdfBlockType.DL) {
            if (meta.nextDlLink() != null && meta.nextDlLink() != 0L) {
                collectDataBlocks(access, graph, meta.nextDlLink(), out, seen, unsupported, token);
            }
            for (int i = 1; i < node.get().outgoingLinks().size(); i++) {
                long dataLink = node.get().outgoingLinks().get(i);
                if (dataLink != 0L) {
                    collectDataBlocks(access, graph, dataLink, out, seen, unsupported, token);
                }
            }
        } else if (node.get().type() == MdfBlockType.HL) {
            if (meta.hlFirstDlLink() != null && meta.hlFirstDlLink() != 0L) {
                collectDataBlocks(access, graph, meta.hlFirstDlLink(), out, seen, unsupported, token);
            }
        }
    }

    private static MdfDgMetadata readDg(MdfFileAccess access, MdfBlockNode node) throws ParserException {
        MdfBlockHeader h = node.header();
        long next = linkOrZero(node, 0);
        long cg = linkOrZero(node, 1);
        long data = linkOrZero(node, 2);
        long comment = linkOrZero(node, 3);
        int recIdSize = 0;
        if (h.declaredDataLength() >= 1) {
            recIdSize = access.readUInt8(h.dataSectionOffset(), "dg_rec_id_size@" + h.address());
        }
        return new MdfDgMetadata(h.address(), next, cg, data, comment, recIdSize);
    }

    private static MdfCgMetadata readCg(
            MdfFileAccess access,
            MdfBlockGraph graph,
            MdfBlockNode node,
            long dgAddress
    ) throws ParserException {
        MdfBlockHeader h = node.header();
        long next = linkOrZero(node, 0);
        long cn = linkOrZero(node, 1);
        long acqName = linkOrZero(node, 2);
        long acqSource = linkOrZero(node, 3);
        long comment = linkOrZero(node, 5);

        // CG data section (MDF 4.1 layout, first fields stable across 4.x):
        // record_id u64, cycle_count u64, flags u16, path_separator u16, reserved u32,
        // data_bytes u32, inval_bytes u32
        long recordId = 0;
        long cycleCount = 0;
        int flags = 0;
        long dataBytes = 0;
        long invalBytes = 0;
        if (h.declaredDataLength() >= 32) {
            ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), 32, "CG-data@" + h.address());
            recordId = buf.getLong();
            cycleCount = buf.getLong();
            flags = Short.toUnsignedInt(buf.getShort());
            buf.getShort(); // path separator
            buf.getInt(); // reserved
            dataBytes = Integer.toUnsignedLong(buf.getInt());
            invalBytes = Integer.toUnsignedLong(buf.getInt());
        } else if (h.declaredDataLength() >= 24) {
            ByteBuffer buf = access.readOrdered(
                    h.dataSectionOffset(), (int) Math.min(h.declaredDataLength(), 24), "CG-data@" + h.address());
            recordId = buf.getLong();
            cycleCount = buf.getLong();
            if (buf.remaining() >= 2) {
                flags = Short.toUnsignedInt(buf.getShort());
            }
        }
        SiInfo si = readSi(access, graph, acqSource);
        return new MdfCgMetadata(
                h.address(), dgAddress, next, cn, acqName, acqSource, comment,
                recordId, cycleCount, flags, dataBytes, invalBytes,
                si.sourceType(), si.busType(), si.sourceName(), si.sourcePath()
        );
    }

    /**
     * Walks {@code cn_composition} → CN sibling chains and supported CA → CN expansions.
     */
    private static void collectCompositionMembers(
            MdfFileAccess access,
            MdfBlockGraph graph,
            MdfCnMetadata parent,
            long cgAddress,
            List<MdfCnMetadata> out,
            List<MdfCaMetadata> cas,
            Set<Long> seenCa,
            Set<Long> visitedCn,
            List<String> unsupported,
            CancellationToken token
    ) throws ParserException {
        long memberLink = parent.compositionLink();
        if (memberLink == 0L) {
            return;
        }
        Optional<MdfBlockNode> first = graph.nodeAt(memberLink);
        if (first.isEmpty()) {
            unsupported.add("Missing composition target at " + memberLink
                    + " for channel @" + parent.address());
            return;
        }
        if (first.get().type() == MdfBlockType.CA) {
            expandCaComposition(
                    access, graph, parent, first.get(), cgAddress, out, cas, seenCa, visitedCn, unsupported, token
            );
            return;
        }
        if (first.get().type() != MdfBlockType.CN) {
            unsupported.add("Unexpected composition target type " + first.get().type()
                    + " at " + memberLink);
            return;
        }
        walkCnCompositionChain(
                access, graph, memberLink, parent.address(), cgAddress, out, cas, seenCa, visitedCn, unsupported, token
        );
    }

    private static void expandCaComposition(
            MdfFileAccess access,
            MdfBlockGraph graph,
            MdfCnMetadata parent,
            MdfBlockNode caNode,
            long cgAddress,
            List<MdfCnMetadata> out,
            List<MdfCaMetadata> cas,
            Set<Long> seenCa,
            Set<Long> visitedCn,
            List<String> unsupported,
            CancellationToken token
    ) throws ParserException {
        if (!seenCa.add(caNode.address())) {
            return;
        }
        MdfCaMetadata ca = readCa(access, caNode);
        cas.add(ca);

        if (ca.storage() == MdfCaMetadata.STORAGE_CG_TEMPLATE
                || ca.storage() == MdfCaMetadata.STORAGE_VECTOR) {
            unsupported.add("CA storage mode " + ca.storage() + " at " + ca.address()
                    + " is not expanded in this slice (parent CN @" + parent.address() + ")");
            return;
        }
        if (ca.type() != MdfCaMetadata.TYPE_ARRAY && ca.type() != MdfCaMetadata.TYPE_SCALE_AXIS) {
            unsupported.add("CA type " + ca.type() + " at " + ca.address()
                    + " is not expanded (parent CN @" + parent.address() + ")");
            // Still try composition link if present — may point at CN template members.
        }
        if (ca.compositionLink() == 0L) {
            unsupported.add("CA @" + ca.address() + " has no composition link");
            return;
        }
        if (ca.ndim() > 0 && ca.elementCount() == 0L) {
            unsupported.add("CA @" + ca.address() + " has invalid dimensions");
        }
        Optional<MdfBlockNode> target = graph.nodeAt(ca.compositionLink());
        if (target.isEmpty()) {
            unsupported.add("CA composition target missing at " + ca.compositionLink());
            return;
        }
        if (target.get().type() == MdfBlockType.CA) {
            expandCaComposition(
                    access, graph, parent, target.get(), cgAddress, out, cas, seenCa, visitedCn, unsupported, token
            );
            return;
        }
        if (target.get().type() != MdfBlockType.CN) {
            unsupported.add("CA composition target is " + target.get().type() + " at " + ca.compositionLink());
            return;
        }
        walkCnCompositionChain(
                access, graph, ca.compositionLink(), parent.address(), cgAddress,
                out, cas, seenCa, visitedCn, unsupported, token
        );
    }

    private static void walkCnCompositionChain(
            MdfFileAccess access,
            MdfBlockGraph graph,
            long firstCn,
            long parentChannelAddress,
            long cgAddress,
            List<MdfCnMetadata> out,
            List<MdfCaMetadata> cas,
            Set<Long> seenCa,
            Set<Long> visitedCn,
            List<String> unsupported,
            CancellationToken token
    ) throws ParserException {
        Set<Long> siblingVisited = new HashSet<>();
        long cnLink = firstCn;
        while (cnLink != 0L) {
            if (token.isCancellationRequested()) {
                return;
            }
            if (!siblingVisited.add(cnLink)) {
                unsupported.add("Cyclic composition sibling chain at " + cnLink);
                return;
            }
            if (!visitedCn.add(cnLink)) {
                return;
            }
            Optional<MdfBlockNode> cnNode = graph.nodeAt(cnLink);
            if (cnNode.isEmpty() || cnNode.get().type() != MdfBlockType.CN) {
                unsupported.add("Expected composition CN at " + cnLink);
                return;
            }
            MdfCnMetadata child = readCn(access, graph, cnNode.get(), cgAddress, parentChannelAddress);
            out.add(child);
            collectCompositionMembers(
                    access, graph, child, cgAddress, out, cas, seenCa, visitedCn, unsupported, token
            );
            cnLink = child.nextCnLink();
        }
    }

    private static MdfCaMetadata readCa(MdfFileAccess access, MdfBlockNode node) throws ParserException {
        MdfBlockHeader h = node.header();
        long composition = linkOrZero(node, 0);
        long data = linkOrZero(node, 1);
        int type = 0;
        int storage = 0;
        int ndim = 0;
        int flags = 0;
        long byteOffsetBase = 0;
        int[] dims = new int[0];
        // Minimal CA data layout (ASAM 4.1): type u8, storage u8, ndim u16, flags u32, ...
        if (h.declaredDataLength() >= 8) {
            ByteBuffer buf = access.readOrdered(
                    h.dataSectionOffset(),
                    (int) Math.min(h.declaredDataLength(), 64),
                    "CA-data@" + h.address()
            );
            type = Byte.toUnsignedInt(buf.get());
            storage = Byte.toUnsignedInt(buf.get());
            ndim = Short.toUnsignedInt(buf.getShort());
            flags = buf.getInt();
            if (buf.remaining() >= 8) {
                byteOffsetBase = Integer.toUnsignedLong(buf.getInt());
                buf.getInt(); // reserved / invariant
            }
            if (ndim > 0 && ndim < 16 && buf.remaining() >= ndim * 8) {
                dims = new int[ndim];
                for (int i = 0; i < ndim; i++) {
                    long dim = buf.getLong();
                    dims[i] = dim > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, dim);
                }
            }
        }
        return new MdfCaMetadata(
                h.address(), composition, data, type, storage, ndim, flags, byteOffsetBase, dims, new long[0]
        );
    }

    private static MdfCnMetadata readCn(
            MdfFileAccess access,
            MdfBlockGraph graph,
            MdfBlockNode node,
            long cgAddress,
            long parentChannelAddress
    ) throws ParserException {
        MdfBlockHeader h = node.header();
        long next = linkOrZero(node, 0);
        long composition = linkOrZero(node, 1);
        long nameLink = linkOrZero(node, 2);
        long sourceLink = linkOrZero(node, 3);
        long conversionLink = linkOrZero(node, 4);
        long dataLink = linkOrZero(node, 5);
        long unitLink = linkOrZero(node, 6);
        long commentLink = linkOrZero(node, 7);

        int channelType = 0;
        int syncType = 0;
        int dataType = 0;
        int bitOffset = 0;
        long byteOffset = 0;
        long bitCount = 0;
        int flags = 0;
        long invalBitPos = 0;

        // CN data: type u8, sync u8, data_type u8, bit_offset u8, byte_offset u32, bit_count u32,
        // flags u32, inval_bit_pos u32, ...
        if (h.declaredDataLength() >= 20) {
            ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), 20, "CN-data@" + h.address());
            channelType = Byte.toUnsignedInt(buf.get());
            syncType = Byte.toUnsignedInt(buf.get());
            dataType = Byte.toUnsignedInt(buf.get());
            bitOffset = Byte.toUnsignedInt(buf.get());
            byteOffset = Integer.toUnsignedLong(buf.getInt());
            bitCount = Integer.toUnsignedLong(buf.getInt());
            flags = buf.getInt();
            invalBitPos = Integer.toUnsignedLong(buf.getInt());
        }

        String name = resolveText(access, graph, nameLink);
        String unit = resolveText(access, graph, unitLink);
        SiInfo si = readSi(access, graph, sourceLink);
        com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion conversion =
                readConversion(access, graph, conversionLink);
        // ASAM: CN unit may be empty while the linked CC carries the physical unit (e.g. "s").
        if ((unit == null || unit.isBlank()) && conversionLink != 0L) {
            String ccUnit = readConversionUnit(access, graph, conversionLink);
            if (ccUnit != null && !ccUnit.isBlank()) {
                unit = ccUnit;
            }
        }

        return new MdfCnMetadata(
                h.address(),
                cgAddress,
                next,
                composition,
                parentChannelAddress,
                nameLink,
                sourceLink,
                conversionLink,
                dataLink,
                unitLink,
                commentLink,
                channelType,
                syncType,
                dataType,
                byteOffset,
                bitOffset,
                bitCount,
                flags,
                invalBitPos,
                name,
                unit,
                si.sourceType(),
                si.busType(),
                si.sourceName(),
                si.sourcePath(),
                conversion
        );
    }

    /**
     * Reads a CC block. A missing link stays identity. A present CC block whose type
     * or parameters cannot be applied is returned unapplied; it is not rewritten as identity.
     */
    private static com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion readConversion(
            MdfFileAccess access,
            MdfBlockGraph graph,
            long conversionLink
    ) throws ParserException {
        return readConversion(access, graph, conversionLink, 0);
    }

    private static com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion readConversion(
            MdfFileAccess access,
            MdfBlockGraph graph,
            long conversionLink,
            int depth
    ) throws ParserException {
        if (depth > 8) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                    -1, "conversion reference cycle");
        }
        if (conversionLink == 0L) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.IDENTITY;
        }
        Optional<MdfBlockNode> node = graph.nodeAt(conversionLink);
        if (node.isEmpty() || node.get().type() != MdfBlockType.CC) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.IDENTITY;
        }
        MdfBlockHeader h = node.get().header();
        // ASAM CC data: type u8, precision u8, flags u16, ref_count u16, val_count u16,
        // phy_range_min f64, phy_range_max f64, cc_val[val_count] f64
        if (h.declaredDataLength() < 24) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                    -1, "CC block data is shorter than the ASAM header");
        }
        ByteBuffer header = access.readOrdered(h.dataSectionOffset(), 8, "CC-header@" + h.address());
        int type = Byte.toUnsignedInt(header.get());
        header.get(); // precision
        header.getShort(); // flags
        int refCount = Short.toUnsignedInt(header.getShort());
        int valCount = Short.toUnsignedInt(header.getShort());
        if (valCount > 8192) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                    type, "cc_val count " + valCount + " exceeds 8192");
        }
        int needed = 24 + valCount * 8;
        if (h.declaredDataLength() < needed) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                    type, "CC data length " + h.declaredDataLength() + " is shorter than " + needed);
        }
        ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), needed, "CC-data@" + h.address());
        buf.position(8);
        buf.getDouble(); // phy min
        buf.getDouble(); // phy max
        double[] values = new double[valCount];
        for (int i = 0; i < valCount; i++) {
            values[i] = buf.getDouble();
        }
        String formula = "";
        if (type == com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.TYPE_ALGEBRAIC) {
            if (refCount < 1) {
                return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                        type, "algebraic formula is missing");
            }
            formula = resolveText(access, graph, linkOrZero(node.get(), 4))
                    .replace("\u0000", "")
                    .trim();
        }
        if (type == com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.TYPE_VALUE_TO_TEXT
                || type == com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.TYPE_VALUE_RANGE_TO_TEXT) {
            return readTextConversion(access, graph, node.get(), type, refCount, valCount, values, depth);
        }
        return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.fromAsam(type, values, formula);
    }

    /**
     * CC type 7 keys and type 8 inclusive ranges. Link {@code 4 + i} is the TX for entry
     * {@code i}. One extra link, when {@code refCount} is one past the entry count, is the
     * default text or the default CC used only when no entry matches.
     */
    private static com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion readTextConversion(
            MdfFileAccess access,
            MdfBlockGraph graph,
            MdfBlockNode node,
            int type,
            int refCount,
            int valCount,
            double[] values,
            int depth
    ) throws ParserException {
        if (type == com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.TYPE_VALUE_RANGE_TO_TEXT
                && valCount % 2 != 0) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                    type, "value range to text requires lower/upper pairs");
        }
        int entries = type == com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.TYPE_VALUE_TO_TEXT
                ? valCount
                : valCount / 2;
        boolean hasDefault = refCount == entries + 1;
        if (refCount != entries && !hasDefault) {
            return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                    type, "text conversion reference count " + refCount + " does not match " + entries + " entries");
        }
        String[] labels = new String[entries];
        for (int i = 0; i < entries; i++) {
            labels[i] = resolveText(access, graph, linkOrZero(node, 4 + i));
        }
        String defaultLabel = "";
        com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion defaultConversion = null;
        if (hasDefault) {
            long defaultLink = linkOrZero(node, 4 + entries);
            Optional<MdfBlockNode> defaultNode = defaultLink == 0L
                    ? Optional.empty()
                    : graph.nodeAt(defaultLink);
            if (defaultNode.isEmpty()) {
                return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                        type, "text conversion default reference is missing");
            }
            MdfBlockType defaultType = defaultNode.get().type();
            if (defaultType == MdfBlockType.TX || defaultType == MdfBlockType.MD) {
                defaultLabel = resolveText(access, graph, defaultLink);
                if (defaultLabel.isBlank()) {
                    return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                            type, "text conversion default reference is empty");
                }
            } else if (defaultType == MdfBlockType.CC) {
                defaultConversion = readConversion(access, graph, defaultLink, depth + 1);
            } else {
                return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.unsupported(
                        type, "text conversion default reference is missing");
            }
        }
        return com.stellar.platform.parser.mf4.dynamicschema.MdfChannelConversion.fromAsam(
                type, values, "", labels, defaultLabel, defaultConversion);
    }

    /** CC link index 1 is the optional unit TX/MD (ASAM). */
    private static String readConversionUnit(MdfFileAccess access, MdfBlockGraph graph, long conversionLink)
            throws ParserException {
        Optional<MdfBlockNode> node = graph.nodeAt(conversionLink);
        if (node.isEmpty() || node.get().type() != MdfBlockType.CC) {
            return "";
        }
        return resolveText(access, graph, linkOrZero(node.get(), 1));
    }

    private static SiInfo readSi(MdfFileAccess access, MdfBlockGraph graph, long sourceLink)
            throws ParserException {
        if (sourceLink == 0L) {
            return SiInfo.EMPTY;
        }
        Optional<MdfBlockNode> node = graph.nodeAt(sourceLink);
        if (node.isEmpty() || node.get().type() != MdfBlockType.SI) {
            return SiInfo.EMPTY;
        }
        MdfBlockHeader h = node.get().header();
        String sourceName = resolveText(access, graph, linkOrZero(node.get(), 0));
        String sourcePath = resolveText(access, graph, linkOrZero(node.get(), 1));
        int sourceType = -1;
        int busType = -1;
        if (h.declaredDataLength() >= 2) {
            ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), 2, "SI-data@" + h.address());
            sourceType = Byte.toUnsignedInt(buf.get());
            busType = Byte.toUnsignedInt(buf.get());
        }
        return new SiInfo(sourceType, busType, sourceName, sourcePath);
    }

    private record SiInfo(int sourceType, int busType, String sourceName, String sourcePath) {
        static final SiInfo EMPTY = new SiInfo(-1, -1, "", "");
    }

    private static MdfDataBlockMetadata readDataBlock(MdfFileAccess access, MdfBlockNode node)
            throws ParserException {
        MdfBlockHeader h = node.header();
        long availableFromData = Math.max(0L, access.fileSize() - h.dataSectionOffset());
        long physicalReadable = Math.max(0L, Math.min(h.declaredDataLength(), availableFromData));
        long bytesAfterBlockEnd = Math.max(0L, access.fileSize() - h.blockEnd());

        Long nextDl = null;
        Integer dlFlags = null;
        Integer dlCount = null;
        Long hlFirstDl = null;
        Integer hlZip = null;
        Integer dzZip = null;
        Long dzOrgLen = null;
        Long dzCompLen = null;
        String dzOrgType = null;

        if (h.type() == MdfBlockType.DL && h.declaredDataLength() >= 8) {
            nextDl = linkOrZero(node, 0);
            ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), 8, "DL-data@" + h.address());
            dlFlags = Byte.toUnsignedInt(buf.get());
            buf.get();
            buf.getShort();
            dlCount = buf.getInt();
        } else if (h.type() == MdfBlockType.HL && h.declaredDataLength() >= 4) {
            hlFirstDl = linkOrZero(node, 0);
            ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), 4, "HL-data@" + h.address());
            buf.getShort(); // flags
            hlZip = Byte.toUnsignedInt(buf.get());
        } else if (h.type() == MdfBlockType.DZ && h.declaredDataLength() >= 24) {
            ByteBuffer buf = access.readOrdered(h.dataSectionOffset(), 24, "DZ-data@" + h.address());
            byte[] orgType = new byte[2];
            buf.get(orgType);
            dzOrgType = new String(orgType, StandardCharsets.US_ASCII).trim();
            dzZip = Byte.toUnsignedInt(buf.get());
            buf.get(); // reserved
            buf.getInt(); // zip parameter
            dzOrgLen = buf.getLong();
            dzCompLen = buf.getLong();
        }

        return new MdfDataBlockMetadata(
                h.type(),
                h.address(),
                h.blockEnd(),
                h.declaredLength(),
                h.declaredDataLength(),
                physicalReadable,
                bytesAfterBlockEnd,
                nextDl,
                dlFlags,
                dlCount,
                hlFirstDl,
                hlZip,
                dzZip,
                dzOrgLen,
                dzCompLen,
                dzOrgType
        );
    }

    private static String resolveText(MdfFileAccess access, MdfBlockGraph graph, long link)
            throws ParserException {
        if (link == 0L) {
            return "";
        }
        Optional<MdfBlockNode> node = graph.nodeAt(link);
        if (node.isEmpty()) {
            return "";
        }
        MdfBlockHeader h = node.get().header();
        if (h.type() != MdfBlockType.TX && h.type() != MdfBlockType.MD) {
            return "";
        }
        if (h.declaredDataLength() <= 0) {
            return "";
        }
        int len = (int) Math.min(h.declaredDataLength(), Integer.MAX_VALUE);
        String text = access.readUtf8(h.dataSectionOffset(), len, "text@" + link);
        return text.toLowerCase(Locale.ROOT).startsWith("<minversion")
                ? text // MD XML kept as-is
                : text;
    }

    private static long linkOrZero(MdfBlockNode node, int index) {
        if (index < 0 || index >= node.outgoingLinks().size()) {
            return 0L;
        }
        Long v = node.outgoingLinks().get(index);
        return v == null ? 0L : v;
    }
}
