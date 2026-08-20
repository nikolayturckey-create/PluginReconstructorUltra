package dev.nik.reconstructor.analysis;

import java.util.List;

public record ObfuscationReport(
        String level,
        double overallScore,
        double nameObfuscationScore,
        double controlFlowScore,
        double stringProtectionScore,
        double reflectionScore,
        double runtimeLoadingScore,
        double antiTamperScore,
        boolean nativeCodePresent,
        List<String> reasons,
        List<SuspiciousClass> suspiciousClasses
) {
    public record SuspiciousClass(
            String className,
            double score,
            int bytecodeSize,
            int branches,
            int switches,
            int invokeDynamic,
            int xorOperations,
            String reason
    ) {}
}
