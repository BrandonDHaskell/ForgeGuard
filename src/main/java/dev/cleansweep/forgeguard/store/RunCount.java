package dev.cleansweep.forgeguard.store;

import dev.cleansweep.forgeguard.verify.Outcome;

/**
 * How many runs share one state, verdict and producer claim. RunStore.counts()
 * returns one of these per group, and the stats command summarizes them.
 *
 * <p>outcome is null for a run with no verdict yet. A run whose producer has
 * not reported counts as not claiming success, the same as in RunRecord.
 */
public record RunCount(RunState state, Outcome outcome, boolean producerReportedSuccess, long runs) {}
