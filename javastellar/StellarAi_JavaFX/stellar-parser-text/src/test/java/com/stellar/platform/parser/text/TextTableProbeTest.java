package com.stellar.platform.parser.text;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTableProbeTest {

    @TempDir
    Path temp;

    @Test
    void csvDatAndTsvLogAreAccepted() throws Exception {
        Path csv = temp.resolve("table.dat");
        Files.writeString(csv, """
                time_s,Speed (m/s)
                0.0,1.5
                0.1,1.6
                0.2,1.7
                """);
        Path tsv = temp.resolve("table.log");
        Files.writeString(tsv, "time_ms\tSpeed (km/h)\n0\t10\n10\t11\n20\t12\n");
        assertEquals("Delimited text", TextTableProbe.probe(csv).orElseThrow().formatName());
        assertEquals("HIGH", TextTableProbe.layout(csv).orElseThrow().confidence());
        assertEquals("Delimited text", TextTableProbe.probe(tsv).orElseThrow().formatName());

        List<CanonicalMeasurementSample> samples = new ArrayList<>();
        new TextTableReader().readFramesAndSignals(csv, ReaderContext.ofBatchSize(8), frames -> { }, samples::addAll, warning -> { });
        assertEquals(3, samples.size());
        assertEquals("Speed", samples.get(0).signalName());
        assertEquals("m/s", samples.get(0).unit());
        assertEquals(0L, samples.get(0).timestampNanos());
        assertEquals(100_000_000L, samples.get(1).timestampNanos());
    }

    @Test
    void structuredLogRequiresAnExplicitTimeUnit() throws Exception {
        Path file = temp.resolve("trace.log");
        Files.writeString(file, """
                0.100s Speed=1.5
                0.200s Speed=1.6
                0.300s Speed=1.7
                """);
        assertEquals("Structured text", TextTableProbe.probe(file).orElseThrow().formatName());
        List<CanonicalMeasurementSample> samples = new ArrayList<>();
        new TextTableReader().readFramesAndSignals(file, ReaderContext.ofBatchSize(8), frames -> { }, samples::addAll, warning -> { });
        assertEquals(3, samples.size());
        assertEquals(100_000_000L, samples.get(0).timestampNanos());
        assertEquals("", samples.get(0).unit());
    }

    @Test
    void randomTextAndAmbiguousNumbersAreRejected() throws Exception {
        Path text = temp.resolve("notes.dat");
        Files.writeString(text, "hello world\nthis is not a measurement\n1 2 3\n");
        assertTrue(TextTableProbe.probe(text).isEmpty());

        Path numbers = temp.resolve("numbers.log");
        Files.writeString(numbers, "1,2,3\n4,5,6\n7,8,9\n10,11,12\n");
        assertTrue(TextTableProbe.probe(numbers).isEmpty());

        Path binary = temp.resolve("blob.dat");
        Files.write(binary, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
        assertTrue(TextTableProbe.probe(binary).isEmpty());
    }
}
