package dev.nik.reconstructor.classfile;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

final class ConstantPool {
    private final Entry[] entries;

    private ConstantPool(Entry[] entries) {
        this.entries = entries;
    }

    static ConstantPool read(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        Entry[] entries = new Entry[count];
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            entries[i] = switch (tag) {
                case 1 -> new Utf8Entry(input.readUTF());
                case 3 -> new IntegerEntry(input.readInt());
                case 4 -> new FloatEntry(input.readFloat());
                case 5 -> {
                    LongEntry value = new LongEntry(input.readLong());
                    i++;
                    yield value;
                }
                case 6 -> {
                    DoubleEntry value = new DoubleEntry(input.readDouble());
                    i++;
                    yield value;
                }
                case 7 -> new ClassEntry(input.readUnsignedShort());
                case 8 -> new StringEntry(input.readUnsignedShort());
                case 9, 10, 11 -> new RefEntry(tag, input.readUnsignedShort(), input.readUnsignedShort());
                case 12 -> new NameAndTypeEntry(input.readUnsignedShort(), input.readUnsignedShort());
                case 15 -> new MethodHandleEntry(input.readUnsignedByte(), input.readUnsignedShort());
                case 16 -> new MethodTypeEntry(input.readUnsignedShort());
                case 17, 18 -> new DynamicEntry(tag, input.readUnsignedShort(), input.readUnsignedShort());
                case 19 -> new ModuleEntry(input.readUnsignedShort());
                case 20 -> new PackageEntry(input.readUnsignedShort());
                default -> throw new IOException("Unsupported constant-pool tag " + tag + " at #" + i);
            };
        }
        return new ConstantPool(entries);
    }

    int size() {
        return entries.length;
    }

    Entry entry(int index) {
        if (index <= 0 || index >= entries.length) {
            return null;
        }
        return entries[index];
    }

    String utf8(int index) {
        Entry entry = entry(index);
        return entry instanceof Utf8Entry utf8 ? utf8.value() : null;
    }

    String className(int index) {
        Entry entry = entry(index);
        if (entry instanceof ClassEntry classEntry) {
            return utf8(classEntry.nameIndex());
        }
        return null;
    }

    String stringValue(int index) {
        Entry entry = entry(index);
        if (entry instanceof StringEntry stringEntry) {
            return utf8(stringEntry.utf8Index());
        }
        return null;
    }

    String describe(int index) {
        Entry entry = entry(index);
        if (entry == null) {
            return "#" + index + "<?>";
        }
        if (entry instanceof Utf8Entry utf8) {
            return quote(utf8.value());
        }
        if (entry instanceof IntegerEntry integer) {
            return Integer.toString(integer.value());
        }
        if (entry instanceof FloatEntry value) {
            return value.value() + "f";
        }
        if (entry instanceof LongEntry value) {
            return value.value() + "L";
        }
        if (entry instanceof DoubleEntry value) {
            return Double.toString(value.value());
        }
        if (entry instanceof ClassEntry classEntry) {
            return "Class " + safe(utf8(classEntry.nameIndex()));
        }
        if (entry instanceof StringEntry stringEntry) {
            return "String " + quote(utf8(stringEntry.utf8Index()));
        }
        if (entry instanceof NameAndTypeEntry nameAndType) {
            return safe(utf8(nameAndType.nameIndex())) + ":" + safe(utf8(nameAndType.descriptorIndex()));
        }
        if (entry instanceof RefEntry ref) {
            String kind = switch (ref.tag()) {
                case 9 -> "Field";
                case 10 -> "Method";
                case 11 -> "InterfaceMethod";
                default -> "Ref";
            };
            return kind + " " + safe(className(ref.classIndex())) + "." + describe(ref.nameAndTypeIndex());
        }
        if (entry instanceof MethodHandleEntry methodHandle) {
            return "MethodHandle(kind=" + methodHandle.referenceKind() + ", " + describe(methodHandle.referenceIndex()) + ")";
        }
        if (entry instanceof MethodTypeEntry methodType) {
            return "MethodType " + safe(utf8(methodType.descriptorIndex()));
        }
        if (entry instanceof DynamicEntry dynamic) {
            return (dynamic.tag() == 18 ? "InvokeDynamic" : "Dynamic")
                    + " bootstrap=" + dynamic.bootstrapIndex() + " " + describe(dynamic.nameAndTypeIndex());
        }
        if (entry instanceof ModuleEntry module) {
            return "Module " + safe(utf8(module.nameIndex()));
        }
        if (entry instanceof PackageEntry packageEntry) {
            return "Package " + safe(utf8(packageEntry.nameIndex()));
        }
        return "#" + index + "<?>";
    }

    Set<String> referencedClasses() {
        Set<String> references = new LinkedHashSet<>();
        for (int i = 1; i < entries.length; i++) {
            Entry entry = entries[i];
            if (entry instanceof ClassEntry classEntry) {
                String name = utf8(classEntry.nameIndex());
                if (name != null) {
                    if (name.startsWith("[")) {
                        references.addAll(DescriptorUtil.collectClassNames(name));
                    } else {
                        references.add(name);
                    }
                }
            } else if (entry instanceof NameAndTypeEntry nameAndType) {
                references.addAll(DescriptorUtil.collectClassNames(utf8(nameAndType.descriptorIndex())));
            } else if (entry instanceof MethodTypeEntry methodType) {
                references.addAll(DescriptorUtil.collectClassNames(utf8(methodType.descriptorIndex())));
            }
        }
        return references;
    }

    Set<String> stringConstants() {
        Set<String> values = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry instanceof StringEntry stringEntry) {
                String value = utf8(stringEntry.utf8Index());
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    boolean containsUtf8(String text) {
        for (Entry entry : entries) {
            if (entry instanceof Utf8Entry utf8 && utf8.value().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static String quote(String value) {
        if (value == null) {
            return "<missing>";
        }
        String compact = value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\"", "\\\"");
        if (compact.length() > 160) {
            compact = compact.substring(0, 157) + "...";
        }
        return '"' + compact + '"';
    }

    private static String safe(String value) {
        return value == null ? "<missing>" : value;
    }

    sealed interface Entry permits Utf8Entry, IntegerEntry, FloatEntry, LongEntry, DoubleEntry,
            ClassEntry, StringEntry, RefEntry, NameAndTypeEntry, MethodHandleEntry,
            MethodTypeEntry, DynamicEntry, ModuleEntry, PackageEntry {}

    record Utf8Entry(String value) implements Entry {}
    record IntegerEntry(int value) implements Entry {}
    record FloatEntry(float value) implements Entry {}
    record LongEntry(long value) implements Entry {}
    record DoubleEntry(double value) implements Entry {}
    record ClassEntry(int nameIndex) implements Entry {}
    record StringEntry(int utf8Index) implements Entry {}
    record RefEntry(int tag, int classIndex, int nameAndTypeIndex) implements Entry {}
    record NameAndTypeEntry(int nameIndex, int descriptorIndex) implements Entry {}
    record MethodHandleEntry(int referenceKind, int referenceIndex) implements Entry {}
    record MethodTypeEntry(int descriptorIndex) implements Entry {}
    record DynamicEntry(int tag, int bootstrapIndex, int nameAndTypeIndex) implements Entry {}
    record ModuleEntry(int nameIndex) implements Entry {}
    record PackageEntry(int nameIndex) implements Entry {}
}
