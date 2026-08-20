package dev.nik.reconstructor.classfile;

import java.util.ArrayList;
import java.util.List;

public final class AccessFlags {
    private AccessFlags() {}

    public static String classFlags(int access) {
        List<String> flags = visibility(access);
        if ((access & ClassInfo.ACC_STATIC) != 0) flags.add("static");
        if ((access & ClassInfo.ACC_FINAL) != 0) flags.add("final");
        if ((access & ClassInfo.ACC_ABSTRACT) != 0) flags.add("abstract");
        if ((access & ClassInfo.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & ClassInfo.ACC_INTERFACE) != 0) flags.add("interface");
        if ((access & ClassInfo.ACC_ANNOTATION) != 0) flags.add("annotation");
        if ((access & ClassInfo.ACC_ENUM) != 0) flags.add("enum");
        if ((access & ClassInfo.ACC_RECORD) != 0) flags.add("record");
        if ((access & ClassInfo.ACC_MODULE) != 0) flags.add("module");
        return String.join(" ", flags);
    }

    public static String fieldFlags(int access) {
        List<String> flags = visibility(access);
        if ((access & ClassInfo.ACC_STATIC) != 0) flags.add("static");
        if ((access & ClassInfo.ACC_FINAL) != 0) flags.add("final");
        if ((access & ClassInfo.ACC_VOLATILE) != 0) flags.add("volatile");
        if ((access & ClassInfo.ACC_TRANSIENT) != 0) flags.add("transient");
        if ((access & ClassInfo.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        if ((access & ClassInfo.ACC_ENUM) != 0) flags.add("enum");
        return String.join(" ", flags);
    }

    public static String methodFlags(int access) {
        List<String> flags = visibility(access);
        if ((access & ClassInfo.ACC_STATIC) != 0) flags.add("static");
        if ((access & ClassInfo.ACC_FINAL) != 0) flags.add("final");
        if ((access & ClassInfo.ACC_SYNCHRONIZED) != 0) flags.add("synchronized");
        if ((access & ClassInfo.ACC_NATIVE) != 0) flags.add("native");
        if ((access & ClassInfo.ACC_ABSTRACT) != 0) flags.add("abstract");
        if ((access & ClassInfo.ACC_STRICT) != 0) flags.add("strictfp");
        if ((access & ClassInfo.ACC_SYNTHETIC) != 0) flags.add("synthetic");
        return String.join(" ", flags);
    }

    private static List<String> visibility(int access) {
        List<String> flags = new ArrayList<>();
        if ((access & ClassInfo.ACC_PUBLIC) != 0) flags.add("public");
        if ((access & ClassInfo.ACC_PRIVATE) != 0) flags.add("private");
        if ((access & ClassInfo.ACC_PROTECTED) != 0) flags.add("protected");
        return flags;
    }
}
