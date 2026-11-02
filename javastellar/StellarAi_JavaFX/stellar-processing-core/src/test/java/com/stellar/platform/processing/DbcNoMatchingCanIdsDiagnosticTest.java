package com.stellar.platform.processing;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbcNoMatchingCanIdsDiagnosticTest {

    @TempDir
    Path temp;

    @Test
    void whenAllFramesUnmatched_emitsFileLevelDbcNoMatchingCanIds() throws Exception {
        Path measurement = temp.resolve("bus.mf4");
        Files.writeString(measurement, "placeholder");
        Path dbc = temp.resolve("obd2.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 2024 OBD2: 8 Vector__XXX
                 SG_ S : 0|8@1+ (1,0) [0|255] "" Vector__XXX
                """);

        AutomotiveFileReader reader = new AutomotiveFileReader() {
            @Override
            public String formatKey() {
                return "mf4";
            }

            @Override
            public boolean supports(Path path) {
                return true;
            }

            @Override
            public FileMetadata readMetadata(Path path) {
                return new FileMetadata("mf4", path.getFileName().toString(), path.toString());
            }

            @Override
            public void readFrames(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                try {
                    byte[] payload = new byte[8];
                    CanonicalCanFrame frame = new CanonicalCanFrame(
                            0L, 1_000L, 0, 0x100L, false, FrameDirection.RX, 8, payload,
                            FrameQuality.RECOVERED, new SourceReference(1L, 0L)
                    );
                    frameConsumer.onBatch(List.of(frame));
                } catch (ParserException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ParserException("test reader failed", e);
                }
            }
        };

        DbcDatabase database = new DbcParser().parse(dbc);
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()),
                new DefaultDbcDecoder()
        );

        ProcessingResult result = pipeline.process(
                reader,
                measurement,
                database,
                ReaderContext.ofBatchSize(8),
                batch -> {
                }
        );

        assertEquals(1L, result.statistics().sourceFramesRead());
        assertEquals(0L, result.statistics().dbcMatchedFrames());
        assertEquals(1L, result.statistics().dbcUnmatchedFrames());
        assertEquals(0L, result.statistics().signalsEmitted());
        assertEquals(ProcessingOutcome.COMPLETED_WITH_WARNINGS, result.outcome());
        assertTrue(result.completedSuccessfully());
        assertTrue(result.aggregatedDiagnostics().stream()
                        .anyMatch(d -> "DBC_NO_MATCHING_CAN_IDS".equals(d.code())),
                String.valueOf(result.aggregatedDiagnostics()));
        assertTrue(result.aggregatedDiagnostics().stream()
                .filter(d -> "DBC_NO_MATCHING_CAN_IDS".equals(d.code()))
                .anyMatch(d -> d.message().toLowerCase().contains("does not contain definitions")));
    }
}
