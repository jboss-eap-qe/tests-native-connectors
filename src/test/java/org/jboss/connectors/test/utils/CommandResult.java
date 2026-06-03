package org.jboss.connectors.test.utils;

/**
 * Immutable result of executing a native OS command.
 * Captures exit code, stdout, and stderr separately so callers can
 * inspect success/failure and parse output without re-executing.
 *
 * @see NativeProcessManager#execCommand(java.nio.file.Path, String...)
 */
public class CommandResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public CommandResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    @Override
    public String toString() {
        return "CommandResult{exitCode=" + exitCode +
                ", stdout='" + (stdout.length() > 100 ? stdout.substring(0, 100) + "..." : stdout) + "'" +
                ", stderr='" + (stderr.length() > 100 ? stderr.substring(0, 100) + "..." : stderr) + "'" +
                '}';
    }
}
