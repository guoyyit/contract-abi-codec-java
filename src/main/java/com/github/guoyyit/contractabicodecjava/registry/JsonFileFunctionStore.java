package com.github.guoyyit.contractabicodecjava.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractabicodecjava.encoding.AbiTypeParser;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * JSON file implementation of {@link FunctionStore}.
 *
 * <p>Fault atomicity: a new state is serialized in memory, written to a unique temp
 * file, fsynced, then atomically moved over the destination. Any I/O failure while
 * writing, flushing or replacing the file makes {@link #persist} fail immediately with
 * {@link ErrorCode#PERSISTENCE_ERROR}; the destination is never touched and the temp
 * file is removed. A crash at any point therefore leaves either the previous complete
 * file or the new complete file — never a partially written state.
 *
 * <p>Integrity: new files are written in format version 2 and carry an SHA-256
 * checksum of the canonical payload bytes. A file whose checksum does not match (for
 * example a torn write that happened to land as parseable JSON) is rejected with
 * {@link ErrorCode#CORRUPTED_STATE}. Files written by the previous version (no
 * checksum field, {@code version: 1}) continue to load without migration.
 */
public class JsonFileFunctionStore implements FunctionStore {

    static final int FORMAT_VERSION = 2;

    private final Path file;
    private final Path tmp;
    private final ObjectMapper mapper;

    public JsonFileFunctionStore(Path file) {
        this.file = file;
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized void persist(List<FunctionDefinition> functions) {
        byte[] bytes = serialize(functions);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel ch = FileChannel.open(
                    tmp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    writeBytes(ch, buf);
                }
                forceFile(ch);
            }
            replaceFile(tmp, file);
        } catch (IOException e) {
            cleanupQuietly();
            throw new CodecException(ErrorCode.PERSISTENCE_ERROR,
                    "failed to persist registry to " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            cleanupQuietly();
            throw e;
        }
    }

    /** Writes remaining buffer bytes; overridable for fault injection tests. */
    protected void writeBytes(FileChannel channel, ByteBuffer buffer) throws IOException {
        channel.write(buffer);
    }

    /** Flushes file data and metadata; overridable for fault injection tests. */
    protected void forceFile(FileChannel channel) throws IOException {
        channel.force(true);
    }

    /** Atomically publishes the temp file as the destination; overridable for tests. */
    protected void replaceFile(Path tempFile, Path targetFile) throws IOException {
        Files.move(tempFile, targetFile,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        fsyncDirectoryQuietly(targetFile.toAbsolutePath().getParent());
    }

    /**
     * Best-effort directory fsync so the rename itself is durable. Not portable across
     * platforms (some reject opening a directory as a channel); the file-level fsync
     * and atomic move already guarantee that the destination content is never torn, so
     * failure here must not mask a successful publish.
     */
    private static void fsyncDirectoryQuietly(Path parent) {
        if (parent == null) {
            return;
        }
        try (FileChannel dir = FileChannel.open(parent, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException ignored) {
            // directory fsync unsupported on this platform
        }
    }

    private void cleanupQuietly() {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // best effort; a stale temp is never treated as state and is reclaimed on load
        }
    }

    private byte[] serialize(List<FunctionDefinition> functions) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("version", FORMAT_VERSION);
            var arr = payload.putArray("functions");
            for (FunctionDefinition f : functions) {
                var node = arr.addObject();
                node.put("name", f.name());
                writeParams(node.putArray("inputs"), f, false);
                writeParams(node.putArray("outputs"), f, true);
            }
            byte[] payloadBytes = mapper.writeValueAsBytes(payload);
            String checksum = sha256Hex(payloadBytes);
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("version", FORMAT_VERSION);
            envelope.put("checksum", checksum);
            envelope.put("algorithm", "SHA-256");
            envelope.put("payload", new String(payloadBytes, StandardCharsets.UTF_8));
            return mapper.writeValueAsBytes(envelope);
        } catch (IOException e) {
            throw new CodecException(ErrorCode.PERSISTENCE_ERROR,
                    "failed to serialize registry: " + e.getMessage(), e);
        }
    }

    private void writeParams(com.fasterxml.jackson.databind.node.ArrayNode params,
                             FunctionDefinition f, boolean outputs) {
        List<Parameter> list = outputs ? f.outputs() : f.inputs();
        for (Parameter p : list) {
            var n = params.addObject();
            n.put("name", p.name());
            n.put("type", p.type().canonicalName());
        }
    }

    @Override
    public synchronized List<FunctionDefinition> load() {
        // A leftover temp means a previous write crashed (or failed) before the atomic
        // move. It is never a valid state: ignore and remove it.
        if (Files.exists(tmp)) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file is unreadable: " + file + ": " + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = mapper.readTree(fileBytes);
        } catch (IOException e) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file is not valid JSON: " + file + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file is empty or not a JSON object: " + file);
        }
        JsonNode versionNode = root.get("version");
        int version = versionNode != null && versionNode.canConvertToInt() ? versionNode.intValue() : 1;

        JsonNode payloadNode;
        if (version >= FORMAT_VERSION) {
            // Version 2+: envelope with a checksum over the exact payload bytes. Parse
            // acceptability alone is never enough: a torn write that happens to be
            // valid JSON must still be reported as CORRUPTED_STATE.
            JsonNode checksumNode = root.get("checksum");
            JsonNode algorithmNode = root.get("algorithm");
            payloadNode = root.get("payload");
            if (checksumNode == null || !checksumNode.isTextual()
                    || algorithmNode == null || !algorithmNode.isTextual()
                    || payloadNode == null || !payloadNode.isTextual()) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "registry file is missing integrity fields (checksum/algorithm/payload): "
                                + file);
            }
            if (!"SHA-256".equals(algorithmNode.asText())) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "registry file uses unsupported checksum algorithm '"
                                + algorithmNode.asText() + "': " + file);
            }
            byte[] payloadBytes = payloadNode.asText().getBytes(StandardCharsets.UTF_8);
            String actual = sha256Hex(payloadBytes);
            if (!constantTimeEquals(checksumNode.asText(), actual)) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "registry file failed integrity verification: checksum mismatch: "
                                + file);
            }
            try {
                payloadNode = mapper.readTree(payloadNode.asText());
            } catch (IOException e) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "registry payload is not valid JSON despite checksum match: "
                                + file + ": " + e.getMessage(), e);
            }
        } else {
            // Version 1 (previous release): payload is the whole document and there is
            // no checksum. It loads unchanged; no migration step is required.
            payloadNode = root;
        }
        return readState(payloadNode, file);
    }

    private List<FunctionDefinition> readState(JsonNode root, Path file) {
        if (root == null || !root.isObject()) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file is empty or not a JSON object: " + file);
        }
        JsonNode functions = root.get("functions");
        if (functions == null || !functions.isArray()) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file missing 'functions' array: " + file);
        }
        List<FunctionDefinition> result = new ArrayList<>();
        for (JsonNode fn : functions) {
            result.add(readFunction(fn));
        }
        return result;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private FunctionDefinition readFunction(JsonNode fn) {
        JsonNode nameNode = fn.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE, "function entry missing name");
        }
        List<Parameter> inputs = readParams(fn.get("inputs"), "inputs");
        List<Parameter> outputs = readParams(fn.get("outputs"), "outputs");
        return new FunctionDefinition(nameNode.asText(), inputs, outputs);
    }

    private List<Parameter> readParams(JsonNode node, String label) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE, "'" + label + "' must be an array");
        }
        List<Parameter> params = new ArrayList<>();
        for (JsonNode p : node) {
            JsonNode typeNode = p.get("type");
            if (typeNode == null || !typeNode.isTextual()) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "'" + label + "' entry missing type string");
            }
            AbiType type;
            try {
                type = AbiTypeParser.parse(typeNode.asText());
            } catch (CodecException | IllegalArgumentException e) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "'" + label + "' entry has invalid type '" + typeNode.asText()
                                + "': " + e.getMessage());
            }
            JsonNode pn = p.get("name");
            params.add(new Parameter(pn != null && pn.isTextual() ? pn.asText() : "", type));
        }
        return params;
    }
}
