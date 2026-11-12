from dataclasses import dataclass


@dataclass(frozen=True)
class Interval:
    """A closed interval [start, end]."""

    start: int
    end: int

    def __post_init__(self) -> None:
        if self.end < self.start:
            raise ValueError("end must not precede start")

    def extend(self, new_end: int) -> "Interval":
        """Returns this interval stretched to cover new_end, never shrinking."""
        return Interval(self.start, max(self.end, new_end))
