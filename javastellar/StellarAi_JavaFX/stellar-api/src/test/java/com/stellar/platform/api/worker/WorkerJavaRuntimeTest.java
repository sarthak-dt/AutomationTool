package com.stellar.platform.api.worker;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerJavaRuntimeTest {

    @Test
    void executableIsBundledJavaHomeBinaryNotPath() {
        Path java = WorkerJavaRuntime.executable();
        assertTrue(Files.isRegularFile(java), java.toString());
        String name = java.getFileName().toString().toLowerCase();
        assertTrue(name.equals("java.exe") || name.equals("java"), name);
        Path home = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        assertTrue(java.startsWith(home), "worker Java must be under java.home=" + home + " but was " + java);
        assertTrue(java.getParent() != null && java.getParent().endsWith("bin"));
    }
}
