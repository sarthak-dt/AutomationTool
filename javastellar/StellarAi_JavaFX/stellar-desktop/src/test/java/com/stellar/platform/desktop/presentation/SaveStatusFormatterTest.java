package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveStatusFormatterTest {

    @Test
    void todayShowsTimeOnly() {
        ZoneId zone = ZoneId.of("Europe/London");
        Instant saved = ZonedDateTime.of(LocalDate.now(zone).atTime(18, 27), zone).toInstant();
        assertEquals("Saved 18:27", SaveStatusFormatter.compactStatus(saved, zone));
        assertTrue(SaveStatusFormatter.tooltip(saved, zone).contains("18:27:00"));
    }

    @Test
    void nullInstantShowsSavedWithoutTime() {
        assertEquals("Saved", SaveStatusFormatter.compactStatus(null, ZoneId.systemDefault()));
    }
}
