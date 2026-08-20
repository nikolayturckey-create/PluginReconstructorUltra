package dev.nik.reconstructor.decompile;

import dev.nik.reconstructor.core.RecoveryOptions;
import dev.nik.reconstructor.jar.JarInventory;
import dev.nik.reconstructor.tools.ToolInstaller;
import dev.nik.reconstructor.util.FilesEx;
import dev.nik.reconstructor.util.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class DecompilerRunner {
    private static final int FALLBACK_BATCH_SIZE = 1;
    private static final int CFR_CLASS_BATCH_SIZE = 20;

    private final SourceSelector selector = new SourceSelector();
    private final SourceCoverageAnalyzer coverageAnalyzer = new SourceCoverageAnalyzer();

    public DecompilerSummary run(
            RecoveryOptions options,
            Path recoveryDirectory,
            Path reportsDirectory,
            JarInventory inventory,
            Consumer<String> progress
    ) throws IOException, InterruptedException {
        Path candidatesRoot = recoveryDirectory.resolve("decompiler-candidates");
        Path logs = reportsDirectory.resolve("logs");
        Files.createDirectories(candidatesRoot);
        Files.createDirectories(logs);

        List<String> warnings = new ArrayList<>();
        Path vineflower = locate(options.vineflowerJar(), options.toolsDirectory(), ToolInstaller.VINEFLOWER_FILE_NAMES);
        Path cfr = locate(options.cfrJar(), options.toolsDirectory(), ToolInstaller.CFR_FILE_NAMES);
        Path procyon = locate(options.procyonJar(), options.toolsDirectory(), ToolInstaller.PROCYON_FILE_NAMES);

        if (options.downloadTools() && options.engineMode() != EngineMode.NONE) {
            progress.accept("      Подготовка декомпиляторов (первый запуск может скачать файлы)...");
            ToolInstaller.Installation installation = new ToolInstaller().install(options.toolsDirectory(), false);
            installation.messages().forEach(message -> progress.accept("      " + message));
            for (String message : installation.messages()) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("failed") || lower.contains("invalid") || lower.contains("error")) warnings.add(message);
            }
            vineflower = locate(options.vineflowerJar(), options.toolsDirectory(), ToolInstaller.VINEFLOWER_FILE_NAMES);
            cfr = locate(options.cfrJar(), options.toolsDirectory(), ToolInstaller.CFR_FILE_NAMES);
            procyon = locate(options.procyonJar(), options.toolsDirectory(), ToolInstaller.PROCYON_FILE_NAMES);
        }

        List<DecompilerResult> results = new ArrayList<>();
        if (shouldRunVineflower(options.engineMode())) {
            if (vineflower == null) {
                warnings.add("Vineflower is unavailable. Automatic download failed or was disabled.");
            } else {
                progress.accept("      Vineflower: полная декомпиляция...");
                results.add(runVineflower(options, vineflower, candidatesRoot.resolve("vineflower"), logs.resolve("vineflower.log"), false));
            }
        }
        if (shouldRunCfr(options.engineMode())) {
            if (cfr == null) {
                warnings.add("CFR is unavailable. Automatic download failed or was disabled.");
            } else {
                progress.accept("      CFR: полная декомпиляция...");
                results.add(runCfr(options, cfr, candidatesRoot.resolve("cfr"), logs.resolve("cfr.log"), false));
            }
        }
        if (shouldRunProcyon(options.engineMode())) {
            if (procyon == null) {
                warnings.add("Procyon is unavailable. Automatic download failed or was disabled.");
            } else {
                progress.accept("      Procyon: полная декомпиляция...");
                results.add(runProcyon(options, procyon, candidatesRoot.resolve("procyon"), logs.resolve("procyon.log")));
            }
        }

        Path javaSources = options.outputDirectory().resolve("src/main/java");
        SourceSelectionReport selection = selector.select(results, javaSources);
        SourceCoverageReport coverage = coverageAnalyzer.analyze(inventory, javaSources);

        if (!coverage.complete() && options.engineMode() != EngineMode.NONE && (vineflower != null || cfr != null)) {
            progress.accept("      Не хватает " + coverage.missingSources().size()
                    + " исходных файлов — запускаю повторную декомпиляцию пакетами...");
            List<DecompilerResult> fallback = runFallbackRound(
                    options,
                    inventory,
                    coverage.missingSources(),
                    vineflower,
                    cfr,
                    candidatesRoot.resolve("fallback-batches"),
                    logs.resolve("fallback-batches"),
                    false,
                    FALLBACK_BATCH_SIZE,
                    CFR_CLASS_BATCH_SIZE
            );
            results.addAll(fallback);
            selection = selector.select(results, javaSources);
            coverage = coverageAnalyzer.analyze(inventory, javaSources);
        }

        if (!coverage.complete() && options.engineMode() != EngineMode.NONE && (vineflower != null || cfr != null)) {
            progress.accept("      Осталось " + coverage.missingSources().size()
                    + " проблемных исходников — пробую каждый отдельно в усиленном режиме...");
            List<DecompilerResult> fallback = runFallbackRound(
                    options,
                    inventory,
                    coverage.missingSources(),
                    vineflower,
                    cfr,
                    candidatesRoot.resolve("fallback-individual"),
                    logs.resolve("fallback-individual"),
                    true,
                    1,
                    1
            );
            results.addAll(fallback);
            selection = selector.select(results, javaSources);
            coverage = coverageAnalyzer.analyze(inventory, javaSources);
        }

        warnings.addAll(selection.warnings());
        warnings.addAll(coverage.warnings());
        if (results.stream().noneMatch(DecompilerResult::success)) {
            warnings.add("No external decompiler produced Java source files.");
        }
        return new DecompilerSummary(List.copyOf(results), selection, coverage, warnings.stream().distinct().toList());
    }

    private static List<DecompilerResult> runFallbackRound(
            RecoveryOptions options,
            JarInventory inventory,
            List<SourceCoverageReport.MissingSource> missing,
            Path vineflower,
            Path cfr,
            Path root,
            Path logs,
            boolean aggressive,
            int vineflowerBatchSize,
            int cfrBatchSize
    ) throws IOException, InterruptedException {
        Files.createDirectories(root);
        Files.createDirectories(logs);
        List<DecompilerResult> results = new ArrayList<>();
        if (vineflower != null && shouldRunVineflower(options.engineMode())) {
            results.add(runVineflowerFallback(
                    options, vineflower, inventory, missing,
                    root.resolve("vineflower"), logs.resolve("vineflower"),
                    aggressive, vineflowerBatchSize
            ));
        }
        if (cfr != null && shouldRunCfr(options.engineMode())) {
            results.add(runCfrFallback(
                    options, cfr, inventory, missing,
                    root.resolve("cfr"), logs.resolve("cfr"),
                    aggressive, cfrBatchSize
            ));
        }
        return results;
    }

    private static DecompilerResult runVineflower(RecoveryOptions options, Path tool, Path output, Path log, boolean aggressive)
            throws IOException, InterruptedException {
        FilesEx.recreateDirectory(output, true);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xmx" + options.maxMemoryMb() + "m");
        command.add("-jar");
        command.add(tool.toAbsolutePath().toString());
        command.add("--folder");
        command.add("--kt-enable=0");
        command.add("--rename-parameters=1");
        command.add("--variable-renaming=tiny");
        command.add("--decompile-complex-constant-dynamic=1");
        command.add("--dump-bytecode-on-error=1");
        command.add("--dump-exception-on-error=1");
        command.add("--log-level=warn");
        if (aggressive) command.add("--old-try-dedup=1");
        command.add(options.inputJar().toAbsolutePath().toString());
        command.add(output.toAbsolutePath().toString());
        ProcessRunner.Result result = ProcessRunner.run(command, options.outputDirectory(), options.decompilerTimeout(), log);
        return result(aggressive ? "Vineflower-aggressive" : "Vineflower",
                ToolInstaller.VINEFLOWER_VERSION, tool, output, log, result);
    }

    private static DecompilerResult runCfr(RecoveryOptions options, Path tool, Path output, Path log, boolean aggressive)
            throws IOException, InterruptedException {
        FilesEx.recreateDirectory(output, true);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xmx" + options.maxMemoryMb() + "m");
        command.add("-jar");
        command.add(tool.toAbsolutePath().toString());
        command.add(options.inputJar().toAbsolutePath().toString());
        command.add("--outputdir");
        command.add(output.toAbsolutePath().toString());
        command.add("--renameillegalidents");
        command.add("true");
        command.add("--renamedupmembers");
        command.add("true");
        command.add("--clobber");
        command.add("true");
        if (aggressive) {
            command.add("--forcetopsort");
            command.add("true");
            command.add("--forceexceptionprune");
            command.add("true");
            command.add("--antiobf");
            command.add("true");
            command.add("--constobf");
            command.add("true");
        }
        ProcessRunner.Result result = ProcessRunner.run(command, options.outputDirectory(), options.decompilerTimeout(), log);
        return result(aggressive ? "CFR-aggressive" : "CFR",
                ToolInstaller.CFR_VERSION, tool, output, log, result);
    }

    private static DecompilerResult runProcyon(RecoveryOptions options, Path tool, Path output, Path log)
            throws IOException, InterruptedException {
        FilesEx.recreateDirectory(output, true);
        List<String> command = List.of(
                javaExecutable(), "-Xmx" + options.maxMemoryMb() + "m", "-jar", tool.toAbsolutePath().toString(),
                "-jar", options.inputJar().toAbsolutePath().toString(), "-o", output.toAbsolutePath().toString()
        );
        ProcessRunner.Result result = ProcessRunner.run(command, options.outputDirectory(), options.decompilerTimeout(), log);
        return result("Procyon", ToolInstaller.PROCYON_VERSION, tool, output, log, result);
    }

    private static DecompilerResult runVineflowerFallback(
            RecoveryOptions options,
            Path tool,
            JarInventory inventory,
            List<SourceCoverageReport.MissingSource> missing,
            Path aggregateOutput,
            Path logDirectory,
            boolean aggressive,
            int batchSize
    ) throws IOException, InterruptedException {
        FilesEx.recreateDirectory(aggregateOutput, true);
        Files.createDirectories(logDirectory);
        Path work = aggregateOutput.resolveSibling(aggregateOutput.getFileName() + "-work");
        FilesEx.recreateDirectory(work, true);

        List<List<SourceCoverageReport.MissingSource>> batches = batches(missing, Math.max(1, batchSize));
        long duration = 0;
        int completed = 0;
        int timedOut = 0;
        List<String> summary = new ArrayList<>();
        Duration timeout = fallbackTimeout(options.decompilerTimeout(), aggressive);
        for (int index = 0; index < batches.size(); index++) {
            List<SourceCoverageReport.MissingSource> batch = batches.get(index);
            Set<String> prefixes = new LinkedHashSet<>();
            for (SourceCoverageReport.MissingSource source : batch) {
                for (String entry : source.classEntries()) prefixes.add(classPrefix(entry));
            }
            if (prefixes.isEmpty()) continue;
            Path output = work.resolve(String.format(Locale.ROOT, "batch-%05d", index + 1));
            Files.createDirectories(output);
            Path log = logDirectory.resolve(String.format(Locale.ROOT, "batch-%05d.log", index + 1));
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-Xmx" + options.maxMemoryMb() + "m");
            command.add("-jar");
            command.add(tool.toAbsolutePath().toString());
            command.add("--folder");
            command.add("--kt-enable=0");
            command.add("--rename-parameters=1");
            command.add("--variable-renaming=tiny");
            command.add("--dump-bytecode-on-error=1");
            command.add("--dump-exception-on-error=1");
            command.add("--decompile-complex-constant-dynamic=1");
            command.add("--log-level=warn");
            if (aggressive) command.add("--old-try-dedup=1");
            command.add("--only=" + String.join(",", prefixes));
            command.add(options.inputJar().toAbsolutePath().toString());
            command.add(output.toAbsolutePath().toString());
            ProcessRunner.Result process = ProcessRunner.run(command, options.outputDirectory(), timeout, log);
            duration += process.durationMillis();
            if (process.completed()) completed++; else timedOut++;
            mergeJavaTree(output, aggregateOutput);
            summary.add("batch " + (index + 1) + ": exit=" + process.exitCode()
                    + ", completed=" + process.completed() + ", prefixes=" + prefixes.size());
        }
        FilesEx.deleteRecursively(work);
        int sources = countSources(aggregateOutput);
        Path summaryLog = logDirectory.resolve("summary.log");
        Files.writeString(summaryLog, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
        String name = aggressive ? "Vineflower-fallback-individual" : "Vineflower-fallback-batch";
        return new DecompilerResult(
                name,
                ToolInstaller.VINEFLOWER_VERSION,
                tool,
                aggregateOutput,
                summaryLog,
                true,
                sources > 0,
                timedOut > 0,
                sources > 0 ? 0 : 1,
                duration,
                sources,
                "Tried " + batches.size() + " fallback batches; produced " + sources + " Java files.",
                List.of("multiple Vineflower fallback invocations")
        );
    }

    private static DecompilerResult runCfrFallback(
            RecoveryOptions options,
            Path tool,
            JarInventory inventory,
            List<SourceCoverageReport.MissingSource> missing,
            Path aggregateOutput,
            Path logDirectory,
            boolean aggressive,
            int batchSize
    ) throws IOException, InterruptedException {
        FilesEx.recreateDirectory(aggregateOutput, true);
        Files.createDirectories(logDirectory);
        Path work = aggregateOutput.resolveSibling(aggregateOutput.getFileName() + "-work");
        FilesEx.recreateDirectory(work, true);
        Path originalClasses = options.outputDirectory().resolve("recovery/original-classes");

        List<List<SourceCoverageReport.MissingSource>> batches = batches(missing, Math.max(1, batchSize));
        long duration = 0;
        int timedOut = 0;
        int actualBatches = 0;
        List<String> summary = new ArrayList<>();
        Duration timeout = fallbackTimeout(options.decompilerTimeout(), aggressive);
        for (int index = 0; index < batches.size(); index++) {
            List<Path> inputs = new ArrayList<>();
            for (SourceCoverageReport.MissingSource source : batches.get(index)) {
                Path primary = primaryClassFile(originalClasses, source.classEntries());
                if (primary != null) inputs.add(primary);
            }
            if (inputs.isEmpty()) continue;
            actualBatches++;
            Path output = work.resolve(String.format(Locale.ROOT, "batch-%05d", index + 1));
            Files.createDirectories(output);
            Path log = logDirectory.resolve(String.format(Locale.ROOT, "batch-%05d.log", index + 1));
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-Xmx" + options.maxMemoryMb() + "m");
            command.add("-jar");
            command.add(tool.toAbsolutePath().toString());
            inputs.forEach(path -> command.add(path.toAbsolutePath().toString()));
            command.add("--outputdir");
            command.add(output.toAbsolutePath().toString());
            command.add("--extraclasspath");
            command.add(inventory.input().toAbsolutePath().toString());
            command.add("--renameillegalidents");
            command.add("true");
            command.add("--renamedupmembers");
            command.add("true");
            command.add("--clobber");
            command.add("true");
            if (aggressive) {
                command.add("--forcetopsort");
                command.add("true");
                command.add("--forceexceptionprune");
                command.add("true");
                command.add("--antiobf");
                command.add("true");
                command.add("--constobf");
                command.add("true");
            }
            ProcessRunner.Result process = ProcessRunner.run(command, options.outputDirectory(), timeout, log);
            duration += process.durationMillis();
            if (!process.completed()) timedOut++;
            mergeJavaTree(output, aggregateOutput);
            summary.add("batch " + (index + 1) + ": exit=" + process.exitCode()
                    + ", completed=" + process.completed() + ", classes=" + inputs.size());
        }
        FilesEx.deleteRecursively(work);
        int sources = countSources(aggregateOutput);
        Path summaryLog = logDirectory.resolve("summary.log");
        Files.writeString(summaryLog, String.join("\n", summary) + "\n", StandardCharsets.UTF_8);
        String name = aggressive ? "CFR-fallback-individual" : "CFR-fallback-batch";
        return new DecompilerResult(
                name,
                ToolInstaller.CFR_VERSION,
                tool,
                aggregateOutput,
                summaryLog,
                true,
                sources > 0,
                timedOut > 0,
                sources > 0 ? 0 : 1,
                duration,
                sources,
                "Tried " + actualBatches + " fallback batches; produced " + sources + " Java files.",
                List.of("multiple CFR fallback invocations")
        );
    }

    private static Duration fallbackTimeout(Duration configured, boolean individual) {
        long seconds = configured.toSeconds();
        long max = individual ? 120 : 300;
        return Duration.ofSeconds(Math.max(20, Math.min(max, seconds)));
    }

    private static String classPrefix(String entry) {
        String value = entry.replace('\\', '/');
        if (value.endsWith(".class")) value = value.substring(0, value.length() - 6);
        int dollar = value.lastIndexOf('$');
        int slash = value.lastIndexOf('/');
        if (dollar > slash) value = value.substring(0, dollar);
        return value;
    }

    private static Path primaryClassFile(Path root, List<String> entries) {
        String chosen = null;
        for (String entry : entries) {
            String normalized = entry.replace('\\', '/');
            String name = normalized.substring(normalized.lastIndexOf('/') + 1);
            if (!name.contains("$")) {
                chosen = normalized;
                break;
            }
            if (chosen == null) chosen = normalized;
        }
        if (chosen == null) return null;
        try {
            Path path = FilesEx.safeResolve(root, chosen);
            return Files.isRegularFile(path) ? path : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static <T> List<List<T>> batches(List<T> values, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size) {
            result.add(values.subList(start, Math.min(values.size(), start + size)));
        }
        return result;
    }

    private static void mergeJavaTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) continue;
                Path relative = source.relativize(file);
                Path destination = target.resolve(relative).normalize();
                Path absoluteTarget = target.toAbsolutePath().normalize();
                destination = destination.toAbsolutePath().normalize();
                if (!destination.startsWith(absoluteTarget)) continue;
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static DecompilerResult result(
            String engine, String version, Path tool, Path output, Path log, ProcessRunner.Result process
    ) throws IOException {
        int sources = countSources(output);
        // Some decompilers return a non-zero exit code when only a few classes fail. Keep every usable Java file.
        boolean success = sources > 0;
        String message;
        if (!process.completed() && sources > 0) {
            message = "Timed out, but produced " + sources + " usable Java source files before termination.";
        } else if (!process.completed()) {
            message = "Timed out after " + process.durationMillis() + " ms.";
        } else if (process.exitCode() != 0 && sources > 0) {
            message = "Exited with code " + process.exitCode() + ", but produced " + sources
                    + " usable Java source files. See " + log + '.';
        } else if (process.exitCode() != 0) {
            message = "Exited with code " + process.exitCode() + ". See " + log + '.';
        } else if (sources == 0) {
            message = "Process finished but produced no Java source files.";
        } else {
            message = "Produced " + sources + " Java source files.";
        }
        return new DecompilerResult(
                engine, version, tool, output, log, true, success, !process.completed(), process.exitCode(),
                process.durationMillis(), sources, message, process.command()
        );
    }

    private static int countSources(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> stream = Files.walk(directory)) {
            return Math.toIntExact(stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                    .count());
        }
    }

    private static Path locate(Path explicit, Path toolsDirectory, List<String> names) {
        if (explicit != null && Files.isRegularFile(explicit)) return explicit.toAbsolutePath().normalize();
        for (String name : names) {
            Path candidate = toolsDirectory.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    private static boolean shouldRunVineflower(EngineMode mode) {
        return mode == EngineMode.AUTO || mode == EngineMode.VINEFLOWER || mode == EngineMode.BOTH || mode == EngineMode.ALL;
    }

    private static boolean shouldRunCfr(EngineMode mode) {
        return mode == EngineMode.AUTO || mode == EngineMode.CFR || mode == EngineMode.BOTH || mode == EngineMode.ALL;
    }

    private static boolean shouldRunProcyon(EngineMode mode) {
        return mode == EngineMode.AUTO || mode == EngineMode.PROCYON || mode == EngineMode.ALL;
    }

    private static String javaExecutable() {
        Path home = Path.of(System.getProperty("java.home"));
        Path executable = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        return Files.isExecutable(executable) ? executable.toString() : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
