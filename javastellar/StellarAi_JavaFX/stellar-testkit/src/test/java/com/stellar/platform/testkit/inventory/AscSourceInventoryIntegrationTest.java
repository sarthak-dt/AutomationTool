package com.stellar.platform.testkit.inventory;

import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.inventory.SourceInventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscSourceInventoryIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void classicalAscFeedsSharedInventory() throws Exception {
        Path asc = temp.resolve("classical.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                // version 1.0.0
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 123 Rx d 8 01 02 03 04 05 06 07 08
                  0.200000 2 18FAF110x Tx d 4 AA BB CC DD
                  0.300000 1 7FF Rx d 0
                  1.5 1 100 Tx d 2 DE AD
                End TriggerBlock
                """);

        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        List<CanSourceInventory> progress = new ArrayList<>();
        CanSourceInventory result = service.inventory(
                new AscFileReader(), asc, ReaderContext.ofBatchSize(2), progress::add);

        assertEquals(CanInventoryStatus.INVENTORY_READY, result.status());
        assertEquals(2, result.totalCanChannels());
        assertEquals(4, result.totalCanFrames());
        assertEquals(4, result.totalUniqueMessageIds());
        assertTrue(result.channels().stream().anyMatch(c -> c.channelId() == 1));
        assertTrue(result.channels().stream().anyMatch(c -> c.channelId() == 2));
        assertTrue(progress.stream().anyMatch(p ->
                p.status() == CanInventoryStatus.DISCOVERING_CHANNELS
                        || p.status() == CanInventoryStatus.INDEXING_MESSAGE_IDS));
    }

    @Test
    void malformedAscLinesDoNotCorruptValidInventory() throws Exception {
        Path asc = temp.resolve("mixed.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  this is not a frame
                  0.100000 1 321 Rx d 2 AA BB
                  0.200000 garbage
                  0.300000 3 18FF10A2x Rx d 8 01 02 03 04 05 06 07 08
                End TriggerBlock
                """);

        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        CanSourceInventory result = service.inventory(
                new AscFileReader(), asc, ReaderContext.ofBatchSize(8), inv -> { });
        assertEquals(CanInventoryStatus.INVENTORY_READY, result.status());
        assertEquals(2, result.totalCanFrames());
        assertEquals(2, result.totalCanChannels());
        assertTrue(result.channels().stream().anyMatch(c -> c.channelId() == 1));
        assertTrue(result.channels().stream().anyMatch(c -> c.channelId() == 3));
    }

    @Test
    void standardAndExtendedSameNumericIdRemainDistinct() throws Exception {
        Path asc = temp.resolve("std-ext.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 123 Rx d 1 01
                  0.200000 1 123x Rx d 1 02
                End TriggerBlock
                """);
        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        CanSourceInventory result = service.inventory(
                new AscFileReader(), asc, ReaderContext.ofBatchSize(8), inv -> { });
        assertEquals(1, result.totalCanChannels());
        assertEquals(2, result.totalUniqueMessageIds());
        assertEquals(1, result.channels().getFirst().standardMessageCount());
        assertEquals(1, result.channels().getFirst().extendedMessageCount());
    }

    @Test
    void outOfRangeIdentifierIsDiagnosedAndValidFramesRemain() throws Exception {
        Path asc = temp.resolve("range.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026
                  0.100000 1 100 Rx d 1 AA
                  1.000000 3 62000230 Rx d 5 00 00 00 E0 05
                  2.000000 1 200 Rx d 1 BB
                End TriggerBlock
                """);
        SourceInventoryService service = new SourceInventoryService(new ParserRegistry(List.of()), 1);
        CanSourceInventory result = service.inventory(
                new AscFileReader(), asc, ReaderContext.ofBatchSize(8), inv -> { });
        assertEquals(CanInventoryStatus.INVENTORY_READY, result.status());
        assertEquals(2, result.totalCanFrames());
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.code() == com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode.MALFORMED_RECORD
                        && d.message().contains("62000230")));
    }
}
