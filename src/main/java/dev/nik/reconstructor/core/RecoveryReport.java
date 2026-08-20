package dev.nik.reconstructor.core;

import dev.nik.reconstructor.analysis.DependencyReport;
import dev.nik.reconstructor.analysis.ObfuscationReport;
import dev.nik.reconstructor.decompile.DecompilerSummary;
import dev.nik.reconstructor.jar.JarInventory;

import java.time.Instant;
import java.util.List;

public record RecoveryReport(
        String toolVersion,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        JarInventory inventory,
        ObfuscationReport obfuscation,
        DependencyReport dependencies,
        DecompilerSummary decompilation,
        List<String> warnings
) {}
