package dev.cleansweep.forgeguard.sandbox;

/** How a sandboxed process stopped. Distinguishes a failing suite from a killed one. */
public enum Termination { EXITED, TIMEOUT, OOM_KILLED, HARNESS_ERROR }
