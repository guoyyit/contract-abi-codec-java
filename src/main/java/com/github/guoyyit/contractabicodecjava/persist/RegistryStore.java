package com.github.guoyyit.contractabicodecjava.persist;

import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.abi.AbiType;
import com.github.guoyyit.contractabicodecjava.abi.Keccak256;
import com.github.guoyyit.contractabicodecjava.abi.TypeParser;
import com.github.guoyyit.contractabicodecjava.registry.FunctionDefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 注册表持久化。
 *
 * 写入协议：序列化到内存 -> 写 {@code <file>.tmp} -> fsync -> 原子 rename 覆盖正式文件
 * -> fsync 父目录。崩溃时要么留下旧正式文件，要么留下新正式文件，
 * 残留的 .tmp 不参与加载，绝不会出现半写入状态。
 *
 * 文件格式带魔数、格式版本与 CRC32 校验和；加载时校验失败抛
 * {@link AbiException.ErrorCode#PERSISTENCE_CORRUPT}，不会返回截断数据。
 */
public class RegistryStore {

    private static final int MAGIC = 0x41424952; // "ABIR"
    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Path tmpFile;

    public RegistryStore(Path file) {
        this.file = file;
        this.tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
    }

    public Path file() {
        return file;
    }

    public synchronized void save(List<FunctionDefinition> definitions) {
        byte[] payload = serialize(definitions);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 1. 写临时文件并 fsync
            try (FileChannel ch = FileChannel.open(tmpFile,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(payload);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            // 2. 原子 rename
            try {
                Files.move(tmpFile, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            // 3. fsync 父目录，保证 rename 持久
            if (parent != null) {
                try (FileChannel dir = FileChannel.open(parent, StandardOpenOption.READ)) {
                    dir.force(true);
                } catch (IOException ignored) {
                    // 某些平台不支持目录 fsync，尽力而为
                }
            }
        } catch (IOException e) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "failed to persist registry: " + e.getMessage());
        }
    }

    /** 恢复定义列表；文件不存在返回空表，损坏时抛 PERSISTENCE_CORRUPT。 */
    public List<FunctionDefinition> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] payload;
        try {
            payload = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "failed to read registry file: " + e.getMessage());
        }
        return deserialize(payload);
    }

    /** 清理崩溃残留的临时文件（可选调用）。 */
    public void cleanupTemp() {
        try {
            Files.deleteIfExists(tmpFile);
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- 序列化

    private static byte[] serialize(List<FunctionDefinition> definitions) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, definitions.size());
            for (FunctionDefinition def : definitions) {
                writeString(body, def.name());
                writeString(body, def.canonicalSignature());
                writeString(body, def.alias() == null ? "" : def.alias());
                writeShort(body, def.inputs().size());
                for (AbiType t : def.inputs()) {
                    writeString(body, TypeParser.canonical(t));
                }
                writeShort(body, def.outputs().size());
                for (AbiType t : def.outputs()) {
                    writeString(body, TypeParser.canonical(t));
                }
            }
            byte[] bodyBytes = body.toByteArray();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeInt(out, MAGIC);
            writeInt(out, FORMAT_VERSION);
            writeInt(out, bodyBytes.length);
            CRC32 crc = new CRC32();
            crc.update(bodyBytes);
            writeLong(out, crc.getValue());
            out.writeBytes(bodyBytes);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // ByteArrayOutputStream 不会抛 IOException
        }
    }

    private static List<FunctionDefinition> deserialize(byte[] payload) {
        if (payload.length < 20) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "registry file too short: " + payload.length + " bytes");
        }
        ByteBuffer head = ByteBuffer.wrap(payload);
        int magic = head.getInt();
        if (magic != MAGIC) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "bad magic in registry file");
        }
        int version = head.getInt();
        if (version != FORMAT_VERSION) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "unsupported format version: " + version);
        }
        int bodyLen = head.getInt();
        long expectedCrc = head.getLong();
        if (bodyLen < 0 || 20L + bodyLen != payload.length) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "body length mismatch: header says " + bodyLen
                            + ", file has " + (payload.length - 20));
        }
        byte[] body = Arrays.copyOfRange(payload, 20, payload.length);
        CRC32 crc = new CRC32();
        crc.update(body);
        if (crc.getValue() != expectedCrc) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "checksum mismatch in registry file");
        }
        ByteBuffer buf = ByteBuffer.wrap(body);
        int count = buf.getInt();
        if (count < 0 || count > 1_000_000) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "implausible entry count: " + count);
        }
        List<FunctionDefinition> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readString(buf);
            String signature = readString(buf);
            String alias = readString(buf);
            int inCount = readCount(buf);
            List<AbiType> inputs = new ArrayList<>(inCount);
            for (int j = 0; j < inCount; j++) {
                inputs.add(TypeParser.parse(readString(buf)));
            }
            int outCount = readCount(buf);
            List<AbiType> outputs = new ArrayList<>(outCount);
            for (int j = 0; j < outCount; j++) {
                outputs.add(TypeParser.parse(readString(buf)));
            }
            // 选择器不信任存储内容，按签名重新计算
            byte[] hash = Keccak256.digest(signature.getBytes(StandardCharsets.UTF_8));
            out.add(new FunctionDefinition(name, signature,
                    List.copyOf(inputs), List.copyOf(outputs),
                    Arrays.copyOf(hash, 4), alias.isEmpty() ? null : alias));
        }
        if (buf.hasRemaining()) {
            throw new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT,
                    "trailing bytes in registry file body");
        }
        return out;
    }

    private static int readCount(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            throw corrupt("unexpected end of body");
        }
        return buf.getShort() & 0xFFFF;
    }

    private static String readString(ByteBuffer buf) {
        int len = readCount(buf);
        if (buf.remaining() < len) {
            throw corrupt("string extends past end of body");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static AbiException corrupt(String msg) {
        return new AbiException(AbiException.ErrorCode.PERSISTENCE_CORRUPT, msg);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) (v >>> (8 * i)) & 0xFF);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        if (v > 0xFFFF) {
            throw new IllegalArgumentException("too many entries: " + v);
        }
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeShort(out, bytes.length);
        out.write(bytes);
    }
}
