package dev.nik.reconstructor.classfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DescriptorUtil {
    private DescriptorUtil() {}

    public static Set<String> collectClassNames(String descriptor) {
        Set<String> names = new LinkedHashSet<>();
        if (descriptor == null) {
            return names;
        }
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i + 1);
                if (end < 0) {
                    break;
                }
                String name = descriptor.substring(i + 1, end);
                int generic = name.indexOf('<');
                if (generic >= 0) {
                    name = name.substring(0, generic);
                }
                if (!name.isBlank()) {
                    names.add(name);
                }
                i = end;
            }
        }
        return names;
    }

    public static MethodDescriptor parseMethod(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return new MethodDescriptor(List.of(), "java.lang.Object");
        }
        List<String> parameters = new ArrayList<>();
        int[] cursor = {1};
        while (cursor[0] < descriptor.length() && descriptor.charAt(cursor[0]) != ')') {
            parameters.add(parseType(descriptor, cursor));
        }
        if (cursor[0] < descriptor.length() && descriptor.charAt(cursor[0]) == ')') {
            cursor[0]++;
        }
        String returnType = cursor[0] < descriptor.length() ? parseType(descriptor, cursor) : "void";
        return new MethodDescriptor(List.copyOf(parameters), returnType);
    }

    public static String parseField(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return "java.lang.Object";
        }
        return parseType(descriptor, new int[]{0});
    }

    private static String parseType(String descriptor, int[] cursor) {
        int arrays = 0;
        while (cursor[0] < descriptor.length() && descriptor.charAt(cursor[0]) == '[') {
            arrays++;
            cursor[0]++;
        }
        if (cursor[0] >= descriptor.length()) {
            return appendArrays("java.lang.Object", arrays);
        }
        char type = descriptor.charAt(cursor[0]++);
        String base = switch (type) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> {
                int end = descriptor.indexOf(';', cursor[0]);
                if (end < 0) {
                    cursor[0] = descriptor.length();
                    yield "java.lang.Object";
                }
                String name = descriptor.substring(cursor[0], end).replace('/', '.');
                cursor[0] = end + 1;
                yield name;
            }
            default -> "java.lang.Object";
        };
        return appendArrays(base, arrays);
    }

    private static String appendArrays(String base, int arrays) {
        return base + "[]".repeat(Math.max(0, arrays));
    }

    public record MethodDescriptor(List<String> parameterTypes, String returnType) {}
}
