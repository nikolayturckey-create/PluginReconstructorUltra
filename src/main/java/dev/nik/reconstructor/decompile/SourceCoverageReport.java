package dev.nik.reconstructor.decompile;

import java.util.List;

/** Describes how many JVM class files are represented by normal Java source files. */
public record SourceCoverageReport(
        int classFiles,
        int expectedSourceUnits,
        int recoveredSourceUnits,
        int representedClassFiles,
        List<MissingSource> missingSources,
        List<String> warnings
) {
    public boolean complete() {
        return missingSources.isEmpty();
    }

    public double ratio() {
        return expectedSourceUnits == 0 ? 1.0 : (double) recoveredSourceUnits / (double) expectedSourceUnits;
    }

    public record MissingSource(
            String expectedJavaPath,
            List<String> classEntries,
            List<String> internalNames
    ) {}
}
