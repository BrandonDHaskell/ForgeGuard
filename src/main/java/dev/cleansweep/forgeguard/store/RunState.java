package dev.cleansweep.forgeguard.store;

/** Lifecycle of a run row. Drives the work queue and the lease reaper. */
public enum RunState { PENDING, CLAIMED, DONE }
