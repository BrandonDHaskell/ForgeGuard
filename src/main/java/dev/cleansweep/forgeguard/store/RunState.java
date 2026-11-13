package dev.cleansweep.forgeguard.store;

/**
 * Lifecycle of a run row. Drives the work queue: RunStore.claim takes PENDING
 * runs, and CLAIMED runs whose lease expired, which replaces a separate reaper.
 */
public enum RunState { PENDING, CLAIMED, DONE }
