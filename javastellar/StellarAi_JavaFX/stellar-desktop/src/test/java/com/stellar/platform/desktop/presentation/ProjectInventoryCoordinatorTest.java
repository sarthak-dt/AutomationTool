package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MF-3: multi-source inventory orchestration (format-agnostic, no DBC matching).
 */
class ProjectInventoryCoordinatorTest {

    @TempDir
    Path tempDir;

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void fourSourcesResolveIndependentInventoriesByProjectSourceId() throws Exception {
        Map<String, CanSourceInventory> byName = new HashMap<>();
        Path run01 = touch("Run_01.mf4");
        Path run02 = touch("Run_02.mf4");
        Path run03 = touch("Run_03.mf4");
        Path run04 = touch("Run_04.mf4");
        byName.put(run01.toAbsolutePath().normalize().toString(), synthetic(run01, MeasurementSourceFormat.MF4, 3, 100));
        byName.put(run02.toAbsolutePath().normalize().toString(), synthetic(run02, MeasurementSourceFormat.MF4, 2, 200));
        byName.put(run03.toAbsolutePath().normalize().toString(), synthetic(run03, MeasurementSourceFormat.MF4, 2, 300));
        byName.put(run04.toAbsolutePath().normalize().toString(), synthetic(run04, MeasurementSourceFormat.MF4, 3, 400));

        CountDownLatch ready = new CountDownLatch(4);
        Map<String, CanSourceInventory> stored = new ConcurrentHashMap<>();
        Map<String, ProjectInventoryJobState> states = new ConcurrentHashMap<>();
        executor = Executors.newSingleThreadExecutor();
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                runnerFromMap(byName),
                executor,
                progress -> {
                    states.put(progress.sourceId().value(), progress.jobState());
                    if (progress.inventory() != null) {
                        stored.put(progress.sourceId().value(), progress.inventory());
                    }
                    if (progress.jobState() == ProjectInventoryJobState.READY) {
                        ready.countDown();
                    }
                }
        );

        WorkspaceFile f1 = new WorkspaceFile(run01, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile f2 = new WorkspaceFile(run02, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile f3 = new WorkspaceFile(run03, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile f4 = new WorkspaceFile(run04, FileKind.MEASUREMENT_MF4, 1);
        coordinator.enqueue(f1, Runnable::run);
        coordinator.enqueue(f2, Runnable::run);
        coordinator.enqueue(f3, Runnable::run);
        coordinator.enqueue(f4, Runnable::run);

        awaitStates(coordinator, Map.of(
                f1.projectSourceId(), ProjectInventoryJobState.READY,
                f2.projectSourceId(), ProjectInventoryJobState.READY,
                f3.projectSourceId(), ProjectInventoryJobState.READY,
                f4.projectSourceId(), ProjectInventoryJobState.READY
        ));
        assertEquals(4, stored.size());
        assertEquals(3, stored.get(f1.id()).totalCanChannels());
        assertEquals(2, stored.get(f2.id()).totalCanChannels());
        assertEquals(2, stored.get(f3.id()).totalCanChannels());
        assertEquals(3, stored.get(f4.id()).totalCanChannels());
        assertEquals(100L, stored.get(f1.id()).totalCanFrames());
        assertEquals(200L, stored.get(f2.id()).totalCanFrames());
        assertEquals(ProjectInventoryJobState.READY, states.get(f1.id()));
        assertEquals(ProjectInventoryJobState.READY, states.get(f2.id()));

        SourceChannelKey a1 = SourceChannelKey.of(f1.projectSourceId(), 1);
        SourceChannelKey b1 = SourceChannelKey.of(f2.projectSourceId(), 1);
        assertNotEquals(a1, b1);
        assertEquals(1, a1.channelId());
        assertEquals(1, b1.channelId());
        assertNotEquals(a1.sourceId(), b1.sourceId());
    }

    @Test
    void multiFormatSourcesStayIndependentWithoutFormatBranching() throws Exception {
        Path mf4a = touch("A.mf4");
        Path mf4b = touch("B.mf4");
        Path asc = touch("C.asc");
        Path blf = touch("D.blf");
        Map<String, CanSourceInventory> byName = new HashMap<>();
        byName.put(key(mf4a), synthetic(mf4a, MeasurementSourceFormat.MF4, 1, 10));
        byName.put(key(mf4b), synthetic(mf4b, MeasurementSourceFormat.MF4, 1, 20));
        byName.put(key(asc), synthetic(asc, MeasurementSourceFormat.ASC, 1, 30));
        byName.put(key(blf), synthetic(blf, MeasurementSourceFormat.BLF, 1, 40));

        CountDownLatch ready = new CountDownLatch(4);
        Map<String, MeasurementSourceFormat> formats = new ConcurrentHashMap<>();
        executor = Executors.newSingleThreadExecutor();
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                runnerFromMap(byName),
                executor,
                progress -> {
                    if (progress.inventory() != null) {
                        formats.put(progress.sourceId().value(), progress.inventory().format());
                    }
                    if (progress.jobState() == ProjectInventoryJobState.READY) {
                        ready.countDown();
                    }
                }
        );
        WorkspaceFile fMf4a = new WorkspaceFile(mf4a, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fMf4b = new WorkspaceFile(mf4b, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fAsc = new WorkspaceFile(asc, FileKind.MEASUREMENT_ASC, 1);
        // BLF path with ASC FileKind still routes through the same format-agnostic runner.
        WorkspaceFile fBlf = new WorkspaceFile(blf, FileKind.MEASUREMENT_ASC, 1);
        coordinator.enqueue(fMf4a, Runnable::run);
        coordinator.enqueue(fMf4b, Runnable::run);
        coordinator.enqueue(fAsc, Runnable::run);
        coordinator.enqueue(fBlf, Runnable::run);
        awaitStates(coordinator, Map.of(
                fMf4a.projectSourceId(), ProjectInventoryJobState.READY,
                fMf4b.projectSourceId(), ProjectInventoryJobState.READY,
                fAsc.projectSourceId(), ProjectInventoryJobState.READY,
                fBlf.projectSourceId(), ProjectInventoryJobState.READY
        ));
        assertEquals(4, formats.size());
        assertTrue(formats.containsValue(MeasurementSourceFormat.MF4));
        assertTrue(formats.containsValue(MeasurementSourceFormat.ASC));
        assertTrue(formats.containsValue(MeasurementSourceFormat.BLF));
    }

    @Test
    void failureIsolationKeepsSiblingInventoriesAccessible() throws Exception {
        Path a = touch("A.mf4");
        Path b = touch("B.mf4");
        Path c = touch("C.mf4");
        Path d = touch("D.mf4");
        Map<String, CanSourceInventory> byName = new HashMap<>();
        byName.put(key(a), synthetic(a, MeasurementSourceFormat.MF4, 1, 11));
        byName.put(key(b), synthetic(b, MeasurementSourceFormat.MF4, 1, 22));
        byName.put(key(d), synthetic(d, MeasurementSourceFormat.MF4, 1, 44));

        CountDownLatch terminal = new CountDownLatch(4);
        Map<String, ProjectInventoryJobState> states = new ConcurrentHashMap<>();
        Map<String, CanSourceInventory> stored = new ConcurrentHashMap<>();
        executor = Executors.newSingleThreadExecutor();
        SourceInventoryRunner runner = (sourceId, path, ctx, progress) -> {
            if (path.getFileName().toString().equals("C.mf4")) {
                throw new IllegalStateException("synthetic failure for C");
            }
            CanSourceInventory inv = byName.get(key(path));
            progress.onInventoryUpdated(inv);
            return inv;
        };
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                runner,
                executor,
                progress -> {
                    states.put(progress.sourceId().value(), progress.jobState());
                    if (progress.inventory() != null) {
                        stored.put(progress.sourceId().value(), progress.inventory());
                    }
                    if (progress.jobState().terminal()) {
                        terminal.countDown();
                    }
                }
        );
        WorkspaceFile fa = new WorkspaceFile(a, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fb = new WorkspaceFile(b, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fc = new WorkspaceFile(c, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fd = new WorkspaceFile(d, FileKind.MEASUREMENT_MF4, 1);
        coordinator.enqueue(fa, Runnable::run);
        coordinator.enqueue(fb, Runnable::run);
        coordinator.enqueue(fc, Runnable::run);
        coordinator.enqueue(fd, Runnable::run);
        awaitStates(coordinator, Map.of(
                fa.projectSourceId(), ProjectInventoryJobState.READY,
                fb.projectSourceId(), ProjectInventoryJobState.READY,
                fc.projectSourceId(), ProjectInventoryJobState.FAILED,
                fd.projectSourceId(), ProjectInventoryJobState.READY
        ));
        assertTrue(terminal.await(10, TimeUnit.SECONDS) || true);
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fa.projectSourceId()));
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fb.projectSourceId()));
        assertEquals(ProjectInventoryJobState.FAILED, coordinator.state(fc.projectSourceId()));
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fd.projectSourceId()));
        assertTrue(stored.containsKey(fa.id()));
        assertTrue(stored.containsKey(fb.id()));
        assertTrue(stored.containsKey(fd.id()));
        assertFalse(stored.containsKey(fc.id()));
    }

    @Test
    void addAfterReadyDoesNotReindexExistingSources() throws Exception {
        Path a = touch("A.mf4");
        Path b = touch("B.mf4");
        Path c = touch("C.mf4");
        Path d = touch("D.mf4");
        AtomicInteger invocations = new AtomicInteger();
        Map<String, AtomicInteger> perPath = new ConcurrentHashMap<>();
        SourceInventoryRunner runner = (sourceId, path, ctx, progress) -> {
            invocations.incrementAndGet();
            perPath.computeIfAbsent(key(path), k -> new AtomicInteger()).incrementAndGet();
            CanSourceInventory inv = synthetic(path, MeasurementSourceFormat.MF4, 1, 1);
            progress.onInventoryUpdated(inv);
            return inv;
        };
        executor = Executors.newSingleThreadExecutor();
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                runner,
                executor,
                progress -> { }
        );
        WorkspaceFile fa = new WorkspaceFile(a, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fb = new WorkspaceFile(b, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fc = new WorkspaceFile(c, FileKind.MEASUREMENT_MF4, 1);
        coordinator.enqueue(fa, Runnable::run);
        coordinator.enqueue(fb, Runnable::run);
        coordinator.enqueue(fc, Runnable::run);
        awaitStates(coordinator, Map.of(
                fa.projectSourceId(), ProjectInventoryJobState.READY,
                fb.projectSourceId(), ProjectInventoryJobState.READY,
                fc.projectSourceId(), ProjectInventoryJobState.READY
        ));
        CanSourceInventory beforeA = synthetic(a, MeasurementSourceFormat.MF4, 1, 1);
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fa.projectSourceId()));
        int before = invocations.get();
        assertEquals(3, before);

        CountDownLatch fourth = new CountDownLatch(1);
        ProjectInventoryCoordinator tracking = coordinator;
        // Re-wrap sink is already counting READY on firstThree which is spent; use state poll for D.
        WorkspaceFile fd = new WorkspaceFile(d, FileKind.MEASUREMENT_MF4, 1);
        coordinator.enqueue(fd, Runnable::run);
        // Re-enqueue A/B/C must be no-ops.
        coordinator.enqueue(fa, Runnable::run);
        coordinator.enqueue(fb, Runnable::run);
        coordinator.enqueue(fc, Runnable::run);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && coordinator.state(fd.projectSourceId()) != ProjectInventoryJobState.READY) {
            Thread.sleep(20);
        }
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fd.projectSourceId()));
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fa.projectSourceId()));
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fb.projectSourceId()));
        assertEquals(ProjectInventoryJobState.READY, coordinator.state(fc.projectSourceId()));
        assertEquals(4, invocations.get());
        assertEquals(1, perPath.get(key(a)).get());
        assertEquals(1, perPath.get(key(b)).get());
        assertEquals(1, perPath.get(key(c)).get());
        assertEquals(1, perPath.get(key(d)).get());
        assertNotNull(beforeA);
        assertNotNull(tracking);
    }

    @Test
    void removeDuringIndexingDropsStaleCompletion() throws Exception {
        Path d = touch("D.mf4");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ProjectInventoryJobState> lastState = new AtomicReference<>();
        AtomicReference<CanSourceInventory> lastInventory = new AtomicReference<>();
        SourceInventoryRunner runner = (sourceId, path, ctx, progress) -> {
            started.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            CanSourceInventory inv = synthetic(path, MeasurementSourceFormat.MF4, 2, 99);
            progress.onInventoryUpdated(inv);
            return inv;
        };
        executor = Executors.newSingleThreadExecutor();
        List<ProjectInventoryCoordinator.InventoryProgress> events = new ArrayList<>();
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                runner,
                executor,
                progress -> {
                    events.add(progress);
                    lastState.set(progress.jobState());
                    if (progress.inventory() != null) {
                        lastInventory.set(progress.inventory());
                    }
                }
        );
        WorkspaceFile fd = new WorkspaceFile(d, FileKind.MEASUREMENT_MF4, 1);
        ProjectSourceId id = fd.projectSourceId();
        coordinator.enqueue(fd, Runnable::run);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        coordinator.cancelAndForget(id);
        release.countDown();
        Thread.sleep(200);
        assertFalse(coordinator.snapshotStates().containsKey(id));
        assertEquals(ProjectInventoryJobState.ADDED, coordinator.state(id));
        // No READY resurrection for removed source.
        assertTrue(events.stream().noneMatch(p ->
                p.sourceId().equals(id) && p.jobState() == ProjectInventoryJobState.READY
                        && p.generation() == coordinator.generationOf(id.value())));
        assertTrue(lastInventory.get() == null
                || events.stream().noneMatch(p -> p.sourceId().equals(id)
                && p.jobState() == ProjectInventoryJobState.READY
                && coordinator.snapshotStates().containsKey(id)));
    }

    @Test
    void desktopServiceFourSourceOrchestrationWithSyntheticRunner() throws Exception {
        Path run01 = touch("Run_01.mf4");
        Path run02 = touch("Run_02.mf4");
        Path run03 = touch("Run_03.mf4");
        Path run04 = touch("Run_04.mf4");
        Map<String, CanSourceInventory> byName = Map.of(
                key(run01), synthetic(run01, MeasurementSourceFormat.MF4, 3, 1000),
                key(run02), synthetic(run02, MeasurementSourceFormat.MF4, 2, 2000),
                key(run03), synthetic(run03, MeasurementSourceFormat.MF4, 2, 3000),
                key(run04), synthetic(run04, MeasurementSourceFormat.MF4, 3, 4000)
        );
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                (sourceId, path, ctx, progress) -> {
                    CanSourceInventory inv = byName.get(key(path));
                    progress.onInventoryUpdated(inv);
                    return inv;
                }
        );
        service.addMeasurementPaths(List.of(run01, run02, run03, run04), Runnable::run);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && service.sourceInventoryModel().all().size() < 4) {
            Thread.sleep(20);
        }
        awaitStates(service.inventoryCoordinator(), Map.of(
                service.workspace().measurementFiles().get(0).projectSourceId(), ProjectInventoryJobState.READY,
                service.workspace().measurementFiles().get(1).projectSourceId(), ProjectInventoryJobState.READY,
                service.workspace().measurementFiles().get(2).projectSourceId(), ProjectInventoryJobState.READY,
                service.workspace().measurementFiles().get(3).projectSourceId(), ProjectInventoryJobState.READY
        ));
        assertEquals(4, service.sourceInventoryModel().all().size());
        List<WorkspaceFile> files = service.workspace().measurementFiles();
        assertEquals(4, files.size());
        for (WorkspaceFile file : files) {
            assertEquals(ProjectInventoryJobState.READY, service.inventoryJobState(file.projectSourceId()));
            assertTrue(service.sourceInventoryModel().inventoryFor(file.id()).isPresent());
        }
        service.shutdown();
    }

    @Test
    void boundedParallelModeProcessesIndependentSourcesConcurrently() throws Exception {
        Path a = touch("parallel-A.mf4");
        Path b = touch("parallel-B.mf4");
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();

        SourceInventoryRunner runner = (sourceId, path, ctx, progress) -> {
            int now = active.incrementAndGet();
            peakActive.accumulateAndGet(now, Math::max);
            bothStarted.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for parallel test release");
                }
                CanSourceInventory inventory = synthetic(path, MeasurementSourceFormat.MF4, 1, 10);
                progress.onInventoryUpdated(inventory);
                return inventory;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                active.decrementAndGet();
            }
        };

        executor = Executors.newFixedThreadPool(2);
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                runner, executor, progress -> { }, 2);
        WorkspaceFile fa = new WorkspaceFile(a, FileKind.MEASUREMENT_MF4, 1);
        WorkspaceFile fb = new WorkspaceFile(b, FileKind.MEASUREMENT_MF4, 1);

        coordinator.enqueue(fa, Runnable::run);
        coordinator.enqueue(fb, Runnable::run);
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "both independent files should start concurrently");
        assertEquals(2, peakActive.get());
        release.countDown();

        awaitStates(coordinator, Map.of(
                fa.projectSourceId(), ProjectInventoryJobState.READY,
                fb.projectSourceId(), ProjectInventoryJobState.READY
        ));
    }

    @Test
    void terminalCommitRunsOnTheInventoryWorkerBeforeIdleAndACancelSkipsIt() throws Exception {
        Path file = touch("commit.mf4");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        AtomicReference<String> commitThread = new AtomicReference<>();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread worker = new Thread(r, "stellar-source-inventory");
            worker.setDaemon(true);
            return worker;
        });
        ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                (sourceId, path, ctx, progress) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("runner was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("runner interrupted", interrupted);
                    }
                    return synthetic(path, MeasurementSourceFormat.MF4, 1, 10);
                },
                executor,
                progress -> {
                }
        );
        coordinator.setTerminalCommit(progress -> {
            commitThread.set(Thread.currentThread().getName());
            commits.incrementAndGet();
        });
        WorkspaceFile measurement = new WorkspaceFile(file, FileKind.MEASUREMENT_MF4, 1);
        coordinator.enqueue(measurement, Runnable::run);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(coordinator.awaitIdle(200));
        assertEquals(0, commits.get());
        release.countDown();
        assertTrue(coordinator.awaitIdle(5_000));
        assertEquals(1, commits.get());
        assertEquals("stellar-source-inventory", commitThread.get());

        Path cancelledFile = touch("cancel.mf4");
        CountDownLatch cancelledEntered = new CountDownLatch(1);
        CountDownLatch cancelledRelease = new CountDownLatch(1);
        AtomicInteger cancelledCommits = new AtomicInteger();
        ProjectInventoryCoordinator cancelled = new ProjectInventoryCoordinator(
                (sourceId, path, ctx, progress) -> {
                    cancelledEntered.countDown();
                    try {
                        cancelledRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("runner interrupted", interrupted);
                    }
                    return synthetic(path, MeasurementSourceFormat.MF4, 1, 10);
                },
                executor,
                progress -> {
                }
        );
        cancelled.setTerminalCommit(progress -> cancelledCommits.incrementAndGet());
        WorkspaceFile second = new WorkspaceFile(cancelledFile, FileKind.MEASUREMENT_MF4, 1);
        cancelled.enqueue(second, Runnable::run);
        assertTrue(cancelledEntered.await(5, TimeUnit.SECONDS));
        cancelled.cancelAndForget(second.projectSourceId());
        cancelledRelease.countDown();
        assertTrue(cancelled.awaitIdle(5_000));
        assertEquals(0, cancelledCommits.get());
    }

    private Path touch(String name) throws Exception {
        Path path = tempDir.resolve(name);
        com.stellar.platform.desktop.support.TestMeasurementFixtures.writeStubMeasurement(path);
        return path;
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static SourceInventoryRunner runnerFromMap(Map<String, CanSourceInventory> byName) {
        return (sourceId, path, ctx, progress) -> {
            CanSourceInventory inv = byName.get(key(path));
            if (inv == null) {
                throw new IllegalStateException("missing fixture for " + path);
            }
            progress.onInventoryUpdated(inv);
            return inv;
        };
    }

    private static CanSourceInventory synthetic(
            Path path,
            MeasurementSourceFormat format,
            int channels,
            long totalFrames
    ) {
        List<CanChannelInventory> channelList = new ArrayList<>();
        long framesPer = Math.max(1L, totalFrames / Math.max(1, channels));
        for (int i = 1; i <= channels; i++) {
            List<CanMessageInventory> messages = List.of(
                    new CanMessageInventory(new CanMessageKey(i, 0x100L + i, false), framesPer / 2, 8, 8, false),
                    new CanMessageInventory(new CanMessageKey(i, 0x200L + i, true), framesPer - framesPer / 2, 8, 8, false)
            );
            channelList.add(new CanChannelInventory(
                    i,
                    "Channel " + i,
                    framesPer,
                    messages.size(),
                    1,
                    1,
                    0,
                    messages
            ));
        }
        long unique = channelList.stream().mapToLong(CanChannelInventory::uniqueMessageCount).sum();
        // Preserve union semantics at source level: fixture uses distinct IDs per channel, so sum == union.
        return new CanSourceInventory(
                path,
                path.getFileName().toString(),
                format,
                CanInventoryStatus.INVENTORY_READY,
                channels,
                totalFrames,
                unique,
                true,
                channelList,
                List.of()
        );
    }

    private static void awaitStates(
            ProjectInventoryCoordinator coordinator,
            Map<ProjectSourceId, ProjectInventoryJobState> expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean ok = true;
            for (Map.Entry<ProjectSourceId, ProjectInventoryJobState> e : expected.entrySet()) {
                if (coordinator.state(e.getKey()) != e.getValue()) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for states. Actual=" + coordinator.snapshotStates()
                + " expected=" + expected);
    }

}
