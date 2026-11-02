package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDisplayNameResolverTest {

    @Test
    void longCommonPrefixKeepsDistinguishingSuffixVisible() {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        String f1 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4";
        String f2 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4";
        Map<ProjectSourceId, SourceDisplayNameResolver.DisplayLabel> labels =
                SourceDisplayNameResolver.resolve(List.of(
                        new SourceDisplayNameResolver.SourceRef(a, f1, Path.of("C:/data/" + f1)),
                        new SourceDisplayNameResolver.SourceRef(b, f2, Path.of("C:/data/" + f2))
                ));
        String d1 = labels.get(a).distinguishingName();
        String d2 = labels.get(b).distinguishingName();
        assertTrue(d1.contains("164651"), d1);
        assertTrue(d1.endsWith(".mf4"), d1);
        assertTrue(d2.endsWith(".mf4"), d2);
        assertTrue(d2.contains("171438"), d2);
        assertNotEquals(d1, d2);
        assertTrue(d1.length() < f1.length(), d1);
        assertEquals("Source A", labels.get(a).alias());
        assertEquals("Source B", labels.get(b).alias());
        assertEquals(f1, labels.get(a).fullFileName());
        assertEquals(f1, SourceDisplayNameResolver.primaryIdentity(labels.get(a)));
        assertEquals(f2, SourceDisplayNameResolver.primaryIdentity(labels.get(b)));
        assertTrue(SourceDisplayNameResolver.identityTooltip(labels.get(a)).contains(f1));
    }

    @Test
    void sameFileNameDifferentPathsUseAliasAndPathTooltip() {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        String name = "run.mf4";
        Map<ProjectSourceId, SourceDisplayNameResolver.DisplayLabel> labels =
                SourceDisplayNameResolver.resolve(List.of(
                        new SourceDisplayNameResolver.SourceRef(a, name, Path.of("C:/alpha/run.mf4")),
                        new SourceDisplayNameResolver.SourceRef(b, name, Path.of("C:/beta/run.mf4"))
                ));
        assertEquals(name, labels.get(a).distinguishingName());
        assertEquals(name, labels.get(b).distinguishingName());
        assertNotEquals(labels.get(a).alias(), labels.get(b).alias());
        assertTrue(labels.get(a).fullPath().contains("alpha"));
        assertTrue(labels.get(b).fullPath().contains("beta"));
    }

    @Test
    void peerClusterKeepsBengaluruCompactWhenDissimilarSourcePresent() {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        ProjectSourceId c = ProjectSourceId.generate();
        String f1 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4";
        String f2 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4";
        String f3 = "OBD2 Data_Lavasa_001.mf4";
        Map<ProjectSourceId, SourceDisplayNameResolver.DisplayLabel> labels =
                SourceDisplayNameResolver.resolve(List.of(
                        new SourceDisplayNameResolver.SourceRef(a, f1, Path.of("C:/data/" + f1)),
                        new SourceDisplayNameResolver.SourceRef(b, f2, Path.of("C:/data/" + f2)),
                        new SourceDisplayNameResolver.SourceRef(c, f3, Path.of("C:/data/" + f3))
                ));
        String d1 = labels.get(a).distinguishingName();
        String d2 = labels.get(b).distinguishingName();
        assertNotEquals(d1, d2);
        assertTrue(d1.contains("164651"), d1);
        assertTrue(d2.contains("171438"), d2);
        assertTrue(d1.length() < f1.length() - 10, d1);
        assertTrue(d2.length() < f2.length() - 10, d2);
    }

    @Test
    void dissimilarNamesAreNotOverAbbreviated() {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        Map<ProjectSourceId, SourceDisplayNameResolver.DisplayLabel> labels =
                SourceDisplayNameResolver.resolve(List.of(
                        new SourceDisplayNameResolver.SourceRef(a, "engine.mf4", Path.of("engine.mf4")),
                        new SourceDisplayNameResolver.SourceRef(b, "chassis.asc", Path.of("chassis.asc"))
                ));
        assertEquals("engine.mf4", labels.get(a).distinguishingName());
        assertEquals("chassis.asc", labels.get(b).distinguishingName());
    }

    @Test
    void shortClearNamesRemainUnchanged() {
        ProjectSourceId a = ProjectSourceId.generate();
        Map<ProjectSourceId, SourceDisplayNameResolver.DisplayLabel> labels =
                SourceDisplayNameResolver.resolve(List.of(
                        new SourceDisplayNameResolver.SourceRef(a, "a.mf4", Path.of("a.mf4"))
                ));
        assertEquals("a.mf4", labels.get(a).distinguishingName());
        assertFalse(labels.get(a).distinguishingName().startsWith("…"));
    }

    @Test
    void preservesExtensionAndTrailingIdInMiddleTruncate() {
        String longName = "OBD2_Data_Lavasa_CityDrive_Session_102230.mf4";
        String compact = SourceDisplayNameResolver.middleTruncate(longName, 36);
        assertTrue(compact.endsWith(".mf4"), compact);
        assertTrue(compact.contains("102230"), compact);
        assertTrue(compact.contains("\u2026") || compact.equals(longName), compact);
    }
}
