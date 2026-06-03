package org.jboss.connectors.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages a native OS process (WildFly server or httpd) for test execution.
 *
 * <p>Provides process lifecycle ({@link #start()}, {@link #stop()}, {@link #kill()}),
 * startup detection via log polling ({@link #waitForStartup(String, Duration)}),
 * and a static utility for running short-lived commands ({@link #execCommand(Path, String...)}).
 *
 * <p>Stdout and stderr are merged into {@code process-output.log} in the working directory.
 * A JVM shutdown hook ensures all tracked processes are destroyed on exit, preventing
 * orphaned httpd or WildFly processes from holding ports.
 *
 * <p>On Windows, {@link #stop()} destroys the entire process tree (descendants first)
 * because batch scripts like {@code standalone.bat} spawn child {@code java.exe} processes
 * that are not killed when the parent {@code cmd.exe} is destroyed.
 */
public class NativeProcessManager {

    private static final Logger log = LoggerFactory.getLogger(NativeProcessManager.class);

    private static final List<Process> TRACKED_PROCESSES = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : TRACKED_PROCESSES) {
                if (p.isAlive()) {
                    log.info("Shutdown hook: destroying process tree (pid {})", p.pid());
                    destroyProcessTree(p);
                }
            }
        }, "native-process-cleanup"));
    }

    private final String name;
    private final List<String> command;
    private final Path workDir;
    private final Map<String, String> environment;
    private final Path outputLog;

    private Process process;

    public NativeProcessManager(String name, List<String> command, Path workDir,
                                Map<String, String> environment) {
        this.name = name;
        this.command = new ArrayList<>(command);
        this.workDir = workDir;
        this.environment = environment != null ? environment : Collections.emptyMap();
        this.outputLog = workDir.resolve("process-output.log");
    }

    public void start() throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Process '" + name + "' is already running (pid "
                    + process.pid() + ")");
        }

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile());

        pb.environment().putAll(environment);

        log.info("Starting '{}': {} (workDir={})", name, String.join(" ", command), workDir);
        process = pb.start();
        TRACKED_PROCESSES.add(process);
        log.info("Process '{}' started (pid {}), output -> {}", name, process.pid(), outputLog);
    }

    public void waitForStartup(String pattern, Duration timeout) {
        log.info("Waiting for '{}' startup pattern '{}' (timeout: {})", name, pattern, timeout);
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                String logContent = readOutputLog();
                throw new RuntimeException("Process '" + name + "' exited with code "
                        + process.exitValue() + " before startup pattern '" + pattern
                        + "' appeared. Output:\n" + logContent);
            }

            String logContent = readOutputLog();
            if (logContent.contains(pattern)) {
                log.info("Startup pattern '{}' detected for '{}'", pattern, name);
                return;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for '" + name + "' startup", e);
            }
        }

        String logContent = readOutputLog();
        throw new RuntimeException("Timeout waiting for '" + name + "' startup pattern '"
                + pattern + "' after " + timeout + ". Output:\n" + logContent);
    }

    public void stop() {
        if (process == null || !process.isAlive()) {
            log.debug("Process '{}' is not running, nothing to stop", name);
            return;
        }

        log.info("Stopping process '{}' (pid {})", name, process.pid());
        destroyProcessTree(process);

        try {
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("Process '{}' did not exit within 30s after tree kill", name);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        TRACKED_PROCESSES.remove(process);
        log.info("Process '{}' stopped", name);
    }

    public void kill() {
        if (process == null || !process.isAlive()) {
            log.debug("Process '{}' is not running, nothing to kill", name);
            return;
        }

        log.info("Killing process '{}' (pid {})", name, process.pid());
        destroyProcessTree(process);

        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        TRACKED_PROCESSES.remove(process);
        log.info("Process '{}' killed", name);
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public Path getOutputLog() {
        return outputLog;
    }

    public String readOutputLog() {
        try {
            if (Files.exists(outputLog)) {
                return Files.readString(outputLog, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to read output log for '{}': {}", name, e.getMessage());
        }
        return "";
    }

    public static CommandResult execCommand(Path workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile());

        Process proc = pb.start();
        proc.getOutputStream().close();

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                return "";
            }
        });

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                return "";
            }
        });

        long timeoutSeconds = TestTimeouts.EXEC_COMMAND.toSeconds();
        boolean completed = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            proc.destroyForcibly();
            throw new RuntimeException("Command timed out after " + timeoutSeconds + "s: "
                    + String.join(" ", command));
        }

        String stdout = stdoutFuture.get(10, TimeUnit.SECONDS);
        String stderr = stderrFuture.get(10, TimeUnit.SECONDS);

        return new CommandResult(proc.exitValue(), stdout, stderr);
    }

    private static void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(descendant -> {
            log.info("Destroying descendant process (pid {})", descendant.pid());
            descendant.destroyForcibly();
        });
        process.destroyForcibly();
    }
}
