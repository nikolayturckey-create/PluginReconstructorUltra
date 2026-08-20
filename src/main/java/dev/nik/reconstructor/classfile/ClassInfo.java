package dev.nik.reconstructor.classfile;

import java.util.List;
import java.util.Set;

public record ClassInfo(
        String entryName,
        String internalName,
        String superName,
        List<String> interfaces,
        int access,
        int minorVersion,
        int majorVersion,
        String sourceFile,
        String signature,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        Set<String> referencedClasses,
        List<String> stringConstants,
        int bytecodeSize,
        int branchCount,
        int switchCount,
        int invokeDynamicCount,
        int xorCount,
        int exceptionHandlers,
        int nativeMethodCount,
        boolean kotlinMetadata,
        boolean debugInfo,
        List<String> warnings
) {
    public static final int ACC_PUBLIC = 0x0001;
    public static final int ACC_PRIVATE = 0x0002;
    public static final int ACC_PROTECTED = 0x0004;
    public static final int ACC_STATIC = 0x0008;
    public static final int ACC_FINAL = 0x0010;
    public static final int ACC_SYNCHRONIZED = 0x0020;
    public static final int ACC_VOLATILE = 0x0040;
    public static final int ACC_TRANSIENT = 0x0080;
    public static final int ACC_NATIVE = 0x0100;
    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;
    public static final int ACC_STRICT = 0x0800;
    public static final int ACC_SYNTHETIC = 0x1000;
    public static final int ACC_ANNOTATION = 0x2000;
    public static final int ACC_ENUM = 0x4000;
    public static final int ACC_MODULE = 0x8000;
    public static final int ACC_RECORD = 0x10000;

    public String packageName() {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    }

    public String simpleName() {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    public String dottedName() {
        return internalName.replace('/', '.');
    }

    public boolean isInterface() {
        return (access & ACC_INTERFACE) != 0;
    }

    public boolean isAnnotation() {
        return (access & ACC_ANNOTATION) != 0;
    }

    public boolean isEnum() {
        return (access & ACC_ENUM) != 0;
    }

    public boolean isRecord() {
        return (access & ACC_RECORD) != 0;
    }

    public String javaVersion() {
        if (majorVersion <= 0) {
            return "unknown";
        }
        int release = majorVersion - 44;
        if (majorVersion <= 48) {
            return "1." + release;
        }
        return Integer.toString(release);
    }

    public record FieldInfo(
            String name,
            String descriptor,
            String signature,
            String constantValue,
            int access
    ) {}

    public record MethodInfo(
            String name,
            String descriptor,
            String signature,
            List<String> exceptions,
            List<String> parameterNames,
            List<String> localVariables,
            int access,
            int maxStack,
            int maxLocals,
            int codeLength,
            int branchCount,
            int switchCount,
            int invokeDynamicCount,
            int xorCount,
            int exceptionHandlers,
            boolean debugInfo
    ) {
        public boolean isNative() {
            return (access & ACC_NATIVE) != 0;
        }

        public boolean isAbstract() {
            return (access & ACC_ABSTRACT) != 0;
        }
    }
}
