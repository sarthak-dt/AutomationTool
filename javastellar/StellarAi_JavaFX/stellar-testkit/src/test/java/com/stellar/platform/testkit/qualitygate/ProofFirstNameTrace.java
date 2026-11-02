package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodeStatus;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.SignalBitExtractor;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ProofFirstNameTrace {
    public static void main(String[] args) throws Exception {
        Path dbcPath = Path.of(args.length > 0 ? args[0] : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");
        Path mf4 = Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/Devendra/Prasad Sangpal/Demo_DT_00000985_00000001-63C8CA43.MF4");

        System.out.println("=== STEP2 RUNTIME DBC INVENTORY ===");
        System.out.println("DBC=" + dbcPath.toAbsolutePath());
        DbcDatabase db = new DbcParser().parse(dbcPath);
        long sigCount = db.messages().stream().mapToLong(m -> m.signals().size()).sum();
        long unique = db.messages().stream().flatMap(m -> m.signals().stream()).map(DbcSignal::name).distinct().count();
        System.out.println("messageCount=" + db.messages().size());
        System.out.println("signalCount=" + sigCount);
        System.out.println("uniqueSignalNameCount=" + unique);

        List<String> targets = List.of(
                "S", "S01PID0C_EngineRPM", "S01PID0D_VehicleSpeed", "S01PID05_EngineCoolantTemp",
                "S01PID0F_IntakeAirTemperature", "S01PID04_CalcEngineLoad", "S01PID11_ThrottlePosition");
        System.out.println("TARGET_PRESENCE:");
        for (String t : targets) {
            boolean found = db.messages().stream().flatMap(m -> m.signals().stream()).anyMatch(s -> s.name().equals(t));
            System.out.println("  " + t + " => " + found);
        }

        System.out.println("=== STEP3 SG_ SOURCE LINES ===");
        List<String> lines = Files.readAllLines(dbcPath);
        for (String want : targets) {
            Pattern p = Pattern.compile("^SG_\\s+" + Pattern.quote(want) + "\\s+.*");
            boolean printed = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (p.matcher(t).matches()) {
                    System.out.println("LINE " + (i + 1) + " SRC: " + t);
                    printed = true;
                    break;
                }
            }
            if (!printed) System.out.println("LINE ? SRC missing for " + want);
        }

        DbcMessage msg = db.messages().iterator().next();
        System.out.println("=== OBD2 MESSAGE first40 DbcSignal ===");
        System.out.println("messageName=" + msg.name() + " canIdKey=" + msg.canId() + " ext=" + msg.extended());
        int n = 0;
        for (DbcSignal s : msg.signals()) {
            if (n++ >= 40) break;
            System.out.println(String.format(Locale.ROOT,
                    "  name=[%s] start=%d len=%d order=%s signed=%s factor=%s offset=%s unit=[%s] role=%s muxVal=%s secondary=%s",
                    s.name(), s.startBit(), s.bitLength(), s.byteOrder(), s.valueType(),
                    s.factor(), s.offset(), s.unit(), s.multiplexRole(), s.multiplexValue(), s.secondaryMultiplexor()));
        }
        System.out.println("first30Names=" + msg.signals().stream().map(DbcSignal::name).limit(30).toList());

        System.out.println("=== STEP5 DIRECT DECODER first 0x7E8 ===");
        System.out.println("MF4=" + mf4.toAbsolutePath());
        var decodedMf4 = new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));
        long c7 = decodedMf4.frames().stream().filter(f -> f.canId() == 0x7E8L).count();
        System.out.println("totalFrames=" + decodedMf4.frames().size() + " frames7E8=" + c7);
        CanonicalCanFrame frame7e8 = decodedMf4.frames().stream().filter(f -> f.canId() == 0x7E8L).findFirst().orElse(null);
        if (frame7e8 == null) {
            System.out.println("NO_0x7E8_FRAME");
            return;
        }
        System.out.println("payload=" + HexFormat.of().formatHex(frame7e8.data())
                + " dlc=" + frame7e8.dlc() + " quality=" + frame7e8.quality()
                + " tsNanos=" + frame7e8.timestampNanos());

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        DecodingResult dr = decoder.decode(frame7e8, db);
        System.out.println("decodeStatus=" + dr.status()
                + " matchedMessage=" + (dr.matchedMessage() == null ? null : dr.matchedMessage().name()));
        System.out.println("diagnostics=" + dr.diagnostics().stream().map(d -> d.code() + ":" + d.message()).toList());

        DbcSignal mux = msg.multiplexor().orElse(null);
        Long rootMux = null;
        if (mux != null) {
            var er = SignalBitExtractor.extract(frame7e8.data(), mux.startBit(), mux.bitLength(), mux.byteOrder(), mux.valueType());
            rootMux = er.isOk() ? er.rawValue() : null;
            System.out.println("multiplexorSignal=" + mux.name() + " raw=" + rootMux);
        }
        boolean hasSecondary = msg.signals().stream().anyMatch(DbcSignal::secondaryMultiplexor);
        Long leafMux = rootMux;
        if (hasSecondary && rootMux != null) {
            leafMux = null;
            for (DbcSignal s : msg.signals()) {
                if (s.secondaryMultiplexor() && s.isApplicableForMux(rootMux)) {
                    var er = SignalBitExtractor.extract(frame7e8.data(), s.startBit(), s.bitLength(), s.byteOrder(), s.valueType());
                    leafMux = er.isOk() ? er.rawValue() : null;
                    System.out.println("secondaryActive=" + s.name() + " requiredRoot=" + s.multiplexValue() + " leafRaw=" + leafMux);
                    break;
                }
            }
            if (leafMux == null) System.out.println("secondaryActive=NONE rootMux=" + rootMux);
        }

        System.out.println("APPLICABILITY:");
        for (DbcSignal s : msg.signals()) {
            boolean interesting = targets.contains(s.name())
                    || s.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR
                    || s.secondaryMultiplexor();
            if (!interesting) continue;
            boolean active;
            String reason;
            if (s.multiplexRole() == DbcMultiplexRole.NONE || s.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR) {
                active = true; reason = "always";
            } else if (s.secondaryMultiplexor()) {
                active = s.isApplicableForMux(rootMux);
                reason = "secondary needs root==" + s.multiplexValue() + " got=" + rootMux;
            } else if (hasSecondary) {
                active = leafMux != null && s.isApplicableForMux(leafMux);
                reason = "leaf needs secondary==" + s.multiplexValue() + " got=" + leafMux;
            } else {
                active = s.isApplicableForMux(rootMux);
                reason = "leaf needs root==" + s.multiplexValue() + " got=" + rootMux;
            }
            System.out.println("  " + s.name() + " requiredMux=" + s.multiplexValue() + " active=" + active + " reason=" + reason);
        }

        System.out.println("EMITTED=" + dr.signals().size());
        for (CanonicalSignal s : dr.signals()) {
            System.out.println("  name=[" + s.signalName() + "] raw=" + s.rawValue()
                    + " phys=" + s.physicalValue() + " unit=[" + s.unit() + "] ts=" + s.timestampNanos());
        }

        int matched = 0; int leafFrames = 0;
        for (CanonicalCanFrame f : decodedMf4.frames()) {
            DecodingResult r = decoder.decode(f, db);
            if (r.status() == DecodeStatus.UNMATCHED) continue;
            matched++;
            boolean anyLeaf = r.signals().stream().anyMatch(s ->
                    !s.signalName().equals("S") && !s.signalName().equals("S01PID") && !s.signalName().equals("S02PID"));
            if (anyLeaf) leafFrames++;
        }
        System.out.println("ALL matched=" + matched + " withLeaf=" + leafFrames);
        System.out.println("DECISION=" + (unique <= 1 ? "CASE_A" : (leafFrames == 0 ? "CASE_B" : "CASE_C_OR_D")));
    }
}
