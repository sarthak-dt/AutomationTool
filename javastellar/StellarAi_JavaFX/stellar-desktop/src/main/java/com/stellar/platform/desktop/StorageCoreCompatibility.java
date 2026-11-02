package com.stellar.platform.desktop;

import com.stellar.platform.storage.metadata.ProjectMetadata;

import java.time.Instant;

/**
 * Detects a stale {@code stellar-storage-core} SNAPSHOT on the runtime classpath.
 *
 * <p>{@code mvn -pl stellar-desktop javafx:run} without a current {@code mvn install}
 * (or {@code -am}) can load a pre-Phase-6 storage JAR. Desktop then throws
 * {@code NoSuchMethodError} on create/open because {@link ProjectMetadata} lacks
 * {@code lastWorkspace}.</p>
 */
public final class StorageCoreCompatibility {

    private StorageCoreCompatibility() {
    }

    public static boolean lastWorkspaceApiPresent() {
        try {
            ProjectMetadata.class.getDeclaredConstructor(
                    String.class, String.class, Instant.class, Instant.class, String.class);
            ProjectMetadata.class.getMethod("lastWorkspace");
            return true;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    public static String incompatibleClasspathMessage() {
        return """
                The installed storage module is older than this desktop build.
                Create and Open cannot work until sibling SNAPSHOTs are installed.

                From the repository root run:
                  mvn clean install
                  mvn -pl stellar-desktop -am org.openjfx:javafx-maven-plugin:run

                Isolated javafx:run after `mvn verify` (without install) loads stale ~/.m2 JARs.""";
    }
}
