package com.stellar.platform.desktop.processing;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.ContainerFormat;
import com.stellar.platform.processing.DetectionConfidence;
import com.stellar.platform.processing.MeasurementReaderRegistry;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectMeasurementIngestionTest {

    @TempDir
    Path temp;

    @Test
    void datAndLogDoNotRequireDbc() {
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_DAT));
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_LOG));
        assertTrue(MeasurementSourceCapability.supportsDirectProcessing(FileKind.MEASUREMENT_DAT));
        assertTrue(MeasurementSourceCapability.supportsDirectProcessing(FileKind.MEASUREMENT_LOG));
        assertTrue(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_MF4));
        assertTrue(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_ASC));
        assertTrue(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_BLF));
    }

    @Test
    void readerFollowsContentAndSignalsReachTheGraphCatalog() throws Exception {
        Path dat = temp.resolve("capture.dat");
        Files.write(dat, mdf320());
        MeasurementReaderRegistry registry = MeasurementReaderRegistry.load();
        var detection = registry.detect(dat);
        assertEquals(ContainerFormat.MDF, detection.containerFormat());
        assertEquals("3.20", detection.version());
        assertEquals(DetectionConfidence.HIGH, detection.confidence());
        assertEquals("mf4", registry.findReader(dat, detection).orElseThrow().formatKey());

        WorkspaceFile file = new WorkspaceFile(dat, FileKind.MEASUREMENT_DAT, Files.size(dat));
        file.setStatus(FileValidationStatus.READY, "Direct processing. DBC is not required.");
        LocalProcessingGateway.GatewayResult result = LocalProcessingGateway.createDefault().process(
                file,
                null,
                32,
                new SignalPreviewBuffer(100),
                ProcessingProgressListener.NOOP,
                null
        );
        try {
            assertEquals(ProjectProcessingStatus.COMPLETED, result.summary().completionStatus());
            assertTrue(result.summary().userMessage().startsWith("Direct processing"));
            SignalCatalogEntry latitude = result.catalogStore().snapshot().entries().stream()
                    .filter(entry -> entry.signalName().equals("Latitude"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(SignalSourceType.MF4_NATIVE, latitude.sourceType());
            assertEquals(InMemorySignalCatalogStore.DIRECT_MEASUREMENT_MESSAGE, latitude.messageName());
            assertEquals(dat.getFileName().toString(), latitude.sourceFile());
            assertEquals("degrees", latitude.unit());
            assertTrue(result.catalogStore().querySamples(latitude.key(), 0, 10).size() >= 1);
        } finally {
            if (result.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    @Test
    void csvDatNormalizesAndRandomTextFailsSafely() throws Exception {
        Path csv = temp.resolve("table.dat");
        Files.writeString(csv, "time_s,Speed (m/s)\n0.0,1.5\n0.1,1.6\n0.2,1.7\n");
        MeasurementReaderRegistry registry = MeasurementReaderRegistry.load();
        var detection = registry.detect(csv);
        assertEquals(ContainerFormat.DELIMITED_TEXT, detection.containerFormat());
        assertEquals("text", registry.findReader(csv, detection).orElseThrow().formatKey());

        WorkspaceFile file = new WorkspaceFile(csv, FileKind.MEASUREMENT_DAT, Files.size(csv));
        LocalProcessingGateway.GatewayResult result = LocalProcessingGateway.createDefault().process(
                file, null, 16, new SignalPreviewBuffer(20), ProcessingProgressListener.NOOP, null);
        try {
            assertEquals(ProjectProcessingStatus.COMPLETED, result.summary().completionStatus());
            assertTrue(result.catalogStore().snapshot().entries().stream()
                    .anyMatch(entry -> entry.signalName().equals("Speed") && "m/s".equals(entry.unit())));
        } finally {
            if (result.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }

        Path notes = temp.resolve("notes.log");
        Files.writeString(notes, "this is a workshop note\nno table here\n");
        WorkspaceFile unknown = new WorkspaceFile(notes, FileKind.MEASUREMENT_LOG, Files.size(notes));
        unknown.setStatus(FileValidationStatus.UNSUPPORTED, "StellarAI could not determine the measurement structure automatically.");
        LocalProcessingGateway.GatewayResult failed = LocalProcessingGateway.createDefault().process(
                unknown, null, 16, new SignalPreviewBuffer(20), ProcessingProgressListener.NOOP, null);
        assertEquals(ProjectProcessingStatus.FAILED, failed.summary().completionStatus());
        assertTrue(failed.summary().userMessage().contains("could not determine the measurement structure"));
        assertTrue(failed.catalogStore() == null);
    }

    private static byte[] mdf320() {
        ByteBuffer buf = ByteBuffer.allocate(832).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = new byte[64];
        writeAscii(id, 0, "MDF     ");
        writeAscii(id, 8, "3.20");
        ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 320);
        buf.put(0, id);
        buf.put(64, (byte) 'H');
        buf.put(65, (byte) 'D');
        buf.putInt(68, 100);
        buf.put(100, (byte) 'D');
        buf.put(101, (byte) 'G');
        buf.putInt(108, 140);
        buf.putInt(116, 800);
        buf.put(140, (byte) 'C');
        buf.put(141, (byte) 'G');
        buf.putInt(148, 180);
        buf.putShort(160, (short) 16);
        buf.putInt(162, 2);
        writeChannel(buf, 180, 400, 620, 1, "time", 0);
        writeChannel(buf, 400, 0, 700, 0, "Latitude", 64);
        writeConversion(buf, 620, "s");
        writeConversion(buf, 700, "degrees");
        buf.putDouble(800, 0.1);
        buf.putDouble(808, 18.5);
        buf.putDouble(816, 0.2);
        buf.putDouble(824, 18.6);
        return buf.array();
    }

    private static void writeChannel(ByteBuffer buf, int offset, int next, int cc, int type, String name, int bitOffset) {
        buf.put(offset, (byte) 'C');
        buf.put(offset + 1, (byte) 'N');
        buf.putShort(offset + 2, (short) 192);
        buf.putInt(offset + 4, next);
        buf.putInt(offset + 8, cc);
        buf.putShort(offset + 24, (short) type);
        buf.put(offset + 26, name.getBytes(StandardCharsets.US_ASCII));
        buf.putShort(offset + 186, (short) bitOffset);
        buf.putShort(offset + 188, (short) 64);
        buf.putShort(offset + 190, (short) 3);
    }

    private static void writeConversion(ByteBuffer buf, int offset, String unit) {
        buf.put(offset, (byte) 'C');
        buf.put(offset + 1, (byte) 'C');
        buf.putShort(offset + 2, (short) 64);
        buf.put(offset + 22, unit.getBytes(StandardCharsets.US_ASCII));
        buf.putShort(offset + 42, (short) 0);
    }

    private static void writeAscii(byte[] raw, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, raw, offset, bytes.length);
    }
}
