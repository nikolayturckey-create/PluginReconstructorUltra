package dev.nik.reconstructor.analysis;

import dev.nik.reconstructor.classfile.ClassInfo;
import dev.nik.reconstructor.jar.JarInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ObfuscationAnalyzer {
    private static final Set<String> COMMON_SHORT_METHODS = Set.of(
            "run", "get", "set", "add", "put", "map", "read", "write", "call", "test", "apply", "accept"
    );

    public ObfuscationReport analyze(JarInventory inventory) {
        List<ClassInfo> classes = inventory.classes();
        if (classes.isEmpty()) {
            return new ObfuscationReport(
                    "UNKNOWN", 0, 0, 0, 0, 0, 0, 0,
                    inventory.nativeLibraries() > 0,
                    List.of("No class was parsed successfully."),
                    List.of()
            );
        }

        int suspiciousClassNames = 0;
        int suspiciousPackageSegments = 0;
        int unusualIdentifiers = 0;
        int classNameSamples = 0;
        int shortMembers = 0;
        int memberSamples = 0;
        int debugMissing = 0;
        int highEntropyStrings = 0;
        int stringSamples = 0;
        int cryptoReferences = 0;
        int reflectionReferences = 0;
        int runtimeLoadingReferences = 0;
        int antiTamperReferences = 0;
        long bytecodeBytes = 0;
        long branches = 0;
        long switches = 0;
        long xor = 0;
        long invokeDynamic = 0;
        long handlers = 0;

        List<ObfuscationReport.SuspiciousClass> suspicious = new ArrayList<>();
        for (ClassInfo info : classes) {
            String simple = outerSimpleName(info.simpleName());
            classNameSamples++;
            if (isSuspiciousName(simple)) suspiciousClassNames++;
            if (containsUnusualIdentifierCharacters(simple)) unusualIdentifiers++;
            String packageName = info.packageName();
            if (!packageName.isBlank()) {
                for (String segment : packageName.split("\\.")) {
                    if (segment.length() <= 1) suspiciousPackageSegments++;
                }
            }
            if (!info.debugInfo()) debugMissing++;

            for (ClassInfo.FieldInfo field : info.fields()) {
                memberSamples++;
                if (isSuspiciousName(field.name())) shortMembers++;
                if (containsUnusualIdentifierCharacters(field.name())) unusualIdentifiers++;
            }
            for (ClassInfo.MethodInfo method : info.methods()) {
                if (method.name().startsWith("<")) continue;
                memberSamples++;
                if (isSuspiciousMember(method.name())) shortMembers++;
                if (containsUnusualIdentifierCharacters(method.name())) unusualIdentifiers++;
            }

            for (String value : info.stringConstants()) {
                if (value.length() < 8) continue;
                stringSamples++;
                if (value.length() >= 20 && entropy(value) >= 4.35) highEntropyStrings++;
            }

            for (String ref : info.referencedClasses()) {
                if (startsWithAny(ref,
                        "java/lang/reflect/", "java/lang/invoke/MethodHandle", "java/lang/invoke/MethodHandles",
                        "sun/misc/Unsafe", "jdk/internal/misc/Unsafe")) reflectionReferences++;
                if (startsWithAny(ref,
                        "java/lang/ClassLoader", "java/net/URLClassLoader", "java/lang/instrument/",
                        "java/lang/invoke/MethodHandles$Lookup")) runtimeLoadingReferences++;
                if (startsWithAny(ref,
                        "javax/crypto/", "java/security/MessageDigest", "java/util/Base64",
                        "org/bouncycastle/")) cryptoReferences++;
                if (startsWithAny(ref,
                        "java/security/ProtectionDomain", "java/security/CodeSource", "java/util/jar/JarFile",
                        "java/util/zip/CRC32", "java/lang/Runtime", "java/lang/ProcessBuilder")) antiTamperReferences++;
            }

            bytecodeBytes += info.bytecodeSize();
            branches += info.branchCount();
            switches += info.switchCount();
            xor += info.xorCount();
            invokeDynamic += info.invokeDynamicCount();
            handlers += info.exceptionHandlers();

            double classScore = classSuspicionScore(info);
            if (classScore >= 0.22) {
                suspicious.add(new ObfuscationReport.SuspiciousClass(
                        info.dottedName(),
                        classScore,
                        info.bytecodeSize(),
                        info.branchCount(),
                        info.switchCount(),
                        info.invokeDynamicCount(),
                        info.xorCount(),
                        classReason(info)
                ));
            }
        }

        double classShortRatio = ratio(suspiciousClassNames, classNameSamples);
        double memberShortRatio = ratio(shortMembers, memberSamples);
        double packagePenalty = clamp(suspiciousPackageSegments / Math.max(1.0, classes.size() * 2.0));
        double unusualPenalty = clamp(unusualIdentifiers / Math.max(1.0, classNameSamples + memberSamples));
        double debugPenalty = ratio(debugMissing, classes.size());
        double nameScore = clamp(classShortRatio * 0.42 + memberShortRatio * 0.34
                + packagePenalty * 0.10 + unusualPenalty * 0.09 + debugPenalty * 0.05);

        double branchDensity = branches / Math.max(1.0, bytecodeBytes);
        double switchDensity = switches / Math.max(1.0, classes.size());
        double handlerDensity = handlers / Math.max(1.0, classes.size());
        double controlFlowScore = clamp(scale(branchDensity, 0.025, 0.16) * 0.58
                + scale(switchDensity, 0.05, 2.0) * 0.27
                + scale(handlerDensity, 0.3, 6.0) * 0.15);

        double entropyRatio = ratio(highEntropyStrings, stringSamples);
        double xorDensity = xor / Math.max(1.0, bytecodeBytes);
        double cryptoDensity = cryptoReferences / Math.max(1.0, classes.size());
        double stringScore = clamp(entropyRatio * 0.45
                + scale(xorDensity, 0.0005, 0.02) * 0.30
                + scale(cryptoDensity, 0.05, 1.0) * 0.25);

        double reflectionScore = clamp(scale(reflectionReferences / Math.max(1.0, classes.size()), 0.1, 3.0));
        double runtimeScore = clamp(scale(runtimeLoadingReferences / Math.max(1.0, classes.size()), 0.03, 1.3));
        double antiTamperScore = clamp(scale(antiTamperReferences / Math.max(1.0, classes.size()), 0.04, 1.4));

        double overall = clamp(
                nameScore * 0.34
                        + controlFlowScore * 0.25
                        + stringScore * 0.16
                        + reflectionScore * 0.08
                        + runtimeScore * 0.10
                        + antiTamperScore * 0.07
        );
        if (inventory.failedClasses() > 0) {
            overall = clamp(overall + Math.min(0.18, inventory.failedClasses() / Math.max(1.0, inventory.classEntries()) * 0.3));
        }
        if (inventory.nativeLibraries() > 0) overall = clamp(overall + 0.04);

        String level = level(overall);
        List<String> reasons = new ArrayList<>();
        addReason(reasons, classShortRatio >= 0.35,
                percent(classShortRatio) + " of class names are unusually short.");
        addReason(reasons, memberShortRatio >= 0.42,
                percent(memberShortRatio) + " of field/method names look minimized.");
        addReason(reasons, controlFlowScore >= 0.55,
                "Control-flow density is high: " + branches + " branches and " + switches + " switch instructions.");
        addReason(reasons, stringScore >= 0.45,
                "String-protection indicators were found: entropy ratio " + percent(entropyRatio) + ", XOR ops " + xor + ".");
        addReason(reasons, reflectionScore >= 0.4,
                "Heavy reflection/method-handle usage may hide call targets.");
        addReason(reasons, runtimeScore >= 0.3,
                "Dynamic class-loading indicators are present.");
        addReason(reasons, antiTamperScore >= 0.35,
                "Archive/self-integrity or process-control indicators are present; treat as a heuristic, not proof.");
        addReason(reasons, invokeDynamic > classes.size() * 3L,
                "Large invokedynamic count: " + invokeDynamic + ". This can be normal for Kotlin/lambdas.");
        addReason(reasons, inventory.failedClasses() > 0,
                inventory.failedClasses() + " class files could not be parsed and were preserved raw.");
        addReason(reasons, inventory.nativeLibraries() > 0,
                inventory.nativeLibraries() + " native libraries require a separate native-code workflow.");
        if (reasons.isEmpty()) reasons.add("No strong obfuscation signal was detected by static heuristics.");

        suspicious.sort(Comparator.comparingDouble(ObfuscationReport.SuspiciousClass::score).reversed());
        if (suspicious.size() > 50) suspicious = new ArrayList<>(suspicious.subList(0, 50));
        return new ObfuscationReport(
                level,
                overall,
                nameScore,
                controlFlowScore,
                stringScore,
                reflectionScore,
                runtimeScore,
                antiTamperScore,
                inventory.nativeLibraries() > 0,
                List.copyOf(reasons),
                List.copyOf(suspicious)
        );
    }

    private static double classSuspicionScore(ClassInfo info) {
        int memberCount = info.fields().size() + info.methods().size();
        int shortMembers = 0;
        for (ClassInfo.FieldInfo field : info.fields()) if (isSuspiciousName(field.name())) shortMembers++;
        for (ClassInfo.MethodInfo method : info.methods()) {
            if (!method.name().startsWith("<") && isSuspiciousMember(method.name())) shortMembers++;
        }
        double names = isSuspiciousName(outerSimpleName(info.simpleName())) ? 0.35 : 0;
        names += ratio(shortMembers, memberCount) * 0.25;
        double flow = scale(info.branchCount() / Math.max(1.0, info.bytecodeSize()), 0.03, 0.2) * 0.25;
        double string = scale(info.xorCount() / Math.max(1.0, info.bytecodeSize()), 0.001, 0.025) * 0.1;
        double switchPart = scale(info.switchCount(), 1, 12) * 0.05;
        return clamp(names + flow + string + switchPart);
    }

    private static String classReason(ClassInfo info) {
        List<String> parts = new ArrayList<>();
        if (isSuspiciousName(outerSimpleName(info.simpleName()))) parts.add("short class name");
        if (info.branchCount() > Math.max(10, info.bytecodeSize() / 12)) parts.add("dense branches");
        if (info.switchCount() > 2) parts.add("many switches");
        if (info.xorCount() > 2) parts.add("XOR-heavy bytecode");
        if (!info.debugInfo()) parts.add("debug metadata absent");
        return parts.isEmpty() ? "combined static indicators" : String.join(", ", parts);
    }

    private static boolean isSuspiciousMember(String name) {
        if (COMMON_SHORT_METHODS.contains(name)) return false;
        return isSuspiciousName(name);
    }

    private static boolean isSuspiciousName(String name) {
        if (name == null || name.isBlank()) return true;
        if (name.length() <= 2) return true;
        if (name.matches("[a-zA-Z]\\d?")) return true;
        return name.matches("[Il1O0]{3,}");
    }

    private static String outerSimpleName(String name) {
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    private static boolean containsUnusualIdentifierCharacters(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if (!(Character.isJavaIdentifierPart(cp) || cp == '$')) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static double entropy(String value) {
        if (value.isEmpty()) return 0;
        int[] counts = new int[256];
        for (int i = 0; i < value.length(); i++) counts[value.charAt(i) & 0xFF]++;
        double result = 0;
        for (int count : counts) {
            if (count == 0) continue;
            double p = count / (double) value.length();
            result -= p * (Math.log(p) / Math.log(2));
        }
        return result;
    }

    private static double scale(double value, double low, double high) {
        if (value <= low) return 0;
        if (value >= high) return 1;
        return (value - low) / (high - low);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : numerator / (double) denominator;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String level(double score) {
        if (score >= 0.78) return "VERY_HIGH";
        if (score >= 0.58) return "HIGH";
        if (score >= 0.36) return "MEDIUM";
        if (score >= 0.18) return "LOW";
        return "NONE_OR_MINIMAL";
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static void addReason(List<String> reasons, boolean condition, String text) {
        if (condition) reasons.add(text);
    }
}
