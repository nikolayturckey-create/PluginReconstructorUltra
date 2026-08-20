package dev.nik.reconstructor.classfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PseudoSourceGenerator {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
            "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
            "implements","import","instanceof","int","interface","long","native","new","package","private",
            "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
            "throw","throws","transient","try","void","volatile","while","record","sealed","permits","var",
            "yield","non-sealed","true","false","null"
    );

    private PseudoSourceGenerator() {}

    static String generate(ClassInfo info) {
        StringBuilder out = new StringBuilder(4096);
        out.append("/*\n")
                .append(" * GENERATED PSEUDO-SOURCE. This is not claimed to be the author's original source.\n")
                .append(" * Every method body points to the corresponding bytecode dump.\n")
                .append(" * Original class: ").append(info.dottedName()).append("\n")
                .append(" */\n");
        if (!info.packageName().isBlank()) {
            out.append("package ").append(sanitizeQualified(info.packageName())).append(";\n\n");
        }

        String originalSimple = info.simpleName();
        String simple = sanitizeIdentifier(originalSimple);
        if (!simple.equals(originalSimple)) {
            out.append("// Original binary name segment: ").append(escapeComment(originalSimple)).append("\n");
        }

        appendClassModifiers(out, info.access());
        String kind;
        if (info.isAnnotation()) kind = "@interface";
        else if (info.isInterface()) kind = "interface";
        else if (info.isEnum()) kind = "enum";
        else if (info.isRecord()) kind = "record";
        else kind = "class";
        out.append(kind).append(' ').append(simple);

        if (info.isRecord()) {
            out.append("(/* record components were not reconstructed */)");
        }
        if (!info.isInterface() && !info.isEnum() && !info.isRecord()
                && info.superName() != null && !"java/lang/Object".equals(info.superName())) {
            out.append(" extends ").append(sanitizeQualified(info.superName().replace('/', '.')));
        }
        if (!info.interfaces().isEmpty()) {
            out.append(info.isInterface() ? " extends " : " implements ");
            for (int i = 0; i < info.interfaces().size(); i++) {
                if (i > 0) out.append(", ");
                out.append(sanitizeQualified(info.interfaces().get(i).replace('/', '.')));
            }
        }
        out.append(" {\n");

        Set<String> usedFields = new HashSet<>();
        for (ClassInfo.FieldInfo field : info.fields()) {
            out.append("    ");
            appendMemberModifiers(out, field.access(), false);
            String type = sanitizeType(DescriptorUtil.parseField(field.descriptor()));
            String name = unique(sanitizeIdentifier(field.name()), usedFields);
            out.append(type).append(' ').append(name);
            if (field.constantValue() != null) {
                out.append(" /* constant: ").append(escapeComment(field.constantValue())).append(" */");
            }
            out.append(";\n");
        }
        if (!info.fields().isEmpty()) out.append('\n');

        Set<String> usedMethods = new HashSet<>();
        int methodOrdinal = 0;
        for (ClassInfo.MethodInfo method : info.methods()) {
            if ("<clinit>".equals(method.name())) {
                out.append("    static {\n")
                        .append("        throw new UnsupportedOperationException(\"Pseudo-source only; see bytecode dump.\");\n")
                        .append("    }\n\n");
                continue;
            }
            DescriptorUtil.MethodDescriptor descriptor = DescriptorUtil.parseMethod(method.descriptor());
            out.append("    ");
            appendMemberModifiers(out, method.access(), true);
            boolean constructor = "<init>".equals(method.name());
            String methodName;
            if (constructor) {
                methodName = simple;
            } else {
                methodName = unique(sanitizeIdentifier(method.name()), usedMethods);
                out.append(sanitizeType(descriptor.returnType())).append(' ');
            }
            out.append(methodName).append('(');
            List<String> parameterNames = chooseParameterNames(method, descriptor.parameterTypes().size());
            for (int i = 0; i < descriptor.parameterTypes().size(); i++) {
                if (i > 0) out.append(", ");
                out.append(sanitizeType(descriptor.parameterTypes().get(i))).append(' ')
                        .append(sanitizeIdentifier(parameterNames.get(i)));
            }
            out.append(')');
            if (!method.exceptions().isEmpty()) {
                out.append(" throws ");
                for (int i = 0; i < method.exceptions().size(); i++) {
                    if (i > 0) out.append(", ");
                    out.append(sanitizeQualified(method.exceptions().get(i).replace('/', '.')));
                }
            }
            boolean noBody = method.isAbstract() || method.isNative()
                    || (info.isInterface() && (method.access() & ClassInfo.ACC_STATIC) == 0
                    && (method.access() & ClassInfo.ACC_PRIVATE) == 0);
            if (noBody) {
                out.append(";\n\n");
            } else {
                out.append(" {\n")
                        .append("        // Bytecode method #").append(methodOrdinal)
                        .append(", descriptor ").append(escapeComment(method.descriptor())).append("\n")
                        .append("        throw new UnsupportedOperationException(\"Pseudo-source only; see bytecode dump.\");\n")
                        .append("    }\n\n");
            }
            methodOrdinal++;
        }
        out.append("}\n");
        return out.toString();
    }

    private static List<String> chooseParameterNames(ClassInfo.MethodInfo method, int count) {
        List<String> names = new ArrayList<>(count);
        for (String name : method.parameterNames()) {
            if (name != null && !name.isBlank() && !"this".equals(name)) names.add(name);
            if (names.size() == count) break;
        }
        int i = 0;
        while (names.size() < count) {
            names.add("arg" + i++);
        }
        return names;
    }

    private static void appendClassModifiers(StringBuilder out, int access) {
        if ((access & ClassInfo.ACC_PUBLIC) != 0) out.append("public ");
        if ((access & ClassInfo.ACC_ABSTRACT) != 0 && (access & ClassInfo.ACC_INTERFACE) == 0) out.append("abstract ");
        if ((access & ClassInfo.ACC_FINAL) != 0 && (access & ClassInfo.ACC_ENUM) == 0) out.append("final ");
    }

    private static void appendMemberModifiers(StringBuilder out, int access, boolean method) {
        if ((access & ClassInfo.ACC_PUBLIC) != 0) out.append("public ");
        else if ((access & ClassInfo.ACC_PROTECTED) != 0) out.append("protected ");
        else if ((access & ClassInfo.ACC_PRIVATE) != 0) out.append("private ");
        if ((access & ClassInfo.ACC_STATIC) != 0) out.append("static ");
        if ((access & ClassInfo.ACC_FINAL) != 0) out.append("final ");
        if (!method) {
            if ((access & ClassInfo.ACC_VOLATILE) != 0) out.append("volatile ");
            if ((access & ClassInfo.ACC_TRANSIENT) != 0) out.append("transient ");
        } else {
            if ((access & ClassInfo.ACC_SYNCHRONIZED) != 0) out.append("synchronized ");
            if ((access & ClassInfo.ACC_NATIVE) != 0) out.append("native ");
            if ((access & ClassInfo.ACC_ABSTRACT) != 0) out.append("abstract ");
            if ((access & ClassInfo.ACC_STRICT) != 0) out.append("strictfp ");
        }
    }

    private static String unique(String candidate, Set<String> used) {
        String value = candidate;
        int suffix = 2;
        while (!used.add(value)) value = candidate + '_' + suffix++;
        return value;
    }

    private static String sanitizeType(String type) {
        int arrays = 0;
        while (type.endsWith("[]")) {
            arrays++;
            type = type.substring(0, type.length() - 2);
        }
        String base = switch (type) {
            case "void","boolean","byte","char","short","int","long","float","double" -> type;
            default -> sanitizeQualified(type);
        };
        return base + "[]".repeat(arrays);
    }

    private static String sanitizeQualified(String value) {
        String[] parts = value.split("\\.", -1);
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('.');
            out.append(sanitizeIdentifier(part));
        }
        return out.toString();
    }

    private static String sanitizeIdentifier(String value) {
        if (value == null || value.isEmpty()) return "recoveredSymbol";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            boolean valid = out.isEmpty() ? Character.isJavaIdentifierStart(cp) : Character.isJavaIdentifierPart(cp);
            if (valid) out.appendCodePoint(cp);
            else out.append(String.format(Locale.ROOT, "_u%04X", cp));
            i += Character.charCount(cp);
        }
        String result = out.toString();
        if (KEYWORDS.contains(result)) result += "_recovered";
        return result;
    }

    private static String escapeComment(String value) {
        if (value == null) return "";
        return value.replace("*/", "* /").replace("\r", "\\r").replace("\n", "\\n");
    }
}
