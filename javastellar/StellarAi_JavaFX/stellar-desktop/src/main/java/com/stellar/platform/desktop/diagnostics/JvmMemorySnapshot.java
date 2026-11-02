package com.stellar.platform.desktop.diagnostics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Developer/NFT helper for distinguishing heap USED vs COMMITTED vs non-heap.
 * Not a production UI; safe to call from tests and diagnostics.
 */
public final class JvmMemorySnapshot {

    public record Snapshot(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long nonHeapCommittedBytes,
            long metaspaceUsedBytes,
            long metaspaceCommittedBytes,
            long gcCount,
            int threadCount,
            int stellarThreadCount
    ) {
        public Map<String, Long> asMap() {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("heapUsed", heapUsedBytes);
            m.put("heapCommitted", heapCommittedBytes);
            m.put("heapMax", heapMaxBytes);
            m.put("nonHeapUsed", nonHeapUsedBytes);
            m.put("nonHeapCommitted", nonHeapCommittedBytes);
            m.put("metaspaceUsed", metaspaceUsedBytes);
            m.put("metaspaceCommitted", metaspaceCommittedBytes);
            m.put("gcCount", gcCount);
            m.put("threadCount", (long) threadCount);
            m.put("stellarThreadCount", (long) stellarThreadCount);
            return m;
        }

        @Override
        public String toString() {
            return "heapUsed=" + mb(heapUsedBytes)
                    + "MB heapCommitted=" + mb(heapCommittedBytes)
                    + "MB metaspaceUsed=" + mb(metaspaceUsedBytes)
                    + "MB nonHeapUsed=" + mb(nonHeapUsedBytes)
                    + "MB threads=" + threadCount
                    + " stellarThreads=" + stellarThreadCount
                    + " gcCount=" + gcCount;
        }

        private static long mb(long bytes) {
            return Math.round(bytes / (1024.0 * 1024.0));
        }
    }

    private JvmMemorySnapshot() {
    }

    public static Snapshot capture() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long gc = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            if (c > 0) gc += c;
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MemoryUsage metaspace = metaspaceUsage();
        return new Snapshot(
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                nonHeap.getCommitted(),
                metaspace.getUsed(),
                metaspace.getCommitted(),
                gc,
                threads.getThreadCount(),
                stellarThreadCount()
        );
    }

    private static MemoryUsage metaspaceUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName() != null && pool.getName().toLowerCase().contains("metaspace")) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    return usage;
                }
            }
        }
        return new MemoryUsage(0, 0, 0, 0);
    }

    private static int stellarThreadCount() {
        int n = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (thread.isAlive() && name != null && name.startsWith("stellar-")) {
                n++;
            }
        }
        return n;
    }

    /** Optional NFT-only hint; JVM may ignore. Never used as the primary Clear fix. */
    public static void suggestGcForNftOnly() {
        System.gc();
    }
}
