package dev.nik.reconstructor.cli;

import dev.nik.reconstructor.core.RecoveryOptions;
import dev.nik.reconstructor.decompile.EngineMode;
import dev.nik.reconstructor.util.FilesEx;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class Arguments {
    private Arguments() {}

    public static Parsed parse(String[] raw) {
        List<String> args = new ArrayList<>(List.of(raw));
        if (args.isEmpty() || args.contains("--help") || args.contains("-h") || "help".equalsIgnoreCase(args.getFirst())) {
            return Parsed.help();
        }
        if (args.contains("--version") || "version".equalsIgnoreCase(args.getFirst())) {
            return Parsed.version();
        }
        if ("setup-tools".equalsIgnoreCase(args.getFirst())) {
            args.removeFirst();
            Path tools = defaultToolsDirectory();
            boolean force = false;
            while (!args.isEmpty()) {
                String token = args.removeFirst();
                switch (token) {
                    case "--tools-dir" -> tools = Path.of(requireValue(args, token));
                    case "--force", "-f" -> force = true;
                    default -> throw new IllegalArgumentException("Unknown setup-tools option: " + token);
                }
            }
            return Parsed.setup(tools.toAbsolutePath().normalize(), force);
        }

        if ("recover".equalsIgnoreCase(args.getFirst())) args.removeFirst();
        Path input = null;
        Path output = null;
        Path tools = defaultToolsDirectory();
        Path vineflower = null;
        Path cfr = null;
        Path procyon = null;
        EngineMode mode = EngineMode.AUTO;
        boolean force = false;
        boolean downloadTools = true;
        boolean verbose = false;
        int maxMemory = defaultMemoryMb();
        int timeoutMinutes = 20;
        String paperApiVersion = null;

        while (!args.isEmpty()) {
            String token = args.removeFirst();
            switch (token) {
                case "-o", "--output" -> output = Path.of(requireValue(args, token));
                case "--tools-dir" -> tools = Path.of(requireValue(args, token));
                case "--vineflower" -> vineflower = Path.of(requireValue(args, token));
                case "--cfr" -> cfr = Path.of(requireValue(args, token));
                case "--procyon" -> procyon = Path.of(requireValue(args, token));
                case "--engine" -> mode = EngineMode.parse(requireValue(args, token));
                case "--download-tools" -> downloadTools = true;
                case "--no-download-tools" -> downloadTools = false;
                case "--force", "-f" -> force = true;
                case "--verbose", "-v" -> verbose = true;
                case "--max-memory-mb" -> maxMemory = parseInt(requireValue(args, token), token, 256, 131_072);
                case "--timeout-minutes" -> timeoutMinutes = parseInt(requireValue(args, token), token, 1, 1_440);
                case "--paper-api-version" -> paperApiVersion = requireValue(args, token);
                default -> {
                    if (token.startsWith("-")) throw new IllegalArgumentException("Unknown option: " + token);
                    if (input != null) throw new IllegalArgumentException("Only one input JAR can be processed per command.");
                    input = Path.of(token);
                }
            }
        }
        if (input == null) throw new IllegalArgumentException("Input JAR is missing.");
        if (output == null) {
            String file = input.getFileName().toString();
            int dot = file.toLowerCase().lastIndexOf(".jar");
            String base = dot > 0 ? file.substring(0, dot) : file;
            Path parent = input.toAbsolutePath().normalize().getParent();
            if (parent == null) parent = Path.of(".").toAbsolutePath().normalize();
            output = parent.resolve("Recovered_" + FilesEx.sanitizeFileName(base));
        }
        RecoveryOptions options = new RecoveryOptions(
                input,
                output,
                tools,
                vineflower,
                cfr,
                procyon,
                mode,
                force,
                downloadTools,
                verbose,
                maxMemory,
                Duration.ofMinutes(timeoutMinutes),
                paperApiVersion
        );
        return Parsed.recover(options);
    }

    private static Path defaultToolsDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".plugin-reconstructor-ultra", "tools");
    }

    private static int defaultMemoryMb() {
        long max = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if (max <= 0) return 2048;
        return (int) Math.max(768, Math.min(4096, max / 2));
    }

    private static String requireValue(List<String> args, String option) {
        if (args.isEmpty()) throw new IllegalArgumentException("Missing value after " + option);
        return args.removeFirst();
    }

    private static int parseInt(String value, String option, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid number for " + option + ": " + value);
        }
    }

    public enum Command { HELP, VERSION, SETUP_TOOLS, RECOVER }

    public record Parsed(Command command, RecoveryOptions recoveryOptions, Path toolsDirectory, boolean setupForce) {
        static Parsed help() { return new Parsed(Command.HELP, null, null, false); }
        static Parsed version() { return new Parsed(Command.VERSION, null, null, false); }
        static Parsed setup(Path tools, boolean force) { return new Parsed(Command.SETUP_TOOLS, null, tools, force); }
        static Parsed recover(RecoveryOptions options) { return new Parsed(Command.RECOVER, options, null, false); }
    }
}
