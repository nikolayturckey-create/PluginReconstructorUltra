package dev.nik.reconstructor.tools;

import dev.nik.reconstructor.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Downloads and validates the external decompiler engines used by the application. */
public final class ToolInstaller {
    public static final String VINEFLOWER_VERSION = "1.12.0";
    public static final String CFR_VERSION = "0.152";
    public static final String PROCYON_VERSION = "0.6.0";

    public static final List<String> VINEFLOWER_FILE_NAMES = List.of(
            "vineflower.jar", "vineflower-" + VINEFLOWER_VERSION + ".jar"
    );
    public static final List<String> CFR_FILE_NAMES = List.of(
            "cfr.jar", "cfr-" + CFR_VERSION + ".jar"
    );
    public static final List<String> PROCYON_FILE_NAMES = List.of(
            "procyon.jar", "procyon-decompiler-" + PROCYON_VERSION + ".jar"
    );

    private static final Tool VINEFLOWER = new Tool(
            "Vineflower",
            VINEFLOWER_VERSION,
            URI.create("https://repo.maven.apache.org/maven2/org/vineflower/vineflower/" + VINEFLOWER_VERSION
                    + "/vineflower-" + VINEFLOWER_VERSION + ".jar"),
            "vineflower-" + VINEFLOWER_VERSION + ".jar",
            "org/jetbrains/java/decompiler/main/decompiler/ConsoleDecompiler.class",
            1_000_000
    );
    private static final Tool CFR = new Tool(
            "CFR",
            CFR_VERSION,
            URI.create("https://repo.maven.apache.org/maven2/org/benf/cfr/" + CFR_VERSION
                    + "/cfr-" + CFR_VERSION + ".jar"),
            "cfr-" + CFR_VERSION + ".jar",
            "org/benf/cfr/reader/Main.class",
            500_000
    );
    private static final Tool PROCYON = new Tool(
            "Procyon",
            PROCYON_VERSION,
            URI.create("https://github.com/mstrobel/procyon/releases/download/v" + PROCYON_VERSION
                    + "/procyon-decompiler-" + PROCYON_VERSION + ".jar"),
            "procyon-decompiler-" + PROCYON_VERSION + ".jar",
            "com/strobel/decompiler/DecompilerDriver.class",
            500_000
    );

    private static final List<Tool> TOOLS = List.of(VINEFLOWER, CFR, PROCYON);

    public static int expectedToolCount() {
        return TOOLS.size();
    }

    public Installation install(Path directory, boolean overwrite) {
        List<ToolResult> results = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(25))
                    .build();
            for (Tool tool : TOOLS) {
                ToolResult result = installOne(client, directory, tool, overwrite);
                results.add(result);
                messages.add(tool.name() + ": " + result.message());
            }
        } catch (Exception error) {
            messages.add("Tool setup failed: " + safeMessage(error));
        }
        return new Installation(List.copyOf(results), List.copyOf(messages));
    }

    private static ToolResult installOne(HttpClient client, Path directory, Tool tool, boolean overwrite)
            throws IOException, InterruptedException {
        Path target = directory.resolve(tool.fileName());
        if (Files.isRegularFile(target) && !overwrite) {
            try {
                validateJar(target, tool);
                return new ToolResult(tool.name(), tool.version(), target, Hashing.sha256(target), false,
                        "already installed at " + target.toAbsolutePath());
            } catch (IOException invalidExisting) {
                // One-click mode should repair interrupted/corrupt downloads automatically.
                Files.deleteIfExists(target);
            }
        }

        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temporary);
        HttpRequest request = HttpRequest.newBuilder(tool.uri())
                .timeout(Duration.ofMinutes(4))
                .header("User-Agent", "PluginReconstructorUltra/0.2")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            return new ToolResult(tool.name(), tool.version(), target, null, false,
                    "download failed with HTTP " + response.statusCode());
        }
        long copied;
        try (InputStream body = response.body()) {
            copied = Files.copy(body, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        if (copied < tool.minimumBytes()) {
            Files.deleteIfExists(temporary);
            return new ToolResult(tool.name(), tool.version(), target, null, false,
                    "downloaded file is unexpectedly small (" + copied + " bytes)");
        }
        try {
            validateJar(temporary, tool);
            moveIntoPlace(temporary, target);
        } catch (Exception validation) {
            Files.deleteIfExists(temporary);
            return new ToolResult(tool.name(), tool.version(), target, null, false,
                    "downloaded JAR failed validation: " + safeMessage(validation));
        }
        String sha = Hashing.sha256(target);
        return new ToolResult(tool.name(), tool.version(), target, sha, true,
                "installed at " + target.toAbsolutePath() + " (SHA-256 " + sha + ")");
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateJar(Path path, Tool tool) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("not a file");
        if (Files.size(path) < tool.minimumBytes()) throw new IOException("file is too small");
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getJarEntry(tool.requiredEntry()) == null) {
                throw new IOException("required class is missing: " + tool.requiredEntry());
            }
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Tool(
            String name,
            String version,
            URI uri,
            String fileName,
            String requiredEntry,
            long minimumBytes
    ) {}

    public record ToolResult(
            String name,
            String version,
            Path path,
            String sha256,
            boolean downloaded,
            String message
    ) {}

    public record Installation(List<ToolResult> tools, List<String> messages) {
        public boolean complete() {
            return tools.size() == expectedToolCount() && tools.stream().allMatch(tool -> tool.sha256() != null);
        }
    }
}
