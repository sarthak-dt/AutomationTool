package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.export.AlignedSignalCsvExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live check of the Tata Sierra measurement that produced the 18,007-row workspace pages.
 * Reference CSVs are the pre-existing extracts beside that measurement. They are not a
 * live asammdf run.
 */
class TataSierraWorkspaceQaTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final double SCALE = 0.0218204013122759;
    private static final double OFFSET = -60.0;
    private static final double PHYSICAL_TOLERANCE = 1e-6;
    private static final double TIMESTAMP_TOLERANCE_SECONDS = 1e-6;

    private static final String[][] REFERENCE_FILES = {
            {"CH01_FRONT_BUMPER_GRILL_AMBIENT", "asm.ChannelGroup_0_CAN2_-_message_DEVICE_56606576_0_0xA_EXT=False.csv"},
            {"CH05_NEAR_TO_TURBO_AIR_TEMP", "asm.ChannelGroup_1_CAN2_-_message_DEVICE_56606576_1_0xB_EXT=False.csv"},
            {"CH09_REAR_RH_DAMPER", "asm.ChannelGroup_2_CAN2_-_message_DEVICE_56606576_2_0xC_EXT=False.csv"},
            {"CH13_EXTRA", "asm.ChannelGroup_3_CAN2_-_message_DEVICE_56606576_3_0xD_EXT=False.csv"}
    };

    @TempDir
    Path temp;

    @Test
    @Timeout(180)
    void paginationAndCsvMatchTheSierraMeasurement() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC), "Tata Sierra MF4 and DBC are not on this machine");

        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            ProjectProcessingStatus status = processed.summary().completionStatus();
            assertTrue(status == ProjectProcessingStatus.COMPLETED
                            || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS,
                    processed.summary().userMessage());
            SignalSeriesStore store = processed.catalogStore();
            List<SignalCatalogEntry> entries = store.snapshot().entries().stream()
                    .sorted(Comparator.comparing(SignalCatalogEntry::signalName))
                    .toList();
            Map<String, SignalCatalogEntry> byName = new LinkedHashMap<>();
            for (SignalCatalogEntry entry : entries) {
                byName.put(entry.signalName(), entry);
            }

            Map<String, List<String[]>> reference = new LinkedHashMap<>();
            for (String[] spec : REFERENCE_FILES) {
                reference.put(spec[0], readCsv(ROOT.resolve(spec[1])));
            }
            StringBuilder report = new StringBuilder();
            report.append("status=").append(status).append(' ').append(processed.summary().userMessage()).append('\n');
            report.append("measurement=").append(MF4.getFileName()).append('\n');
            report.append("dbc=").append(DBC.getFileName()).append('\n');
            report.append("catalog=").append(entries.size()).append('\n');

            int compared = 0;
            int zeroPhysical = 0;
            int rawZero = 0;
            double maxPhysical = 0.0;
            double maxTimestamp = 0.0;
            int maxRawDelta = 0;
            for (List<String[]> table : reference.values()) {
                String[] header = table.getFirst();
                assertEquals(18_008, table.size(), header[1] + " reference rows include header");
                for (int column = 1; column < header.length; column++) {
                    String signalName = header[column];
                    int dot = signalName.lastIndexOf('.');
                    if (dot >= 0) {
                        signalName = signalName.substring(dot + 1);
                    }
                    SignalCatalogEntry entry = byName.get(signalName);
                    assertTrue(entry != null, "missing decoded signal " + signalName + " catalog=" + byName.keySet());
                    List<SignalSamplePoint> samples = readAll(store, entry.key());
                    assertEquals(18_007, samples.size(), entry.signalName());
                    assertTrue(entry.unit() != null && entry.unit().contains("C"),
                            entry.signalName() + " unit=" + entry.unit());
                    if ("CH01_FRONT_BUMPER_GRILL_AMBIENT".equals(entry.signalName())) {
                        report.append("unit=").append(entry.unit()).append('\n');
                    }
                    for (int index = 0; index < samples.size(); index++) {
                        String[] row = table.get(index + 1);
                        double refSeconds = Double.parseDouble(row[0]);
                        double refPhysical = Double.parseDouble(row[column]);
                        SignalSamplePoint sample = samples.get(index);
                        double stellarSeconds = sample.timestampNanos() / 1_000_000_000d;
                        double timeDelta = Math.abs(stellarSeconds - refSeconds);
                        double valueDelta = Math.abs(sample.physicalValue() - refPhysical);
                        maxTimestamp = Math.max(maxTimestamp, timeDelta);
                        maxPhysical = Math.max(maxPhysical, valueDelta);
                        long expectedRaw = Math.round((sample.physicalValue() - OFFSET) / SCALE);
                        int rawDelta = sample.rawValue() == null
                                ? Integer.MAX_VALUE
                                : (int) Math.abs(sample.rawValue() - expectedRaw);
                        maxRawDelta = Math.max(maxRawDelta, rawDelta);
                        if (sample.physicalValue() == 0.0 || refPhysical == 0.0) {
                            zeroPhysical++;
                        }
                        if (sample.rawValue() != null && sample.rawValue() == 0L) {
                            rawZero++;
                        }
                        compared++;
                        assertTrue(timeDelta <= TIMESTAMP_TOLERANCE_SECONDS,
                                entry.signalName() + " t[" + index + "] stellar=" + stellarSeconds + " ref=" + refSeconds);
                        assertTrue(valueDelta <= PHYSICAL_TOLERANCE,
                                entry.signalName() + " v[" + index + "] stellar=" + sample.physicalValue() + " ref=" + refPhysical);
                        assertEquals(expectedRaw, sample.rawValue(), entry.signalName() + " raw[" + index + "]");
                    }
                }
            }
            report.append("signalsCompared=16 samplesEach=18007 probes=").append(compared).append('\n');
            report.append("maxTimestampDeltaSeconds=").append(maxTimestamp).append('\n');
            report.append("maxPhysicalDelta=").append(maxPhysical).append('\n');
            report.append("maxRawDelta=").append(maxRawDelta).append('\n');
            report.append("rawZeroSamples=").append(rawZero).append('\n');
            report.append("physicalZeroSamples=").append(zeroPhysical).append('\n');

            SignalCatalogEntry ch01 = byName.get("CH01_FRONT_BUMPER_GRILL_AMBIENT");
            SignalCatalogEntry ch04 = byName.get("CH04_HOOD_STAY_ROD");
            SignalCatalogEntry ch05 = byName.get("CH05_NEAR_TO_TURBO_AIR_TEMP");
            List<SignalCatalogKey> group = reference.get("CH01_FRONT_BUMPER_GRILL_AMBIENT").getFirst().length > 1
                    ? names(byName, reference.get("CH01_FRONT_BUMPER_GRILL_AMBIENT").getFirst())
                    : List.of();
            pageBoundaries(store, group, 5_000, 18_007, report);

            List<SignalCatalogKey> selected = List.of(ch01.key(), ch05.key());
            long selectedRows = export(store, List.of(ch01, ch05), temp.resolve("selected.csv"));
            long union = unionCount(readAll(store, ch01.key()), readAll(store, ch05.key()));
            assertEquals(union, selectedRows, "selected export must follow the timestamp union");
            assertTrue(selectedRows > 18_007, "different channel groups must not collapse onto one timeline");
            assertGapIsBlank(temp.resolve("selected.csv"), readAll(store, ch01.key()).getFirst().timestampNanos());
            report.append("selectedExportRows=").append(selectedRows).append(" union=").append(union).append('\n');

            List<SignalCatalogEntry> decoded = new ArrayList<>();
            for (List<String[]> table : reference.values()) {
                for (int column = 1; column < table.getFirst().length; column++) {
                    String signalName = table.getFirst()[column];
                    int dot = signalName.lastIndexOf('.');
                    if (dot >= 0) {
                        signalName = signalName.substring(dot + 1);
                    }
                    decoded.add(byName.get(signalName));
                }
            }
            long dbcRows = export(store, decoded, temp.resolve("dbc.csv"));
            long started = System.nanoTime();
            long allRows = export(store, entries, temp.resolve("all.csv"));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            List<String> allLines = Files.readAllLines(temp.resolve("all.csv"));
            assertEquals(allRows + 1, allLines.size());
            assertTrue(allLines.get((int) allRows).startsWith((allRows - 1) + ","));
            assertTrue(allRows >= dbcRows);
            report.append("dbcExportRows=").append(dbcRows).append('\n');
            report.append("allExportRows=").append(allRows).append(" elapsedMs=").append(elapsedMs).append('\n');
            for (SignalCatalogEntry entry : entries) {
                if (!entry.signalName().startsWith("CH")) {
                    report.append("native ").append(entry.signalName())
                            .append(" count=").append(store.retainedSampleCount(entry.key()))
                            .append(" unit=").append(entry.unit()).append('\n');
                }
            }
            report.append("groupSignals=").append(group.size()).append(" ch04=").append(ch04.signalName()).append('\n');

            Files.writeString(Path.of("target/tata-sierra-qa.txt"), report.toString());
            System.out.println(report);
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static void pageBoundaries(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            int pageSize,
            int total,
            StringBuilder report
    ) throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        try {
            model.replaceCatalog(store);
            model.setSamplePageSize(pageSize);
            model.addToWorkspace(keys);
            AlignedWorkspaceSnapshot page = await(model, true, null);
            int start = 0;
            while (true) {
                int end = start + page.rows().size() - 1;
                assertEquals(start, page.windowStartIndex(), "page start");
                assertEquals(start, page.rows().getFirst().index());
                assertEquals(end, page.rows().getLast().index());
                report.append("page ").append(start).append('-').append(end)
                        .append(" rows=").append(page.rows().size()).append('\n');
                if (!page.hasNext()) {
                    assertEquals(total - 1, end);
                    break;
                }
                assertEquals(pageSize, page.rows().size());
                final int origin = page.windowStartIndex();
                page = await(model, false, true);
                assertEquals(origin + pageSize, page.windowStartIndex());
                start = page.windowStartIndex();
            }
            long jumpIndex = 15_000L;
            AlignedWorkspaceSnapshot jumped = await(model, false, jumpIndex);
            assertEquals(15_000, jumped.windowStartIndex());
            assertEquals(15_000L, jumped.rows().getFirst().index());
            assertEquals(18_006L, jumped.rows().getLast().index());
            report.append("indexJump=15000 window=")
                    .append(jumped.windowStartIndex()).append('-')
                    .append(jumped.rows().getLast().index()).append('\n');
        } finally {
            model.shutdown();
        }
    }

    private static AlignedWorkspaceSnapshot await(SignalsScreenModel model, boolean load, Object request) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        if (load) {
            model.loadWorkspaceAlignedAsync(true, snap -> {
                ref.set(snap);
                done.countDown();
            });
        } else if (request instanceof Boolean next && next) {
            model.navigateNextWindowAsync(snap -> {
                ref.set(snap);
                done.countDown();
            });
        } else {
            long index = (Long) request;
            var err = model.jumpToIndexAsync(index, snap -> {
                ref.set(snap);
                done.countDown();
            });
            assertTrue(err.isEmpty(), err.orElse(""));
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        return ref.get();
    }

    private static long export(SignalSeriesStore store, List<SignalCatalogEntry> columns, Path destination) throws Exception {
        AlignedSignalCsvExporter.ExportResult result = new AlignedSignalCsvExporter().export(
                store, columns, AlignedSignalCsvExporter.ValueMode.PHYSICAL, destination, new java.util.concurrent.atomic.AtomicBoolean());
        assertTrue(!result.cancelled());
        return result.rowCount();
    }

    private static void assertGapIsBlank(Path csv, long firstCh01Nanos) throws Exception {
        List<String> lines = Files.readAllLines(csv);
        String match = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("," + firstCh01Nanos + ",")) {
                match = line;
                break;
            }
        }
        assertTrue(match != null, "selected export missing CH01 first timestamp");
        String[] cells = match.split(",", -1);
        assertEquals(4, cells.length);
        assertTrue(!cells[2].isBlank(), "CH01 value missing at its own timestamp");
        assertTrue(cells[3].isBlank(), "CH05 was filled at a CH01 timestamp: " + match);
    }

    private static long unionCount(List<SignalSamplePoint> left, List<SignalSamplePoint> right) {
        int i = 0;
        int j = 0;
        long count = 0;
        while (i < left.size() || j < right.size()) {
            long a = i < left.size() ? left.get(i).timestampNanos() : Long.MAX_VALUE;
            long b = j < right.size() ? right.get(j).timestampNanos() : Long.MAX_VALUE;
            long next = Math.min(a, b);
            count++;
            if (a == next) {
                i++;
            }
            if (b == next) {
                j++;
            }
        }
        return count;
    }

    private static List<SignalCatalogKey> names(Map<String, SignalCatalogEntry> byName, String[] header) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        for (int i = 1; i < header.length; i++) {
            String signalName = header[i];
            int dot = signalName.lastIndexOf('.');
            if (dot >= 0) {
                signalName = signalName.substring(dot + 1);
            }
            keys.add(byName.get(signalName).key());
        }
        return keys;
    }

    private static List<SignalSamplePoint> readAll(SignalSeriesStore store, SignalCatalogKey key) {
        int retained = store.retainedSampleCount(key);
        List<SignalSamplePoint> all = new ArrayList<>(Math.max(16, retained));
        int offset = 0;
        while (offset < retained) {
            List<SignalSamplePoint> page = store.querySamples(key, offset, 5_000);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            offset += page.size();
        }
        return all;
    }

    private static List<String[]> readCsv(Path path) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) {
                rows.add(line.split(",", -1));
            }
        }
        return rows;
    }
}
