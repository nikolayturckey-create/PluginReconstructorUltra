package dev.nik.reconstructor.analysis;

import dev.nik.reconstructor.jar.JarInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DependencyDetector {
    private static final List<Known> KNOWN = List.of(
            new Known("Paper API", "io/papermc/paper/", "io.papermc.paper:paper-api:<version>", "https://repo.papermc.io/repository/maven-public/", "Use the server/API version matching the plugin."),
            new Known("Bukkit API", "org/bukkit/", "io.papermc.paper:paper-api:<version>", "https://repo.papermc.io/repository/maven-public/", "Paper API normally supplies Bukkit symbols."),
            new Known("Velocity API", "com/velocitypowered/api/", "com.velocitypowered:velocity-api:<version>", "https://repo.papermc.io/repository/maven-public/", "Annotation processing may also be needed."),
            new Known("BungeeCord API", "net/md_5/bungee/api/", "net.md-5:bungeecord-api:<version>", "https://oss.sonatype.org/content/repositories/snapshots/", "Pick the proxy version used by the original build."),
            new Known("Adventure", "net/kyori/adventure/", "net.kyori:adventure-api:<version>", "mavenCentral()", "Often already exposed by Paper/Velocity."),
            new Known("ProtocolLib", "com/comphenix/protocol/", "com.comphenix.protocol:ProtocolLib:<version>", "https://repo.dmulloy2.net/repository/public/", "Usually compileOnly on a plugin project."),
            new Known("PlaceholderAPI", "me/clip/placeholderapi/", "me.clip:placeholderapi:<version>", "https://repo.extendedclip.com/content/repositories/placeholderapi/", "Usually compileOnly."),
            new Known("Vault API", "net/milkbowl/vault/", "com.github.MilkBowl:VaultAPI:<version>", "https://jitpack.io", "Usually compileOnly."),
            new Known("WorldEdit", "com/sk89q/worldedit/", "com.sk89q.worldedit:worldedit-bukkit:<version>", "https://maven.enginehub.org/repo/", "Use the edition matching the server."),
            new Known("WorldGuard", "com/sk89q/worldguard/", "com.sk89q.worldguard:worldguard-bukkit:<version>", "https://maven.enginehub.org/repo/", "Usually compileOnly."),
            new Known("LuckPerms API", "net/luckperms/api/", "net.luckperms:api:<version>", "mavenCentral()", "Usually compileOnly."),
            new Known("Kotlin stdlib", "kotlin/", "org.jetbrains.kotlin:kotlin-stdlib:<version>", "mavenCentral()", "May be bundled by the original plugin."),
            new Known("Kotlin coroutines", "kotlinx/coroutines/", "org.jetbrains.kotlinx:kotlinx-coroutines-core:<version>", "mavenCentral()", "Check whether it was shaded."),
            new Known("Gson", "com/google/gson/", "com.google.code.gson:gson:<version>", "mavenCentral()", "Could be server-provided or shaded."),
            new Known("Guava", "com/google/common/", "com.google.guava:guava:<version>", "mavenCentral()", "Could be server-provided or shaded."),
            new Known("SLF4J", "org/slf4j/", "org.slf4j:slf4j-api:<version>", "mavenCentral()", "Use compileOnly when supplied by the platform."),
            new Known("JetBrains annotations", "org/jetbrains/annotations/", "org.jetbrains:annotations:<version>", "mavenCentral()", "Compile-time annotations."),
            new Known("HikariCP", "com/zaxxer/hikari/", "com.zaxxer:HikariCP:<version>", "mavenCentral()", "Often shaded into plugins."),
            new Known("SQLite JDBC", "org/sqlite/", "org.xerial:sqlite-jdbc:<version>", "mavenCentral()", "Often runtime/shaded."),
            new Known("MySQL Connector/J", "com/mysql/cj/", "com.mysql:mysql-connector-j:<version>", "mavenCentral()", "Usually runtimeOnly."),
            new Known("MariaDB JDBC", "org/mariadb/jdbc/", "org.mariadb.jdbc:mariadb-java-client:<version>", "mavenCentral()", "Usually runtimeOnly."),
            new Known("PostgreSQL JDBC", "org/postgresql/", "org.postgresql:postgresql:<version>", "mavenCentral()", "Usually runtimeOnly."),
            new Known("SnakeYAML", "org/yaml/snakeyaml/", "org.yaml:snakeyaml:<version>", "mavenCentral()", "May already be provided by Bukkit/Paper."),
            new Known("Jackson", "com/fasterxml/jackson/", "com.fasterxml.jackson.core:jackson-databind:<version>", "mavenCentral()", "Confirm exact Jackson modules."),
            new Known("Jedis", "redis/clients/jedis/", "redis.clients:jedis:<version>", "mavenCentral()", "Usually runtime/shaded."),
            new Known("Lettuce", "io/lettuce/core/", "io.lettuce:lettuce-core:<version>", "mavenCentral()", "Usually runtime/shaded.")
    );

    public DependencyReport detect(JarInventory inventory) {
        Set<String> owned = inventory.ownedClasses();
        Set<String> external = inventory.referencedClasses();
        List<DependencyReport.DetectedDependency> dependencies = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();

        for (Known known : KNOWN) {
            int referenced = countPrefix(external, known.prefix());
            int bundled = countPrefix(owned, known.prefix());
            if (referenced == 0 && bundled == 0) continue;
            dependencies.add(new DependencyReport.DetectedDependency(
                    known.name(),
                    known.prefix(),
                    known.coordinate(),
                    known.repository(),
                    referenced,
                    bundled > 0,
                    bundled > 0 ? known.note() + " Classes with this prefix are present inside the JAR." : known.note()
            ));
            external.stream().filter(ref -> ref.startsWith(known.prefix())).forEach(consumed::add);
        }

        List<String> internalJdk = external.stream()
                .filter(DependencyDetector::isInternalJdk)
                .sorted()
                .limit(200)
                .toList();
        List<String> nms = external.stream()
                .filter(DependencyDetector::isNms)
                .sorted()
                .limit(300)
                .toList();

        Map<String, Integer> unresolvedCounts = new LinkedHashMap<>();
        external.stream()
                .filter(ref -> !consumed.contains(ref))
                .filter(ref -> !isStandardJdk(ref))
                .filter(ref -> !isInternalJdk(ref))
                .filter(ref -> !isNms(ref))
                .forEach(ref -> unresolvedCounts.merge(prefixOf(ref), 1, Integer::sum));
        Map<String, Integer> unresolved = unresolvedCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
                .limit(100)
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);

        dependencies.sort(Comparator.comparing(DependencyReport.DetectedDependency::name));
        return new DependencyReport(List.copyOf(dependencies), Map.copyOf(unresolved), internalJdk, nms);
    }

    private static int countPrefix(Set<String> values, String prefix) {
        int count = 0;
        for (String value : values) if (value.startsWith(prefix)) count++;
        return count;
    }

    private static String prefixOf(String internalName) {
        String[] parts = internalName.split("/");
        if (parts.length <= 1) return internalName;
        int count = Math.min(parts.length - 1, 3);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append('.');
            out.append(parts[i]);
        }
        return out.toString();
    }

    private static boolean isStandardJdk(String ref) {
        return ref.startsWith("java/") || ref.startsWith("javax/") || ref.startsWith("jdk/")
                || ref.startsWith("org/w3c/") || ref.startsWith("org/xml/");
    }

    private static boolean isInternalJdk(String ref) {
        return ref.startsWith("sun/") || ref.startsWith("com/sun/") || ref.startsWith("jdk/internal/");
    }

    private static boolean isNms(String ref) {
        return ref.startsWith("net/minecraft/") || ref.startsWith("org/bukkit/craftbukkit/");
    }

    private record Known(String name, String prefix, String coordinate, String repository, String note) {}
}
