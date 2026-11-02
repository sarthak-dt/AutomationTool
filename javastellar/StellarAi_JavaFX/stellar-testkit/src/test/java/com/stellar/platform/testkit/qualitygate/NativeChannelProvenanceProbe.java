package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.decode.MdfNativeMeasurementResolver;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Path;
import java.util.Locale;

public final class NativeChannelProvenanceProbe {

    private NativeChannelProvenanceProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: NativeChannelProvenanceProbe mf4Path");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            var id = MdfIdentificationReader.read(access);
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            var meta = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            var analysis = MdfConsistencyAnalyser.analyse(id, graph, meta);
            var schemas = new MdfDynamicSchemaBuilder().build(graph, meta, analysis);
            System.out.println("channels=" + meta.channels().size() + " cgs=" + meta.channelGroups().size());
            System.out.println("--- ALL CN ---");
            for (var cn : meta.channels()) {
                System.out.printf(Locale.ROOT,
                        "name=%s addr=0x%x cg=0x%x type=%d sync=%d dt=%d byteOff=%d bitOff=%d bits=%d unit=[%s] ccLink=0x%x conv=%s src=[%s/%s]%n",
                        cn.name(), cn.address(), cn.parentCgAddress(), cn.channelType(), cn.syncType(),
                        cn.dataType(), cn.byteOffset(), cn.bitOffset(), cn.bitCount(), cn.unit(),
                        cn.conversionLink(), cn.conversion(), cn.sourceName(), cn.sourcePath());
            }
            System.out.println("--- SCHEMAS ---");
            for (MdfRecordSchema s : schemas.recordSchemas()) {
                boolean nativeOk = MdfNativeMeasurementResolver.emitsNativeMeasurements(s);
                System.out.printf(Locale.ROOT,
                        "CG=0x%x usable=%s bus=%s busEvent=%s vlsd=%s canEmit=%s nativeEmit=%s cycles=%d fields=%d status=%s dataBytes=%d%n",
                        s.channelGroupAddress(), s.usable(), s.busKind(), s.busEventChannelGroup(),
                        s.vlsdChannelGroup(), s.emitsCanonicalCanFrames(), nativeOk,
                        s.declaredCycleCount(), s.channelFields().size(), s.status(), s.dataBytesPerRecord());
                for (var f : s.channelFields()) {
                    System.out.printf(Locale.ROOT,
                            "  field=%s leaf=%s type=%d sync=%d kind=%s byteOff=%d bits=%d unit=[%s] cc=%s supported=%s%n",
                            f.name(), f.leafName(), f.channelType(), f.syncType(), f.dataTypeKind(),
                            f.byteOffset(), f.bitLength(), f.unit(), f.conversion(), f.supported());
                }
            }
        }

        var decoded = new MdfDynamicRecordDecoder().decodeAll(path, ReaderContext.ofBatchSize(256));
        System.out.println("frames=" + decoded.frames().size()
                + " nativeSignals=" + decoded.nativeSignals().size());
        decoded.nativeSignals().stream().limit(40).forEach(s ->
                System.out.printf(Locale.ROOT, "native %s t=%.12g phys=%s raw=%s unit=%s%n",
                        s.signalName(),
                        s.timestampNanos() / 1_000_000_000.0,
                        s.physicalValue(),
                        s.rawValue(),
                        s.unit()));
    }
}
