package dev.nik.reconstructor.core;

import dev.nik.reconstructor.analysis.DependencyDetector;
import dev.nik.reconstructor.analysis.DependencyReport;
import dev.nik.reconstructor.analysis.ObfuscationAnalyzer;
import dev.nik.reconstructor.analysis.ObfuscationReport;
import dev.nik.reconstructor.decompile.DecompilerRunner;
import dev.nik.reconstructor.decompile.DecompilerSummary;
import dev.nik.reconstructor.decompile.SourceCoverageAnalyzer;
import dev.nik.reconstructor.decompile.SourceCoverageReport;
import dev.nik.reconstructor.decompile.SourceSelectionReport;
import dev.nik.reconstructor.jar.JarInventory;
import dev.nik.reconstructor.jar.JarScanner;
import dev.nik.reconstructor.output.ProjectWriter;
import dev.nik.reconstructor.output.ReportWriter;
import dev.nik.reconstructor.util.FilesEx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RecoveryEngine {
    public static final String VERSION = "0.2.0";
    private static final String OUTPUT_MARKER = ".plugin-reconstructor-output";

    private final JarScanner scanner = new JarScanner();
    private final ObfuscationAnalyzer obfuscationAnalyzer = new ObfuscationAnalyzer();
    private final DependencyDetector dependencyDetector = new DependencyDetector();
    private final DecompilerRunner decompilerRunner = new DecompilerRunner();
    private final SourceCoverageAnalyzer coverageAnalyzer = new SourceCoverageAnalyzer();
    private final ProjectWriter projectWriter = new ProjectWriter();
    private final ReportWriter reportWriter = new ReportWriter();

    public RecoveryResult recover(RecoveryOptions options, Progress progress) throws Exception {
        validateInput(options.inputJar());
        prepareOutput(options.outputDirectory(), options.force());
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();

        Path output = options.outputDirectory();
        Path javaSources = output.resolve("src/main/java");
        Path recovery = output.resolve("recovery");
        Path reports = output.resolve("reports");
        Files.createDirectories(javaSources);
        Files.createDirectories(recovery);
        Files.createDirectories(reports);

        progress.info("[1/5] Читаю JAR и сохраняю каждый .class без изменений...");
        JarScanner.OutputLayout layout = new JarScanner.OutputLayout(
                recovery.resolve("original"),
                recovery.resolve("original-classes"),
                recovery.resolve("bytecode"),
                recovery.resolve("pseudo-source"),
                recovery.resolve("unparsed-classes"),
                output.resolve("src/main/resources"),
                recovery.resolve("nested-jars"),
                recovery.resolve("signatures"),
                recovery.resolve("duplicate-entries")
        );
        JarInventory inventory = scanner.scan(options.inputJar(), layout);
        progress.info("      Найдено class-файлов: " + inventory.classEntries()
                + ", сохранено оригиналов: " + inventory.classEntries()
                + ", ресурсов: " + inventory.resourceEntries());

        progress.info("[2/5] Определяю структуру, зависимости и уровень обфускации...");
        ObfuscationReport obfuscation = obfuscationAnalyzer.analyze(inventory);
        DependencyReport dependencies = dependencyDetector.detect(inventory);
        progress.info("      Обфускация: " + obfuscation.level() + " (" + percent(obfuscation.overallScore()) + ")");

        progress.info("[3/5] Декомпилирую через несколько движков и выбираю лучший Java для каждого класса...");
        DecompilerSummary decompilation;
        try {
            decompilation = decompilerRunner.run(options, recovery, reports, inventory, progress::info);
        } catch (Exception decompilerError) {
            String warning = "Decompiler stage failed: " + safeMessage(decompilerError);
            Files.createDirectories(javaSources);
            SourceCoverageReport coverage = coverageAnalyzer.analyze(inventory, javaSources);
            decompilation = new DecompilerSummary(
                    List.of(),
                    new SourceSelectionReport(0, 0, 0, List.of(), List.of(warning)),
                    coverage,
                    List.of(warning)
            );
        }
        SourceCoverageReport coverage = decompilation.coverage();
        progress.info("      Java-файлов: " + decompilation.selection().selectedFiles()
                + ", покрытие исходников: " + coverage.recoveredSourceUnits() + "/" + coverage.expectedSourceUnits());

        progress.info("[4/5] Создаю обычный Java-проект для IntelliJ/Gradle...");
        projectWriter.write(options, inventory, dependencies, decompilation);

        List<String> warnings = collectWarnings(inventory, decompilation);
        Instant finishedAt = Instant.now();
        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        RecoveryReport report = new RecoveryReport(
                VERSION,
                startedAt,
                finishedAt,
                durationMillis,
                inventory,
                obfuscation,
                dependencies,
                decompilation,
                List.copyOf(warnings)
        );

        progress.info("[5/5] Записываю отчёт о покрытии и проблемных классах...");
        ReportWriter.Paths paths = reportWriter.write(report, reports);
        Files.writeString(output.resolve(OUTPUT_MARKER),
                "Plugin Reconstructor Ultra output\nversion=" + VERSION + "\ninputSha256=" + inventory.sha256() + "\n",
                StandardCharsets.UTF_8);
        progress.info("Готово: " + output);
        return new RecoveryResult(
                output,
                javaSources,
                paths.html(),
                paths.json(),
                inventory.classEntries(),
                inventory.parsedClasses(),
                inventory.failedClasses(),
                decompilation.selection().selectedFiles(),
                coverage.expectedSourceUnits(),
                coverage.recoveredSourceUnits(),
                coverage.missingSources().size(),
                coverage.representedClassFiles(),
                obfuscation.level()
        );
    }

    private static void validateInput(Path input) throws IOException {
        if (!Files.isRegularFile(input)) throw new IOException("Input JAR does not exist: " + input);
        if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Input must be a .jar file: " + input);
        }
        if (Files.size(input) < 4) throw new IOException("Input JAR is empty or truncated: " + input);
    }

    private static void prepareOutput(Path output, boolean force) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() == null) throw new IOException("Refusing to use a filesystem root as output.");
        if (Files.isSymbolicLink(normalized)) throw new IOException("Refusing to replace a symbolic-link output directory.");
        if (Files.exists(normalized)) {
            if (!force) throw new IOException("Output exists: " + normalized + " (add --force to replace a previous result)");
            boolean marked = Files.isRegularFile(normalized.resolve(OUTPUT_MARKER));
            boolean empty;
            try (var stream = Files.list(normalized)) {
                empty = stream.findAny().isEmpty();
            }
            if (!marked && !empty) {
                throw new IOException("Refusing to delete an unmarked non-empty directory: " + normalized);
            }
            FilesEx.deleteRecursively(normalized);
        }
        Files.createDirectories(normalized);
        Files.writeString(normalized.resolve(OUTPUT_MARKER), "initializing\n", StandardCharsets.UTF_8);
    }

    private static List<String> collectWarnings(JarInventory inventory, DecompilerSummary decompilation) {
        List<String> warnings = new ArrayList<>(inventory.warnings());
        warnings.addAll(decompilation.warnings());
        if (inventory.signed()) {
            warnings.add("The original JAR was signed. Signature files were preserved separately and will not validate after rebuilding.");
        }
        if (inventory.nativeLibraries() > 0) {
            warnings.add("Native libraries were preserved, but native machine code is not converted to Java source.");
        }
        if (inventory.multiReleaseClasses() > 0) {
            warnings.add("The JAR contains multi-release class variants. Their original class files were preserved under recovery/original-classes.");
        }
        if (inventory.failedClasses() > 0) {
            warnings.add(inventory.failedClasses() + " class files could not be parsed internally; their exact bytes and parser errors were preserved.");
        }
        if (decompilation.selection().selectedFiles() == 0) {
            warnings.add("No normal Java source was recovered. Check reports/logs and verify that the decompiler engines were downloaded.");
        }
        if (!decompilation.coverage().complete()) {
            warnings.add(decompilation.coverage().missingSources().size()
                    + " source units are missing. See MISSING_SOURCES.txt; no missing class was silently discarded.");
        }
        return warnings.stream().distinct().toList();
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    public interface Progress {
        void info(String message);
    }
}
