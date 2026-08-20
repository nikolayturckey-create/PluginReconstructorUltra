package dev.nik.reconstructor.core;

import java.nio.file.Path;

public record RecoveryResult(
        Path outputDirectory,
        Path sourceDirectory,
        Path reportHtml,
        Path reportJson,
        int classFiles,
        int parsedClasses,
        int failedClasses,
        int sourceFiles,
        int expectedSourceUnits,
        int recoveredSourceUnits,
        int missingSourceUnits,
        int representedClassFiles,
        String obfuscationLevel
) {
    public boolean completeJavaCoverage() {
        return missingSourceUnits == 0;
    }
}
