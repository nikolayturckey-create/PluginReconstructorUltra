package dev.nik.reconstructor.jar;

import dev.nik.reconstructor.classfile.ClassFileParser;
import dev.nik.reconstructor.classfile.ClassInfo;
import dev.nik.reconstructor.classfile.ParsedClass;
import dev.nik.reconstructor.util.FilesEx;
import dev.nik.reconstructor.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class JarScanner {
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    private static final long MAX_CLASS_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_DECLARED_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;

    private final ClassFileParser classParser = new ClassFileParser();
    private final PluginDescriptorParser descriptorParser = new PluginDescriptorParser();

    public JarInventory scan(Path input, OutputLayout output) throws IOException {
        long fileSize = Files.size(input);
        String archiveSha = Hashing.sha256(input);
        List<ClassInfo> classes = new ArrayList<>();
        List<JarInventory.ArchiveEntryInfo> entries = new ArrayList<>();
        List<JarInventory.ParseFailure> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> descriptors = new LinkedHashMap<>();
        Map<String, Integer> entryOccurrences = new HashMap<>();
        Map<String, Integer> extractionOccurrences = new HashMap<>();
        Map<String, Integer> classNameOccurrences = new HashMap<>();

        int totalEntries = 0;
        int classEntries = 0;
        int resourceEntries = 0;
        int directoryEntries = 0;
        int nestedJars = 0;
        int nativeLibraries = 0;
        int signatureFiles = 0;
        int multiReleaseClasses = 0;
        long declaredBytes = 0;
        boolean signed = false;
        boolean multiRelease = false;

        Files.createDirectories(output.originalClassesDirectory());
        Files.createDirectories(output.bytecodeDirectory());
        Files.createDirectories(output.pseudoSourceDirectory());
        Files.createDirectories(output.resourcesDirectory());
        Files.createDirectories(output.unparsedClassesDirectory());
        Files.createDirectories(output.nestedJarsDirectory());
        Files.createDirectories(output.signaturesDirectory());
        Files.createDirectories(output.duplicateEntriesDirectory());

        try (JarFile jar = new JarFile(input.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                multiRelease = "true".equalsIgnoreCase(attributes.getValue("Multi-Release"));
            }

            Enumeration<JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                totalEntries++;
                if (totalEntries > MAX_ENTRIES) {
                    throw new IOException("Archive has more than " + MAX_ENTRIES + " entries; refusing a possible ZIP bomb.");
                }
                String name = entry.getName();
                int exactOccurrence = entryOccurrences.merge(name, 1, Integer::sum);
                int extractionOccurrence = extractionOccurrences.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
                boolean extractionCollision = extractionOccurrence > 1;
                String artifactName = extractionCollision
                        ? "__duplicates__/" + String.format(Locale.ROOT, "%04d", extractionOccurrence) + "/" + name
                        : name;
                if (exactOccurrence > 1) warnings.add("Duplicate archive entry preserved separately: " + name + " (#" + exactOccurrence + ")");
                else if (extractionCollision) warnings.add("Case-insensitive extraction collision preserved separately: " + name);
                if (entry.isDirectory()) {
                    directoryEntries++;
                    continue;
                }
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_ENTRY_BYTES) {
                    throw new IOException("Archive entry is too large: " + name + " (" + declaredSize + " bytes)");
                }
                if (declaredSize > 0) {
                    declaredBytes += declaredSize;
                    if (declaredBytes > MAX_TOTAL_DECLARED_BYTES) {
                        throw new IOException("Declared uncompressed archive size exceeds safety limit.");
                    }
                }

                String lower = name.toLowerCase(Locale.ROOT);
                boolean classFile = lower.endsWith(".class");
                boolean nestedJar = lower.endsWith(".jar");
                boolean nativeLibrary = lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
                boolean signature = isSignature(lower);
                String kind = classFile ? "class" : nestedJar ? "nested-jar" : nativeLibrary ? "native" : signature ? "signature" : "resource";

                long limit = classFile ? MAX_CLASS_BYTES : MAX_ENTRY_BYTES;
                byte[] bytes;
                try (InputStream stream = jar.getInputStream(entry)) {
                    bytes = readLimited(stream, limit, name);
                }
                String entrySha = Hashing.sha256(bytes);
                entries.add(new JarInventory.ArchiveEntryInfo(
                        artifactName,
                        kind,
                        bytes.length,
                        entry.getCompressedSize(),
                        entry.getCrc(),
                        entrySha
                ));

                if (classFile) {
                    classEntries++;
                    Path originalClassTarget = FilesEx.safeResolve(output.originalClassesDirectory(), artifactName);
                    Files.createDirectories(originalClassTarget.getParent());
                    Files.write(originalClassTarget, bytes);
                    if (lower.startsWith("meta-inf/versions/")) multiReleaseClasses++;
                    try {
                        ParsedClass parsed = classParser.parse(name, bytes);
                        classes.add(parsed.info());
                        classNameOccurrences.merge(parsed.info().internalName(), 1, Integer::sum);
                        writeClassArtifacts(parsed, output, artifactName);
                    } catch (Exception parseError) {
                        failures.add(new JarInventory.ParseFailure(name, safeMessage(parseError)));
                        Path rawTarget = FilesEx.safeResolve(output.unparsedClassesDirectory(), artifactName);
                        Files.createDirectories(rawTarget.getParent());
                        Files.write(rawTarget, bytes);
                        Path errorTarget = rawTarget.resolveSibling(rawTarget.getFileName() + ".error.txt");
                        Files.writeString(errorTarget, parseError.toString(), StandardCharsets.UTF_8);
                    }
                    continue;
                }

                resourceEntries++;
                if (nestedJar) {
                    nestedJars++;
                    Path nestedTarget = FilesEx.safeResolve(output.nestedJarsDirectory(), artifactName);
                    Files.createDirectories(nestedTarget.getParent());
                    Files.write(nestedTarget, bytes);
                }
                if (nativeLibrary) nativeLibraries++;
                if (signature) {
                    signed = true;
                    signatureFiles++;
                    Path signatureTarget = FilesEx.safeResolve(output.signaturesDirectory(), artifactName);
                    Files.createDirectories(signatureTarget.getParent());
                    Files.write(signatureTarget, bytes);
                } else {
                    Path resourceRoot = extractionCollision ? output.duplicateEntriesDirectory() : output.resourcesDirectory();
                    Path resourceTarget = FilesEx.safeResolve(resourceRoot, artifactName);
                    Files.createDirectories(resourceTarget.getParent());
                    Files.write(resourceTarget, bytes);
                }
                if (isDescriptor(lower) && bytes.length <= 4 * 1024 * 1024) {
                    descriptors.put(artifactName, new String(bytes, StandardCharsets.UTF_8));
                }
            }
        }

        for (Map.Entry<String, Integer> duplicate : classNameOccurrences.entrySet()) {
            if (duplicate.getValue() > 1) {
                warnings.add("Binary class name appears " + duplicate.getValue() + " times: " + duplicate.getKey());
            }
        }
        PluginMetadata metadata = descriptorParser.parse(descriptors, classes);
        warnings.addAll(metadata.warnings());
        Set<String> owned = new LinkedHashSet<>();
        Set<String> referenced = new LinkedHashSet<>();
        for (ClassInfo info : classes) {
            owned.add(info.internalName());
            referenced.addAll(info.referencedClasses());
        }
        referenced.removeAll(owned);

        Path originalTarget = output.originalDirectory().resolve(input.getFileName().toString());
        Files.createDirectories(originalTarget.getParent());
        Files.copy(input, originalTarget, StandardCopyOption.REPLACE_EXISTING);

        return new JarInventory(
                input.toAbsolutePath().normalize(),
                fileSize,
                archiveSha,
                totalEntries,
                classEntries,
                classes.size(),
                failures.size(),
                resourceEntries,
                directoryEntries,
                nestedJars,
                nativeLibraries,
                signatureFiles,
                multiReleaseClasses,
                declaredBytes,
                signed,
                multiRelease,
                metadata,
                List.copyOf(classes),
                List.copyOf(entries),
                List.copyOf(failures),
                Set.copyOf(owned),
                Set.copyOf(referenced),
                List.copyOf(warnings)
        );
    }

    private static void writeClassArtifacts(ParsedClass parsed, OutputLayout output, String artifactName) throws IOException {
        String classPath = artifactName;
        String base = classPath.substring(0, classPath.length() - ".class".length());
        Path bytecodeTarget = FilesEx.safeResolve(output.bytecodeDirectory(), base + ".bytecode.txt");
        Path pseudoTarget = FilesEx.safeResolve(output.pseudoSourceDirectory(), base + ".pseudo.java.txt");
        Files.createDirectories(bytecodeTarget.getParent());
        Files.createDirectories(pseudoTarget.getParent());
        Files.writeString(bytecodeTarget, parsed.bytecodeText(), StandardCharsets.UTF_8);
        Files.writeString(pseudoTarget, parsed.pseudoSource(), StandardCharsets.UTF_8);
    }

    private static byte[] readLimited(InputStream input, long limit, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > limit) throw new IOException("Entry exceeds safety limit: " + name);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isDescriptor(String lower) {
        return lower.equals("plugin.yml") || lower.endsWith("/plugin.yml")
                || lower.equals("paper-plugin.yml") || lower.endsWith("/paper-plugin.yml")
                || lower.equals("velocity-plugin.json") || lower.endsWith("/velocity-plugin.json")
                || lower.equals("bungee.yml") || lower.endsWith("/bungee.yml");
    }

    private static boolean isSignature(String lower) {
        if (!lower.startsWith("meta-inf/")) return false;
        return lower.endsWith(".sf") || lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec")
                || lower.equals("meta-inf/index.list");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record OutputLayout(
            Path originalDirectory,
            Path originalClassesDirectory,
            Path bytecodeDirectory,
            Path pseudoSourceDirectory,
            Path unparsedClassesDirectory,
            Path resourcesDirectory,
            Path nestedJarsDirectory,
            Path signaturesDirectory,
            Path duplicateEntriesDirectory
    ) {}
}
