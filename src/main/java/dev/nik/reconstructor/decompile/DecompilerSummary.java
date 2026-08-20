package dev.nik.reconstructor.decompile;

import java.util.List;

public record DecompilerSummary(
        List<DecompilerResult> engines,
        SourceSelectionReport selection,
        SourceCoverageReport coverage,
        List<String> warnings
) {}
