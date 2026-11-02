package com.stellar.platform.processing;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.dbc.model.DbcDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * physical data + recovery attempted + zero frames must not become ordinary COMPLETED.
 */
class ZeroFrameMf4OutcomeTest {

    @TempDir
    Path temp;

    @Test
    void recoveryAttemptedWithZeroFrames_isNotOrdinaryCompleted() throws Exception {
        Path measurement = temp.resolve("empty-recovery.mf4");
        Files.writeString(measurement, "placeholder");
        Path dbc = temp.resolve("empty.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 1 Msg: 8 Vector__XXX
                 SG_ Sig : 0|8@1+ (1,0) [0|0] "" Vector__XXX
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
            public com.stellar.platform.domain.FileMetadata readMetadata(Path path) {
                return new com.stellar.platform.domain.FileMetadata("mf4", path.getFileName().toString(), path.toString());
            }

            @Override
            public void readFrames(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    WarningConsumer warningConsumer
            ) {
                warningConsumer.onWarning(new ProcessingWarning(
                        "RECOVERY_REQUIRED",
                        "Recovery required for DG (not silent zero)",
                        new SourceReference(-1L, 0L)
                ));
                warningConsumer.onWarning(new ProcessingWarning(
                        "RECOVERY_REQUIRED",
                        "[NO_VALID_RECORDS_IN_REGION] Physical region present but no structurally valid records recovered",
                        new SourceReference(-1L, 0L)
                ));
                // zero CanonicalCanFrame batches
            }
        };

        DbcDatabase database = new com.stellar.platform.dbc.parse.DbcParser().parse(dbc);
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()),
                new com.stellar.platform.dbc.decode.DefaultDbcDecoder()
        );

        ProcessingResult result = pipeline.process(
                reader,
                measurement,
                database,
                ReaderContext.ofBatchSize(8),
                batch -> {
                }
        );

        assertEquals(0L, result.statistics().sourceFramesRead());
        assertFalse(result.completedSuccessfully());
        assertEquals(ProcessingOutcome.RECOVERY_FAILED, result.outcome());
        assertEquals(ProcessingOutcome.RECOVERY_FAILED, result.outcome());
    }
}
