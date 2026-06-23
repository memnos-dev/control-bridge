package dev.memnos.controlbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinuteOfDayTest {

    @Test
    void mapsKnownProbes() {
        assertEquals(360, WorldQueryResult.minuteOfDay(0));      // 06:00
        assertEquals(720, WorldQueryResult.minuteOfDay(6000));   // 12:00
        assertEquals(1080, WorldQueryResult.minuteOfDay(12000)); // 18:00
        assertEquals(0, WorldQueryResult.minuteOfDay(18000));    // 00:00
    }

    @Test
    void normalizesAndWraps() {
        assertEquals(360, WorldQueryResult.minuteOfDay(24000));
        assertEquals(359, WorldQueryResult.minuteOfDay(23999));
        assertEquals(360, WorldQueryResult.minuteOfDay(48000));
        assertEquals(360, WorldQueryResult.minuteOfDay(-24000));
    }
}