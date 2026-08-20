package dev.nik.reconstructor.decompile;

import com.sun.source.util.JavacTask;
import dev.nik.reconstructor.util.FilesEx;
import dev.nik.reconstructor.util.Hashing;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SourceSelector {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\p{L}\\p{N}_.$]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(class|interface|enum|record|@interface)\\b");

    public SourceSelectionReport select(List<DecompilerResult> results, Path javaDirectory) throws IOException {
        FilesEx.deleteRecursively(javaDirectory);
        Files.createDirectories(javaDirectory);

        Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (DecompilerResult result : results) {
            if (!result.success() || !Files.isDirectory(result.outputDirectory())) continue;
            try (Stream<Path> stream = Files.walk(result.outputDirectory())) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".java")) continue;
                    String text;
                    try {
                        text = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (Exception encodingProblem) {
                        byte[] bytes = Files.readAllBytes(file);
                        text = new String(bytes, StandardCharsets.ISO_8859_1);
                    }
                    String target = targetPath(file, text);
                    double score = score(text, result.engine(), target);
                    candidates.computeIfAbsent(target, ignored -> new ArrayList<>())
                            .add(new Candidate(result.engine(), file, text, score));
                }
            }
        }

        List<SourceSelectionReport.SelectedSource> selected = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> entry : candidates.entrySet()) {
            List<Candidate> options = entry.getValue();
            options.sort(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparing(Candidate::engine)
                    .thenComparing(candidate -> candidate.path().toString()));
            Candidate best = options.getFirst();
            Path target;
            try {
                target = FilesEx.safeResolve(javaDirectory, entry.getKey());
            } catch (IOException unsafe) {
                warnings.add("Skipped unsafe source path " + entry.getKey() + ": " + unsafe.getMessage());
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(best.path(), target, StandardCopyOption.REPLACE_EXISTING);
            String sha = Hashing.sha256(target);
            List<SourceSelectionReport.Alternative> alternatives = options.stream()
                    .map(candidate -> new SourceSelectionReport.Alternative(
                            candidate.engine(), candidate.path().toString(), candidate.score()))
                    .toList();
            selected.add(new SourceSelectionReport.SelectedSource(
                    entry.getKey(), best.engine(), best.score(), sha, alternatives
            ));
        }
        selected.sort(Comparator.comparing(SourceSelectionReport.SelectedSource::outputPath));
        if (selected.isEmpty()) {
            warnings.add("No normal Java sources were selected. Check the decompiler logs and missing-class report.");
        }
        return new SourceSelectionReport(
                selected.size(), selected.size(), 0, List.copyOf(selected), List.copyOf(warnings)
        );
    }

    private static String targetPath(Path original, String text) {
        String fileName = original.getFileName().toString();
        Matcher matcher = PACKAGE.matcher(text);
        if (matcher.find()) {
            String packageName = matcher.group(1).replace('.', '/');
            return packageName + "/" + fileName;
        }
        return fileName;
    }

    private static double score(String text, String engine, String targetPath) {
        if (text == null || text.isBlank()) return -1000;
        double score = 10;
        int length = text.length();
        score += Math.min(18, Math.log10(Math.max(10, length)) * 4);
        if (PACKAGE.matcher(text).find()) score += 5;
        if (TYPE.matcher(text).find()) score += 8;
        if (balanced(text, '{', '}')) score += 8; else score -= 18;
        if (balanced(text, '(', ')')) score += 3; else score -= 8;
        score += syntaxScore(text, targetPath);

        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower,
                "couldn't be decompiled", "could not be decompiled", "decompilation failed",
                "illegalstateexception(\"decompilation", "error: failed to decompile",
                "this method could not be decompiled")) score -= 70;
        if (lower.contains("bytecode could not be decompiled")) score -= 50;
        if (text.contains("/* synthetic */")) score -= 1;
        if (lower.contains("goto label")) score -= 4;
        if (lower.contains("throw new illegalstateexception(\"an error occurred while decompiling")) score -= 40;

        String engineLower = engine.toLowerCase(Locale.ROOT);
        if (engineLower.equals("vineflower")) score += 4;
        else if (engineLower.equals("cfr")) score += 2.5;
        else if (engineLower.equals("procyon")) score += 1.5;
        if (engineLower.contains("aggressive")) score -= 0.5;
        if (engineLower.contains("fallback")) score -= 0.25;
        return score;
    }

    private static double syntaxScore(String text, String targetPath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return 0;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject source = new StringSource(targetPath, text);
        try {
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    null,
                    diagnostics,
                    List.of("-proc:none", "-Xlint:none"),
                    null,
                    List.of(source)
            );
            task.parse();
            long errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            return errors == 0 ? 18 : Math.max(-36, -12.0 * errors);
        } catch (Throwable ignored) {
            return -12;
        }
    }

    private static boolean balanced(String text, char open, char close) {
        int count = 0;
        boolean string = false;
        boolean character = false;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!string && !character) {
                if (ch == '/' && next == '/') {
                    lineComment = true;
                    i++;
                    continue;
                }
                if (ch == '/' && next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
            }
            if (escape) {
                escape = false;
                continue;
            }
            if ((string || character) && ch == '\\') {
                escape = true;
                continue;
            }
            if (!character && ch == '"') string = !string;
            else if (!string && ch == '\'') character = !character;
            else if (!string && !character) {
                if (ch == open) count++;
                else if (ch == close && --count < 0) return false;
            }
        }
        return count == 0;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private record Candidate(String engine, Path path, String text, double score) {}

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        private StringSource(String path, String source) {
            super(URI.create("string:///" + path.replace('\\', '/')), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
