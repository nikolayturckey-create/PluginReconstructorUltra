package dev.nik.reconstructor.core;

import dev.nik.reconstructor.decompile.EngineMode;

import java.nio.file.Path;
import java.time.Duration;

public record RecoveryOptions(
        Path inputJar,
        Path outputDirectory,
        Path toolsDirectory,
        Path vineflowerJar,
        Path cfrJar,
        Path procyonJar,
        EngineMode engineMode,
        boolean force,
        boolean downloadTools,
        boolean verbose,
        int maxMemoryMb,
        Duration decompilerTimeout,
        String paperApiVersion
) {
    public RecoveryOptions {
        inputJar = inputJar.toAbsolutePath().normalize();
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
        toolsDirectory = toolsDirectory.toAbsolutePath().normalize();
        if (vineflowerJar != null) vineflowerJar = vineflowerJar.toAbsolutePath().normalize();
        if (cfrJar != null) cfrJar = cfrJar.toAbsolutePath().normalize();
        if (procyonJar != null) procyonJar = procyonJar.toAbsolutePath().normalize();
        if (maxMemoryMb < 256) throw new IllegalArgumentException("--max-memory-mb must be at least 256");
        if (decompilerTimeout.isNegative() || decompilerTimeout.isZero()) {
            throw new IllegalArgumentException("Decompiler timeout must be positive");
        }
    }
}
