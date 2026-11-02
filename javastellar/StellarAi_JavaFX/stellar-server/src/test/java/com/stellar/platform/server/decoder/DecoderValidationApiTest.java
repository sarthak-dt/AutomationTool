package com.stellar.platform.server.decoder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecoderValidationApiTest {

    private static final Path ROOT = createRoot();
    private static final Path DATABASE = ROOT.resolve("database").resolve("stellar.db");
    private static final String MUX_DBC = """
            VERSION ""
            NS_ :
            BS_:
            BU_: ECU
            BO_ 1552 PowerMux: 8 ECU
             SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
             SG_ Voltage : 8|8@1+ (0.1,0) [0|25.5] "V" ECU
             SG_ Speed m0 : 16|16@1+ (0.5,0) [0|1000] "km/h" ECU
             SG_ Current m1 : 16|16@1- (0.1,-40) [-400|400] "A" ECU
             SG_ Torque m2 : 31|8@0+ (1,-10) [-10|245] "Nm" ECU
            VAL_ 1552 Selector 0 "Sleep" 1 "Drive" 2 "Charge" ;
            BA_DEF_ SG_ "GenSigInvalidValue" INT 0 0;
            BA_DEF_ SG_ "GenSigInactiveValue" INT 0 0;
            BA_ "GenSigInvalidValue" SG_ 1552 Speed 65535;
            BA_ "GenSigInactiveValue" SG_ 1552 Current 32767;
            """;
    private static final String[][] MUX_FRAMES = {
            {"100000000", "00 7D 64 00"},
            {"200000000", "00 7D 64 00"},
            {"300000000", "01 7D 38 FF"},
            {"400000000", "02 7D 00 32"},
            {"500000000", "09 7D 11 22"},
            {"600000000", "00 7D FF FF"},
            {"700000000", "01 7D FF 7F"},
            {"800000000", "00"},
            {"900000000", ""}
    };

    private static Path muxAsc;
    private static Path muxDbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("stellar.decoder.validation-api", () -> "true");
        registry.add("stellar.decoder.application-version", () -> "1.0.4");
        registry.add("stellar.decoder.metadata-db", () -> DATABASE.toString());
        registry.add("stellar.decoder.allowed-roots", () -> ROOT.toString());
        registry.add("stellar.decoder.session-directory", () -> ROOT.resolve("sessions").toString());
        registry.add("stellar.decoder.max-page-size", () -> "10000");
        registry.add("stellar.decoder.default-page-size", () -> "10000");
        registry.add("stellar.decoder.cursor-ttl-seconds", () -> "600");
        registry.add("stellar.decoder.max-concurrent-decodes", () -> "2");
        registry.add("stellar.decoder.max-snapshots", () -> "16");
        registry.add("stellar.decoder.max-decoded-samples", () -> "1000000");
        registry.add("stellar.decoder.decode-start-delay-ms", () -> "0");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ParserRegistry parserRegistry;

    @Autowired
    private DecoderValidationService service;

    @Autowired
    private RequestMappingHandlerMapping mappings;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void fixtures() throws Exception {
        Files.createDirectories(DATABASE.getParent());
        Files.createDirectories(ROOT.resolve("sessions"));
        muxDbc = ROOT.resolve("power.dbc");
        muxAsc = ROOT.resolve("power.asc");
        Files.writeString(muxDbc, MUX_DBC);
        Files.writeString(muxAsc, muxAsc(MUX_FRAMES));
    }

    @AfterAll
    static void cleanup() throws Exception {
        deleteTree(ROOT);
    }

    @Test
    void onlyTheValidateRouteIsExposed() {
        long decoderRoutes = mappings.getHandlerMethods().keySet().stream()
                .filter(info -> info.getDirectPaths().stream().anyMatch(path -> path.contains("/decoder")))
                .count();
        assertEquals(1, decoderRoutes);
        ResponseEntity<String> get = rest.getForEntity(url(), String.class);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, get.getStatusCode());
    }

    @Test
    void rejectsMissingAndConflictingSources() throws Exception {
        ResponseEntity<String> neither = post(Map.of());
        assertError(neither, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        ResponseEntity<String> both = post(Map.of(
                "projectName", "Vehicle_Validation_01",
                "filePath", muxAsc.toString()));
        assertError(both, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        ResponseEntity<String> page = post(Map.of("filePath", muxAsc.toString(), "dbcPath", muxDbc.toString(), "pageSize", 0));
        assertError(page, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        ResponseEntity<String> huge = post(Map.of("filePath", muxAsc.toString(), "dbcPath", muxDbc.toString(), "pageSize", 10001));
        assertError(huge, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @Test
    void rejectsPathsOutsideTheAllowListAndMissingOrUnsupportedFiles() throws Exception {
        Path outside = ROOT.getParent().resolve("decoder-outside-" + UUID.randomUUID());
        Files.createDirectories(outside);
        Path secret = outside.resolve("secret.asc");
        Files.writeString(secret, "not a measurement");
        try {
            assertError(post(Map.of("filePath", secret.toString(), "dbcPath", muxDbc.toString())),
                    HttpStatus.FORBIDDEN, "PATH_NOT_ALLOWED");
            Path traversed = ROOT.resolve("..").resolve(outside.getFileName()).resolve("secret.asc");
            assertError(post(Map.of("filePath", traversed.toString(), "dbcPath", muxDbc.toString())),
                    HttpStatus.FORBIDDEN, "PATH_NOT_ALLOWED");
            assertError(post(Map.of("filePath", ROOT.resolve("missing.asc").toString(), "dbcPath", muxDbc.toString())),
                    HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
            Path text = ROOT.resolve("notes.txt");
            Files.writeString(text, "hello");
            assertError(post(Map.of("filePath", text.toString(), "dbcPath", muxDbc.toString())),
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FORMAT");
            assertError(post(Map.of("filePath", muxAsc.toString())),
                    HttpStatus.UNPROCESSABLE_ENTITY, "DBC_REQUIRED");
            Path badDbc = ROOT.resolve("malformed.dbc");
            Files.writeString(badDbc, "VERSION \"\"\nBO_ this_is_not_valid: 8 ECU\n SG_ Bad : broken\n");
            assertError(post(Map.of("filePath", muxAsc.toString(), "dbcPath", badDbc.toString())),
                    HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DBC");
            assertFalse(neitherExposesPath(post(Map.of("filePath", secret.toString()))));
        } finally {
            deleteTree(outside);
        }
    }

    @Test
    void freshDecodeReturnsEveryMuxSampleWithoutMergingBranches() throws Exception {
        Map<String, Object> all = ok(post(Map.of(
                "filePath", muxAsc.toString(),
                "dbcPath", muxDbc.toString())));
        assertEquals("2.0", all.get("schemaVersion"));
        assertEquals("COMPLETED", all.get("status"));
        assertEquals("FILE", all.get("sourceType"));
        assertEquals(List.of("power.asc"), all.get("measurementFiles"));
        assertEquals(List.of("power.dbc"), all.get("dbcFiles"));
        assertTrue(all.get("signals") instanceof Map<?, ?>);
        assertColumnLengths(all);
        assertEquals("FRESH_DECODE", all.get("resultOrigin"));
        assertEquals("1.0.4", all.get("applicationVersion"));
        assertEquals("3", all.get("decoderVersion"));
        assertEquals(Boolean.TRUE, all.get("paginationComplete"));
        assertNull(all.get("nextCursor"));
        assertFalse(mapper.writeValueAsString(all).matches("(?s).*[:]\\s*NaN\\b.*"));
        Map<String, List<Map<String, Object>>> byName = byName(all);
        assertEquals(3, byName.get("Speed").size());
        assertEquals(2, byName.get("Current").size());
        assertEquals(1, byName.get("Torque").size());
        assertEquals(8, byName.get("Selector").size());
        assertEquals("Sleep", byName.get("Selector").getFirst().get("textualValue"));
        assertEquals("0", text(byName.get("Selector").getFirst(), "raw"));
        assertTrue(byName.get("Speed").stream().noneMatch(sample -> "300000000".equals(sample.get("timestampNs"))));
        Map<String, Object> invalid = byName.get("Speed").stream()
                .filter(sample -> "600000000".equals(sample.get("timestampNs")))
                .findFirst()
                .orElseThrow();
        assertEquals("INVALID_RAW_CODE", invalid.get("validity"));
        assertEquals("invalid", invalid.get("textualValue"));
        assertEquals("65535", text(invalid, "raw"));
        assertEquals("32767.5", text(invalid, "physical"));
        Map<String, Object> inactive = byName.get("Current").stream()
                .filter(sample -> "700000000".equals(sample.get("timestampNs")))
                .findFirst()
                .orElseThrow();
        assertEquals("INACTIVE_RAW_CODE", inactive.get("validity"));
        assertEquals("inactive", inactive.get("textualValue"));
        assertNotEquals("0", text(inactive, "physical"));

        Map<String, Object> one = ok(post(Map.of(
                "filePath", muxAsc.toString(),
                "dbcPath", muxDbc.toString(),
                "signal", "Speed")));
        assertEquals(1, signalList(one).size());
        assertEquals(3, samples(signalList(one).getFirst()).size());
        assertEquals(number(one.get("catalogSignalCount")) > 1, true);
    }

    @Test
    void paginationDoesNotDropOrRepeatSamplesAcrossSignals() throws Exception {
        Map<String, Object> first = ok(post(Map.of(
                "filePath", muxAsc.toString(),
                "dbcPath", muxDbc.toString(),
                "pageSize", 4)));
        assertEquals(Boolean.FALSE, first.get("paginationComplete"));
        assertNotNull(first.get("nextCursor"));
        List<String> seen = new ArrayList<>();
        collect(first, seen);
        Map<String, Object> page = first;
        while (page.get("nextCursor") != null) {
            page = ok(post(Map.of("cursor", page.get("nextCursor"), "pageSize", 4)));
            collect(page, seen);
        }
        assertEquals(Boolean.TRUE, page.get("paginationComplete"));
        assertEquals(number(first.get("totalSampleCount")), seen.size());
        assertEquals(seen.size(), seen.stream().distinct().count());
        assertEquals(HttpStatus.GONE, post(Map.of("cursor", first.get("nextCursor"))).getStatusCode());
    }

    @Test
    void largePageIsNotCappedAtThePreviewLimit() throws Exception {
        Path dbc = ROOT.resolve("speed.dbc");
        Path asc = ROOT.resolve("speed-large.asc");
        Files.writeString(dbc, """
                VERSION ""
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineSpeed : 0|16@1+ (1,0) [0|65535] "rpm" ECU
                """);
        int count = 8_000;
        StringBuilder ascText = new StringBuilder();
        ascText.append("date Thu Jan 01 12:00:00.000 am 2026\nbase hex timestamps absolute\n");
        for (int i = 0; i < count; i++) {
            long nanos = i * 1_000_000L;
            ascText.append(String.format("  %d.%09d 1 100 Rx d 2 10 00%n",
                    nanos / 1_000_000_000L, nanos % 1_000_000_000L));
        }
        Files.writeString(asc, ascText.toString());
        Map<String, Object> first = ok(post(Map.of(
                "filePath", asc.toString(), "dbcPath", dbc.toString(), "pageSize", 6000, "signal", "EngineSpeed")));
        assertEquals(6000, number(first.get("returnedSampleCount")));
        assertEquals(count, number(first.get("totalSampleCount")));
        List<Map<String, Object>> firstSamples = samples(signalList(first).getFirst());
        assertEquals("0", firstSamples.getFirst().get("sampleIndex"));
        assertEquals("5999", firstSamples.get(5999).get("sampleIndex"));
        Map<String, Object> second = ok(post(Map.of("cursor", first.get("nextCursor"))));
        assertEquals(2000, number(second.get("returnedSampleCount")));
        assertEquals(Boolean.TRUE, second.get("paginationComplete"));
        assertEquals("6000", samples(signalList(second).getFirst()).getFirst().get("sampleIndex"));
        assertEquals("7999", samples(signalList(second).getFirst()).get(1999).get("sampleIndex"));
        assertTrue(Files.list(ROOT.resolve("sessions")).findAny().isEmpty());
    }

    @Test
    void duplicateTimestampsEnumsExtendedMuxAndNonFiniteValuesArePreserved() throws Exception {
        Path dupDbc = ROOT.resolve("dup.dbc");
        Path dupAsc = ROOT.resolve("dup.asc");
        Files.writeString(dupDbc, """
                VERSION ""
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineSpeed : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
                """);
        Files.writeString(dupAsc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000000 1 100 Rx d 2 DE AD
                  0.100000000 1 100 Rx d 2 DE AD
                """);
        Map<String, Object> dup = ok(post(Map.of("filePath", dupAsc.toString(), "dbcPath", dupDbc.toString())));
        List<Map<String, Object>> dupSamples = samples(signalList(dup).getFirst());
        assertEquals(2, dupSamples.size());
        assertEquals(dupSamples.get(0).get("timestampNs"), dupSamples.get(1).get("timestampNs"));
        assertEquals("0", dupSamples.get(0).get("sampleIndex"));
        assertEquals("1", dupSamples.get(1).get("sampleIndex"));

        Path tableDbc = ROOT.resolve("table.dbc");
        Path tableAsc = ROOT.resolve("table.asc");
        Files.writeString(tableDbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                VAL_TABLE_ GroupState 0 "Sleep" 1 "Drive" ;
                BO_ 100 StateMsg: 1 ECU
                 SG_ State : 0|8@1+ (1,0) [0|1] "" ECU
                SGTYPE_ GroupType : 8@1+ (1,0) [0|1] "" 0 , GroupState ;
                SIG_TYPE_REF_ 100 State : GroupType ;
                BO_ 200 LabelMsg: 1 ECU
                 SG_ Label : 0|8@1+ (1,0) [0|1] "" ECU
                VAL_ 200 Label 0 "OFF"
                1 "ON" ;
                """);
        Files.writeString(tableAsc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 64 Rx d 1 00
                  0.200000 1 C8 Rx d 1 01
                """);
        Map<String, Object> table = ok(post(Map.of("filePath", tableAsc.toString(), "dbcPath", tableDbc.toString())));
        Map<String, List<Map<String, Object>>> named = byName(table);
        assertEquals("Sleep", named.get("State").getFirst().get("textualValue"));
        assertEquals("ON", named.get("Label").getFirst().get("textualValue"));

        Path muxDbc = ROOT.resolve("extmux.dbc");
        Path muxFile = ROOT.resolve("extmux.asc");
        Files.writeString(muxDbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_:
                BO_ 256 MuxMsg: 2 Vector__XXX
                 SG_ Mode M : 0|8@1+ (1,0) [0|255] "" Vector__XXX
                 SG_ Temperature m1 : 8|8@1+ (1,0) [0|255] "C" Vector__XXX
                 SG_ Speed m2 : 8|8@1+ (1,0) [0|255] "km/h" Vector__XXX
                 SG_ Pressure m10 : 8|8@1+ (1,0) [0|255] "kPa" Vector__XXX
                SG_MUL_VAL_ 256 Temperature Mode 1-1;
                SG_MUL_VAL_ 256 Speed Mode 2-2;
                SG_MUL_VAL_ 256 Pressure Mode 10-12;
                """);
        Files.writeString(muxFile, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 2 01 2A
                  0.200000 1 100 Rx d 2 02 64
                  0.300000 1 100 Rx d 2 03 55
                """);
        Map<String, Object> extended = ok(post(Map.of("filePath", muxFile.toString(), "dbcPath", muxDbc.toString())));
        Map<String, List<Map<String, Object>>> branches = byName(extended);
        assertEquals(1, branches.get("Temperature").size());
        assertEquals("42", text(branches.get("Temperature").getFirst(), "raw"));
        assertEquals(1, branches.get("Speed").size());
        assertFalse(branches.containsKey("Pressure"));
        assertEquals(3, branches.get("Mode").size());

        Path nanDbc = ROOT.resolve("nan.dbc");
        Path nanAsc = ROOT.resolve("nan.asc");
        Files.writeString(nanDbc, """
                VERSION ""
                BU_: ECU
                BO_ 320 Floats: 8 ECU
                 SG_ FloatIntel : 0|32@1- (1,0) [0|0] "" ECU
                SIG_VALTYPE_ 320 FloatIntel : 1;
                """);
        Files.writeString(nanAsc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 140 Rx d 8 00 00 C0 7F 00 00 00 00
                  0.200000 1 140 Rx d 8 00 00 80 7F 00 00 00 00
                """);
        ResponseEntity<String> nanResponse = post(Map.of("filePath", nanAsc.toString(), "dbcPath", nanDbc.toString()));
        assertEquals(HttpStatus.OK, nanResponse.getStatusCode(), nanResponse.getBody());
        assertFalse(nanResponse.getBody().matches("(?s).*[:]\\s*NaN\\b.*"));
        assertFalse(nanResponse.getBody().matches("(?s).*[:]\\s*Infinity\\b.*"));
        Map<String, Object> nan = read(nanResponse.getBody());
        List<Map<String, Object>> floats = samples(signalList(nan).getFirst());
        assertEquals("NON_FINITE_VALUE", floats.get(0).get("validity"));
        assertEquals("NaN", text(floats.get(0), "physical"));
        assertEquals("Infinity", text(floats.get(1), "physical"));
        assertEquals("2143289344", text(floats.get(0), "raw"));
    }

    @Test
    void storedProjectMatchesFreshDecodeAndIsNotRewritten() throws Exception {
        String name = "Vehicle_Validation_" + UUID.randomUUID().toString().substring(0, 8);
        Saved saved = saveProcessed(name, muxAsc, muxDbc, "stored-source", "stored-dbc");
        String dbHash = hashFile(DATABASE);
        String storeHash = hashTree(saved.store());
        Map<String, Object> stored = ok(post(Map.of("projectName", name, "signal", "Speed")));
        assertEquals("STORED", stored.get("resultOrigin"));
        assertEquals("PROJECT", stored.get("sourceType"));
        assertEquals("COMPLETED", stored.get("status"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) stored.get("processing")).get("reprocessed"));
        assertEquals(3, samples(signalList(stored).getFirst()).size());
        assertEquals("UNAVAILABLE", stored.get("datetimeAvailability"));
        assertTrue(String.valueOf(stored.get("datetimeAvailabilityReason")).contains("fingerprint"));
        assertFalse(stored.containsKey("datetimeTimezone"));
        assertFalse(stored.containsKey("datetime"));
        assertTrue(list(signalList(stored).getFirst(), "sourceDatetime").stream().allMatch(value -> value == null));
        Map<String, Object> fresh = ok(post(Map.of(
                "filePath", muxAsc.toString(), "dbcPath", muxDbc.toString(), "signal", "Speed")));
        assertEquals(identities(fresh), identities(stored));
        assertEquals(sampleFacts(fresh), sampleFacts(stored));
        Map<String, Object> allStored = ok(post(Map.of("projectName", name)));
        Map<String, Object> allFresh = ok(post(Map.of("filePath", muxAsc.toString(), "dbcPath", muxDbc.toString())));
        assertEquals(sampleFacts(allFresh), sampleFacts(allStored));
        assertEquals(dbHash, hashFile(DATABASE));
        assertEquals(storeHash, hashTree(saved.store()));
        Files.writeString(muxAsc, Files.readString(muxAsc) + "\n");
        Map<String, Object> afterChange = ok(post(Map.of("projectName", name, "signal", "Selector")));
        @SuppressWarnings("unchecked")
        Map<String, Object> source = ((List<Map<String, Object>>) afterChange.get("sources")).getFirst();
        assertEquals("stored-source", source.get("sourceFingerprint"));
        assertEquals("stored-dbc", source.get("dbcFingerprint"));
        Files.writeString(muxAsc, muxAsc(MUX_FRAMES));
        assertEquals(storeHash, hashTree(saved.store()));
    }

    @Test
    void severalRecordingsStaySeparateAndPartialProjectsAreNotCompleted() throws Exception {
        String name = "Multi_" + UUID.randomUUID();
        saveProcessed(name, muxAsc, muxDbc, "fp-a", "dbc-a");
        String secondId = addMeasurement(name, muxAsc, muxDbc, "fp-b", "dbc-b");
        assertNotNull(secondId);
        ResponseEntity<String> ambiguous = post(Map.of("projectName", name, "signal", "Selector"));
        assertError(ambiguous, HttpStatus.CONFLICT, "SIGNAL_AMBIGUOUS");
        Map<String, Object> error = read(ambiguous.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) error.get("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) err.get("details");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) details.get("candidates");
        assertEquals(2, candidates.size());
        Map<String, Object> chosen = ok(post(Map.of("projectName", name, "signal", candidates.getFirst().get("canonicalId"))));
        assertEquals(1, signalList(chosen).size());
        assertEquals(candidates.getFirst().get("canonicalId"), signalList(chosen).getFirst().get("canonicalId"));

        String partial = "Partial_" + UUID.randomUUID();
        Saved complete = saveProcessed(partial, muxAsc, muxDbc, "fp", "dbc");
        addUnprocessedMeasurement(partial, "second.asc");
        markInventoryReady(partial, "second-file");
        ResponseEntity<String> blocked = post(Map.of("projectName", partial));
        assertError(blocked, HttpStatus.CONFLICT, "PROJECT_NOT_PROCESSED");
        assertFalse(blocked.getBody().contains("\"status\":\"COMPLETED\""));
        Map<String, Object> allowed = ok(post(Map.of("projectName", partial, "includeIncomplete", true)));
        assertEquals("INCOMPLETE", allowed.get("status"));
        assertNotEquals("COMPLETED", allowed.get("status"));
        assertTrue(number(allowed.get("totalSampleCount")) > 0);
        assertEquals(complete.store().getFileName().toString(), complete.store().getFileName().toString());

        Path flagged = complete.store().resolve(GraphSignalSessionStore.INCOMPLETE_FLAG);
        Files.writeString(flagged, "incomplete");
        try {
            ResponseEntity<String> flaggedResponse = post(Map.of("projectName", partial, "includeIncomplete", true));
            assertError(flaggedResponse, HttpStatus.CONFLICT, "PROJECT_NOT_PROCESSED");
        } finally {
            Files.deleteIfExists(flagged);
        }
    }

    @Test
    void unknownAmbiguousAndUnconfiguredNamesUseTheErrorContract() throws Exception {
        assertError(post(Map.of("projectName", "missing-" + UUID.randomUUID())),
                HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND");
        String name = "Ambiguous_" + UUID.randomUUID();
        saveProcessed(name, muxAsc, muxDbc, "fp", "dbc");
        duplicateProjectRow(name);
        try {
            ResponseEntity<String> ambiguous = post(Map.of("projectName", name));
            assertError(ambiguous, HttpStatus.CONFLICT, "PROJECT_AMBIGUOUS");
            assertFalse(ambiguous.getBody().contains("stellar.db"));
        } finally {
            removeDuplicate(name);
        }
    }

    @Test
    void externalHttpClientReceivesTheSameContract() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "filePath", muxAsc.toString(),
                "dbcPath", muxDbc.toString(),
                "signal", "Torque"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/decoder/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> body = read(response.body());
        assertEquals("COMPLETED", body.get("status"));
        assertEquals("FRESH_DECODE", body.get("resultOrigin"));
        assertEquals(1, samples(signalList(body).getFirst()).size());
        service.expireCursorForTests(cursorFrom(ok(post(Map.of(
                "filePath", muxAsc.toString(), "dbcPath", muxDbc.toString(), "pageSize", 1)))));
    }

    private String cursorFrom(Map<String, Object> body) {
        assertNotNull(body.get("nextCursor"));
        String cursor = body.get("nextCursor").toString();
        service.expireCursorForTests(cursor);
        ResponseEntity<String> expired = post(Map.of("cursor", cursor));
        assertEquals(HttpStatus.GONE, expired.getStatusCode());
        return cursor;
    }

    private Saved saveProcessed(String name, Path asc, Path dbc, String sourceFingerprint, String dbcFingerprint)
            throws Exception {
        String projectId = "p-" + UUID.randomUUID();
        String fileId = "f-" + UUID.randomUUID();
        Path store = ROOT.resolve("stores").resolve(fileId);
        DbcDatabase database = new DbcParser().parse(dbc);
        long samples;
        int signals;
        try (GraphSignalSessionStore session = GraphSignalSessionStore.createPersistent(
                store, asc.getFileName().toString(), dbc.getFileName().toString())) {
            new MeasurementDecodePipeline(parserRegistry).process(
                    asc, database, ReaderContext.ofBatchSize(512), session);
            session.finishWriteSession();
            samples = session.losslessStore().totalSampleCount();
            signals = session.losslessStore().keys().size();
        }
        try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(DATABASE)) {
            metadata.upsertProject(new ProjectMetadata(projectId, name, Instant.now(), Instant.now()));
            metadata.upsertFile(new ProjectFileMetadata(
                    projectId, fileId, asc.toString(), "MEASUREMENT_ASC", Files.size(asc),
                    "READY", "", "", "", false, true, false));
            metadata.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "a-" + fileId, projectId, fileId, 0, "", "",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    store.toString(), "",
                    ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                    sourceFingerprint, dbcFingerprint,
                    signals, samples, null, null,
                    ProcessingArtifactMetadata.STATUS_VALID, Instant.now(), "",
                    "SUCCESS", "", ""));
            metadata.checkpointWal();
        }
        checkpoint(DATABASE);
        return new Saved(projectId, fileId, store);
    }

    private String addMeasurement(String projectName, Path asc, Path dbc, String sourceFingerprint, String dbcFingerprint)
            throws Exception {
        String projectId = projectId(projectName);
        String fileId = "f-" + UUID.randomUUID();
        Path store = ROOT.resolve("stores").resolve(fileId);
        DbcDatabase database = new DbcParser().parse(dbc);
        long samples;
        int signals;
        try (GraphSignalSessionStore session = GraphSignalSessionStore.createPersistent(
                store, asc.getFileName().toString(), dbc.getFileName().toString())) {
            new MeasurementDecodePipeline(parserRegistry).process(
                    asc, database, ReaderContext.ofBatchSize(512), session);
            session.finishWriteSession();
            samples = session.losslessStore().totalSampleCount();
            signals = session.losslessStore().keys().size();
        }
        try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(DATABASE)) {
            metadata.upsertFile(new ProjectFileMetadata(
                    projectId, fileId, asc.toString(), "MEASUREMENT_ASC", Files.size(asc),
                    "READY", "", "", "", false, true, false));
            metadata.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "a-" + fileId, projectId, fileId, 0, "", "",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    store.toString(), "",
                    ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                    sourceFingerprint, dbcFingerprint,
                    signals, samples, null, null,
                    ProcessingArtifactMetadata.STATUS_VALID, Instant.now(), "",
                    "SUCCESS", "", ""));
            metadata.checkpointWal();
        }
        checkpoint(DATABASE);
        return fileId;
    }

    private void addUnprocessedMeasurement(String projectName, String fileName) throws Exception {
        String projectId = projectId(projectName);
        try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(DATABASE)) {
            metadata.upsertFile(new ProjectFileMetadata(
                    projectId, "second-file", ROOT.resolve(fileName).toString(), "MEASUREMENT_ASC", 10,
                    "READY", "", "", "", false, true, false));
            metadata.checkpointWal();
        }
        checkpoint(DATABASE);
    }

    private static void markInventoryReady(String projectName, String sourceId) throws Exception {
        String projectId = projectId(projectName);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             var ps = connection.prepareStatement("""
                     INSERT INTO source_inventory(
                       project_id, source_id, source_path, source_name, source_format, status,
                       total_can_channels, total_can_frames, total_unique_message_ids,
                       channel_discovery_complete, diagnostic_count, updated_at)
                     VALUES(?, ?, 'hidden', 'second.asc', 'ASC', 'READY', 1, 1, 1, 1, 0, '2026-01-01T00:00:00Z')
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, sourceId);
            ps.executeUpdate();
        }
    }

    private static void duplicateProjectRow(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS idx_projects_normalized_name");
            statement.execute("""
                    INSERT INTO projects(project_id, name, normalized_name, created_at, updated_at, last_workspace, last_saved_at, numeric_id)
                    SELECT 'dup-' || project_id, name, normalized_name, created_at, updated_at, last_workspace, last_saved_at, numeric_id + 100000
                    FROM projects WHERE name='%s'
                    """.formatted(name.replace("'", "")));
        }
    }

    private static void removeDuplicate(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM projects WHERE project_id LIKE 'dup-%'");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_projects_normalized_name ON projects(normalized_name)");
        }
    }

    private static String projectId(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
             var ps = connection.prepareStatement("SELECT project_id FROM projects WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("missing project");
                }
                return rs.getString(1);
            }
        }
    }

    private static void checkpoint(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private ResponseEntity<String> post(Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return rest.exchange(url(), HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> ok(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return read(response.getBody());
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code) throws Exception {
        assertEquals(status, response.getStatusCode(), response.getBody());
        Map<String, Object> body = read(response.getBody());
        assertEquals("FAILED", body.get("status"));
        assertEquals("1.0", body.get("schemaVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(code, error.get("code"));
        assertFalse(response.getBody().contains("Exception"));
    }

    private boolean neitherExposesPath(ResponseEntity<String> response) {
        String body = response.getBody() == null ? "" : response.getBody();
        return body.contains(":\\") || body.contains("secret.asc");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    @Test
    void signalNamesSelectsSeveralSeriesAndKeepsCollidingNamesDistinct() throws Exception {
        Map<String, Object> selected = ok(post(Map.of(
                "filePath", muxAsc.toString(),
                "dbcPath", muxDbc.toString(),
                "signalNames", List.of("Torque", "Selector"),
                "pageSize", 1000)));
        assertEquals(2, signalList(selected).size());
        assertEquals(2, number(selected.get("totalSignalCount")));
        assertEquals(List.of("Selector", "Torque"), signalList(selected).stream()
                .map(signal -> signal.get("jsonKey").toString()).toList());
        assertEquals(8, samples(signalList(selected).get(0)).size());
        assertEquals(1, samples(signalList(selected).get(1)).size());
        assertError(post(Map.of(
                "filePath", muxAsc.toString(),
                "dbcPath", muxDbc.toString(),
                "signal", "Speed",
                "signalNames", List.of("Speed"))), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");

        Path dbc = ROOT.resolve("same-name.dbc");
        Path asc = ROOT.resolve("same-name.asc");
        Files.writeString(dbc, """
                VERSION ""
                BU_: ECU
                BO_ 100 Front: 1 ECU
                 SG_ Speed : 0|8@1+ (1,0) [0|255] "km/h" ECU
                BO_ 200 Rear: 1 ECU
                 SG_ Speed : 0|8@1+ (1,0) [0|255] "km/h" ECU
                """);
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 64 Rx d 1 0A
                  0.150000 1 64 Rx d 1 0B
                  0.200000 1 C8 Rx d 1 14
                """);
        Map<String, Object> collided = ok(post(Map.of(
                "filePath", asc.toString(), "dbcPath", dbc.toString(), "signalNames", List.of("Speed"))));
        assertEquals(2, signalList(collided).size());
        List<String> keys = signalList(collided).stream().map(signal -> signal.get("jsonKey").toString()).toList();
        assertEquals(2, keys.stream().distinct().count());
        assertTrue(keys.stream().noneMatch("Speed"::equals));
        assertEquals(List.of("Speed", "Speed"), signalList(collided).stream()
                .map(signal -> signal.get("name").toString()).toList());
        Map<String, Object> firstPage = ok(post(Map.of(
                "filePath", asc.toString(), "dbcPath", dbc.toString(), "pageSize", 1)));
        String continuedKey = signalList(firstPage).getFirst().get("jsonKey").toString();
        Map<String, Object> secondPage = ok(post(Map.of("cursor", firstPage.get("nextCursor"), "pageSize", 1)));
        assertEquals(continuedKey, signalList(secondPage).getFirst().get("jsonKey"));
        assertEquals("1", samples(signalList(secondPage).getFirst()).getFirst().get("sampleIndex"));
        assertError(post(Map.of(
                "filePath", asc.toString(), "dbcPath", dbc.toString(), "signal", "Speed")),
                HttpStatus.CONFLICT, "SIGNAL_AMBIGUOUS");
    }

    @Test
    void fileDecodeDoesNotRequireAProjectCatalog() throws Exception {
        Path missing = ROOT.resolve("missing-catalog.db");
        DecoderValidationException failure = assertThrows(
                DecoderValidationException.class,
                () -> ReadOnlyProjectCatalog.open(missing, List.of(ROOT)));
        assertEquals("INTERNAL_ERROR", failure.code());
        assertEquals("Project catalog is not available.", failure.getMessage());
        Map<String, Object> decoded = ok(post(Map.of(
                "filePath", muxAsc.toString(), "dbcPath", muxDbc.toString(), "signal", "Torque")));
        assertEquals("2.0", decoded.get("schemaVersion"));
        assertEquals("FRESH_DECODE", decoded.get("resultOrigin"));
        assertEquals(1, number(decoded.get("returnedSampleCount")));
        assertEquals("AVAILABLE", decoded.get("datetimeAvailability"));
        assertEquals("ASC date line", decoded.get("datetimeAvailabilityReason"));
        assertFalse(decoded.containsKey("datetimeTimezone"));
        assertFalse(decoded.containsKey("datetime"));
        assertEquals("2026-01-01 00:00:00.400", list(signalList(decoded).getFirst(), "sourceDatetime").getFirst());
    }

    @Test
    void mf4HeaderStartTimeIsFormattedPerSampleAndSurvivesTheCursor() throws Exception {
        Path file = ROOT.resolve("timed.mf4");
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
            long header = builder.writeHd(0, 0, 0, 1_674_028_365_000_000_000L, (short) 300, (short) 0, 2);
            long timeConversion = builder.writeCcLinear(0.0, 1.0e-5);
            long valueConversion = builder.writeCcLinear(0.0, 1.0);
            long speed = builder.writeCn(
                    0L, 0L, builder.writeTx("SpeedOverGround"), 0L, 0L, valueConversion,
                    0, 0, 4, 8, 0, 32, 0, 0);
            long time = builder.writeCn(
                    speed, 0L, builder.writeTx("Timestamp"), 0L, 0L, timeConversion,
                    2, 1, 0, 0, 0, 64, 0, 0);
            long group = builder.writeCg(0, time, 1, 2, 0, 12, 0);
            ByteBuffer first = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            first.putLong(10_000L);
            first.putFloat(42.5f);
            ByteBuffer second = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            second.putLong(20_000L);
            second.putFloat(55.0f);
            byte[] payload = new byte[24];
            System.arraycopy(first.array(), 0, payload, 0, 12);
            System.arraycopy(second.array(), 0, payload, 12, 12);
            long data = builder.writeDt(payload);
            long dataGroup = builder.writeDg(0, group, data, 0);
            builder.patchLink(header, 0, dataGroup);
        }
        Map<String, Object> page = ok(post(Map.of("filePath", file.toString(), "pageSize", 1)));
        assertEquals("AVAILABLE", page.get("datetimeAvailability"));
        assertFalse(page.containsKey("datetimeTimezone"));
        Map<String, Object> signal = signalList(page).getFirst();
        assertEquals("100000000", list(signal, "timestampsNs").getFirst());
        assertEquals("2023-01-18 12:52:45.100", list(signal, "sourceDatetime").getFirst());
        Map<String, Object> full = ok(post(Map.of("filePath", file.toString(), "pageSize", 2)));
        Map<String, Object> fullSignal = signalList(full).getFirst();
        assertEquals(List.of("100000000", "200000000"), list(fullSignal, "timestampsNs"));
        assertEquals(List.of("2023-01-18 12:52:45.100", "2023-01-18 12:52:45.200"), list(fullSignal, "sourceDatetime"));
        assertEquals(list(fullSignal, "samples").getFirst(), list(signal, "samples").getFirst());
        assertEquals(list(fullSignal, "rawSamples").getFirst(), list(signal, "rawSamples").getFirst());
        assertEquals(list(fullSignal, "validity").getFirst(), list(signal, "validity").getFirst());
        Map<String, Object> next = ok(post(Map.of("cursor", page.get("nextCursor"), "pageSize", 1)));
        Map<String, Object> continued = signalList(next).getFirst();
        assertEquals("200000000", list(continued, "timestampsNs").getFirst());
        assertEquals("2023-01-18 12:52:45.200", list(continued, "sourceDatetime").getFirst());
        assertEquals(list(fullSignal, "samples").get(1), list(continued, "samples").getFirst());
        assertEquals(list(fullSignal, "rawSamples").get(1), list(continued, "rawSamples").getFirst());
        assertEquals(list(fullSignal, "validity").get(1), list(continued, "validity").getFirst());
        assertEquals("1", list(continued, "sampleIndex").getFirst());
    }

    @Test
    void ascBlfDatAndLogUseSchemaTwoAndOnlyVerifiedClocks() throws Exception {
        String dbcText = """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 256 SpeedMsg: 1 ECU
                 SG_ Speed : 0|8@1+ (1,0) [0|255] "" ECU
                """;
        Path dbc = ROOT.resolve("speed.dbc");
        Files.writeString(dbc, dbcText);

        Path asc = ROOT.resolve("offset.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 PM 2026 +05:30
                base hex timestamps absolute
                  0.100000000 1 100 Rx d 1 0A
                  0.200000000 1 100 Rx d 1 0B
                """);
        Map<String, Object> ascPage = ok(post(Map.of(
                "filePath", asc.toString(), "dbcPath", dbc.toString(), "pageSize", 1)));
        assertEquals("2.0", ascPage.get("schemaVersion"));
        assertEquals("1.0.4", ascPage.get("applicationVersion"));
        assertEquals("AVAILABLE", ascPage.get("datetimeAvailability"));
        assertEquals("ASC date line", ascPage.get("datetimeAvailabilityReason"));
        Map<String, Object> ascSignal = signalList(ascPage).getFirst();
        assertEquals("100000000", list(ascSignal, "timestampsNs").getFirst());
        assertEquals("2026-01-01 12:00:00.100", list(ascSignal, "sourceDatetime").getFirst());
        assertEquals("10.0", text(samples(ascSignal).getFirst(), "physical"));
        Map<String, Object> ascNext = ok(post(Map.of("cursor", ascPage.get("nextCursor"), "pageSize", 1)));
        Map<String, Object> ascContinued = signalList(ascNext).getFirst();
        assertEquals("200000000", list(ascContinued, "timestampsNs").getFirst());
        assertEquals("2026-01-01 12:00:00.200", list(ascContinued, "sourceDatetime").getFirst());
        assertEquals("11.0", text(samples(ascContinued).getFirst(), "physical"));

        Path blf = ROOT.resolve("local-start.blf");
        Files.write(blf, blfFile(
                blfCan(1, 0x100, new byte[] {0x0A}, 100_000_000L),
                blfCan(1, 0x100, new byte[] {0x0B}, 200_000_000L)));
        Map<String, Object> blfBody = ok(post(Map.of(
                "filePath", blf.toString(), "dbcPath", dbc.toString(), "pageSize", 1)));
        assertEquals("2.0", blfBody.get("schemaVersion"));
        assertEquals("AVAILABLE", blfBody.get("datetimeAvailability"));
        assertEquals("BLF SYSTEMTIME", blfBody.get("datetimeAvailabilityReason"));
        Map<String, Object> blfSignal = signalList(blfBody).getFirst();
        assertEquals("100000000", list(blfSignal, "timestampsNs").getFirst());
        assertEquals("2023-01-18 13:22:45.100", list(blfSignal, "sourceDatetime").getFirst());
        assertEquals("10.0", text(samples(blfSignal).getFirst(), "physical"));
        Map<String, Object> blfNext = ok(post(Map.of("cursor", blfBody.get("nextCursor"), "pageSize", 1)));
        assertEquals("200000000", list(signalList(blfNext).getFirst(), "timestampsNs").getFirst());
        assertEquals("2023-01-18 13:22:45.200", list(signalList(blfNext).getFirst(), "sourceDatetime").getFirst());

        Path dat = ROOT.resolve("elapsed.dat");
        Files.writeString(dat, """
                time_s,Speed,Speed
                0,1.5,2.5
                0.5,3.5,4.5
                1.0,5.5,6.5
                """);
        Map<String, Object> datBody = ok(post(Map.of("filePath", dat.toString(), "pageSize", 10000)));
        assertEquals("2.0", datBody.get("schemaVersion"));
        assertEquals("text", ((Map<?, ?>) ((List<?>) datBody.get("sources")).getFirst()).get("format"));
        assertEquals("UNAVAILABLE", datBody.get("datetimeAvailability"));
        assertTrue(String.valueOf(datBody.get("datetimeAvailabilityReason")).contains("elapsed"));
        List<Map<String, Object>> datSignals = signalList(datBody);
        assertEquals(2, datSignals.size());
        assertNotEquals(datSignals.get(0).get("jsonKey"), datSignals.get(1).get("jsonKey"));
        assertNotEquals(datSignals.get(0).get("canonicalId"), datSignals.get(1).get("canonicalId"));
        assertNotEquals(datSignals.get(0).get("channel"), datSignals.get(1).get("channel"));
        assertEquals(List.of("0", "500000000", "1000000000"), list(datSignals.get(0), "timestampsNs"));
        assertEquals(List.of("1.5", "3.5", "5.5"), samples(datSignals.get(0)).stream().map(row -> text(row, "physical")).toList());
        assertTrue(list(datSignals.get(0), "sourceDatetime").stream().allMatch(value -> value == null));

        Path zoned = ROOT.resolve("zoned.log");
        Files.writeString(zoned, """
                timestamp,Speed
                2023-01-18T07:52:45.100Z,4
                2023-01-18T07:52:45.200Z,5
                2023-01-18T07:52:45.300Z,6
                """);
        Map<String, Object> zonedPage = ok(post(Map.of("filePath", zoned.toString(), "pageSize", 2)));
        assertEquals("AVAILABLE", zonedPage.get("datetimeAvailability"));
        assertEquals("ISO timestamp", zonedPage.get("datetimeAvailabilityReason"));
        Map<String, Object> zonedSignal = signalList(zonedPage).getFirst();
        Instant zonedInstant = Instant.parse("2023-01-18T07:52:45.100Z");
        long zonedNanos = zonedInstant.getEpochSecond() * 1_000_000_000L + zonedInstant.getNano();
        assertEquals(Long.toString(zonedNanos), list(zonedSignal, "timestampsNs").getFirst());
        assertEquals(List.of("2023-01-18 07:52:45.100", "2023-01-18 07:52:45.200"), list(zonedSignal, "sourceDatetime"));
        Map<String, Object> zonedNext = ok(post(Map.of("cursor", zonedPage.get("nextCursor"), "pageSize", 2)));
        assertEquals("2023-01-18 07:52:45.300", list(signalList(zonedNext).getFirst(), "sourceDatetime").getFirst());
        assertEquals(Long.toString(zonedNanos + 200_000_000L), list(signalList(zonedNext).getFirst(), "timestampsNs").getFirst());

        Path bare = ROOT.resolve("unzoned.log");
        Files.writeString(bare, """
                timestamp,Speed
                2023-01-18T13:22:45.100,1
                2023-01-18T13:22:45.200,2
                2023-01-18T13:22:45.300,3
                """);
        Map<String, Object> bareBody = ok(post(Map.of("filePath", bare.toString())));
        assertEquals("AVAILABLE", bareBody.get("datetimeAvailability"));
        assertEquals("ISO timestamp", bareBody.get("datetimeAvailabilityReason"));
        assertEquals("2023-01-18 13:22:45.100", list(signalList(bareBody).getFirst(), "sourceDatetime").getFirst());
        Instant assumed = Instant.parse("2023-01-18T13:22:45.100Z");
        long assumedNanos = assumed.getEpochSecond() * 1_000_000_000L + assumed.getNano();
        assertEquals(Long.toString(assumedNanos), list(signalList(bareBody).getFirst(), "timestampsNs").getFirst());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> signalList(Map<String, Object> body) {
        Object raw = body.get("signals");
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        Map<String, Object> keyed = (Map<String, Object>) raw;
        List<Map<String, Object>> signals = new ArrayList<>();
        for (Map.Entry<String, Object> entry : keyed.entrySet()) {
            Map<String, Object> source = (Map<String, Object>) entry.getValue();
            Map<String, Object> signal = new LinkedHashMap<>(source);
            signal.put("jsonKey", entry.getKey());
            signal.put("samples", rows(source));
            signals.add(signal);
        }
        return signals;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> signal) {
        List<?> timestamps = list(signal, "timestamps");
        List<?> timestampsNs = list(signal, "timestampsNs");
        List<?> samples = list(signal, "samples");
        List<?> rawSamples = list(signal, "rawSamples");
        List<?> textValues = list(signal, "textValues");
        List<?> validity = list(signal, "validity");
        List<?> sampleIndex = list(signal, "sampleIndex");
        List<?> datetime = list(signal, "sourceDatetime");
        int count = timestampsNs.size();
        if (timestamps.size() != count || samples.size() != count || rawSamples.size() != count
                || textValues.size() != count || validity.size() != count || sampleIndex.size() != count
                || datetime.size() != count) {
            throw new AssertionError("parallel signal arrays have different lengths");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestampNs", timestampsNs.get(i));
            row.put("sampleIndex", String.valueOf(sampleIndex.get(i)));
            row.put("textualValue", textValues.get(i));
            row.put("validity", validity.get(i));
            row.put("raw", Map.of("value", scalar(rawSamples.get(i))));
            row.put("physical", Map.of("value", scalar(samples.get(i))));
            rows.add(row);
        }
        return rows;
    }

    private static List<?> list(Map<String, Object> signal, String name) {
        Object value = signal.get(name);
        if (!(value instanceof List<?> items)) {
            throw new AssertionError(name + " is not an array");
        }
        return items;
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return String.valueOf(map.get("value"));
        }
        if (value instanceof Double || value instanceof Float) {
            return Double.toString(((Number) value).doubleValue());
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static void assertColumnLengths(Map<String, Object> body) {
        Map<String, Object> signals = (Map<String, Object>) body.get("signals");
        int returned = 0;
        for (Object value : signals.values()) {
            returned += rows((Map<String, Object>) value).size();
        }
        assertEquals(number(body.get("returnedSampleCount")), returned);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> samples(Map<String, Object> signal) {
        return (List<Map<String, Object>>) signal.get("samples");
    }

    private static Map<String, List<Map<String, Object>>> byName(Map<String, Object> body) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> signal : signalList(body)) {
            out.put(signal.get("name").toString(), samples(signal));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> sample, String field) {
        return ((Map<String, Object>) sample.get(field)).get("value").toString();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void collect(Map<String, Object> page, List<String> seen) {
        for (Map<String, Object> signal : signalList(page)) {
            for (Map<String, Object> sample : samples(signal)) {
                seen.add(signal.get("canonicalId") + "#" + sample.get("sampleIndex"));
            }
        }
    }

    private static List<String> identities(Map<String, Object> body) {
        return signalList(body).stream().map(signal -> signal.get("signalIdentity").toString()).sorted().toList();
    }

    private static Map<String, List<String>> sampleFacts(Map<String, Object> body) {
        Map<String, List<String>> facts = new TreeMap<>();
        for (Map<String, Object> signal : signalList(body)) {
            List<String> rows = new ArrayList<>();
            for (Map<String, Object> sample : samples(signal)) {
                rows.add(sample.get("timestampNs") + "|" + text(sample, "raw") + "|"
                        + text(sample, "physical") + "|" + sample.get("textualValue") + "|"
                        + sample.get("validity") + "|" + sample.get("sampleIndex"));
            }
            facts.put(signal.get("signalIdentity").toString(), rows);
        }
        return facts;
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/api/v1/decoder/validate";
    }

    private static byte[] blfFile(byte[]... frames) {
        byte[] inner = concat(frames);
        byte[] container = blfObject(10, 0, 0L, logContainer(inner));
        byte[] header = blfHeader(container.length);
        byte[] file = new byte[header.length + container.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(container, 0, file, header.length, container.length);
        return file;
    }

    private static byte[] blfCan(int channel, int rawId, byte[] data, long timestampNanos) {
        ByteBuffer payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short) channel);
        payload.put((byte) 0);
        payload.put((byte) data.length);
        payload.putInt(rawId);
        byte[] padded = new byte[8];
        System.arraycopy(data, 0, padded, 0, data.length);
        payload.put(padded);
        return blfObject(1, 2, timestampNanos, payload.array());
    }

    private static byte[] logContainer(byte[] inner) {
        ByteBuffer buf = ByteBuffer.allocate(16 + inner.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putInt(inner.length);
        buf.putInt(0);
        buf.put(inner);
        return buf.array();
    }

    private static byte[] blfObject(int type, int flags, long timestamp, byte[] payload) {
        int headerSize = 32;
        int objectSize = headerSize + payload.length;
        int aligned = (objectSize + 3) & ~3;
        ByteBuffer buf = ByteBuffer.allocate(aligned).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'L').put((byte) 'O').put((byte) 'B').put((byte) 'J');
        buf.putShort((short) headerSize);
        buf.putShort((short) 1);
        buf.putInt(objectSize);
        buf.putInt(type);
        buf.putInt(flags);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putLong(timestamp);
        buf.put(payload);
        return buf.array();
    }

    private static byte[] blfHeader(int bodyLength) {
        ByteBuffer buf = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'L').put((byte) 'O').put((byte) 'G').put((byte) 'G');
        buf.putInt(144);
        buf.put((byte) 5);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 3);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putLong(144L + bodyLength);
        buf.putLong(144L + bodyLength);
        buf.putInt(1);
        buf.putInt(0);
        buf.position(40);
        buf.putShort((short) 2023);
        buf.putShort((short) 1);
        buf.putShort((short) 4);
        buf.putShort((short) 18);
        buf.putShort((short) 13);
        buf.putShort((short) 22);
        buf.putShort((short) 45);
        buf.putShort((short) 0);
        return buf.array();
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] all = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, offset, part.length);
            offset += part.length;
        }
        return all;
    }

    private static String muxAsc(String[][] frames) {
        StringBuilder text = new StringBuilder();
        text.append("date Thu Jan 01 12:00:00.000 am 2026\nbase hex timestamps absolute\n");
        for (String[] frame : frames) {
            long nanos = Long.parseLong(frame[0]);
            String[] bytes = frame[1].isBlank() ? new String[0] : frame[1].split(" ");
            text.append(String.format("  %d.%09d 1 610 Rx d %d",
                    nanos / 1_000_000_000L, nanos % 1_000_000_000L, bytes.length));
            for (String hex : bytes) {
                text.append(' ').append(hex);
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static Path createRoot() {
        try {
            return Files.createTempDirectory("decoder-api-");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String hashFile(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String hashTree(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).sorted().forEach(path -> {
                try {
                    digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(path));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temp cleanup is best-effort.
                }
            });
        }
    }

    private record Saved(String projectId, String fileId, Path store) {
    }
}
