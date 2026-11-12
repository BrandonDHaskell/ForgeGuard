import pytest

from intervals import Interval, merge


def test_returns_empty_for_empty_input():
    assert merge([]) == []


def test_rejects_none_input():
    with pytest.raises(ValueError):
        merge(None)


def test_passes_through_disjoint_intervals():
    given = [Interval(1, 2), Interval(5, 7)]
    assert merge(given) == given


def test_merges_overlapping():
    assert merge([Interval(1, 5), Interval(3, 8)]) == [Interval(1, 8)]


def test_sorts_unordered_input():
    assert merge([Interval(9, 11), Interval(1, 3)]) == [Interval(1, 3), Interval(9, 11)]


def test_merges_contained_interval():
    assert merge([Interval(1, 10), Interval(2, 3)]) == [Interval(1, 10)]


def test_merges_adjacent_intervals():
    assert merge([Interval(1, 3), Interval(3, 5)]) == [Interval(1, 5)]
