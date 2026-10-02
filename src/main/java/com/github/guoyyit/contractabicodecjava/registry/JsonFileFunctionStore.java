package com.github.guoyyit.contractabicodecjava.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * <p>Fault atomicity: a new state is fully rendered, written to {@code <file>.tmp},
 * fsynced ({@code force(true)}) and only then atomically moved over the destination.
 * Every read/write/fsync/move failure is propagated as
 * {@link ErrorCode#PERSISTENCE_ERROR}; on failure the temp file is deleted and the
 * previous destination file is never touched, so both memory and disk stay exactly
 * as they were before the call. A leftover {@code .tmp} from a crashed process is
 * never accepted as state: it is removed on startup and can never block a later
 * successful write (the temp is always truncated/recreated).
 *
 * <p>Integrity: files written by this version use format {@code version: 2} and carry
 * a SHA-256 {@code checksum} over the compact serialization of the {@code functions}
 * array. A file that is parseable JSON but truncated, partially overwritten or
 * otherwise incomplete fails checksum verification and is rejected with
 * {@link ErrorCode#CORRUPTED_STATE}; it is never silently treated as an empty
 * registry. Legacy {@code version: 1} files (no checksum field) keep loading
 * without migration; they are upgraded to version 2 on the next successful write.
 */
public class JsonFileFunctionStore implements FunctionStore {

    static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;

    private final Path file;
    private final Path tmp;
    private final ObjectMapper mapper;
    private final ObjectMapper compactMapper;

    public JsonFileFunctionStore(Path file) {
        this.file = file;
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.compactMapper = new ObjectMapper();
    }

    /** Stages at which a simulated storage fault can be injected (testing extension point). */
    protected enum WriteStage { CREATE, WRITE, FORCE, MOVE }

    /**
     * Testing extension point: throws to simulate a disk/quota/read-only-directory
     * failure at the given write stage. Production instances never inject anything.
     */
    protected void injectWriteFault(WriteStage stage) throws IOException {
        // no-op
    }

    @Override
    public synchronized void persist(List<FunctionDefinition> functions) {
        boolean committed = false;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var root = mapper.createObjectNode();
            root.put("version", FORMAT_VERSION);
            var arr = root.putArray("functions");
            for (FunctionDefinition f : functions) {
                var node = arr.addObject();
                node.put("name", f.name());
                writeParams(node.putArray("inputs"), f);
                writeParams(node.putArray("outputs"), f, true);
            }
            root.put("checksum", sha256Hex(compactMapper.writeValueAsBytes(arr)));
            byte[] bytes = mapper.writeValueAsBytes(root);

            injectWriteFault(WriteStage.CREATE);
            try (FileChannel ch = FileChannel.open(
                    tmp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    int written = ch.write(buf);
                    if (written < 0) {
                        throw new IOException("channel reached end-of-stream while writing " + tmp);
                    }
                    injectWriteFault(WriteStage.WRITE);
                }
                injectWriteFault(WriteStage.FORCE);
                ch.force(true);
            }
            injectWriteFault(WriteStage.MOVE);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            committed = true;
        } catch (IOException e) {
            if (!committed) {
                deleteIfExistsQuietly(tmp);
            }
            throw new CodecException(ErrorCode.PERSISTENCE_ERROR,
                    "failed to persist registry to " + file + ": " + e.getMessage(), e);
        } catch (CodecException e) {
            if (!committed) {
                deleteIfExistsQuietly(tmp);
            }
            throw e;
        } finally {
            if (!committed) {
                deleteIfExistsQuietly(tmp);
            }
        }
    }

    private void writeParams(com.fasterxml.jackson.databind.node.ArrayNode params, FunctionDefinition f) {
        writeParams(params, f, false);
    }

    private void writeParams(com.fasterxml.jackson.databind.node.ArrayNode params, FunctionDefinition f, boolean outputs) {
        List<Parameter> list = outputs ? f.outputs() : f.inputs();
        for (Parameter p : list) {
            var n = params.addObject();
            n.put("name", p.name());
            n.put("type", p.type().canonicalName());
        }
    }

    @Override
    public synchronized List<FunctionDefinition> load() {
        // A leftover temp means the previous write crashed before the atomic move.
        deleteIfExistsQuietly(tmp);
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CodecException(ErrorCode.PERSISTENCE_ERROR,
                    "registry file is not readable: " + file + ": " + e.getMessage(), e);
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "registry file is empty or not a JSON object: " + file);
            }
            int version = readVersion(root);
            JsonNode functions = root.get("functions");
            if (functions == null || !functions.isArray()) {
                throw new CodecException(ErrorCode.CORRUPTED_STATE,
                        "registry file missing 'functions' array: " + file);
            }
            if (version >= FORMAT_VERSION) {
                verifyChecksum(root, functions);
            }
            List<FunctionDefinition> result = new ArrayList<>();
            for (JsonNode fn : functions) {
                result.add(readFunction(fn));
            }
            return result;
        } catch (CodecException e) {
            throw e;
        } catch (IOException e) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file is unreadable/corrupt: " + file + ": " + e.getMessage(), e);
        }
    }

    private int readVersion(JsonNode root) {
        JsonNode versionNode = root.get("version");
        if (versionNode == null || !versionNode.isNumber() || !versionNode.canConvertToInt()) {
            return LEGACY_FORMAT_VERSION;
        }
        return versionNode.intValue();
    }

    private void verifyChecksum(JsonNode root, JsonNode functions) {
        JsonNode checksumNode = root.get("checksum");
        if (checksumNode == null || !checksumNode.isTextual()) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file version " + FORMAT_VERSION
                            + " is missing its integrity checksum; the state was not fully persisted: "
                            + file);
        }
        String expected = checksumNode.asText();
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file carries a malformed integrity checksum: " + file);
        }
        final String actual;
        try {
            actual = sha256Hex(compactMapper.writeValueAsBytes(functions));
        } catch (IOException e) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "could not canonicalize registry content for verification: " + file, e);
        }
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry integrity checksum mismatch; the state was not fully persisted: "
                            + file);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest not available", e);
        }
    }

    private static void deleteIfExistsQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort: a stale temp is recreated with TRUNCATE_EXISTING on the next
            // write and ignored on load, so it can never block recovery or registration.
        }
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
            AbiType type = AbiTypeParser.parse(typeNode.asText());
            JsonNode pn = p.get("name");
            params.add(new Parameter(pn != null && pn.isTextual() ? pn.asText() : "", type));
        }
        return params;
    }
}
