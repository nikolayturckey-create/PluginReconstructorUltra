package dev.nik.reconstructor.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner {
    private ProcessRunner() {}

    public static Result run(List<String> command, Path workingDirectory, Duration timeout, Path logFile)
            throws IOException, InterruptedException {
        Files.createDirectories(logFile.getParent());
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        long started = System.nanoTime();
        Process process = builder.start();
        boolean completed = process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        int exitCode = completed ? process.exitValue() : -1;
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String tail = tail(logFile, 12_000);
        return new Result(List.copyOf(command), exitCode, completed, durationMillis, tail);
    }

    private static String tail(Path file, int maxChars) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(text.length() - maxChars);
    }

    public record Result(List<String> command, int exitCode, boolean completed, long durationMillis, String logTail) {
        public boolean success() {
            return completed && exitCode == 0;
        }
    }
}
