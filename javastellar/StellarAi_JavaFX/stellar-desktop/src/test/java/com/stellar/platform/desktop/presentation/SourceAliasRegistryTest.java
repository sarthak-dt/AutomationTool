package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceAliasRegistryTest {

    @Test
    void assignsStableAliasesInCallOrderAndPreservesGaps() {
        SourceAliasRegistry registry = new SourceAliasRegistry();
        ProjectSourceId a = ProjectSourceId.of("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        ProjectSourceId b = ProjectSourceId.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        ProjectSourceId c = ProjectSourceId.of("cccccccc-cccc-cccc-cccc-cccccccccccc");

        assertEquals("Source A", registry.aliasFor(a));
        assertEquals("Source B", registry.aliasFor(b));
        assertEquals("Source C", registry.aliasFor(c));
        assertEquals("Source A", registry.aliasFor(a));

        registry.retainOnly(Set.of(a.value(), c.value()));
        assertNull(registry.aliasOrNull(b));
        assertEquals("Source A", registry.aliasFor(a));
        assertEquals("Source C", registry.aliasFor(c));

        ProjectSourceId d = ProjectSourceId.of("dddddddd-dddd-dddd-dddd-dddddddddddd");
        assertEquals("Source D", registry.aliasFor(d));
    }

    @Test
    void clearResetsAllocationToSourceA() {
        SourceAliasRegistry registry = new SourceAliasRegistry();
        ProjectSourceId first = ProjectSourceId.generate();
        ProjectSourceId second = ProjectSourceId.generate();
        assertEquals("Source A", registry.aliasFor(first));
        assertEquals("Source B", registry.aliasFor(second));

        registry.clear();
        ProjectSourceId next = ProjectSourceId.generate();
        assertEquals("Source A", registry.aliasFor(next));
    }

    @Test
    void freshRegistryStartsAtSourceAThenBThenC() {
        SourceAliasRegistry registry = new SourceAliasRegistry();
        assertEquals("Source A", registry.aliasFor(ProjectSourceId.generate()));
        assertEquals("Source B", registry.aliasFor(ProjectSourceId.generate()));
        assertEquals("Source C", registry.aliasFor(ProjectSourceId.generate()));
    }
}