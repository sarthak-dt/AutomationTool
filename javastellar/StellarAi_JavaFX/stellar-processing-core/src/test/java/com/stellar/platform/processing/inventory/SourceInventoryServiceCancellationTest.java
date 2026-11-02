package com.stellar.platform.processing.inventory;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryServiceCancellationTest {

    @TempDir
    Path temp;

    @Test
    void cancellationStopsInventory() throws Exception {
        Path file = temp.resolve("slow.bin");
        Files.writeString(file, "x");
        SimpleCancellationToken token = new SimpleCancellationToken();
        AutomotiveFileReader reader = new AutomotiveFileReader() {
            @Override
            public String formatKey() {
                return "asc";
            }

            @Override
            public boolean supports(Path path) {
                return true;
            }

            @Override
            public FileMetadata readMetadata(Path path) {
                return new FileMetadata("asc", path.getFileName().toString(), path.toString());
            }

            @Override
            public void readFrames(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                try {
                    for (int i = 0; i < 10_000; i++) {
                        if (context.cancellationToken().isCancellationRequested()) {
                            return;
                        }
                        if (i == 5) {
                            token.cancel();
                        }
                        frameConsumer.onBatch(List.of(new CanonicalCanFrame(
                                i,
                                i,
                                1,
                                0x10,
                                false,
                                FrameDirection.RX,
                                1,
                                new byte[]{1},
                                FrameQuality.OK,
                                SourceReference.ofLine(i + 1)
                        )));
                    }
                } catch (ParserException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ParserException("consumer failed", e);
                }
            }
        };

        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        CanSourceInventory result = service.inventory(
                reader, file, new ReaderContext(8, token), inv -> { });
        assertEquals(CanInventoryStatus.CANCELLED, result.status());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().name().equals("INVENTORY_CANCELLED")));
    }
}
