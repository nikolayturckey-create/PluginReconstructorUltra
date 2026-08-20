package dev.nik.reconstructor;

import dev.nik.reconstructor.cli.Arguments;
import dev.nik.reconstructor.core.RecoveryEngine;
import dev.nik.reconstructor.core.RecoveryResult;
import dev.nik.reconstructor.gui.GuiLauncher;
import dev.nik.reconstructor.tools.ToolInstaller;

import java.awt.GraphicsEnvironment;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        boolean guiRequested = args.length == 0 || (args.length == 1 && "gui".equalsIgnoreCase(args[0]));
        if (guiRequested && !GraphicsEnvironment.isHeadless()) {
            GuiLauncher.launch();
            return;
        }
        if (guiRequested && args.length == 1) {
            System.err.println("GUI недоступен в headless-среде. Используй --help для CLI.");
            System.exit(2);
            return;
        }
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        try {
            Arguments.Parsed parsed = Arguments.parse(args);
            return switch (parsed.command()) {
                case HELP -> {
                    printUsage();
                    yield 0;
                }
                case VERSION -> {
                    System.out.println("Plugin Reconstructor Ultra " + RecoveryEngine.VERSION);
                    yield 0;
                }
                case SETUP_TOOLS -> setupTools(parsed);
                case RECOVER -> recover(parsed);
            };
        } catch (IllegalArgumentException badArguments) {
            System.err.println("Ошибка аргументов: " + badArguments.getMessage());
            System.err.println("Используй --help для примеров.");
            return 2;
        } catch (Throwable error) {
            System.err.println("Критическая ошибка: " + safeMessage(error));
            if (Boolean.getBoolean("pluginReconstructor.debug")) error.printStackTrace(System.err);
            return 1;
        }
    }

    private static int setupTools(Arguments.Parsed parsed) {
        System.out.println("Установка декомпиляторов в " + parsed.toolsDirectory() + "...");
        ToolInstaller.Installation installation = new ToolInstaller().install(parsed.toolsDirectory(), parsed.setupForce());
        installation.messages().forEach(message -> System.out.println("- " + message));
        return installation.complete() ? 0 : 1;
    }

    private static int recover(Arguments.Parsed parsed) throws Exception {
        System.out.println("Plugin Reconstructor Ultra " + RecoveryEngine.VERSION);
        System.out.println("Используй инструмент только для собственных JAR или с разрешения владельца.\n");
        RecoveryResult result = new RecoveryEngine().recover(parsed.recoveryOptions(), System.out::println);
        System.out.println();
        System.out.println("Итог:");
        System.out.println("  Проект: " + result.outputDirectory());
        System.out.println("  Java: " + result.sourceDirectory());
        System.out.println("  HTML-отчёт: " + result.reportHtml());
        System.out.println("  Java-файлов: " + result.sourceFiles());
        System.out.println("  Покрытие source units: " + result.recoveredSourceUnits() + "/" + result.expectedSourceUnits());
        System.out.println("  Проблемных source units: " + result.missingSourceUnits());
        System.out.println("  Оригинальных class-файлов сохранено: " + result.classFiles() + "/" + result.classFiles());
        System.out.println("  Обфускация: " + result.obfuscationLevel());
        return result.completeJavaCoverage() ? 0 : 3;
    }

    private static void printUsage() {
        System.out.println("""
                Plugin Reconstructor Ultra 0.2.0

                Самый простой запуск:
                  двойной клик по PluginReconstructorUltra-0.2.0.jar
                  перетащи JAR в окно → нажми «Извлечь Java-исходники»

                CLI:
                  java -jar PluginReconstructorUltra-0.2.0.jar plugin.jar
                  java -jar PluginReconstructorUltra-0.2.0.jar plugin.jar -o RecoveredPlugin

                Движки Vineflower, CFR и Procyon скачиваются автоматически один раз.

                Дополнительные параметры:
                  -o, --output <dir>          папка результата
                  --engine <mode>             auto | vineflower | cfr | procyon | both | all | none
                  --tools-dir <dir>           папка движков
                  --vineflower <jar>           собственный Vineflower JAR
                  --cfr <jar>                  собственный CFR JAR
                  --procyon <jar>              собственный Procyon JAR
                  --no-download-tools          не скачивать движки
                  --max-memory-mb <n>          память каждому движку
                  --timeout-minutes <n>        лимит времени движка
                  --paper-api-version <ver>    записать версию Paper API в gradle.properties
                  -f, --force                  заменить только предыдущий отмеченный результат
                  -h, --help                   эта справка
                  --version                    версия инструмента

                Результат:
                  src/main/java                выбранный Java-код
                  src/main/resources           ресурсы JAR
                  recovery/original-classes    точная копия каждого физического .class
                  MISSING_SOURCES.txt           список классов без нормального Java
                  reports/report.html          подробный отчёт
                """);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
