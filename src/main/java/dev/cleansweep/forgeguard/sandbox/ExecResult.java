package dev.cleansweep.forgeguard.sandbox;

import java.util.List;

/**
 * The record of one sandboxed execution. The command field is the argument list
 * actually passed to the process, so the stored evidence and the thing that ran
 * are the same data.
 */
public record ExecResult(int exitCode, String stdout, String stderr,
                         Termination termination, long durationMs, List<String> command) {

    public ExecResult {
        command = List.copyOf(command);
    }
}
