package dev.nik.reconstructor.jar;

import dev.nik.reconstructor.classfile.ClassInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PluginDescriptorParser {
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    private static final Pattern JSON_ARRAY = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED_ITEM = Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"");

    public PluginMetadata parse(Map<String, String> descriptors, List<ClassInfo> classes) {
        List<String> warnings = new ArrayList<>();
        String selected = selectDescriptor(descriptors);
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        List<String> commands = new ArrayList<>();
        List<String> permissions = new ArrayList<>();

        if (selected != null) {
            String text = descriptors.get(selected);
            if (selected.endsWith(".json")) {
                parseJson(text, scalars, lists);
            } else {
                parseYaml(text, scalars, lists, commands, permissions, warnings);
            }
        } else {
            warnings.add("No plugin.yml, paper-plugin.yml, bungee.yml or velocity-plugin.json was found.");
        }

        String name = firstNonBlank(scalars.get("name"), scalars.get("id"), "UnknownPlugin");
        String version = firstNonBlank(scalars.get("version"), "unknown");
        String main = firstNonBlank(scalars.get("main"), inferMainClass(classes));
        String apiVersion = firstNonBlank(scalars.get("api-version"), scalars.get("apiVersion"));
        boolean folia = Boolean.parseBoolean(firstNonBlank(scalars.get("folia-supported"), "false"));

        List<String> authors = merge(lists.get("authors"), lists.get("author"), scalarAsList(scalars.get("author")));
        List<String> dependencies = merge(lists.get("depend"), lists.get("dependencies"));
        List<String> softDependencies = merge(lists.get("softdepend"), lists.get("soft-dependencies"));
        List<String> libraries = merge(lists.get("libraries"));
        if (commands.isEmpty()) commands.addAll(nullToEmpty(lists.get("commands")));
        if (permissions.isEmpty()) permissions.addAll(nullToEmpty(lists.get("permissions")));

        String platform = detectPlatform(selected, scalars, classes, folia);
        return new PluginMetadata(
                selected,
                name,
                version,
                main,
                apiVersion,
                platform,
                folia,
                List.copyOf(new LinkedHashSet<>(authors)),
                List.copyOf(new LinkedHashSet<>(commands)),
                List.copyOf(new LinkedHashSet<>(permissions)),
                List.copyOf(new LinkedHashSet<>(dependencies)),
                List.copyOf(new LinkedHashSet<>(softDependencies)),
                List.copyOf(new LinkedHashSet<>(libraries)),
                Map.copyOf(scalars),
                List.copyOf(warnings)
        );
    }

    private static String selectDescriptor(Map<String, String> descriptors) {
        for (String preferred : List.of("paper-plugin.yml", "plugin.yml", "velocity-plugin.json", "bungee.yml")) {
            for (String key : descriptors.keySet()) {
                if (key.equalsIgnoreCase(preferred) || key.toLowerCase(Locale.ROOT).endsWith("/" + preferred)) {
                    return key;
                }
            }
        }
        return null;
    }

    private static void parseJson(String text, Map<String, String> scalars, Map<String, List<String>> lists) {
        Matcher strings = JSON_STRING.matcher(text);
        while (strings.find()) {
            scalars.put(strings.group(1), unescapeJson(strings.group(2)));
        }
        Matcher arrays = JSON_ARRAY.matcher(text);
        while (arrays.find()) {
            List<String> values = new ArrayList<>();
            Matcher items = QUOTED_ITEM.matcher(arrays.group(2));
            while (items.find()) values.add(unescapeJson(items.group(1)));
            lists.put(arrays.group(1), values);
        }
    }

    private static void parseYaml(
            String text,
            Map<String, String> scalars,
            Map<String, List<String>> lists,
            List<String> commands,
            List<String> permissions,
            List<String> warnings
    ) {
        String currentTopLevel = null;
        Integer sectionItemIndent = null;
        int lineNumber = 0;
        for (String originalLine : text.replace("\r", "").split("\n", -1)) {
            lineNumber++;
            String line = stripYamlComment(originalLine);
            if (line.isBlank()) continue;
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (indent == 0) {
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    warnings.add("Ignored malformed descriptor line " + lineNumber + ": " + trimmed);
                    currentTopLevel = null;
                    sectionItemIndent = null;
                    continue;
                }
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                currentTopLevel = key;
                sectionItemIndent = null;
                if (!value.isEmpty()) {
                    String normalized = unquote(value);
                    scalars.put(key, normalized);
                    List<String> inline = parseInlineList(value);
                    if (inline != null) lists.put(key, inline);
                } else {
                    lists.computeIfAbsent(key, ignored -> new ArrayList<>());
                }
                continue;
            }
            if (currentTopLevel == null) continue;
            if (trimmed.startsWith("- ")) {
                lists.computeIfAbsent(currentTopLevel, ignored -> new ArrayList<>())
                        .add(unquote(trimmed.substring(2).trim()));
            } else if ("commands".equals(currentTopLevel) || "permissions".equals(currentTopLevel)) {
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    if (sectionItemIndent == null) sectionItemIndent = indent;
                    if (indent == sectionItemIndent) {
                        String child = unquote(trimmed.substring(0, colon).trim());
                        if ("commands".equals(currentTopLevel)) commands.add(child);
                        else permissions.add(child);
                    }
                }
            }
        }
    }

    private static String detectPlatform(String descriptor, Map<String, String> scalars, List<ClassInfo> classes, boolean folia) {
        if (descriptor != null && descriptor.toLowerCase(Locale.ROOT).endsWith("velocity-plugin.json")) return "Velocity";
        boolean velocity = hasReference(classes, "com/velocitypowered/api/");
        boolean bungee = hasReference(classes, "net/md_5/bungee/api/")
                || classes.stream().anyMatch(c -> "net/md_5/bungee/api/plugin/Plugin".equals(c.superName()));
        boolean paper = hasReference(classes, "io/papermc/paper/") || hasReference(classes, "io/papermc/paperweight/");
        boolean bukkit = hasReference(classes, "org/bukkit/")
                || classes.stream().anyMatch(c -> "org/bukkit/plugin/java/JavaPlugin".equals(c.superName()));
        if (velocity) return "Velocity";
        if (bungee && !bukkit) return "BungeeCord";
        if (folia) return "Paper/Folia";
        if (descriptor != null && descriptor.toLowerCase(Locale.ROOT).endsWith("paper-plugin.yml")) return "Paper";
        if (paper) return "Paper";
        if (bukkit) return "Bukkit/Spigot/Paper";
        if ("true".equalsIgnoreCase(scalars.get("folia-supported"))) return "Paper/Folia";
        return "Unknown JVM plugin";
    }

    private static boolean hasReference(List<ClassInfo> classes, String prefix) {
        return classes.stream().anyMatch(info -> info.referencedClasses().stream().anyMatch(ref -> ref.startsWith(prefix)));
    }

    private static String inferMainClass(List<ClassInfo> classes) {
        for (ClassInfo info : classes) {
            if ("org/bukkit/plugin/java/JavaPlugin".equals(info.superName())
                    || "net/md_5/bungee/api/plugin/Plugin".equals(info.superName())) {
                return info.dottedName();
            }
        }
        return null;
    }

    @SafeVarargs
    private static List<String> merge(List<String>... sources) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (List<String> source : sources) {
            if (source == null) continue;
            for (String value : source) if (value != null && !value.isBlank()) result.add(value.trim());
        }
        return new ArrayList<>(result);
    }

    private static List<String> scalarAsList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> inline = parseInlineList(value);
        return inline == null ? List.of(unquote(value)) : inline;
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> parseInlineList(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null;
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) return new ArrayList<>();
        List<String> values = new ArrayList<>();
        boolean single = false;
        boolean doub = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'' && !doub) single = !single;
            else if (ch == '"' && !single) doub = !doub;
            if (ch == ',' && !single && !doub) {
                values.add(unquote(current.toString().trim()));
                current.setLength(0);
            } else current.append(ch);
        }
        if (!current.isEmpty()) values.add(unquote(current.toString().trim()));
        return values;
    }

    private static String stripYamlComment(String line) {
        boolean single = false;
        boolean doub = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !doub) single = !single;
            else if (ch == '"' && !single) doub = !doub;
            else if (ch == '#' && !single && !doub && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length()) {
            char ch = value.charAt(count);
            if (ch == ' ') count++;
            else if (ch == '\t') count += 2;
            else break;
        }
        return count;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }
}
