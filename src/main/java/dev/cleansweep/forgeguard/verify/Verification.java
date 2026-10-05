package dev.cleansweep.forgeguard.verify;

import dev.cleansweep.forgeguard.sandbox.ExecResult;

/** A verdict together with the execution that produced it. */
public record Verification(Outcome outcome, ExecResult evidence) {}
