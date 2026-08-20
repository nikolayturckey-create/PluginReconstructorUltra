package dev.nik.reconstructor.classfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Bytecode {
    private static final String[] NAMES = new String[256];
    private static final int[] OPERANDS = new int[256];

    static {
        String[] names = {
                "nop","aconst_null","iconst_m1","iconst_0","iconst_1","iconst_2","iconst_3","iconst_4",
                "iconst_5","lconst_0","lconst_1","fconst_0","fconst_1","fconst_2","dconst_0","dconst_1",
                "bipush","sipush","ldc","ldc_w","ldc2_w","iload","lload","fload","dload","aload",
                "iload_0","iload_1","iload_2","iload_3","lload_0","lload_1","lload_2","lload_3",
                "fload_0","fload_1","fload_2","fload_3","dload_0","dload_1","dload_2","dload_3",
                "aload_0","aload_1","aload_2","aload_3","iaload","laload","faload","daload","aaload",
                "baload","caload","saload","istore","lstore","fstore","dstore","astore","istore_0",
                "istore_1","istore_2","istore_3","lstore_0","lstore_1","lstore_2","lstore_3","fstore_0",
                "fstore_1","fstore_2","fstore_3","dstore_0","dstore_1","dstore_2","dstore_3","astore_0",
                "astore_1","astore_2","astore_3","iastore","lastore","fastore","dastore","aastore","bastore",
                "castore","sastore","pop","pop2","dup","dup_x1","dup_x2","dup2","dup2_x1","dup2_x2",
                "swap","iadd","ladd","fadd","dadd","isub","lsub","fsub","dsub","imul","lmul","fmul",
                "dmul","idiv","ldiv","fdiv","ddiv","irem","lrem","frem","drem","ineg","lneg","fneg",
                "dneg","ishl","lshl","ishr","lshr","iushr","lushr","iand","land","ior","lor","ixor",
                "lxor","iinc","i2l","i2f","i2d","l2i","l2f","l2d","f2i","f2l","f2d","d2i","d2l",
                "d2f","i2b","i2c","i2s","lcmp","fcmpl","fcmpg","dcmpl","dcmpg","ifeq","ifne","iflt",
                "ifge","ifgt","ifle","if_icmpeq","if_icmpne","if_icmplt","if_icmpge","if_icmpgt","if_icmple",
                "if_acmpeq","if_acmpne","goto","jsr","ret","tableswitch","lookupswitch","ireturn","lreturn",
                "freturn","dreturn","areturn","return","getstatic","putstatic","getfield","putfield","invokevirtual",
                "invokespecial","invokestatic","invokeinterface","invokedynamic","new","newarray","anewarray","arraylength",
                "athrow","checkcast","instanceof","monitorenter","monitorexit","wide","multianewarray","ifnull","ifnonnull",
                "goto_w","jsr_w","breakpoint"
        };
        System.arraycopy(names, 0, NAMES, 0, names.length);
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i] == null) NAMES[i] = String.format(Locale.ROOT, "opcode_%02x", i);
        }
        setOperands(1, 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc);
        setOperands(2, 0x11, 0x13, 0x14, 0x84,
                0x99,0x9a,0x9b,0x9c,0x9d,0x9e,0x9f,0xa0,0xa1,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,0xa8,
                0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xbb,0xbd,0xc0,0xc1,0xc6,0xc7);
        setOperands(3, 0xc5);
        setOperands(4, 0xb9, 0xba, 0xc8, 0xc9);
        OPERANDS[0xaa] = -1;
        OPERANDS[0xab] = -1;
        OPERANDS[0xc4] = -1;
    }

    private Bytecode() {}

    static Result disassemble(byte[] code, ConstantPool pool) {
        StringBuilder output = new StringBuilder(Math.max(256, code.length * 5));
        int branches = 0;
        int switches = 0;
        int invokeDynamic = 0;
        int xor = 0;
        List<String> warnings = new ArrayList<>();

        int offset = 0;
        while (offset < code.length) {
            int opcode = u1(code, offset);
            int start = offset;
            output.append(String.format(Locale.ROOT, "    %5d: %-18s", offset, NAMES[opcode]));
            try {
                if (isShortBranch(opcode)) {
                    int delta = s2(code, offset + 1);
                    output.append(offset + delta);
                    branches++;
                    offset += 3;
                } else if (opcode == 0xc8 || opcode == 0xc9) {
                    int delta = s4(code, offset + 1);
                    output.append(offset + delta);
                    branches++;
                    offset += 5;
                } else if (opcode == 0xaa) {
                    int padding = (4 - ((offset + 1) & 3)) & 3;
                    int cursor = offset + 1 + padding;
                    int defaultDelta = s4(code, cursor);
                    int low = s4(code, cursor + 4);
                    int high = s4(code, cursor + 8);
                    long countLong = (long) high - low + 1;
                    if (countLong < 0 || countLong > 1_000_000 || cursor + 12L + countLong * 4L > code.length) {
                        throw new IllegalArgumentException("invalid tableswitch range");
                    }
                    int count = (int) countLong;
                    output.append("low=").append(low).append(" high=").append(high)
                            .append(" default=").append(offset + defaultDelta).append(" {");
                    int itemCursor = cursor + 12;
                    for (int i = 0; i < count; i++) {
                        if (i < 64) {
                            if (i > 0) output.append(", ");
                            output.append(low + i).append("->").append(offset + s4(code, itemCursor + i * 4));
                        }
                    }
                    if (count > 64) output.append(", ... ").append(count - 64).append(" more");
                    output.append('}');
                    branches += count + 1;
                    switches++;
                    offset = itemCursor + count * 4;
                } else if (opcode == 0xab) {
                    int padding = (4 - ((offset + 1) & 3)) & 3;
                    int cursor = offset + 1 + padding;
                    int defaultDelta = s4(code, cursor);
                    int pairs = s4(code, cursor + 4);
                    if (pairs < 0 || pairs > 1_000_000 || cursor + 8L + pairs * 8L > code.length) {
                        throw new IllegalArgumentException("invalid lookupswitch pairs");
                    }
                    output.append("pairs=").append(pairs).append(" default=").append(offset + defaultDelta).append(" {");
                    int itemCursor = cursor + 8;
                    for (int i = 0; i < pairs; i++) {
                        if (i < 64) {
                            if (i > 0) output.append(", ");
                            int match = s4(code, itemCursor + i * 8);
                            int target = offset + s4(code, itemCursor + i * 8 + 4);
                            output.append(match).append("->").append(target);
                        }
                    }
                    if (pairs > 64) output.append(", ... ").append(pairs - 64).append(" more");
                    output.append('}');
                    branches += pairs + 1;
                    switches++;
                    offset = itemCursor + pairs * 8;
                } else if (opcode == 0xc4) {
                    int widened = u1(code, offset + 1);
                    output.append(NAMES[widened]).append(' ');
                    if (widened == 0x84) {
                        output.append(u2(code, offset + 2)).append(' ').append(s2(code, offset + 4));
                        offset += 6;
                    } else {
                        output.append(u2(code, offset + 2));
                        offset += 4;
                    }
                } else {
                    int operands = OPERANDS[opcode];
                    appendOperands(output, code, offset, opcode, operands, pool);
                    if (opcode == 0xba) invokeDynamic++;
                    if (opcode == 0x82 || opcode == 0x83) xor++;
                    offset += 1 + Math.max(0, operands);
                }
            } catch (RuntimeException malformed) {
                warnings.add("Malformed instruction at offset " + start + ": " + malformed.getMessage());
                output.append(" <malformed: ").append(malformed.getMessage()).append('>');
                offset = start + 1;
            }
            output.append('\n');
            if (offset <= start) {
                warnings.add("Decoder made no progress at offset " + start);
                offset = start + 1;
            }
        }
        return new Result(output.toString(), branches, switches, invokeDynamic, xor, List.copyOf(warnings));
    }

    private static void appendOperands(StringBuilder out, byte[] code, int offset, int opcode, int operands, ConstantPool pool) {
        if (operands <= 0) {
            return;
        }
        out.append(' ');
        switch (opcode) {
            case 0x10 -> out.append((byte) u1(code, offset + 1));
            case 0x11 -> out.append(s2(code, offset + 1));
            case 0x12 -> appendCp(out, pool, u1(code, offset + 1));
            case 0x13, 0x14,
                    0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xbb,0xbd,0xc0,0xc1 -> appendCp(out, pool, u2(code, offset + 1));
            case 0xb9 -> {
                appendCp(out, pool, u2(code, offset + 1));
                out.append(" count=").append(u1(code, offset + 3));
            }
            case 0xba -> appendCp(out, pool, u2(code, offset + 1));
            case 0xc5 -> {
                appendCp(out, pool, u2(code, offset + 1));
                out.append(" dimensions=").append(u1(code, offset + 3));
            }
            case 0x84 -> out.append(u1(code, offset + 1)).append(' ').append((byte) u1(code, offset + 2));
            case 0xbc -> out.append(newArrayType(u1(code, offset + 1)));
            default -> {
                for (int i = 0; i < operands; i++) {
                    if (i > 0) out.append(' ');
                    out.append(u1(code, offset + 1 + i));
                }
            }
        }
    }

    private static void appendCp(StringBuilder out, ConstantPool pool, int index) {
        out.append('#').append(index).append(" // ").append(pool.describe(index));
    }

    private static String newArrayType(int type) {
        return switch (type) {
            case 4 -> "boolean";
            case 5 -> "char";
            case 6 -> "float";
            case 7 -> "double";
            case 8 -> "byte";
            case 9 -> "short";
            case 10 -> "int";
            case 11 -> "long";
            default -> "unknown(" + type + ")";
        };
    }

    private static boolean isShortBranch(int opcode) {
        return (opcode >= 0x99 && opcode <= 0xa8) || opcode == 0xc6 || opcode == 0xc7;
    }

    private static void setOperands(int count, int... opcodes) {
        for (int opcode : opcodes) {
            OPERANDS[opcode] = count;
        }
    }

    private static int u1(byte[] bytes, int offset) {
        if (offset < 0 || offset >= bytes.length) throw new IllegalArgumentException("truncated bytecode");
        return bytes[offset] & 0xFF;
    }

    private static int u2(byte[] bytes, int offset) {
        return (u1(bytes, offset) << 8) | u1(bytes, offset + 1);
    }

    private static int s2(byte[] bytes, int offset) {
        return (short) u2(bytes, offset);
    }

    private static int s4(byte[] bytes, int offset) {
        return (u1(bytes, offset) << 24)
                | (u1(bytes, offset + 1) << 16)
                | (u1(bytes, offset + 2) << 8)
                | u1(bytes, offset + 3);
    }

    record Result(String text, int branchCount, int switchCount, int invokeDynamicCount, int xorCount,
                  List<String> warnings) {}
}
