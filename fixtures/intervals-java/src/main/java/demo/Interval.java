package demo;

/** A closed interval [start, end]. */
public record Interval(long start, long end) {

    public Interval {
        if (end < start) {
            throw new IllegalArgumentException("end must not precede start");
        }
    }

    /** Returns this interval stretched to cover newEnd, never shrinking. */
    public Interval extend(long newEnd) {
        return new Interval(start, Math.max(this.end, newEnd));
    }

    @Override
    public String toString() {
        return "[" + start + "," + end + "]";
    }
}
