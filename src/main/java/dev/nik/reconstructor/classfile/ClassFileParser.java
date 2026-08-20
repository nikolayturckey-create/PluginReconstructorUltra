package dev.nik.reconstructor.classfile;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ClassFileParser {
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int MAX_ATTRIBUTE_SIZE = 256 * 1024 * 1024;

    public ParsedClass parse(String entryName, byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != CLASS_MAGIC) {
                throw new IOException("Not a JVM class file: " + entryName);
            }
            int minor = input.readUnsignedShort();
            int major = input.readUnsignedShort();
            ConstantPool pool = ConstantPool.read(input);
            int access = input.readUnsignedShort();
            String internalName = required(pool.className(input.readUnsignedShort()), "this_class");
            int superIndex = input.readUnsignedShort();
            String superName = superIndex == 0 ? null : pool.className(superIndex);

            int interfaceCount = input.readUnsignedShort();
            List<String> interfaces = new ArrayList<>(interfaceCount);
            for (int i = 0; i < interfaceCount; i++) {
                interfaces.add(required(pool.className(input.readUnsignedShort()), "interface"));
            }

            Set<String> references = new LinkedHashSet<>(pool.referencedClasses());
            List<String> strings = new ArrayList<>(pool.stringConstants());
            List<String> warnings = new ArrayList<>();
            List<ClassInfo.FieldInfo> fields = parseFields(input, pool, references, warnings);
            List<ParsedMethod> parsedMethods = parseMethods(input, pool, references, warnings);

            String sourceFile = null;
            String signature = null;
            boolean classDebug = false;
            int classAttributeCount = input.readUnsignedShort();
            for (int i = 0; i < classAttributeCount; i++) {
                Attribute attribute = readAttribute(input, pool);
                try (DataInputStream data = attribute.stream()) {
                    switch (attribute.name()) {
                        case "SourceFile" -> {
                            if (attribute.bytes().length >= 2) sourceFile = pool.utf8(data.readUnsignedShort());
                        }
                        case "Signature" -> {
                            if (attribute.bytes().length >= 2) signature = pool.utf8(data.readUnsignedShort());
                        }
                        case "SourceDebugExtension" -> classDebug = true;
                        default -> {
                            // Preserved in the original class file; no destructive transformation is done here.
                        }
                    }
                } catch (RuntimeException | IOException malformed) {
                    warnings.add("Could not parse class attribute " + attribute.name() + ": " + malformed.getMessage());
                }
            }

            references.addAll(DescriptorUtil.collectClassNames(signature));
            references.remove(internalName);

            int bytecodeSize = 0;
            int branches = 0;
            int switches = 0;
            int invokeDynamic = 0;
            int xor = 0;
            int handlers = 0;
            int nativeMethods = 0;
            boolean methodDebug = false;
            List<ClassInfo.MethodInfo> methods = new ArrayList<>(parsedMethods.size());
            for (ParsedMethod parsed : parsedMethods) {
                ClassInfo.MethodInfo method = parsed.info();
                methods.add(method);
                bytecodeSize += method.codeLength();
                branches += method.branchCount();
                switches += method.switchCount();
                invokeDynamic += method.invokeDynamicCount();
                xor += method.xorCount();
                handlers += method.exceptionHandlers();
                if (method.isNative()) nativeMethods++;
                methodDebug |= method.debugInfo();
            }

            boolean kotlinMetadata = pool.containsUtf8("Lkotlin/Metadata;") || references.contains("kotlin/Metadata");
            ClassInfo info = new ClassInfo(
                    entryName,
                    internalName,
                    superName,
                    List.copyOf(interfaces),
                    access,
                    minor,
                    major,
                    sourceFile,
                    signature,
                    List.copyOf(fields),
                    List.copyOf(methods),
                    Set.copyOf(references),
                    List.copyOf(strings),
                    bytecodeSize,
                    branches,
                    switches,
                    invokeDynamic,
                    xor,
                    handlers,
                    nativeMethods,
                    kotlinMetadata,
                    classDebug || methodDebug || sourceFile != null,
                    List.copyOf(warnings)
            );
            String bytecode = renderBytecode(info, parsedMethods, bytes.length);
            String pseudoSource = PseudoSourceGenerator.generate(info);
            return new ParsedClass(info, bytecode, pseudoSource);
        } catch (EOFException truncated) {
            throw new IOException("Truncated class file: " + entryName, truncated);
        }
    }

    private static List<ClassInfo.FieldInfo> parseFields(
            DataInputStream input,
            ConstantPool pool,
            Set<String> references,
            List<String> warnings
    ) throws IOException {
        int count = input.readUnsignedShort();
        List<ClassInfo.FieldInfo> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int access = input.readUnsignedShort();
            String name = required(pool.utf8(input.readUnsignedShort()), "field name");
            String descriptor = required(pool.utf8(input.readUnsignedShort()), "field descriptor");
            references.addAll(DescriptorUtil.collectClassNames(descriptor));
            String signature = null;
            String constantValue = null;
            int attributeCount = input.readUnsignedShort();
            for (int j = 0; j < attributeCount; j++) {
                Attribute attribute = readAttribute(input, pool);
                try (DataInputStream data = attribute.stream()) {
                    if ("Signature".equals(attribute.name()) && attribute.bytes().length >= 2) {
                        signature = pool.utf8(data.readUnsignedShort());
                        references.addAll(DescriptorUtil.collectClassNames(signature));
                    } else if ("ConstantValue".equals(attribute.name()) && attribute.bytes().length >= 2) {
                        constantValue = pool.describe(data.readUnsignedShort());
                    }
                } catch (RuntimeException | IOException malformed) {
                    warnings.add("Could not parse field attribute " + attribute.name() + " on " + name + ": " + malformed.getMessage());
                }
            }
            fields.add(new ClassInfo.FieldInfo(name, descriptor, signature, constantValue, access));
        }
        return fields;
    }

    private static List<ParsedMethod> parseMethods(
            DataInputStream input,
            ConstantPool pool,
            Set<String> references,
            List<String> warnings
    ) throws IOException {
        int count = input.readUnsignedShort();
        List<ParsedMethod> methods = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int access = input.readUnsignedShort();
            String name = required(pool.utf8(input.readUnsignedShort()), "method name");
            String descriptor = required(pool.utf8(input.readUnsignedShort()), "method descriptor");
            references.addAll(DescriptorUtil.collectClassNames(descriptor));

            String signature = null;
            List<String> exceptions = new ArrayList<>();
            List<String> methodParameters = new ArrayList<>();
            List<LocalVariable> locals = new ArrayList<>();
            int maxStack = 0;
            int maxLocals = 0;
            int codeLength = 0;
            int exceptionHandlers = 0;
            boolean debugInfo = false;
            Bytecode.Result disassembly = new Bytecode.Result("", 0, 0, 0, 0, List.of());

            int attributeCount = input.readUnsignedShort();
            for (int j = 0; j < attributeCount; j++) {
                Attribute attribute = readAttribute(input, pool);
                try (DataInputStream data = attribute.stream()) {
                    switch (attribute.name()) {
                        case "Signature" -> {
                            if (attribute.bytes().length >= 2) {
                                signature = pool.utf8(data.readUnsignedShort());
                                references.addAll(DescriptorUtil.collectClassNames(signature));
                            }
                        }
                        case "Exceptions" -> {
                            int exceptionCount = data.readUnsignedShort();
                            for (int k = 0; k < exceptionCount; k++) {
                                String exception = pool.className(data.readUnsignedShort());
                                if (exception != null) {
                                    exceptions.add(exception);
                                    references.add(exception);
                                }
                            }
                        }
                        case "MethodParameters" -> {
                            int parameterCount = data.readUnsignedByte();
                            for (int k = 0; k < parameterCount; k++) {
                                int nameIndex = data.readUnsignedShort();
                                data.readUnsignedShort();
                                methodParameters.add(nameIndex == 0 ? "arg" + k : pool.utf8(nameIndex));
                            }
                            if (!methodParameters.isEmpty()) debugInfo = true;
                        }
                        case "Code" -> {
                            CodeParse code = parseCode(data, pool, references, warnings, name + descriptor);
                            maxStack = code.maxStack();
                            maxLocals = code.maxLocals();
                            codeLength = code.codeLength();
                            exceptionHandlers = code.exceptionHandlers();
                            disassembly = code.disassembly();
                            locals.addAll(code.localVariables());
                            debugInfo |= code.debugInfo();
                        }
                        default -> {
                            // Unknown attributes are retained in the original class and skipped safely.
                        }
                    }
                } catch (RuntimeException | IOException malformed) {
                    warnings.add("Could not parse method attribute " + attribute.name() + " on " + name + descriptor + ": " + malformed.getMessage());
                }
            }

            List<String> localNames = locals.stream()
                    .sorted(Comparator.comparingInt(LocalVariable::slot).thenComparingInt(LocalVariable::startPc))
                    .map(LocalVariable::name)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .toList();
            if (methodParameters.isEmpty()) {
                int parameterCount = DescriptorUtil.parseMethod(descriptor).parameterTypes().size();
                boolean isStatic = (access & ClassInfo.ACC_STATIC) != 0;
                methodParameters = locals.stream()
                        .filter(local -> local.startPc() == 0)
                        .filter(local -> isStatic || local.slot() != 0)
                        .sorted(Comparator.comparingInt(LocalVariable::slot))
                        .map(LocalVariable::name)
                        .filter(value -> value != null && !value.isBlank() && !"this".equals(value))
                        .distinct()
                        .limit(parameterCount)
                        .toList();
            }
            warnings.addAll(disassembly.warnings().stream().map(w -> name + descriptor + ": " + w).toList());
            ClassInfo.MethodInfo methodInfo = new ClassInfo.MethodInfo(
                    name,
                    descriptor,
                    signature,
                    List.copyOf(exceptions),
                    List.copyOf(methodParameters),
                    List.copyOf(localNames),
                    access,
                    maxStack,
                    maxLocals,
                    codeLength,
                    disassembly.branchCount(),
                    disassembly.switchCount(),
                    disassembly.invokeDynamicCount(),
                    disassembly.xorCount(),
                    exceptionHandlers,
                    debugInfo
            );
            methods.add(new ParsedMethod(methodInfo, disassembly.text()));
        }
        return methods;
    }

    private static CodeParse parseCode(
            DataInputStream data,
            ConstantPool pool,
            Set<String> references,
            List<String> warnings,
            String methodLabel
    ) throws IOException {
        int maxStack = data.readUnsignedShort();
        int maxLocals = data.readUnsignedShort();
        long codeLengthLong = Integer.toUnsignedLong(data.readInt());
        if (codeLengthLong > 65_535L || codeLengthLong > data.available()) {
            throw new IOException("Invalid Code length " + codeLengthLong + " for " + methodLabel);
        }
        byte[] code = data.readNBytes((int) codeLengthLong);
        if (code.length != codeLengthLong) throw new EOFException("Truncated Code attribute");
        Bytecode.Result disassembly = Bytecode.disassemble(code, pool);

        int exceptionHandlers = data.readUnsignedShort();
        for (int i = 0; i < exceptionHandlers; i++) {
            data.readUnsignedShort(); // start_pc
            data.readUnsignedShort(); // end_pc
            data.readUnsignedShort(); // handler_pc
            int catchType = data.readUnsignedShort();
            if (catchType != 0) {
                String exception = pool.className(catchType);
                if (exception != null) references.add(exception);
            }
        }

        List<LocalVariable> locals = new ArrayList<>();
        boolean debugInfo = false;
        int nestedCount = data.readUnsignedShort();
        for (int i = 0; i < nestedCount; i++) {
            Attribute nested = readAttribute(data, pool);
            try (DataInputStream nestedData = nested.stream()) {
                switch (nested.name()) {
                    case "LineNumberTable" -> {
                        debugInfo = true;
                    }
                    case "LocalVariableTable", "LocalVariableTypeTable" -> {
                        debugInfo = true;
                        int localCount = nestedData.readUnsignedShort();
                        for (int j = 0; j < localCount; j++) {
                            int startPc = nestedData.readUnsignedShort();
                            int length = nestedData.readUnsignedShort();
                            String name = pool.utf8(nestedData.readUnsignedShort());
                            String descriptor = pool.utf8(nestedData.readUnsignedShort());
                            int slot = nestedData.readUnsignedShort();
                            if (descriptor != null) references.addAll(DescriptorUtil.collectClassNames(descriptor));
                            locals.add(new LocalVariable(startPc, length, name, descriptor, slot));
                        }
                    }
                    default -> {
                        // StackMapTable and annotations are deliberately not rewritten.
                    }
                }
            } catch (RuntimeException | IOException malformed) {
                warnings.add("Could not parse Code attribute " + nested.name() + " on " + methodLabel + ": " + malformed.getMessage());
            }
        }
        return new CodeParse(maxStack, maxLocals, code.length, exceptionHandlers, disassembly, List.copyOf(locals), debugInfo);
    }

    private static Attribute readAttribute(DataInputStream input, ConstantPool pool) throws IOException {
        String name = pool.utf8(input.readUnsignedShort());
        if (name == null) name = "<unknown>";
        long length = Integer.toUnsignedLong(input.readInt());
        if (length > MAX_ATTRIBUTE_SIZE || length > input.available()) {
            throw new IOException("Invalid attribute length " + length + " for " + name);
        }
        byte[] bytes = input.readNBytes((int) length);
        if (bytes.length != length) throw new EOFException("Truncated attribute " + name);
        return new Attribute(name, bytes);
    }

    private static String renderBytecode(ClassInfo info, List<ParsedMethod> methods, int classFileSize) {
        StringBuilder out = new StringBuilder(Math.max(2048, classFileSize * 4));
        out.append("Plugin Reconstructor Ultra - structured JVM bytecode dump\n")
                .append("Entry: ").append(info.entryName()).append('\n')
                .append("Class: ").append(info.dottedName()).append('\n')
                .append("Class file version: ").append(info.majorVersion()).append('.').append(info.minorVersion())
                .append(" (Java ").append(info.javaVersion()).append(")\n")
                .append("Access: 0x").append(Integer.toHexString(info.access())).append(' ')
                .append(AccessFlags.classFlags(info.access())).append('\n')
                .append("Super: ").append(info.superName() == null ? "<none>" : info.superName().replace('/', '.')).append('\n');
        if (!info.interfaces().isEmpty()) {
            out.append("Interfaces: ").append(String.join(", ", info.interfaces().stream().map(v -> v.replace('/', '.')).toList())).append('\n');
        }
        if (info.sourceFile() != null) out.append("SourceFile: ").append(info.sourceFile()).append('\n');
        if (info.signature() != null) out.append("Signature: ").append(info.signature()).append('\n');
        out.append("Class bytes: ").append(classFileSize).append('\n')
                .append("Bytecode bytes: ").append(info.bytecodeSize()).append('\n')
                .append("References: ").append(info.referencedClasses().size()).append('\n')
                .append("String constants: ").append(info.stringConstants().size()).append("\n\n");

        out.append("FIELDS\n------\n");
        if (info.fields().isEmpty()) out.append("<none>\n");
        for (ClassInfo.FieldInfo field : info.fields()) {
            out.append(AccessFlags.fieldFlags(field.access())).append(' ')
                    .append(field.name()).append(' ').append(field.descriptor());
            if (field.signature() != null) out.append(" signature=").append(field.signature());
            if (field.constantValue() != null) out.append(" constant=").append(field.constantValue());
            out.append('\n');
        }

        out.append("\nMETHODS\n-------\n");
        for (ParsedMethod parsed : methods) {
            ClassInfo.MethodInfo method = parsed.info();
            out.append('\n').append(AccessFlags.methodFlags(method.access())).append(' ')
                    .append(method.name()).append(method.descriptor()).append('\n')
                    .append("  max_stack=").append(method.maxStack())
                    .append(" max_locals=").append(method.maxLocals())
                    .append(" code_length=").append(method.codeLength())
                    .append(" handlers=").append(method.exceptionHandlers()).append('\n');
            if (method.signature() != null) out.append("  signature=").append(method.signature()).append('\n');
            if (!method.exceptions().isEmpty()) out.append("  throws=").append(String.join(", ", method.exceptions())).append('\n');
            if (!method.parameterNames().isEmpty()) out.append("  parameters=").append(String.join(", ", method.parameterNames())).append('\n');
            if (!method.localVariables().isEmpty()) out.append("  locals=").append(String.join(", ", method.localVariables())).append('\n');
            if (parsed.instructions().isBlank()) out.append("    <no bytecode: abstract or native>\n");
            else out.append(parsed.instructions());
        }

        if (!info.stringConstants().isEmpty()) {
            out.append("\nSTRING CONSTANTS\n----------------\n");
            for (String value : info.stringConstants()) {
                String escaped = value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
                if (escaped.length() > 500) escaped = escaped.substring(0, 497) + "...";
                out.append('"').append(escaped).append("\"\n");
            }
        }
        if (!info.warnings().isEmpty()) {
            out.append("\nWARNINGS\n--------\n");
            for (String warning : info.warnings()) out.append("- ").append(warning).append('\n');
        }
        return out.toString();
    }

    private static String required(String value, String label) throws IOException {
        if (value == null) throw new IOException("Missing " + label + " in constant pool");
        return value;
    }

    private record Attribute(String name, byte[] bytes) {
        DataInputStream stream() {
            return new DataInputStream(new ByteArrayInputStream(bytes));
        }
    }

    private record LocalVariable(int startPc, int length, String name, String descriptor, int slot) {}

    private record CodeParse(
            int maxStack,
            int maxLocals,
            int codeLength,
            int exceptionHandlers,
            Bytecode.Result disassembly,
            List<LocalVariable> localVariables,
            boolean debugInfo
    ) {}

    private record ParsedMethod(ClassInfo.MethodInfo info, String instructions) {}
}
