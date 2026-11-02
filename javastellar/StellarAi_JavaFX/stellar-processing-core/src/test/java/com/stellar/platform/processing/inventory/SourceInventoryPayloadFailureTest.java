package com.stellar.platform.processing.inventory;

import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanChannelDiscoveryCapable;
import com.stellar.platform.parser.spi.CanChannelDiscoveryResult;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryPayloadFailureTest {

    @TempDir
    Path temp;

    @Test
    void canStructuresWithDzSpillFailure_failInsteadOfReadyZeroCan() throws Exception {
        Path file = temp.resolve("payload-fail.bin");
        Files.writeString(file, "x");
        AutomotiveFileReader reader = new FailingDzReader();
        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()));
        CanSourceInventory inv = service.inventory(reader, file, ReaderContext.ofBatchSize(8), null);
        assertEquals(CanInventoryStatus.FAILED, inv.status());
        assertEquals(0, inv.totalCanFrames());
        assertTrue(inv.diagnostics().stream().anyMatch(d ->
                d.code() == CanInventoryDiagnosticCode.CAN_PAYLOAD_UNREADABLE));
    }

    @Test
    void isPayloadUnreadableWarningRecognizesDzSpillCodes() {
        assertTrue(SourceInventoryService.isPayloadUnreadableWarning(
                "DZ_DECOMPRESSION_FAILED", "DZ spill decompression failed: no such file"));
        assertTrue(SourceInventoryService.isPayloadUnreadableWarning(
                "DZ_INVALID_LENGTH", "spill supports Deflate-only DZ"));
    }

    private static final class FailingDzReader implements AutomotiveFileReader, CanChannelDiscoveryCapable {
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
        public CanChannelDiscoveryResult discoverCanChannels(Path path, CancellationToken cancellation) {
            return CanChannelDiscoveryResult.structuresOnly(1);
        }

        @Override
        public void readFrames(
                Path path,
                ReaderContext context,
                CanFrameConsumer frameConsumer,
                WarningConsumer warningConsumer
        ) {
            warningConsumer.onWarning(new ProcessingWarning(
                    "DZ_DECOMPRESSION_FAILED",
                    "DZ spill decompression failed: AccessDeniedException",
                    new SourceReference(-1L, 0L)
            ));
        }
    }
}
