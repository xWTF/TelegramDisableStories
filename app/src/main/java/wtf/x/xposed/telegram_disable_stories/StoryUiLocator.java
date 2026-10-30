package wtf.x.xposed.telegram_disable_stories;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class StoryUiLocator {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    static final class Target {
        final String className;
        final String methodName;
        final Set<String> controllerChecks;

        Target(String className, String methodName, Set<String> controllerChecks) {
            this.className = className;
            this.methodName = methodName;
            this.controllerChecks = controllerChecks;
        }
    }

    static Target find(String[] apkPaths, String controllerClassName) throws IOException {
        String controllerType = "L" + controllerClassName.replace('.', '/') + ";";
        ArrayList<Target> targets = new ArrayList<>();
        for (String path : apkPaths) {
            try (ZipFile apk = new ZipFile(path)) {
                Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("classes[0-9]*\\.dex")) {
                        continue;
                    }
                    try (InputStream input = apk.getInputStream(entry)) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                        new Dex(output.toByteArray(), controllerType).findTargets(targets);
                    }
                }
            }
        }
        if (targets.size() != 1) {
            throw new IOException("Expected one Story visibility method, found " + targets.size());
        }
        return targets.get(0);
    }

    // Only indexes and instruction references are needed; no decompilation or name mapping.
    private static final class Dex {
        private final ByteBuffer data;
        private final byte[] bytes;
        private final String controllerType;
        private final String[] strings;
        private final int stringIds;
        private final int typeIds;
        private final int protoIds;
        private final int fieldIds;
        private final int methodIds;

        Dex(byte[] bytes, String controllerType) throws IOException {
            this.bytes = bytes;
            this.controllerType = controllerType;
            data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 112 || data.getInt(0) != 0x0a786564
                    || data.getInt(36) != 112 || data.getInt(40) != 0x12345678) {
                throw new IOException("Unsupported DEX header");
            }
            strings = new String[data.getInt(56)];
            stringIds = data.getInt(60);
            typeIds = data.getInt(68);
            protoIds = data.getInt(76);
            fieldIds = data.getInt(84);
            methodIds = data.getInt(92);
        }

        void findTargets(ArrayList<Target> targets) throws IOException {
            int classCount = data.getInt(96);
            int classDefs = data.getInt(100);
            for (int index = 0; index < classCount; index++) {
                int classData = data.getInt(classDefs + index * 32 + 24);
                if (classData == 0) {
                    continue;
                }
                ByteBuffer cursor = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                cursor.position(classData);
                int staticFields = readUleb128(cursor);
                int instanceFields = readUleb128(cursor);
                int directMethods = readUleb128(cursor);
                int virtualMethods = readUleb128(cursor);
                for (int field = 0; field < staticFields + instanceFields; field++) {
                    readUleb128(cursor);
                    readUleb128(cursor);
                }
                readMethods(cursor, directMethods, targets);
                readMethods(cursor, virtualMethods, targets);
            }
        }

        private void readMethods(ByteBuffer cursor, int count, ArrayList<Target> targets) throws IOException {
            int methodIndex = 0;
            for (int index = 0; index < count; index++) {
                methodIndex += readUleb128(cursor);
                int access = readUleb128(cursor);
                int code = readUleb128(cursor);
                if (code != 0 && (access & 0x8) == 0 && shorty(methodIndex).equals("VZ")) {
                    inspectMethod(methodIndex, code, targets);
                }
            }
        }

        private void inspectMethod(int methodIndex, int code, ArrayList<Target> targets) throws IOException {
            Set<Integer> storyLists = new LinkedHashSet<>();
            Set<String> controllerChecks = new LinkedHashSet<>();
            boolean animatesVisibility = false;
            int instructions = code + 16;
            int length = data.getInt(code + 12);
            for (int offset = 0; offset < length;) {
                int position = instructions + offset * 2;
                int opcode = unsignedShort(position) & 0xff;
                if (opcode == 0x54) { // iget-object
                    int fieldIndex = unsignedShort(position + 2);
                    int field = fieldIds + fieldIndex * 8;
                    if (type(unsignedShort(field)).equals(controllerType)
                            && type(unsignedShort(field + 2)).equals("Ljava/util/ArrayList;")) {
                        storyLists.add(fieldIndex);
                    }
                } else if ((opcode >= 0x6e && opcode <= 0x72) || (opcode >= 0x74 && opcode <= 0x78)) {
                    int calledIndex = unsignedShort(position + 2);
                    String owner = methodOwner(calledIndex);
                    if (owner.equals(controllerType) && shorty(calledIndex).equals("Z")) {
                        controllerChecks.add(methodName(calledIndex));
                    }
                    if (owner.equals("Landroid/animation/ValueAnimator;") && methodName(calledIndex).equals("ofFloat")) {
                        animatesVisibility = true;
                    }
                }
                int width = instructionWidth(position);
                if (width <= 0 || width > length - offset) {
                    throw new IOException("Invalid DEX instruction width");
                }
                offset += width;
            }
            // updateStoriesVisibility(boolean): both feed lists, both self-story checks,
            // and visibility animation. Require a unique match across all APKs.
            if (storyLists.size() == 2 && controllerChecks.size() == 2 && animatesVisibility) {
                String owner = methodOwner(methodIndex);
                targets.add(new Target(owner.substring(1, owner.length() - 1).replace('/', '.'),
                        methodName(methodIndex), controllerChecks));
            }
        }

        private String methodOwner(int index) throws IOException {
            return type(unsignedShort(methodIds + index * 8));
        }

        private String methodName(int index) throws IOException {
            return string(data.getInt(methodIds + index * 8 + 4));
        }

        private String shorty(int index) throws IOException {
            int proto = unsignedShort(methodIds + index * 8 + 2);
            return string(data.getInt(protoIds + proto * 12));
        }

        private String type(int index) throws IOException {
            return string(data.getInt(typeIds + index * 4));
        }

        private String string(int index) throws IOException {
            if (strings[index] == null) {
                ByteBuffer cursor = data.duplicate();
                cursor.position(data.getInt(stringIds + index * 4));
                readUleb128(cursor);
                int start = cursor.position();
                while (cursor.get() != 0) {
                    // The identifiers used by this matcher are ASCII, also valid MUTF-8.
                }
                strings[index] = new String(bytes, start, cursor.position() - start - 1, UTF_8);
            }
            return strings[index];
        }

        private int unsignedShort(int offset) {
            return data.getShort(offset) & 0xffff;
        }

        private int instructionWidth(int position) throws IOException {
            int instruction = unsignedShort(position);
            int opcode = instruction & 0xff;
            if (opcode == 0) {
                switch (instruction) {
                    case 0x0000:
                        return 1;
                    case 0x0100:
                        return 4 + unsignedShort(position + 2) * 2;
                    case 0x0200:
                        return 2 + unsignedShort(position + 2) * 4;
                    case 0x0300:
                        long size = (long) unsignedShort(position + 2) * (data.getInt(position + 4) & 0xffffffffL);
                        if (size > bytes.length) {
                            throw new IOException("Invalid DEX array payload");
                        }
                        return 4 + (int) ((size + 1) / 2);
                    default:
                        throw new IOException("Unsupported DEX payload");
                }
            }
            if (opcode <= 0x09) {
                return (opcode - 1) % 3 + 1;
            }
            if (opcode <= 0x12 || opcode == 0x1d || opcode == 0x1e
                    || opcode == 0x21 || opcode == 0x27 || opcode == 0x28
                    || (opcode >= 0x7b && opcode <= 0x8f)
                    || (opcode >= 0xb0 && opcode <= 0xcf)) {
                return 1;
            }
            if (opcode == 0x18) {
                return 5;
            }
            if (opcode == 0xfa || opcode == 0xfb) {
                return 4;
            }
            if (opcode == 0x14 || opcode == 0x17 || opcode == 0x1b
                    || (opcode >= 0x24 && opcode <= 0x26)
                    || (opcode >= 0x2a && opcode <= 0x2c)
                    || (opcode >= 0x6e && opcode <= 0x72)
                    || (opcode >= 0x74 && opcode <= 0x78)
                    || opcode == 0xfc || opcode == 0xfd) {
                return 3;
            }
            if ((opcode >= 0x13 && opcode <= 0x23) || opcode == 0x29
                    || (opcode >= 0x2d && opcode <= 0x3d)
                    || (opcode >= 0x44 && opcode <= 0x6d)
                    || (opcode >= 0x90 && opcode <= 0xaf)
                    || (opcode >= 0xd0 && opcode <= 0xe2)
                    || opcode == 0xfe || opcode == 0xff) {
                return 2;
            }
            throw new IOException("Unsupported DEX opcode: " + opcode);
        }

        private static int readUleb128(ByteBuffer cursor) throws IOException {
            int result = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int value = cursor.get() & 0xff;
                result |= (value & 0x7f) << shift;
                if ((value & 0x80) == 0) {
                    return result;
                }
            }
            throw new IOException("Invalid DEX ULEB128");
        }
    }
}
