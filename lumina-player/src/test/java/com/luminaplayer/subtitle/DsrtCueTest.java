package com.luminaplayer.subtitle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DsrtCueTest {

    @Test
    void testCueCreation() {
        DsrtCue cue = new DsrtCue(1, 1000, 5000, "Hello world", 0);
        assertEquals(1, cue.id());
        assertEquals(1000, cue.startTimeMs());
        assertEquals(5000, cue.endTimeMs());
        assertEquals("Hello world", cue.text());
        assertEquals(0, cue.chunkIndex());
    }

    @Test
    void testCompareTo_sortsChronologically() {
        DsrtCue cue1 = new DsrtCue(1, 1000, 3000, "First", 0);
        DsrtCue cue2 = new DsrtCue(2, 5000, 8000, "Second", 0);
        DsrtCue cue3 = new DsrtCue(3, 3000, 5000, "Third", 0);

        List<DsrtCue> cues = new ArrayList<>(List.of(cue2, cue3, cue1));
        Collections.sort(cues);

        assertEquals("First", cues.get(0).text());
        assertEquals("Third", cues.get(1).text());
        assertEquals("Second", cues.get(2).text());
    }

    @Test
    void testCompareTo_equalStartTimes() {
        DsrtCue cue1 = new DsrtCue(1, 1000, 3000, "A", 0);
        DsrtCue cue2 = new DsrtCue(2, 1000, 5000, "B", 0);

        assertEquals(0, cue1.compareTo(cue2));
    }

    @Test
    void testRecordEquality() {
        DsrtCue cue1 = new DsrtCue(1, 1000, 5000, "Hello", 0);
        DsrtCue cue2 = new DsrtCue(1, 1000, 5000, "Hello", 0);

        assertEquals(cue1, cue2);
        assertEquals(cue1.hashCode(), cue2.hashCode());
    }

    @Test
    void testRecordInequality() {
        DsrtCue cue1 = new DsrtCue(1, 1000, 5000, "Hello", 0);
        DsrtCue cue2 = new DsrtCue(2, 1000, 5000, "Hello", 0);

        assertNotEquals(cue1, cue2);
    }

    @Test
    void testMultilineText() {
        DsrtCue cue = new DsrtCue(1, 1000, 5000, "Line one\nLine two", 0);
        assertTrue(cue.text().contains("\n"));
    }
}
