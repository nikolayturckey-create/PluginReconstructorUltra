package dev.nik.reconstructor.output;

import dev.nik.reconstructor.analysis.DependencyReport;
import dev.nik.reconstructor.core.RecoveryOptions;
import dev.nik.reconstructor.decompile.DecompilerSummary;
import dev.nik.reconstructor.decompile.SourceCoverageReport;
import dev.nik.reconstructor.jar.JarInventory;
import dev.nik.reconstructor.jar.PluginMetadata;
import dev.nik.reconstructor.util.FilesEx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Writes a plain Java project. The recovery tool intentionally does not compile the generated project. */
public final class ProjectWriter {
    public void write(
            RecoveryOptions options,
            JarInventory inventory,
            DependencyReport dependencies,
            DecompilerSummary decompilation
    ) throws IOException {
        Path root = options.outputDirectory();
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/main/resources"));
        copyNestedLibraries(root.resolve("recovery/nested-jars"), root.resolve("libs/unresolved"));

        String projectName = FilesEx.sanitizeFileName(inventory.metadata().name());
        String group = inferGroup(inventory.metadata());
        int targetJava = inferTargetJava(inventory);

        Files.writeString(root.resolve("settings.gradle.kts"),
                "rootProject.name = \"" + escapeKotlin(projectName) + "\"\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("build.gradle.kts"),
                buildScript(group, inventory, dependencies, targetJava), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("gradle.properties"),
                gradleProperties(options, dependencies), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README_RECOVERY.md"),
                readme(inventory, dependencies, decompilation, targetJava), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("HOW_TO_BUILD.md"),
                howToBuild(targetJava), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("MISSING_SOURCES.txt"),
                missingSources(decompilation.coverage()), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("DEPENDENCIES_RECOVERED.md"),
                dependenciesMarkdown(dependencies), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("RECOVERY_LIMITS.md"), limits(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".gitignore"),
                ".gradle/\nbuild/\nout/\n.idea/\n*.iml\n", StandardCharsets.UTF_8);
    }

    private static String buildScript(
            String group,
            JarInventory inventory,
            DependencyReport report,
            int targetJava
    ) {
        StringBuilder out = new StringBuilder();
        out.append("plugins {\n    java\n}\n\n")
                .append("group = \"").append(escapeKotlin(group)).append("\"\n")
                .append("version = \"").append(escapeKotlin(safeVersion(inventory.metadata().version()))).append("-recovered\"\n\n")
                .append("repositories {\n")
                .append("    mavenCentral()\n");
        Set<String> repositories = new LinkedHashSet<>();
        for (DependencyReport.DetectedDependency dependency : report.dependencies()) {
            String repository = dependency.repository();
            if (repository == null || repository.isBlank() || "mavenCentral()".equals(repository)) continue;
            repositories.add(repository);
        }
        for (String repository : repositories) {
            out.append("    maven(\"").append(escapeKotlin(repository)).append("\")\n");
        }
        out.append("}\n\n")
                .append("dependencies {\n")
                .append("    // Nested JARs preserved from the input. Remove entries that are not compile dependencies.\n")
                .append("    compileOnly(fileTree(\"libs/unresolved\") { include(\"**/*.jar\") })\n");

        Map<String, Integer> variableOccurrences = new LinkedHashMap<>();
        for (DependencyReport.DetectedDependency dependency : report.dependencies()) {
            String variable = propertyName(dependency.name(), variableOccurrences);
            if (dependency.bundledInsideJar()) {
                out.append("    // Already bundled/shaded in the original JAR: ")
                        .append(dependency.name()).append(" — ").append(nullSafe(dependency.coordinate())).append("\n");
                continue;
            }
            String coordinate = dependency.coordinate();
            if (coordinate == null || !coordinate.contains("<version>")) {
                out.append("    // TODO unresolved dependency: ").append(dependency.name())
                        .append(" — ").append(nullSafe(coordinate)).append("\n");
                continue;
            }
            String base = coordinate.replace(":<version>", "");
            out.append("    providers.gradleProperty(\"").append(variable).append("\").orNull?.let { version ->\n")
                    .append("        compileOnly(\"").append(escapeKotlin(base)).append(":$version\")\n")
                    .append("    }\n");
        }
        out.append("}\n\n")
                .append("java {\n")
                .append("    toolchain.languageVersion.set(JavaLanguageVersion.of(").append(targetJava).append("))\n")
                .append("}\n\n")
                .append("tasks.withType<JavaCompile>().configureEach {\n")
                .append("    options.encoding = \"UTF-8\"\n")
                .append("    options.release.set(").append(targetJava).append(")\n")
                .append("}\n");
        return out.toString();
    }

    private static String gradleProperties(RecoveryOptions options, DependencyReport report) {
        StringBuilder out = new StringBuilder("org.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8\n");
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (DependencyReport.DetectedDependency dependency : report.dependencies()) {
            if (dependency.bundledInsideJar() || dependency.coordinate() == null
                    || !dependency.coordinate().contains("<version>")) continue;
            String property = propertyName(dependency.name(), occurrences);
            String value = null;
            if ((dependency.name().equals("Paper API") || dependency.name().equals("Bukkit API"))
                    && options.paperApiVersion() != null && !options.paperApiVersion().isBlank()) {
                value = options.paperApiVersion();
            }
            if (value == null) out.append("# ").append(property).append("=<choose matching version>\n");
            else out.append(property).append('=').append(value).append('\n');
        }
        return out.toString();
    }

    private static String readme(
            JarInventory inventory,
            DependencyReport dependencies,
            DecompilerSummary decompilation,
            int targetJava
    ) {
        PluginMetadata metadata = inventory.metadata();
        SourceCoverageReport coverage = decompilation.coverage();
        StringBuilder out = new StringBuilder();
        out.append("# ").append(metadata.name()).append(" — recovered Java project\n\n")
                .append("> Получено из JVM-байткода. Это не точная копия исходного проекта автора: комментарии, форматирование и уничтоженные обфускатором имена вернуть невозможно. Используй только для собственного JAR или с разрешения владельца.\n\n")
                .append("## Результат\n\n")
                .append("- Платформа/тип: **").append(metadata.platform()).append("**\n")
                .append("- Версия: **").append(nullSafe(metadata.version())).append("**\n")
                .append("- Main-класс: **").append(metadata.mainClass() == null ? "не определён" : metadata.mainClass()).append("**\n")
                .append("- Найдено `.class`: **").append(inventory.classEntries()).append("**\n")
                .append("- Сохранено оригинальных `.class`: **").append(inventory.classEntries()).append('/')
                .append(inventory.classEntries()).append("**\n")
                .append("- Ожидалось Java source units: **").append(coverage.expectedSourceUnits()).append("**\n")
                .append("- Восстановлено Java source units: **").append(coverage.recoveredSourceUnits()).append("**\n")
                .append("- Проблемных source units: **").append(coverage.missingSources().size()).append("**\n")
                .append("- Целевая Java: **").append(targetJava).append("**\n\n")
                .append("## Главное\n\n")
                .append("- `src/main/java` — выбранный Java-код.\n")
                .append("- `src/main/resources` — конфиги, descriptor-файлы и остальные ресурсы.\n")
                .append("- `MISSING_SOURCES.txt` — точный список классов, для которых нормальный Java не получен.\n")
                .append("- `recovery/original-classes` — каждый исходный `.class` байт-в-байт.\n")
                .append("- `recovery/decompiler-candidates` — результаты всех декомпиляторов.\n")
                .append("- `recovery/bytecode` и `recovery/pseudo-source` — запасное представление логики и структуры.\n")
                .append("- `reports/report.html` — подробный отчёт.\n\n")
                .append("## Сборка в другом месте\n\n")
                .append("Инструмент намеренно не собирает восстановленный проект. Открой папку в IntelliJ IDEA как Gradle-проект, укажи версии зависимостей в `gradle.properties`, затем запускай `gradle build`. Подробности — `HOW_TO_BUILD.md`.\n\n")
                .append("## Движки\n\n");
        if (decompilation.engines().isEmpty()) {
            out.append("Ни один внешний декомпилятор не завершился успешно. Проверь интернет и `reports/logs`.\n");
        } else {
            for (var engine : decompilation.engines()) {
                out.append("- ").append(engine.engine()).append(' ').append(engine.version()).append(": ")
                        .append(engine.success() ? "успешно" : "ошибка").append(" — ")
                        .append(engine.message()).append('\n');
            }
        }
        out.append("\nНайдено групп зависимостей: **").append(dependencies.dependencies().size())
                .append("**. Таблица находится в `DEPENDENCIES_RECOVERED.md`.\n");
        return out.toString();
    }

    private static String howToBuild(int targetJava) {
        return """
                # Как собрать восстановленный проект

                1. Установи JDK %d или более новую JDK, способную компилировать с `--release %d`.
                2. Открой эту папку в IntelliJ IDEA как Gradle-проект.
                3. Открой `gradle.properties` и задай версии зависимостей в закомментированных строках.
                4. Для неизвестных библиотек положи нужные JAR в `libs/unresolved` либо добавь Maven dependency вручную.
                5. Исправь ошибки, перечисленные в `MISSING_SOURCES.txt` и `reports/report.html`.
                6. Запусти `gradle build` на своём компьютере.

                Важно: успешная декомпиляция не гарантирует сборку без правок. Особенно часто ручная работа нужна для NMS, обфускации, удалённых generic-сигнатур, Kotlin-байткода, native-кода и отсутствующих внешних API.
                """.formatted(targetJava, targetJava);
    }

    private static String missingSources(SourceCoverageReport coverage) {
        StringBuilder out = new StringBuilder();
        out.append("Plugin Reconstructor Ultra — Java source coverage\n")
                .append("Class files: ").append(coverage.classFiles()).append('\n')
                .append("Expected source units: ").append(coverage.expectedSourceUnits()).append('\n')
                .append("Recovered source units: ").append(coverage.recoveredSourceUnits()).append('\n')
                .append("Represented class files: ").append(coverage.representedClassFiles()).append('\n')
                .append("Missing source units: ").append(coverage.missingSources().size()).append("\n\n");
        if (coverage.missingSources().isEmpty()) {
            out.append("No missing source units were detected.\n");
            return out.toString();
        }
        out.append("A missing entry means no normal decompiler-generated Java file was found.\n")
                .append("The exact .class bytes are still available under recovery/original-classes.\n\n");
        int index = 1;
        for (SourceCoverageReport.MissingSource missing : coverage.missingSources()) {
            out.append(index++).append(". Expected: ").append(missing.expectedJavaPath()).append('\n');
            for (String entry : missing.classEntries()) out.append("   class: ").append(entry).append('\n');
            for (String name : missing.internalNames()) out.append("   binary-name: ").append(name).append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    private static String dependenciesMarkdown(DependencyReport report) {
        StringBuilder out = new StringBuilder("# Найденные зависимости\n\n")
                .append("Это статические предположения по именам используемых классов. Версии нужно сверять с сервером и оригинальной сборкой.\n\n")
                .append("| Библиотека | Координата | Ссылок | Внутри JAR | Репозиторий |\n")
                .append("|---|---|---:|:---:|---|\n");
        for (DependencyReport.DetectedDependency dependency : report.dependencies()) {
            out.append('|').append(escapeTable(dependency.name()))
                    .append('|').append('`').append(escapeTable(dependency.coordinate())).append('`')
                    .append('|').append(dependency.referencedClassCount())
                    .append('|').append(dependency.bundledInsideJar() ? "да" : "нет")
                    .append('|').append(escapeTable(dependency.repository())).append("|\n");
        }
        if (!report.unresolvedPrefixes().isEmpty()) {
            out.append("\n## Неопределённые пространства имён\n\n");
            report.unresolvedPrefixes().forEach((prefix, count) -> out.append("- `").append(prefix).append("` — ").append(count).append(" ссылок\n"));
        }
        if (!report.nmsReferences().isEmpty()) {
            out.append("\n## NMS/CraftBukkit\n\nОбнаружено ").append(report.nmsReferences().size())
                    .append(" внутренних ссылок. Они обычно требуют точной версии сервера или mappings.\n");
        }
        return out.toString();
    }

    private static String limits() {
        return """
                # Ограничения восстановления

                Нельзя математически вернуть информацию, которой больше нет в байткоде: комментарии, исходное форматирование, удалённые имена локальных переменных и точные имена после обфускации.

                Инструмент анализирует содержимое предоставленного JAR. Он не выдумывает код, отсутствующий в архиве, не преобразует native `.dll/.so` в исходный Java и не обходит лицензии, активации, внешние ключи или серверные проверки.

                Для каждого физически присутствующего `.class` сохраняется точная копия в `recovery/original-classes`. Если нормальный Java получить не удалось, класс остаётся в отчёте, bytecode dump и pseudo-source — он не исчезает молча.
                """;
    }

    private static void copyNestedLibraries(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        Path absoluteTarget = target.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                Path destination = absoluteTarget.resolve(relative).normalize();
                if (!destination.startsWith(absoluteTarget)) continue;
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static int inferTargetJava(JarInventory inventory) {
        int target = inventory.classes().stream().mapToInt(info -> info.majorVersion() - 44).max().orElse(21);
        return Math.max(8, target);
    }

    private static String inferGroup(PluginMetadata metadata) {
        if (metadata.mainClass() != null && metadata.mainClass().contains(".")) {
            return metadata.mainClass().substring(0, metadata.mainClass().lastIndexOf('.'));
        }
        return "recovered.plugin";
    }

    private static String propertyName(String name, Map<String, Integer> occurrences) {
        String base = name.replaceAll("[^A-Za-z0-9]+", " ").trim();
        String[] words = base.split("\\s+");
        StringBuilder value = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (value.isEmpty()) value.append(word.substring(0, 1).toLowerCase(Locale.ROOT)).append(word.substring(1));
            else value.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        if (value.isEmpty()) value.append("dependency");
        value.append("Version");
        String candidate = value.toString();
        int count = occurrences.merge(candidate, 1, Integer::sum);
        return count == 1 ? candidate : candidate + count;
    }

    private static String safeVersion(String value) {
        if (value == null || value.isBlank()) return "0.0.0";
        return value.replaceAll("[^A-Za-z0-9_.+-]", "_");
    }

    private static String escapeKotlin(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }

    private static String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
