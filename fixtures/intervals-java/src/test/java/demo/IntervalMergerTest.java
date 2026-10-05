package demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntervalMergerTest {

    @Test
    void returnsEmptyForEmptyInput() {
        assertEquals(List.of(), IntervalMerger.merge(List.of()));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> IntervalMerger.merge(null));
    }

    @Test
    void passesThroughDisjointIntervals() {
        List<Interval> in = List.of(new Interval(1, 2), new Interval(5, 7));
        assertEquals(in, IntervalMerger.merge(in));
    }

    @Test
    void mergesOverlapping() {
        List<Interval> in = List.of(new Interval(1, 5), new Interval(3, 8));
        assertEquals(List.of(new Interval(1, 8)), IntervalMerger.merge(in));
    }

    @Test
    void sortsUnorderedInput() {
        List<Interval> in = List.of(new Interval(9, 11), new Interval(1, 3));
        assertEquals(List.of(new Interval(1, 3), new Interval(9, 11)), IntervalMerger.merge(in));
    }

    @Test
    void mergesContainedInterval() {
        List<Interval> in = List.of(new Interval(1, 10), new Interval(2, 3));
        assertEquals(List.of(new Interval(1, 10)), IntervalMerger.merge(in));
    }

    @Test
    void mergesAdjacentIntervals() {
        List<Interval> in = List.of(new Interval(1, 3), new Interval(3, 5));
        assertEquals(List.of(new Interval(1, 5)), IntervalMerger.merge(in));
    }
}
