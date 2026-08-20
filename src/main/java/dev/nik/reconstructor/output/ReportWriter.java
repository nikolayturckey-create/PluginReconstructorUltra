package dev.nik.reconstructor.output;

import dev.nik.reconstructor.analysis.DependencyReport;
import dev.nik.reconstructor.analysis.ObfuscationReport;
import dev.nik.reconstructor.classfile.ClassInfo;
import dev.nik.reconstructor.core.RecoveryReport;
import dev.nik.reconstructor.decompile.DecompilerResult;
import dev.nik.reconstructor.decompile.SourceCoverageReport;
import dev.nik.reconstructor.decompile.SourceSelectionReport;
import dev.nik.reconstructor.jar.JarInventory;
import dev.nik.reconstructor.jar.PluginMetadata;
import dev.nik.reconstructor.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReportWriter {
    public Paths write(RecoveryReport report, Path reportsDirectory) throws IOException {
        Files.createDirectories(reportsDirectory);
        Path json = reportsDirectory.resolve("report.json");
        Path html = reportsDirectory.resolve("report.html");
        Files.writeString(json, json(report), StandardCharsets.UTF_8);
        Files.writeString(html, html(report), StandardCharsets.UTF_8);
        Files.writeString(reportsDirectory.resolve("classes.csv"), classesCsv(report.inventory()), StandardCharsets.UTF_8);
        Files.writeString(reportsDirectory.resolve("archive-entries.csv"), entriesCsv(report.inventory()), StandardCharsets.UTF_8);
        Files.writeString(reportsDirectory.resolve("dependencies.csv"), dependenciesCsv(report.dependencies()), StandardCharsets.UTF_8);
        Files.writeString(reportsDirectory.resolve("sources.csv"), sourcesCsv(report.decompilation().selection()), StandardCharsets.UTF_8);
        Files.writeString(reportsDirectory.resolve("missing-sources.csv"), missingSourcesCsv(report.decompilation().coverage()), StandardCharsets.UTF_8);
        Files.writeString(reportsDirectory.resolve("warnings.txt"), warningsText(report), StandardCharsets.UTF_8);
        return new Paths(html, json);
    }

    private static String json(RecoveryReport report) {
        JarInventory inventory = report.inventory();
        PluginMetadata metadata = inventory.metadata();
        ObfuscationReport obfuscation = report.obfuscation();
        StringBuilder out = new StringBuilder(Math.max(32_000, inventory.classes().size() * 320));
        out.append("{\n")
                .append("  \"schemaVersion\": 2,\n")
                .append("  \"toolVersion\": ").append(Text.json(report.toolVersion())).append(",\n")
                .append("  \"startedAt\": ").append(Text.json(report.startedAt().toString())).append(",\n")
                .append("  \"finishedAt\": ").append(Text.json(report.finishedAt().toString())).append(",\n")
                .append("  \"durationMillis\": ").append(report.durationMillis()).append(",\n")
                .append("  \"input\": {\n")
                .append("    \"path\": ").append(Text.json(inventory.input().toString())).append(",\n")
                .append("    \"size\": ").append(inventory.fileSize()).append(",\n")
                .append("    \"sha256\": ").append(Text.json(inventory.sha256())).append("\n")
                .append("  },\n")
                .append("  \"plugin\": {\n")
                .append("    \"descriptor\": ").append(Text.json(metadata.descriptorName())).append(",\n")
                .append("    \"name\": ").append(Text.json(metadata.name())).append(",\n")
                .append("    \"version\": ").append(Text.json(metadata.version())).append(",\n")
                .append("    \"mainClass\": ").append(Text.json(metadata.mainClass())).append(",\n")
                .append("    \"apiVersion\": ").append(Text.json(metadata.apiVersion())).append(",\n")
                .append("    \"platform\": ").append(Text.json(metadata.platform())).append(",\n")
                .append("    \"foliaSupported\": ").append(metadata.foliaSupported()).append(",\n")
                .append("    \"authors\": "); appendStrings(out, metadata.authors(), 4); out.append(",\n")
                .append("    \"commands\": "); appendStrings(out, metadata.commands(), 4); out.append(",\n")
                .append("    \"permissions\": "); appendStrings(out, metadata.permissions(), 4); out.append(",\n")
                .append("    \"dependencies\": "); appendStrings(out, metadata.dependencies(), 4); out.append(",\n")
                .append("    \"softDependencies\": "); appendStrings(out, metadata.softDependencies(), 4); out.append("\n")
                .append("  },\n")
                .append("  \"archive\": {\n")
                .append("    \"totalEntries\": ").append(inventory.totalEntries()).append(",\n")
                .append("    \"classEntries\": ").append(inventory.classEntries()).append(",\n")
                .append("    \"parsedClasses\": ").append(inventory.parsedClasses()).append(",\n")
                .append("    \"failedClasses\": ").append(inventory.failedClasses()).append(",\n")
                .append("    \"resourceEntries\": ").append(inventory.resourceEntries()).append(",\n")
                .append("    \"nestedJars\": ").append(inventory.nestedJars()).append(",\n")
                .append("    \"nativeLibraries\": ").append(inventory.nativeLibraries()).append(",\n")
                .append("    \"signatureFiles\": ").append(inventory.signatureFiles()).append(",\n")
                .append("    \"signed\": ").append(inventory.signed()).append(",\n")
                .append("    \"multiRelease\": ").append(inventory.multiRelease()).append(",\n")
                .append("    \"multiReleaseClasses\": ").append(inventory.multiReleaseClasses()).append(",\n")
                .append("    \"declaredUncompressedBytes\": ").append(inventory.declaredUncompressedBytes()).append("\n")
                .append("  },\n")
                .append("  \"obfuscation\": {\n")
                .append("    \"level\": ").append(Text.json(obfuscation.level())).append(",\n")
                .append("    \"overallScore\": ").append(decimal(obfuscation.overallScore())).append(",\n")
                .append("    \"nameScore\": ").append(decimal(obfuscation.nameObfuscationScore())).append(",\n")
                .append("    \"controlFlowScore\": ").append(decimal(obfuscation.controlFlowScore())).append(",\n")
                .append("    \"stringProtectionScore\": ").append(decimal(obfuscation.stringProtectionScore())).append(",\n")
                .append("    \"reflectionScore\": ").append(decimal(obfuscation.reflectionScore())).append(",\n")
                .append("    \"runtimeLoadingScore\": ").append(decimal(obfuscation.runtimeLoadingScore())).append(",\n")
                .append("    \"antiTamperScore\": ").append(decimal(obfuscation.antiTamperScore())).append(",\n")
                .append("    \"nativeCodePresent\": ").append(obfuscation.nativeCodePresent()).append(",\n")
                .append("    \"reasons\": "); appendStrings(out, obfuscation.reasons(), 4); out.append("\n")
                .append("  },\n")
                .append("  \"decompilation\": {\n")
                .append("    \"selectedFiles\": ").append(report.decompilation().selection().selectedFiles()).append(",\n")
                .append("    \"javaFiles\": ").append(report.decompilation().selection().javaFiles()).append(",\n")
                .append("    \"expectedSourceUnits\": ").append(report.decompilation().coverage().expectedSourceUnits()).append(",\n")
                .append("    \"recoveredSourceUnits\": ").append(report.decompilation().coverage().recoveredSourceUnits()).append(",\n")
                .append("    \"representedClassFiles\": ").append(report.decompilation().coverage().representedClassFiles()).append(",\n")
                .append("    \"missingSourceUnits\": ").append(report.decompilation().coverage().missingSources().size()).append(",\n")
                .append("    \"coverageRatio\": ").append(decimal(report.decompilation().coverage().ratio())).append(",\n")
                .append("    \"engines\": [\n");
        List<DecompilerResult> engines = report.decompilation().engines();
        for (int i = 0; i < engines.size(); i++) {
            DecompilerResult engine = engines.get(i);
            out.append("      {\"name\": ").append(Text.json(engine.engine()))
                    .append(", \"version\": ").append(Text.json(engine.version()))
                    .append(", \"success\": ").append(engine.success())
                    .append(", \"timedOut\": ").append(engine.timedOut())
                    .append(", \"exitCode\": ").append(engine.exitCode())
                    .append(", \"durationMillis\": ").append(engine.durationMillis())
                    .append(", \"sourceFiles\": ").append(engine.sourceFiles())
                    .append(", \"message\": ").append(Text.json(engine.message())).append('}');
            if (i + 1 < engines.size()) out.append(',');
            out.append('\n');
        }
        out.append("    ]\n  },\n")
                .append("  \"dependencies\": [\n");
        List<DependencyReport.DetectedDependency> dependencies = report.dependencies().dependencies();
        for (int i = 0; i < dependencies.size(); i++) {
            DependencyReport.DetectedDependency dependency = dependencies.get(i);
            out.append("    {\"name\": ").append(Text.json(dependency.name()))
                    .append(", \"prefix\": ").append(Text.json(dependency.prefix()))
                    .append(", \"coordinate\": ").append(Text.json(dependency.coordinate()))
                    .append(", \"repository\": ").append(Text.json(dependency.repository()))
                    .append(", \"referencedClassCount\": ").append(dependency.referencedClassCount())
                    .append(", \"bundledInsideJar\": ").append(dependency.bundledInsideJar())
                    .append(", \"note\": ").append(Text.json(dependency.note())).append('}');
            if (i + 1 < dependencies.size()) out.append(',');
            out.append('\n');
        }
        out.append("  ],\n")
                .append("  \"unresolvedPrefixes\": {");
        int mapIndex = 0;
        for (Map.Entry<String, Integer> entry : report.dependencies().unresolvedPrefixes().entrySet()) {
            if (mapIndex++ > 0) out.append(',');
            out.append('\n').append("    ").append(Text.json(entry.getKey())).append(": ").append(entry.getValue());
        }
        if (!report.dependencies().unresolvedPrefixes().isEmpty()) out.append('\n').append("  ");
        out.append("},\n")
                .append("  \"classes\": [\n");
        List<ClassInfo> classes = inventory.classes().stream().sorted(Comparator.comparing(ClassInfo::dottedName)).toList();
        for (int i = 0; i < classes.size(); i++) {
            ClassInfo info = classes.get(i);
            out.append("    {\"name\": ").append(Text.json(info.dottedName()))
                    .append(", \"entry\": ").append(Text.json(info.entryName()))
                    .append(", \"super\": ").append(Text.json(info.superName()))
                    .append(", \"javaVersion\": ").append(Text.json(info.javaVersion()))
                    .append(", \"majorVersion\": ").append(info.majorVersion())
                    .append(", \"fields\": ").append(info.fields().size())
                    .append(", \"methods\": ").append(info.methods().size())
                    .append(", \"bytecodeSize\": ").append(info.bytecodeSize())
                    .append(", \"branches\": ").append(info.branchCount())
                    .append(", \"switches\": ").append(info.switchCount())
                    .append(", \"invokeDynamic\": ").append(info.invokeDynamicCount())
                    .append(", \"xorOperations\": ").append(info.xorCount())
                    .append(", \"exceptionHandlers\": ").append(info.exceptionHandlers())
                    .append(", \"nativeMethods\": ").append(info.nativeMethodCount())
                    .append(", \"kotlinMetadata\": ").append(info.kotlinMetadata())
                    .append(", \"debugInfo\": ").append(info.debugInfo())
                    .append(", \"references\": ").append(info.referencedClasses().size())
                    .append(", \"stringConstants\": ").append(info.stringConstants().size())
                    .append('}');
            if (i + 1 < classes.size()) out.append(',');
            out.append('\n');
        }
        out.append("  ],\n")
                .append("  \"parseFailures\": [\n");
        for (int i = 0; i < inventory.parseFailures().size(); i++) {
            JarInventory.ParseFailure failure = inventory.parseFailures().get(i);
            out.append("    {\"entry\": ").append(Text.json(failure.entryName()))
                    .append(", \"message\": ").append(Text.json(failure.message())).append('}');
            if (i + 1 < inventory.parseFailures().size()) out.append(',');
            out.append('\n');
        }
        out.append("  ],\n")
                .append("  \"warnings\": ");
        appendStrings(out, report.warnings(), 2);
        out.append("\n}\n");
        return out.toString();
    }

    private static String html(RecoveryReport report) {
        JarInventory inventory = report.inventory();
        PluginMetadata plugin = inventory.metadata();
        ObfuscationReport obf = report.obfuscation();
        StringBuilder out = new StringBuilder(80_000);
        out.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Plugin Reconstructor Ultra — ").append(Text.html(plugin.name())).append("</title>")
                .append("<style>")
                .append("body{font-family:Inter,system-ui,sans-serif;margin:0;background:#0b1020;color:#e8ecf4;line-height:1.5}")
                .append("main{max-width:1180px;margin:auto;padding:28px}.hero{background:linear-gradient(135deg,#18213d,#111831);padding:28px;border:1px solid #2b375c;border-radius:20px}")
                .append("h1{margin:0 0 8px;font-size:34px}h2{margin-top:34px}.muted{color:#aeb8d1}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px;margin-top:20px}")
                .append(".card{background:#11182c;border:1px solid #263250;border-radius:14px;padding:16px}.big{font-size:26px;font-weight:750}.pill{display:inline-block;padding:4px 10px;border-radius:999px;background:#25345b}")
                .append("table{width:100%;border-collapse:collapse;background:#11182c;border-radius:12px;overflow:hidden}th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #27324e;vertical-align:top}th{background:#17203a}")
                .append("code{background:#202a46;padding:2px 5px;border-radius:5px}.bar{height:10px;background:#222d49;border-radius:10px;overflow:hidden}.bar>span{display:block;height:100%;background:#7ea2ff}")
                .append(".warn{background:#332717;border:1px solid #6a5123;padding:12px;border-radius:10px;margin:8px 0}.ok{color:#8ee3a1}.bad{color:#ff9b9b}a{color:#9eb8ff}")
                .append("</style></head><body><main>")
                .append("<section class=\"hero\"><div class=\"pill\">Plugin Reconstructor Ultra ").append(Text.html(report.toolVersion())).append("</div>")
                .append("<h1>").append(Text.html(plugin.name())).append("</h1>")
                .append("<div class=\"muted\">").append(Text.html(plugin.platform())).append(" · ")
                .append(Text.html(plugin.version())).append(" · SHA-256 <code>").append(Text.html(inventory.sha256())).append("</code></div>")
                .append("<div class=\"grid\">")
                .append(card("Классы", inventory.parsedClasses() + " / " + inventory.classEntries()))
                .append(card("Java-файлы", Integer.toString(report.decompilation().selection().selectedFiles())))
                .append(card("Java-покрытие", report.decompilation().coverage().recoveredSourceUnits() + " / " + report.decompilation().coverage().expectedSourceUnits()))
                .append(card("Ресурсы", Integer.toString(inventory.resourceEntries())))
                .append(card("Обфускация", obf.level()))
                .append(card("Вложенные JAR", Integer.toString(inventory.nestedJars())))
                .append(card("Нативные файлы", Integer.toString(inventory.nativeLibraries())))
                .append("</div></section>")
                .append("<h2>Плагин</h2><table><tbody>")
                .append(row("Descriptor", plugin.descriptorName()))
                .append(row("Main class", plugin.mainClass()))
                .append(row("API version", plugin.apiVersion()))
                .append(row("Folia", Boolean.toString(plugin.foliaSupported())))
                .append(row("Команды", String.join(", ", plugin.commands())))
                .append(row("Permissions", String.join(", ", plugin.permissions())))
                .append(row("Зависимости descriptor", String.join(", ", plugin.dependencies())))
                .append("</tbody></table>")
                .append("<h2>Профиль обфускации</h2>")
                .append(scoreRow("Общий", obf.overallScore()))
                .append(scoreRow("Имена", obf.nameObfuscationScore()))
                .append(scoreRow("Control flow", obf.controlFlowScore()))
                .append(scoreRow("Защита строк", obf.stringProtectionScore()))
                .append(scoreRow("Reflection", obf.reflectionScore()))
                .append(scoreRow("Runtime loading", obf.runtimeLoadingScore()))
                .append(scoreRow("Anti-tamper heuristic", obf.antiTamperScore()));
        for (String reason : obf.reasons()) out.append("<div class=\"warn\">").append(Text.html(reason)).append("</div>");

        SourceCoverageReport coverage = report.decompilation().coverage();
        out.append("<h2>Покрытие Java-исходниками</h2><div class=\"grid\">")
                .append(card("Ожидалось source units", Integer.toString(coverage.expectedSourceUnits())))
                .append(card("Восстановлено", Integer.toString(coverage.recoveredSourceUnits())))
                .append(card("Представлено .class", coverage.representedClassFiles() + " / " + coverage.classFiles()))
                .append(card("Пропущено", Integer.toString(coverage.missingSources().size())))
                .append("</div>");
        if (!coverage.missingSources().isEmpty()) {
            out.append("<div class=\"warn\"><strong>Нормальный Java не получен для следующих source units:</strong><ul>");
            for (SourceCoverageReport.MissingSource missing : coverage.missingSources().stream().limit(100).toList()) {
                out.append("<li><code>").append(Text.html(missing.expectedJavaPath())).append("</code> — ")
                        .append(Text.html(String.join(", ", missing.classEntries()))).append("</li>");
            }
            if (coverage.missingSources().size() > 100) {
                out.append("<li>Ещё ").append(coverage.missingSources().size() - 100)
                        .append(" записей находятся в missing-sources.csv и MISSING_SOURCES.txt.</li>");
            }
            out.append("</ul></div>");
        } else {
            out.append("<div class=\"card ok\">Все ожидаемые source units представлены Java-файлами.</div>");
        }

        out.append("<h2>Декомпиляция</h2><table><thead><tr><th>Движок</th><th>Статус</th><th>Файлы</th><th>Время</th><th>Сообщение</th></tr></thead><tbody>");
        if (report.decompilation().engines().isEmpty()) {
            out.append("<tr><td colspan=\"5\">Движки не запускались. Байткод и псевдо-исходник всё равно созданы.</td></tr>");
        }
        for (DecompilerResult engine : report.decompilation().engines()) {
            out.append("<tr><td>").append(Text.html(engine.engine() + " " + engine.version())).append("</td><td class=\"")
                    .append(engine.success() ? "ok\">успешно" : "bad\">ошибка").append("</td><td>")
                    .append(engine.sourceFiles()).append("</td><td>").append(engine.durationMillis()).append(" ms</td><td>")
                    .append(Text.html(engine.message())).append("</td></tr>");
        }
        out.append("</tbody></table>")
                .append("<h2>Зависимости</h2><table><thead><tr><th>Имя</th><th>Координата</th><th>Ссылок</th><th>Bundled</th><th>Примечание</th></tr></thead><tbody>");
        for (DependencyReport.DetectedDependency dependency : report.dependencies().dependencies()) {
            out.append("<tr><td>").append(Text.html(dependency.name())).append("</td><td><code>")
                    .append(Text.html(dependency.coordinate())).append("</code></td><td>")
                    .append(dependency.referencedClassCount()).append("</td><td>")
                    .append(dependency.bundledInsideJar() ? "да" : "нет").append("</td><td>")
                    .append(Text.html(dependency.note())).append("</td></tr>");
        }
        out.append("</tbody></table>");

        out.append("<h2>Самые подозрительные классы</h2><table><thead><tr><th>Класс</th><th>Score</th><th>Bytecode</th><th>Branches</th><th>Switch</th><th>Причина</th></tr></thead><tbody>");
        for (ObfuscationReport.SuspiciousClass suspicious : obf.suspiciousClasses().stream().limit(30).toList()) {
            out.append("<tr><td><code>").append(Text.html(suspicious.className())).append("</code></td><td>")
                    .append(percent(suspicious.score())).append("</td><td>").append(suspicious.bytecodeSize())
                    .append("</td><td>").append(suspicious.branches()).append("</td><td>")
                    .append(suspicious.switches()).append("</td><td>").append(Text.html(suspicious.reason())).append("</td></tr>");
        }
        if (obf.suspiciousClasses().isEmpty()) out.append("<tr><td colspan=\"6\">Сильных сигналов не найдено.</td></tr>");
        out.append("</tbody></table>");

        List<String> warnings = report.warnings();
        out.append("<h2>Предупреждения</h2>");
        if (warnings.isEmpty()) out.append("<div class=\"card\">Нет предупреждений.</div>");
        else for (String warning : warnings) out.append("<div class=\"warn\">").append(Text.html(warning)).append("</div>");
        out.append("<h2>Что важно</h2><div class=\"card\">")
                .append("Оригинальные комментарии и уничтоженные обфускатором имена вернуть нельзя. Псевдо-исходник не подменяет логику: точные JVM-инструкции сохранены в <code>recovery/bytecode</code>. Инструмент не предназначен для обхода лицензий или внешних ключей.")
                .append("</div><p class=\"muted\">Отчёт создан ").append(Text.html(report.finishedAt().toString()))
                .append(" за ").append(report.durationMillis()).append(" ms.</p></main></body></html>");
        return out.toString();
    }

    private static String classesCsv(JarInventory inventory) {
        StringBuilder out = new StringBuilder("entry,class,super,java_version,fields,methods,bytecode_bytes,branches,switches,invokedynamic,xor,handlers,native_methods,kotlin,debug,references,strings,warnings\n");
        inventory.classes().stream().sorted(Comparator.comparing(ClassInfo::dottedName)).forEach(info -> out
                .append(csv(info.entryName())).append(',').append(csv(info.dottedName())).append(',').append(csv(info.superName())).append(',')
                .append(csv(info.javaVersion())).append(',').append(info.fields().size()).append(',').append(info.methods().size()).append(',')
                .append(info.bytecodeSize()).append(',').append(info.branchCount()).append(',').append(info.switchCount()).append(',')
                .append(info.invokeDynamicCount()).append(',').append(info.xorCount()).append(',').append(info.exceptionHandlers()).append(',')
                .append(info.nativeMethodCount()).append(',').append(info.kotlinMetadata()).append(',').append(info.debugInfo()).append(',')
                .append(info.referencedClasses().size()).append(',').append(info.stringConstants().size()).append(',')
                .append(csv(String.join(" | ", info.warnings()))).append('\n'));
        return out.toString();
    }

    private static String entriesCsv(JarInventory inventory) {
        StringBuilder out = new StringBuilder("name,kind,size,compressed_size,crc,sha256\n");
        for (JarInventory.ArchiveEntryInfo entry : inventory.entries()) {
            out.append(csv(entry.name())).append(',').append(csv(entry.kind())).append(',').append(entry.size()).append(',')
                    .append(entry.compressedSize()).append(',').append(entry.crc()).append(',').append(csv(entry.sha256())).append('\n');
        }
        return out.toString();
    }

    private static String dependenciesCsv(DependencyReport report) {
        StringBuilder out = new StringBuilder("name,prefix,coordinate,repository,referenced_classes,bundled,note\n");
        for (DependencyReport.DetectedDependency dependency : report.dependencies()) {
            out.append(csv(dependency.name())).append(',').append(csv(dependency.prefix())).append(',')
                    .append(csv(dependency.coordinate())).append(',').append(csv(dependency.repository())).append(',')
                    .append(dependency.referencedClassCount()).append(',').append(dependency.bundledInsideJar()).append(',')
                    .append(csv(dependency.note())).append('\n');
        }
        return out.toString();
    }

    private static String sourcesCsv(SourceSelectionReport report) {
        StringBuilder out = new StringBuilder("output_path,selected_engine,score,sha256,alternatives\n");
        for (SourceSelectionReport.SelectedSource source : report.files()) {
            String alternatives = source.alternatives().stream()
                    .map(item -> item.engine() + ":" + decimal(item.score()) + ":" + item.sourcePath())
                    .reduce((a, b) -> a + " | " + b).orElse("");
            out.append(csv(source.outputPath())).append(',').append(csv(source.selectedEngine())).append(',')
                    .append(decimal(source.selectedScore())).append(',').append(csv(source.sha256())).append(',')
                    .append(csv(alternatives)).append('\n');
        }
        return out.toString();
    }

    private static String missingSourcesCsv(SourceCoverageReport report) {
        StringBuilder out = new StringBuilder("expected_java_path,class_entries,internal_names\n");
        for (SourceCoverageReport.MissingSource missing : report.missingSources()) {
            out.append(csv(missing.expectedJavaPath())).append(',')
                    .append(csv(String.join(" | ", missing.classEntries()))).append(',')
                    .append(csv(String.join(" | ", missing.internalNames()))).append('\n');
        }
        return out.toString();
    }

    private static String warningsText(RecoveryReport report) {
        List<String> all = new ArrayList<>(report.warnings());
        for (JarInventory.ParseFailure failure : report.inventory().parseFailures()) {
            all.add("Class parse failure: " + failure.entryName() + " — " + failure.message());
        }
        if (all.isEmpty()) return "No warnings.\n";
        StringBuilder out = new StringBuilder();
        for (String warning : all) out.append("- ").append(warning).append('\n');
        return out.toString();
    }

    private static String card(String label, String value) {
        return "<div class=\"card\"><div class=\"muted\">" + Text.html(label) + "</div><div class=\"big\">" + Text.html(value) + "</div></div>";
    }

    private static String row(String label, String value) {
        return "<tr><th>" + Text.html(label) + "</th><td>" + Text.html(value == null ? "—" : value) + "</td></tr>";
    }

    private static String scoreRow(String label, double score) {
        int width = (int) Math.round(Math.max(0, Math.min(1, score)) * 100);
        return "<div class=\"card\"><strong>" + Text.html(label) + "</strong> — " + percent(score)
                + "<div class=\"bar\"><span style=\"width:" + width + "%\"></span></div></div>";
    }

    private static void appendStrings(StringBuilder out, List<String> values, int indent) {
        out.append('[');
        if (!values.isEmpty()) out.append('\n');
        for (int i = 0; i < values.size(); i++) {
            out.append(" ".repeat(indent)).append(Text.json(values.get(i)));
            if (i + 1 < values.size()) out.append(',');
            out.append('\n');
        }
        if (!values.isEmpty()) out.append(" ".repeat(Math.max(0, indent - 2)));
        out.append(']');
    }

    private static String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    public record Paths(Path html, Path json) {}
}
