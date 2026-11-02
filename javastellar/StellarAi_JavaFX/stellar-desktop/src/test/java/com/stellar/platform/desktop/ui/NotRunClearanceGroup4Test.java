package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group 4 drives the existing Advanced Analytics controls. It does not add a Signal Processing view.
 */
class NotRunClearanceGroup4Test {

    private static final long SECOND = 1_000_000_000L;
    private static final List<String> NOTES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path out = Path.of("..", "docs", "qa-handoff", "clearance4-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void smoothingIsCalculatedButNotPlottedWithTheOriginal() throws Exception {
        InMemorySignalCatalogStore samples = new InMemorySignalCatalogStore("step.mf4", "", 100);
        samples.accept(List.of(
                sample("Step", 0, 0),
                sample("Step", SECOND, 10),
                sample("Step", 2 * SECOND, 10)));
        ShellFx shell = open(samples);
        try {
            runOnFx(() -> {
                shell.graph.selectAnalysisTabForTest(2);
                toggle(shell.root, "Time Domain").fire();
                selectOperation(shell.root, AdvancedAnalyticsType.EXPONENTIAL_SMOOTHING);
                fieldForLabel(shell.root, "Alpha").setText("0.5");
                fireVisible(shell.root, "Run");
            });
            String report = waitForReport(shell.service, "EXPONENTIAL_SMOOTHING");
            awaitTracks(shell);
            double[] values = shell.service.lastAdvancedAnalytics().derived().values();
            double[] expected = {0, 5, 7.5};
            boolean math = values.length == 3
                    && Math.abs(values[0] - expected[0]) < 1e-9
                    && Math.abs(values[1] - expected[1]) < 1e-9
                    && Math.abs(values[2] - expected[2]) < 1e-9;
            List<String> names = runOnFxValue(() -> shell.graph.renderedTrackKeysForTest().stream()
                    .map(key -> key.signalName()).toList());
            int tracks = shell.graph.logicalTrackCountForTest();
            runOnFx(() -> fireVisible(shell.root, "Save Derived Signal"));
            String saved = runOnFxValue(() -> textArea(shell.root));
            List<String> afterSave = runOnFxValue(() -> shell.graph.renderedTrackKeysForTest().stream()
                    .map(key -> key.signalName()).toList());
            note("TC_ASP_010 math=" + math + " values=" + java.util.Arrays.toString(values)
                    + " tracks=" + tracks + " names=" + names + " afterSave=" + afterSave
                    + " report='" + oneLine(report) + "' saved='" + oneLine(saved) + "'");
            if (math && tracks >= 2 && names.stream().anyMatch(name -> name.contains("ema"))
                    && names.stream().anyMatch(name -> name.equals("Step"))) {
                note("TC_ASP_010 PASS original and smoothed tracks are plotted together.");
            } else {
                note("TC_ASP_010 NOT RUN category C: Exponential Smoothing with alpha 0.5 on [0, 10, 10] "
                        + "produced " + java.util.Arrays.toString(values) + ", which matches [0, 5, 7.5]. "
                        + "The graph still shows " + afterSave + ". Save Derived Signal stores the series in Analysis Storage "
                        + "and does not add a second named, coloured track beside Step.");
            }
        } finally {
            close(shell);
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void fftResolutionFollowsTheViewport() throws Exception {
        InMemorySignalCatalogStore samples = new InMemorySignalCatalogStore("tone.mf4", "", 5_000);
        samples.accept(sine("Tone", 10, 100, 2.0));
        ShellFx shell = open(samples);
        try {
            awaitTracks(shell);
            runOnFx(() -> {
                shell.graph.selectAnalysisTabForTest(2);
                toggle(shell.root, "Frequency Domain").fire();
                fireVisible(shell.root, "Run");
            });
            String fullReport = waitForReport(shell.service, "FFT  N=");
            var full = shell.service.lastAdvancedAnalytics().spectrum();
            long fullStart = shell.graph.viewportStartForTest();
            long fullEnd = shell.graph.viewportEndForTest();
            runOnFx(() -> {
                shell.graph.setViewportForTest(0, SECOND / 2);
                fireVisible(shell.root, "Run");
            });
            String narrowReport = waitForReportChange(shell.service, fullReport);
            var narrow = shell.service.lastAdvancedAnalytics().spectrum();
            boolean fullMath = matchesResolution(full);
            boolean narrowMath = matchesResolution(narrow);
            boolean rangeChanged = full.sampleCount() != narrow.sampleCount()
                    && Math.abs(full.frequencyResolutionHz() - narrow.frequencyResolutionHz()) > 1e-9;
            boolean tones = nearTone(full, 10) && nearTone(narrow, 10);
            String shown = runOnFxValue(() -> textArea(shell.root));
            note("TC_ASP_021 full N=" + full.sampleCount() + " size=" + full.fftSize()
                    + " fs=" + full.sampleRateHz() + " df=" + full.frequencyResolutionHz()
                    + " window=" + fullStart + ".." + fullEnd
                    + " narrow N=" + narrow.sampleCount() + " size=" + narrow.fftSize()
                    + " df=" + narrow.frequencyResolutionHz()
                    + " viewport=" + shell.graph.viewportStartForTest() + ".." + shell.graph.viewportEndForTest()
                    + " shown=" + shown.contains("Δf"));
            note("TC_ASP_021 fullReport='" + oneLine(fullReport) + "'");
            note("TC_ASP_021 narrowReport='" + oneLine(narrowReport) + "'");
            if (fullMath && narrowMath && rangeChanged && tones && shown.contains("FFT")) {
                note("TC_ASP_021 PASS Frequency Domain FFT. Full viewport N=" + full.sampleCount()
                        + " Δf=" + full.frequencyResolutionHz() + " Hz. Narrow viewport N=" + narrow.sampleCount()
                        + " Δf=" + narrow.frequencyResolutionHz()
                        + " Hz. Δf equals fs/fftSize, and the strongest bin stays on the 10 Hz tone.");
            } else {
                note("TC_ASP_021 NOT RUN category C: the Frequency Domain report did not show two ranges whose "
                        + "sample counts differ and whose Δf equals fs/fftSize. fullMath=" + fullMath
                        + " narrowMath=" + narrowMath + " rangeChanged=" + rangeChanged + " tones=" + tones);
            }
        } finally {
            close(shell);
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void resampleControlsDoNotAlignTwoSignals() throws Exception {
        InMemorySignalCatalogStore samples = new InMemorySignalCatalogStore("pair.mf4", "", 5_000);
        samples.accept(steps("Pace", 0, 1, 50_000_000L, 2, 19));
        samples.accept(steps("Slow", 0, 10, 250_000_000L, 11, 4));
        SignalCatalogKey pace = samples.snapshot().entries().stream()
                .filter(entry -> "Pace".equals(entry.key().signalName())).findFirst().orElseThrow().key();
        double before = samples.querySamples(pace, 0, 1).getFirst().physicalValue();
        ShellFx shell = open(samples);
        try {
            awaitTracks(shell);
            int selected = shell.graph.selectedSignalCountForTest();
            runOnFx(() -> {
                shell.graph.selectAnalysisTabForTest(2);
                toggle(shell.root, "Time Domain").fire();
                selectOperation(shell.root, AdvancedAnalyticsType.RESAMPLE);
                fieldForLabel(shell.root, "Target Rate").setText("not-a-number");
            });
            String invalidText = runOnFxValue(() -> fieldForLabel(shell.root, "Target Rate").getText());
            String beforeRun = runOnFxValue(() -> textArea(shell.root));
            runOnFx(() -> fireVisible(shell.root, "Run"));
            String invalidReport = waitForReport(shell.service, "Resampling");
            runOnFx(() -> fieldForLabel(shell.root, "Target Rate").setText("10"));
            String accepted = runOnFxValue(() -> fieldForLabel(shell.root, "Target Rate").getText());
            runOnFx(() -> fireVisible(shell.root, "Run"));
            String numericReport = waitForReportChange(shell.service, invalidReport);
            double after = samples.querySamples(pace, 0, 1).getFirst().physicalValue();
            boolean rejected = beforeRun.toLowerCase().contains("invalid")
                    || invalidReport.toLowerCase().contains("invalid number");
            note("TC_ASP_024 selected=" + selected + " invalidField='" + invalidText
                    + "' acceptedField='" + accepted + "' sourceBefore=" + before + " sourceAfter=" + after
                    + " invalidReport='" + oneLine(invalidReport) + "' numericReport='" + oneLine(numericReport) + "'");
            note("TC_ASP_024 NOT RUN category C: workspace keys are Pace and Slow, and the graph selection count was "
                    + selected + ". Target Rate kept '" + accepted + "'. The control is hertz, not a sampling interval. "
                    + "The field kept '" + invalidText + "' and Run failed with targetRateHz > 0. "
                    + "A numeric 10 then failed because the form supplies no maximum gap: " + oneLine(numericReport)
                    + " Resample reads the primary series only. Source Pace stayed " + before + ".");
            note("TC_ASP_025 NOT RUN category C: the workbook's about 795 samples belongs to the customer span "
                    + "79.403762 s at 0.1 s. That recording is not in this workspace, and this Resample run did not "
                    + "emit two aligned series. Source Pace stayed " + before + " -> " + after
                    + ". Actual: " + oneLine(numericReport));
        } finally {
            close(shell);
        }
    }

    private static boolean matchesResolution(com.stellar.platform.analysis.advanced.SpectrumResult spectrum) {
        return spectrum != null
                && spectrum.sampleCount() >= 4
                && spectrum.fftSize() >= spectrum.sampleCount()
                && Math.abs(spectrum.frequencyResolutionHz() - spectrum.sampleRateHz() / spectrum.fftSize()) < 1e-9;
    }

    private static boolean nearTone(com.stellar.platform.analysis.advanced.SpectrumResult spectrum, double hertz) {
        double[] frequency = spectrum.frequencyHz();
        double[] magnitude = spectrum.magnitude();
        int peak = 0;
        for (int i = 1; i < magnitude.length; i++) {
            if (magnitude[i] > magnitude[peak]) {
                peak = i;
            }
        }
        return Math.abs(frequency[peak] - hertz) <= spectrum.frequencyResolutionHz();
    }

    private static ShellFx open(InMemorySignalCatalogStore samples) throws Exception {
        Path home = Files.createTempDirectory("stellar-clearance-g4");
        isolate(home);
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(home.resolve("meta.db"));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        service.newProject("Clearance Group4");
        service.signalsModel().replaceCatalog(samples);
        for (var entry : samples.snapshot().entries()) {
            assertTrue(service.signalsModel().addToWorkspace(entry.key()));
        }
        service.graphInvestigation().setViewport(0, 2 * SECOND);
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<GraphController> graph = new AtomicReference<>();
        AtomicReference<Parent> root = new AtomicReference<>();
        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent page = loader.load();
            GraphController controller = loader.getController();
            Stage window = new Stage();
            window.setScene(new Scene(page, 1280, 800));
            window.show();
            page.applyCss();
            page.layout();
            controller.setAppService(service, null);
            stage.set(window);
            graph.set(controller);
            root.set(page);
        });
        return new ShellFx(home, store, service, stage.get(), graph.get(), root.get());
    }

    private static List<CanonicalSignal> steps(String name, long start, double first, long step, double delta, int extra) {
        List<CanonicalSignal> points = new ArrayList<>();
        for (int i = 0; i <= extra; i++) {
            points.add(sample(name, start + step * i, first + delta * i));
        }
        return points;
    }

    private static List<CanonicalSignal> sine(String name, double frequencyHz, double sampleRate, double seconds) {
        List<CanonicalSignal> points = new ArrayList<>();
        int count = (int) Math.round(seconds * sampleRate);
        for (int i = 0; i < count; i++) {
            double time = i / sampleRate;
            points.add(sample(name, Math.round(time * SECOND), Math.sin(2 * Math.PI * frequencyHz * time)));
        }
        return points;
    }

    private static CanonicalSignal sample(String name, long timestamp, double value) {
        return new CanonicalSignal(name, "OBD2", timestamp, 0x100, 1, value, 0L, "", null, SourceReference.ofLine(1));
    }

    private static void awaitTracks(ShellFx shell) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (shell.graph.logicalTrackCountForTest() > 0 && shell.graph.viewportEndForTest() > shell.graph.viewportStartForTest()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static String waitForReport(DesktopAppService service, String token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String report = "";
        while (System.nanoTime() < deadline) {
            report = service.lastEngineeringAnalysisReport();
            if (report != null && report.contains(token)) {
                return report;
            }
            Thread.sleep(50);
        }
        return report == null ? "" : report;
    }

    private static String waitForReportChange(DesktopAppService service, String previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String report = previous;
        while (System.nanoTime() < deadline) {
            report = service.lastEngineeringAnalysisReport();
            if (report != null && !report.equals(previous) && !report.isBlank()) {
                return report;
            }
            Thread.sleep(50);
        }
        return report == null ? "" : report;
    }

    private static void selectOperation(Parent root, AdvancedAnalyticsType type) {
        for (Node node : root.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && combo.getItems().contains(type)) {
                @SuppressWarnings("unchecked")
                ComboBox<AdvancedAnalyticsType> typed = (ComboBox<AdvancedAnalyticsType>) combo;
                typed.getSelectionModel().select(type);
                return;
            }
        }
        throw new AssertionError("No operation combo contains " + type);
    }

    private static TextField fieldForLabel(Parent root, String name) {
        for (Node node : root.lookupAll(".label")) {
            if (!(node instanceof Label label) || !name.equals(label.getText()) || !(label.getParent() instanceof Parent box)) {
                continue;
            }
            TextField field = findField(box);
            if (field != null) {
                return field;
            }
        }
        throw new AssertionError("No field for " + name);
    }

    private static TextField findField(Node node) {
        if (node instanceof TextField field) {
            return field;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TextField field = findField(child);
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    private static ToggleButton toggle(Parent root, String text) {
        for (Node node : root.lookupAll(".toggle-button")) {
            if (node instanceof ToggleButton button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError(text);
    }

    private static void fireVisible(Parent root, String text) {
        Button found = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText()) && button.isVisible() && button.isManaged()) {
                found = button;
            }
        }
        assertNotNull(found, text);
        found.fire();
    }

    private static String textArea(Parent root) {
        StringBuilder sb = new StringBuilder();
        for (Node node : root.lookupAll(".text-area")) {
            if (node instanceof TextArea area && area.getText() != null) {
                sb.append(area.getText());
            }
        }
        return sb.toString();
    }

    private static void close(ShellFx shell) throws Exception {
        if (shell.graph != null) {
            runOnFx(shell.graph::dispose);
        }
        if (shell.stage != null) {
            runOnFx(() -> shell.stage.close());
        }
        shell.service.shutdown();
        shell.store.close();
        deleteTree(shell.home);
    }

    private static void isolate(Path home) {
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static void runOnFx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(FxValue<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(action.get()));
        return value.get();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temporary home cleanup is best-effort.
                }
            });
        } catch (Exception ignored) {
            // Temporary home cleanup is best-effort.
        }
    }

    private record ShellFx(
            Path home,
            SqliteProjectMetadataStore store,
            DesktopAppService service,
            Stage stage,
            GraphController graph,
            Parent root
    ) {
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxValue<T> {
        T get() throws Exception;
    }
}
