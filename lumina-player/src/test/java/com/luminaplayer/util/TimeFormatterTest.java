package com.luminaplayer.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimeFormatterTest {

    @Test
    void formatZero() {
        assertEquals("00:00", TimeFormatter.format(0));
    }

    @Test
    void formatSeconds() {
        assertEquals("00:45", TimeFormatter.format(45000));
    }

    @Test
    void formatMinutesAndSeconds() {
        assertEquals("05:30", TimeFormatter.format(330000));
    }

    @Test
    void formatHoursMinutesSeconds() {
        assertEquals("1:30:00", TimeFormatter.format(5400000));
    }

    @Test
    void formatNegativeReturnsZero() {
        assertEquals("00:00", TimeFormatter.format(-1000));
    }

    @Test
    void formatFullIncludesMillis() {
        assertEquals("01:30:00.500", TimeFormatter.formatFull(5400500));
    }

    @Test
    void formatFullZero() {
        assertEquals("00:00:00.000", TimeFormatter.formatFull(0));
    }
}
