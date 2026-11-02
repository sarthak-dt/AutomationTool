package com.stellar.platform.api.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerParentLifetimeTest {

    @Test
    void parentPidArgumentIsParsed() {
        assertEquals(4242L, StellarLocalWorkerMain.parentPidFrom(new String[] {"--token", "abc", "--parent-pid", "4242"}));
        assertEquals(-1L, StellarLocalWorkerMain.parentPidFrom(new String[] {"--token", "abc"}));
    }

    @Test
    @Timeout(20)
    void workerExitsWhenParentProcessIsAlreadyDead() throws Exception {
        Process dummy = new ProcessBuilder("cmd.exe", "/c", "exit", "0").start();
        dummy.waitFor(5, TimeUnit.SECONDS);
        long deadPid = dummy.pid();
        assertFalse(dummy.isAlive());

        Path java = WorkerJavaRuntime.executable();
        String classpath = WorkerClasspath.resolve();
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath);
        command.add(StellarLocalWorkerMain.class.getName());
        command.add("--token");
        command.add("deadparenttoken");
        command.add("--parent-pid");
        command.add(Long.toString(deadPid));
        Process worker = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = worker.waitFor(8, TimeUnit.SECONDS);
        if (!exited) {
            worker.destroyForcibly();
        }
        assertTrue(exited, "worker must exit when the recorded parent PID is already dead");
        assertEquals(0, worker.exitValue());
    }
}
