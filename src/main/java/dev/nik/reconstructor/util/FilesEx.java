package dev.nik.reconstructor.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

public final class FilesEx {
    private FilesEx() {}

    public static void recreateDirectory(Path directory, boolean force) throws IOException {
        if (Files.exists(directory)) {
            if (!force) {
                throw new IOException("Output already exists: " + directory + " (use --force to replace it)");
            }
            deleteRecursively(directory);
        }
        Files.createDirectories(directory);
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static Path safeResolve(Path root, String entryName) throws IOException {
        String normalizedName = entryName.replace('\\', '/');
        while (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        Path resolved = root.resolve(normalizedName).normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new IOException("Unsafe archive entry rejected: " + entryName);
        }
        return resolved;
    }

    public static void copy(InputStream input, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sanitizeFileName(String value) {
        String sanitized = value == null ? "unknown" : value.trim();
        sanitized = sanitized.replaceAll("[\\x00-\\x1F\\x7F<>:\"/\\\\|?*]+", "_");
        sanitized = sanitized.replaceAll("\\s+", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^[._]+|[._]+$", "");
        if (sanitized.isBlank()) {
            return "unknown";
        }
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }

    public static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
