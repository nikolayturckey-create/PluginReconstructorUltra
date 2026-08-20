package dev.nik.reconstructor.decompile;

import java.util.List;

public record SourceSelectionReport(
        int selectedFiles,
        int javaFiles,
        int kotlinFiles,
        List<SelectedSource> files,
        List<String> warnings
) {
    public record SelectedSource(
            String outputPath,
            String selectedEngine,
            double selectedScore,
            String sha256,
            List<Alternative> alternatives
    ) {}

    public record Alternative(String engine, String sourcePath, double score) {}
}
