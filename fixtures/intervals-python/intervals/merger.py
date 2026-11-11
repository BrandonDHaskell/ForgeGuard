from intervals.interval import Interval


def merge(intervals: list[Interval] | None) -> list[Interval]:
    """Merges overlapping and touching intervals.

    Takes intervals in any order and returns them merged, sorted by start.
    """
    if intervals is None:
        raise ValueError("input must not be None")
    if not intervals:
        return []

    ordered = sorted(intervals, key=lambda i: i.start)

    out = [ordered[0]]
    for cur in ordered[1:]:
        last = out[-1]
        if cur.start < last.end:
            out[-1] = last.extend(cur.end)
        else:
            out.append(cur)
    return out
