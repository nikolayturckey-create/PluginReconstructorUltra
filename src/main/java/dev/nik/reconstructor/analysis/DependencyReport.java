package dev.nik.reconstructor.analysis;

import java.util.List;
import java.util.Map;

public record DependencyReport(
        List<DetectedDependency> dependencies,
        Map<String, Integer> unresolvedPrefixes,
        List<String> internalJdkReferences,
        List<String> nmsReferences
) {
    public record DetectedDependency(
            String name,
            String prefix,
            String coordinate,
            String repository,
            int referencedClassCount,
            boolean bundledInsideJar,
            String note
    ) {}
}
