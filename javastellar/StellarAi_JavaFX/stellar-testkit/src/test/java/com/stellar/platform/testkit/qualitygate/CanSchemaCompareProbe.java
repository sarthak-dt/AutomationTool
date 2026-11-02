package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Path;
import java.util.Locale;

public final class CanSchemaCompareProbe {
    public static void main(String[] args) throws Exception {
        Path[] files = new Path[] {
            Path.of("C:/Users/ADMIN/Desktop/DriveTech/OBD2 Data_Lavasa_0002260_20240305_102230.mf4"),
            Path.of("C:/Users/ADMIN/Desktop/DriveTech/Devendra/Prasad Sangpal/Demo_DT_00000985_00000001-63C8CA43.MF4")
        };
        for (Path mf4 : files) {
            System.out.println("==== " + mf4.getFileName() + " ====");
            var decoded = new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(64));
            System.out.println("frames=" + decoded.frames().size() + " schemas=" + decoded.schemaResult().recordSchemas().size());
            for (MdfRecordSchema s : decoded.schemaResult().recordSchemas()) {
                if (!s.emitsCanonicalCanFrames()) continue;
                System.out.println(String.format(Locale.ROOT,
                        "CG@%d recordId=%d dataBytes=%d invBytes=%d completeLen=%d usable=%s bus=%s",
                        s.channelGroupAddress(), s.recordId(), s.dataBytesPerRecord(),
                        s.invalidationBytesPerRecord(), s.completeRecordLength(), s.usable(), s.busKind()));
                s.channelFields().stream()
                        .filter(f -> f.canRoleCandidate() != null && f.canRoleCandidate().role() != null)
                        .forEach(f -> System.out.println(String.format(Locale.ROOT,
                                "  CN name=%s leaf=%s role=%s byteOff=%d bitOff=%d bitLen=%d dtype=%d order=%s parent=%d member=%s container=%s",
                                f.name(), f.leafName(),
                                f.canRoleCandidate().role(),
                                f.byteOffset(), f.bitOffset(), f.bitLength(), f.dataTypeCode(),
                                f.byteOrder(), f.parentChannelAddress(), f.compositionMember(), f.compositionContainer())));
            }
            decoded.frames().stream().filter(f -> f.canId() == 0x7E8L).limit(3).forEach(f ->
                    System.out.println("7E8 data=" + java.util.HexFormat.of().formatHex(f.data())
                            + " dlc=" + f.dlc() + " quality=" + f.quality()
                            + " off=" + (f.source()==null?-1:f.source().sourceByteOffset())));
            decoded.diagnostics().stream().limit(12).forEach(d ->
                    System.out.println("DIAG " + d.code() + " " + d.reason()));
        }
    }
}