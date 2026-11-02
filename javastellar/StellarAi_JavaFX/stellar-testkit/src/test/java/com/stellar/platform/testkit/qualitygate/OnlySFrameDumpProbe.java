package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodeStatus;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class OnlySFrameDumpProbe {

    public static void main(String[] args) throws Exception {
        Path mf4 = Path.of(args.length > 0 ? args[0]
                : "C:/Users/ADMIN/Desktop/DriveTech/Devendra/Prasad Sangpal/Demo_DT_00000985_00000001-63C8CA43.MF4");
        Path dbcPath = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");

        DbcDatabase db = new DbcParser().parse(dbcPath);
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        var decoded = new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));

        System.out.println("MF4=" + mf4.getFileName() + " frames=" + decoded.frames().size());
        DbcSignal s01 = db.messages().iterator().next().signals().stream()
                .filter(s -> s.name().equals("S01PID")).findFirst().orElseThrow();
        DbcSignal s02 = db.messages().iterator().next().signals().stream()
                .filter(s -> s.name().equals("S02PID")).findFirst().orElseThrow();
        DbcSignal mux = db.messages().iterator().next().multiplexor().orElseThrow();
        System.out.println("mux S start=" + mux.startBit() + " len=" + mux.bitLength()
                + " order=" + mux.byteOrder());
        System.out.println("S01PID secondary=" + s01.secondaryMultiplexor()
                + " muxVal=" + s01.multiplexValue() + " start=" + s01.startBit());
        System.out.println("S02PID secondary=" + s02.secondaryMultiplexor()
                + " muxVal=" + s02.multiplexValue() + " start=" + s02.startBit());

        int matched = 0;
        for (CanonicalCanFrame f : decoded.frames()) {
            DecodingResult dr = decoder.decode(f, db);
            if (dr.status() == DecodeStatus.UNMATCHED) {
                continue;
            }
            matched++;
            String hex = HexFormat.of().formatHex(f.data());
            System.out.println(String.format(Locale.ROOT,
                    "MATCH#%d canId=0x%x ext=%s dlc=%d len=%d off=%d payload=%s signals=%s",
                    matched,
                    f.canId(),
                    f.extended(),
                    f.dlc(),
                    f.data().length,
                    f.source() == null ? -1 : f.source().sourceByteOffset(),
                    hex,
                    dr.signals().stream().map(s -> s.signalName() + "=" + s.rawValue()).toList()
            ));
            byte[] p = f.data();
            if (p.length >= 3) {
                System.out.println("  byte0=" + (p[0] & 0xFF)
                        + " byte1=" + (p[1] & 0xFF)
                        + " byte2=" + (p[2] & 0xFF));
            }
        }
        System.out.println("totalMatched=" + matched);

        var ids = decoded.frames().stream()
                .collect(Collectors.groupingBy(CanonicalCanFrame::canId, TreeMap::new, Collectors.counting()));
        System.out.println("uniqueCanIds=" + ids.size());
        ids.entrySet().stream().limit(40).forEach(e ->
                System.out.println("  0x" + Long.toHexString(e.getKey()) + " count=" + e.getValue()));
    }
}