package dev.nik.reconstructor.jar;

import java.util.List;
import java.util.Map;

public record PluginMetadata(
        String descriptorName,
        String name,
        String version,
        String mainClass,
        String apiVersion,
        String platform,
        boolean foliaSupported,
        List<String> authors,
        List<String> commands,
        List<String> permissions,
        List<String> dependencies,
        List<String> softDependencies,
        List<String> libraries,
        Map<String, String> rawValues,
        List<String> warnings
) {
    public static PluginMetadata unknown() {
        return new PluginMetadata(
                null, "UnknownPlugin", "unknown", null, null, "Unknown", false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of()
        );
    }
}
