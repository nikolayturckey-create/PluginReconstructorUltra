package dev.nik.reconstructor.decompile;

import java.nio.file.Path;
import java.util.List;

public record DecompilerResult(
        String engine,
        String version,
        Path toolJar,
        Path outputDirectory,
        Path logFile,
        boolean attempted,
        boolean success,
        boolean timedOut,
        int exitCode,
        long durationMillis,
        int sourceFiles,
        String message,
        List<String> command
) {}
