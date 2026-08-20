package dev.nik.reconstructor.jar;

import dev.nik.reconstructor.classfile.ClassInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record JarInventory(
        Path input,
        long fileSize,
        String sha256,
        int totalEntries,
        int classEntries,
        int parsedClasses,
        int failedClasses,
        int resourceEntries,
        int directoryEntries,
        int nestedJars,
        int nativeLibraries,
        int signatureFiles,
        int multiReleaseClasses,
        long declaredUncompressedBytes,
        boolean signed,
        boolean multiRelease,
        PluginMetadata metadata,
        List<ClassInfo> classes,
        List<ArchiveEntryInfo> entries,
        List<ParseFailure> parseFailures,
        Set<String> ownedClasses,
        Set<String> referencedClasses,
        List<String> warnings
) {
    public record ArchiveEntryInfo(
            String name,
            String kind,
            long size,
            long compressedSize,
            long crc,
            String sha256
    ) {}

    public record ParseFailure(String entryName, String message) {}
}
