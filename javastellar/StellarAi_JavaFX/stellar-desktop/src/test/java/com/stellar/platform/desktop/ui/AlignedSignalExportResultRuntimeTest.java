package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.export.AlignedSignalCsvExporter;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project-resume failure loaded {@code stellar-desktop/target/classes} with the
 * installed {@code ~/.m2} processing-core JAR, not reactor {@code target/classes}.
 * This forks that classpath and requires {@code ExportResult} to come from the JAR.
 */
class AlignedSignalExportResultRuntimeTest {

    private static final String EXPORT_ENTRY =
            "com/stellar/platform/processing/export/AlignedSignalCsvExporter$ExportResult.class";

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void projectResumeClasspathLoadsExportResultFromProcessingJar() throws Exception {
        Path reactorJar = Path.of("..", "stellar-processing-core", "target",
                "stellar-processing-core-0.1.0-SNAPSHOT.jar").toAbsolutePath().normalize();
        Path m2Jar = Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "stellar", "platform",
                "stellar-processing-core", "0.1.0-SNAPSHOT", "stellar-processing-core-0.1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(reactorJar), reactorJar.toString());
        assertTrue(Files.isRegularFile(m2Jar), m2Jar.toString());
        assertJarEntry(reactorJar);
        assertJarEntry(m2Jar);
        assertEquals(Files.size(reactorJar), Files.size(m2Jar),
                "Maven launch uses the installed SNAPSHOT, which must match the reactor JAR");

        Path home = Files.createTempDirectory("stellar-export-result-home");
        Path db = home.resolve("meta.db");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dprism.order=sw");
        command.add("-Dprism.verbose=false");
        command.add("-Dstellarai.test.isolate=true");
        command.add("-Dstellarai.home=" + home);
        command.add("-Dstellarai.metadata.db=" + db);
        command.add("-cp");
        command.add(runtimeClasspath(m2Jar));
        command.add(AlignedSignalExportResultRuntimeTest.class.getName());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        byte[] outputBytes;
        try (InputStream in = process.getInputStream()) {
            outputBytes = in.readAllBytes();
        }
        boolean finished = process.waitFor(150, TimeUnit.SECONDS);
        String output = new String(outputBytes, StandardCharsets.UTF_8);
        assertTrue(finished, "probe timed out\n" + output);
        assertEquals(0, process.exitValue(), output);
        String sourceLine = output.lines()
                .filter(line -> line.contains("EXPORT_RESULT_SOURCE="))
                .findFirst()
                .orElse("");
        assertTrue(sourceLine.toLowerCase(Locale.ROOT).contains("stellar-processing-core"), sourceLine);
        assertTrue(sourceLine.toLowerCase(Locale.ROOT).endsWith(".jar"), sourceLine);
        assertTrue(!sourceLine.contains("target/classes") && !sourceLine.contains("target\\classes"), sourceLine);
        assertTrue(output.contains("SIGNALS_FXML_OK"), output);
        assertTrue(output.contains("GRAPHS_FXML_OK"), output);
        assertTrue(output.contains("CSV_EXPORT_OK"), output);
        assertTrue(output.contains("PROJECT_REOPEN_OK"), output);
    }

    public static void main(String[] args) throws Exception {
        try {
            Class<?> result = Class.forName(
                    "com.stellar.platform.processing.export.AlignedSignalCsvExporter$ExportResult");
            String source = result.getProtectionDomain().getCodeSource().getLocation().toString();
            System.out.println("EXPORT_RESULT_SOURCE=" + source);
            if (!source.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalStateException("ExportResult was not loaded from a JAR: " + source);
            }
            Path home = Path.of(System.getProperty("stellarai.home"));
            Path db = Path.of(System.getProperty("stellarai.metadata.db"));
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            if (!started.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX did not start");
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Platform.runLater(() -> {
                DesktopAppService service = null;
                MainShellController shell = null;
                try {
                    Platform.setImplicitExit(false);
                    service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                    shell = loadShell(service);
                    service.newProject("Export Result Probe");
                    shell.onProjectCreated();
                    String projectId = service.metadataStore().listProjects().stream()
                            .filter(row -> "Export Result Probe".equals(row.name()))
                            .findFirst()
                            .orElseThrow()
                            .projectId();
                    show(shell, NavigationPage.SIGNALS, SignalsController.class);
                    System.out.println("SIGNALS_FXML_OK");
                    show(shell, NavigationPage.GRAPHS, GraphController.class);
                    System.out.println("GRAPHS_FXML_OK");
                    exportProbe(home.resolve("probe.csv"));
                    shell.dispose();
                    shell = null;
                    service.shutdown();
                    service = null;

                    service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                    if (!service.openSavedProject(projectId).opened()) {
                        throw new IllegalStateException("saved project did not reopen");
                    }
                    shell = loadShell(service);
                    show(shell, NavigationPage.SIGNALS, SignalsController.class);
                    show(shell, NavigationPage.GRAPHS, GraphController.class);
                    System.out.println("PROJECT_REOPEN_OK");
                } catch (Throwable ex) {
                    failure.set(ex);
                } finally {
                    if (shell != null) {
                        shell.dispose();
                    }
                    if (service != null) {
                        service.shutdown();
                    }
                    done.countDown();
                    Platform.exit();
                }
            });
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX probe timed out");
            }
            if (failure.get() != null) {
                failure.get().printStackTrace(System.out);
                System.exit(1);
            }
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static MainShellController loadShell(DesktopAppService service) throws Exception {
        FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
        loader.load();
        MainShellController shell = loader.getController();
        shell.init(service);
        return shell;
    }

    private static void show(MainShellController shell, NavigationPage page, Class<?> controllerType) {
        shell.show(page);
        Object controller = shell.controllerForTest(page);
        if (!controllerType.isInstance(controller)) {
            throw new IllegalStateException(page + " controller was " + controller);
        }
    }

    private static void exportProbe(Path csv) throws Exception {
        SignalCatalogKey key = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Speed", 0x100L, 1, "probe.mf4", "probe.dbc");
        List<SignalSamplePoint> samples = List.of(
                new SignalSamplePoint(1L, 10.0, 10L, null),
                new SignalSamplePoint(2L, 11.0, 11L, null));
        AlignedSignalCsvExporter.ExportResult result = new AlignedSignalCsvExporter().export(
                new OneSignalStore(key, samples),
                List.of(new SignalCatalogEntry(key, "km/h", samples.size(), 1L, 2L, 10.0, 11.0, "probe.mf4", "probe.dbc")),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                csv,
                new java.util.concurrent.atomic.AtomicBoolean());
        if (result.cancelled() || result.rowCount() != 2 || !Files.isRegularFile(csv)) {
            throw new IllegalStateException("CSV export failed: " + result);
        }
        System.out.println("CSV_EXPORT_OK rows=" + result.rowCount());
    }

    private static void assertJarEntry(Path jar) throws Exception {
        try (JarFile zip = new JarFile(jar.toFile())) {
            if (zip.getEntry(EXPORT_ENTRY) == null) {
                throw new AssertionError(EXPORT_ENTRY + " missing from " + jar);
            }
            if (zip.getEntry("com/stellar/platform/processing/export/AlignedSignalCsvExporter.class") == null) {
                throw new AssertionError("AlignedSignalCsvExporter.class missing from " + jar);
            }
        }
    }

    private static String runtimeClasspath(Path processingJar) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.add(Path.of("target", "test-classes").toAbsolutePath().normalize().toString());
        entries.add(Path.of("target", "classes").toAbsolutePath().normalize().toString());
        entries.add(processingJar.toAbsolutePath().normalize().toString());
        String separator = System.getProperty("path.separator");
        for (String raw : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(separator))) {
            if (raw.isBlank()) {
                continue;
            }
            String norm = raw.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (norm.contains("stellar-processing-core/target/classes")) {
                continue;
            }
            if (norm.contains("stellar-processing-core-0.1.0-snapshot.jar")) {
                continue;
            }
            if (norm.endsWith("/stellar-desktop/target/classes")
                    || norm.endsWith("/stellar-desktop/target/test-classes")) {
                continue;
            }
            entries.add(raw);
        }
        return String.join(separator, entries);
    }

    private static final class OneSignalStore implements SignalSeriesStore {
        private final SignalCatalogKey key;
        private final List<SignalSamplePoint> samples;

        private OneSignalStore(SignalCatalogKey key, List<SignalSamplePoint> samples) {
            this.key = key;
            this.samples = samples;
        }

        @Override
        public SignalCatalog snapshot() {
            return SignalCatalog.empty();
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey requested, int offset, int limit) {
            if (!key.equals(requested) || offset >= samples.size()) {
                return List.of();
            }
            int from = Math.max(0, offset);
            int to = Math.min(samples.size(), from + Math.max(1, limit));
            return List.copyOf(samples.subList(from, to));
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey requested) {
            return key.equals(requested) ? samples.size() : 0;
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey requested) {
            return 0L;
        }
    }
}
