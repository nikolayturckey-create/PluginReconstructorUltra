package dev.nik.reconstructor.decompile;

import dev.nik.reconstructor.classfile.ClassInfo;
import dev.nik.reconstructor.jar.JarInventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Compares physical JVM classes with normal Java source units.
 * Inner/anonymous classes intentionally map to their outer Java file, while multi-release and duplicate
 * binary classes are kept as distinct units so the report never calls one source file "complete" for both.
 */
public final class SourceCoverageAnalyzer {
    public SourceCoverageReport analyze(JarInventory inventory, Path javaDirectory) throws IOException {
        Map<String, Unit> expected = new LinkedHashMap<>();
        Map<String, Integer> binaryOccurrences = new LinkedHashMap<>();
        for (ClassInfo info : inventory.classes()) {
            String basePath = expectedPath(info);
            String binaryKey = multiReleasePrefix(info.entryName()) + '|' + info.internalName();
            int occurrence = binaryOccurrences.merge(binaryKey, 1, Integer::sum);
            String path = occurrence == 1
                    ? basePath
                    : "__duplicate_classes__/" + String.format(Locale.ROOT, "%04d", occurrence) + '/' + basePath;
            Unit unit = expected.computeIfAbsent(path, ignored -> new Unit(path));
            unit.classEntries.add(info.entryName());
            unit.internalNames.add(info.internalName());
        }

        Map<String, Integer> failedOccurrences = new LinkedHashMap<>();
        for (JarInventory.ParseFailure failure : inventory.parseFailures()) {
            String basePath = expectedPathFromEntry(failure.entryName());
            int occurrence = failedOccurrences.merge(basePath, 1, Integer::sum);
            String path = occurrence == 1
                    ? basePath
                    : "__duplicate_classes__/" + String.format(Locale.ROOT, "%04d", occurrence) + '/' + basePath;
            Unit unit = expected.computeIfAbsent(path, ignored -> new Unit(path));
            unit.classEntries.add(failure.entryName());
        }

        Set<String> actual = new LinkedHashSet<>();
        Map<String, Integer> actualLowerCounts = new LinkedHashMap<>();
        if (Files.isDirectory(javaDirectory)) {
            try (Stream<Path> stream = Files.walk(javaDirectory)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.endsWith(".java")) continue;
                    String relative = normalize(javaDirectory.relativize(file).toString());
                    actual.add(relative);
                    actualLowerCounts.merge(relative.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
        }

        Map<String, Integer> expectedLowerCounts = new LinkedHashMap<>();
        for (String path : expected.keySet()) {
            expectedLowerCounts.merge(path.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        int recoveredUnits = 0;
        int representedClasses = 0;
        List<SourceCoverageReport.MissingSource> missing = new ArrayList<>();
        for (Unit unit : expected.values()) {
            String lower = unit.path.toLowerCase(Locale.ROOT);
            boolean exact = actual.contains(unit.path);
            boolean safeCaseInsensitiveMatch = expectedLowerCounts.getOrDefault(lower, 0) == 1
                    && actualLowerCounts.getOrDefault(lower, 0) == 1;
            boolean present = exact || safeCaseInsensitiveMatch;
            if (present) {
                recoveredUnits++;
                representedClasses += unit.classEntries.size();
            } else {
                missing.add(new SourceCoverageReport.MissingSource(
                        unit.path,
                        List.copyOf(unit.classEntries),
                        List.copyOf(unit.internalNames)
                ));
            }
        }

        List<String> warnings = new ArrayList<>();
        if (!missing.isEmpty()) {
            warnings.add(missing.size() + " Java source units are still missing after decompilation.");
        }
        if (inventory.failedClasses() > 0) {
            warnings.add(inventory.failedClasses()
                    + " class files could not be parsed by the internal scanner; their raw bytes were preserved.");
        }
        if (inventory.multiReleaseClasses() > 0) {
            warnings.add("Multi-release class variants are counted as separate source units and are not silently merged with root classes.");
        }
        if (binaryOccurrences.values().stream().anyMatch(value -> value > 1)) {
            warnings.add("Duplicate binary class definitions are counted separately; one Java file cannot faithfully represent multiple bytecode bodies.");
        }
        return new SourceCoverageReport(
                inventory.classEntries(),
                expected.size(),
                recoveredUnits,
                representedClasses,
                List.copyOf(missing),
                List.copyOf(warnings)
        );
    }

    public static String expectedPath(ClassInfo info) {
        String internal = info.internalName();
        int slash = internal.lastIndexOf('/');
        String packagePath = slash < 0 ? "" : internal.substring(0, slash + 1);
        String simple = slash < 0 ? internal : internal.substring(slash + 1);
        int inner = simple.indexOf('$');
        if (inner > 0) simple = simple.substring(0, inner);
        if (simple.isBlank()) simple = "RecoveredClass";
        return normalize(multiReleasePrefix(info.entryName()) + packagePath + simple + ".java");
    }

    private static String expectedPathFromEntry(String entryName) {
        String name = normalize(entryName);
        String prefix = multiReleasePrefix(name);
        if (!prefix.isEmpty()) {
            String remainder = name.substring("META-INF/versions/".length());
            int slash = remainder.indexOf('/');
            if (slash >= 0) name = remainder.substring(slash + 1);
        }
        if (name.endsWith(".class")) name = name.substring(0, name.length() - 6);
        int inner = name.lastIndexOf('$');
        int slash = name.lastIndexOf('/');
        if (inner > slash) name = name.substring(0, inner);
        return normalize(prefix + name + ".java");
    }

    private static String multiReleasePrefix(String entryName) {
        String name = normalize(entryName);
        String marker = "META-INF/versions/";
        if (!name.startsWith(marker)) return "";
        String remainder = name.substring(marker.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) return "__multi_release__/unknown/";
        String version = remainder.substring(0, slash).replaceAll("[^0-9A-Za-z_.-]", "_");
        return "__multi_release__/" + version + '/';
    }

    private static String normalize(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }

    private static final class Unit {
        private final String path;
        private final Set<String> classEntries = new LinkedHashSet<>();
        private final Set<String> internalNames = new LinkedHashSet<>();

        private Unit(String path) {
            this.path = path;
        }
    }
}
