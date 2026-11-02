package com.stellar.platform.processing.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalResourceManagerTest {

    @TempDir
    Path temp;

    private final LocalResourceManager manager = new LocalResourceManager();

    @Test
    void normalDiskPermits() {
        LocalResourceManager.Admission admission = manager.admit(temp, 1024L, 0, 8);
        assertTrue(admission.allowed(), admission.reason());
    }

    @Test
    void missingRootBlocks() {
        LocalResourceManager.Admission admission = manager.admit(null, 1024L, 0, 8);
        assertFalse(admission.allowed());
        assertTrue(admission.reason().toLowerCase().contains("analysis storage"));
    }

    @Test
    void fullQueueBlocks() {
        LocalResourceManager.Admission admission = manager.admit(temp, 1024L, 8, 8);
        assertFalse(admission.allowed());
    }

    @Test
    void simulatedLowSpaceNeverAdmits() {
        LocalResourceManager tight = new LocalResourceManager(1024L);
        LocalResourceManager.Admission admission = tight.admit(temp, 2048L, 0, 8);
        assertFalse(admission.allowed());
        assertTrue(admission.reason().contains("Insufficient analysis storage space"));
    }
}
