package demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Collapses a set of intervals into the smallest equivalent set. */
public final class IntervalMerger {

    private IntervalMerger() {
    }

    /**
     * Merges overlapping and touching intervals.
     *
     * @param in intervals in any order
     * @return merged intervals sorted by start
     */
    public static List<Interval> merge(List<Interval> in) {
        if (in == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (in.isEmpty()) {
            return List.of();
        }

        List<Interval> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingLong(Interval::start));

        List<Interval> out = new ArrayList<>();
        out.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            Interval cur = sorted.get(i);
            Interval last = out.get(out.size() - 1);
            if (cur.start() < last.end()) {
                out.set(out.size() - 1, last.extend(cur.end()));
            } else {
                out.add(cur);
            }
        }
        return List.copyOf(out);
    }
}
