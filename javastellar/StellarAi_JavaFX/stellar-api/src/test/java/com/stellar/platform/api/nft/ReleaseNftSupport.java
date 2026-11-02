package com.stellar.platform.api.nft;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Shared host / heap telemetry for Phase 9C NFT. Test-only. */
public final class ReleaseNftSupport {

    private ReleaseNftSupport() {
    }

    public static Map<String, Object> hostBaseline(Path analysisRoot) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capturedAt", Instant.now().toString());
        m.put("osName", System.getProperty("os.name"));
        m.put("osVersion", System.getProperty("os.version"));
        m.put("osArch", System.getProperty("os.arch"));
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaHome", System.getProperty("java.home"));
        m.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        m.put("osMxBean", os.getClass().getName());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            m.put("physicalRamBytes", sun.getTotalMemorySize());
            m.put("freeRamBytes", sun.getFreeMemorySize());
            m.put("cpuLoad", sun.getCpuLoad());
        } else {
            m.put("physicalRamBytes", "NOT AVAILABLE");
            m.put("freeRamBytes", "NOT AVAILABLE");
        }
        m.put("cpuModel", System.getenv("PROCESSOR_IDENTIFIER"));
        FileStore store = Files.getFileStore(analysisRoot.toAbsolutePath().getRoot() == null
                ? Path.of(".").toAbsolutePath()
                : analysisRoot.toAbsolutePath());
        try {
            store = Files.getFileStore(analysisRoot);
        } catch (IOException ex) {
            store = Files.getFileStore(Path.of(".").toAbsolutePath());
        }
        m.put("analysisPath", analysisRoot.toAbsolutePath().toString());
        m.put("fileStore", store.name() + " " + store.type());
        m.put("freeDiskBytes", store.getUsableSpace());
        m.put("totalDiskBytes", store.getTotalSpace());
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        m.put("desktopHeapUsed", heap.getUsed());
        m.put("desktopHeapCommitted", heap.getCommitted());
        m.put("desktopHeapMax", heap.getMax());
        m.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("desktopPid", ProcessHandle.current().pid());
        m.put("workerJvm", "same JVM (LocalPlanWorkerEngine unless a test starts ProcessIsolatedLocalWorker)");
        m.put("xmxHint", heap.getMax());
        return m;
    }

    public static long heapUsed() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getUsed();
    }

    public static int threadCount() {
        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        return t.getThreadCount();
    }

    public static Long openHandleCountOrNull() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        try {
            var method = os.getClass().getMethod("getOpenFileDescriptorCount");
            Object v = method.invoke(os);
            if (v instanceof Number n) {
                return n.longValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    public static long usableDisk(Path path) throws IOException {
        Path probe = Files.isDirectory(path) ? path : path.getParent();
        if (probe == null) {
            probe = Path.of(".").toAbsolutePath();
        }
        Files.createDirectories(probe);
        return Files.getFileStore(probe).getUsableSpace();
    }

    public static long maxInputBytes() {
        return propertyMb("stellar.nft.max.input.mb", 512L);
    }

    public static long minInputBytes() {
        return propertyMb("stellar.nft.min.input.mb", 0L);
    }

    private static long propertyMb(String key, long fallbackMb) {
        String raw = System.getProperty(key, Long.toString(fallbackMb));
        try {
            return Long.parseLong(raw.trim()) * 1024L * 1024L;
        } catch (NumberFormatException ex) {
            return fallbackMb * 1024L * 1024L;
        }
    }

    /**
     * Peak-only heap sampler. Does not retain a sample history (NFT telemetry
     * must stay bounded and is discarded when the test ends).
     */
    public static final class HeapSampler implements AutoCloseable {
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stellar-nft-heap");
            t.setDaemon(true);
            return t;
        });
        private volatile long peakUsed;

        public HeapSampler start(long periodSeconds) {
            exec.scheduleAtFixedRate(() -> peakUsed = Math.max(peakUsed, heapUsed()),
                    0L, periodSeconds, TimeUnit.SECONDS);
            return this;
        }

        public HeapSampler startExternal(long pid, long periodSeconds) {
            exec.scheduleAtFixedRate(() -> {
                Long used = externalHeapUsedBytes(pid);
                if (used != null) {
                    peakUsed = Math.max(peakUsed, used);
                }
            }, 0L, periodSeconds, TimeUnit.SECONDS);
            return this;
        }

        public long peakUsed() {
            return Math.max(peakUsed, heapUsed());
        }

        public long peakUsedOnly() {
            return peakUsed;
        }

        @Override
        public void close() {
            exec.shutdownNow();
        }
    }

    /**
     * Worker-JVM used heap via {@code jstat} attach. Independent of the worker
     * socket, so it can run while {@code submitTask} holds the IO lock.
     * Returns {@code null} when jstat is missing or attach fails.
     */
    public static Long externalHeapUsedBytes(long pid) {
        if (pid <= 0L) {
            return null;
        }
        Path jstat = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "jstat.exe" : "jstat");
        if (!Files.isRegularFile(jstat)) {
            return null;
        }
        ProcessBuilder pb = new ProcessBuilder(jstat.toString(), "-gc", Long.toString(pid));
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String output;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                output = r.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return parseJstatUsedBytes(output);
        } catch (Exception e) {
            return null;
        }
    }

    static Long parseJstatUsedBytes(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String[] lines = output.strip().split("\\R");
        if (lines.length < 2) {
            return null;
        }
        String[] headers = lines[0].trim().split("\\s+");
        String[] values = lines[1].trim().split("\\s+");
        if (headers.length != values.length) {
            return null;
        }
        double usedKb = 0d;
        boolean found = false;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i];
            if ("S0U".equals(h) || "S1U".equals(h) || "EU".equals(h) || "OU".equals(h)) {
                try {
                    usedKb += Double.parseDouble(values[i]);
                    found = true;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        if (!found) {
            return null;
        }
        return Math.round(usedKb * 1024d);
    }
}
