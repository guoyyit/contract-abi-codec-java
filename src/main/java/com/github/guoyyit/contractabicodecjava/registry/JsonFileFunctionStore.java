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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON file implementation of {@link FunctionStore}.
 *
 * <p>Crash safety: a new state is written to {@code <file>.tmp}, flushed and fsynced,
 * then atomically moved over the destination (which is fsynced too). A crash at any
 * point therefore leaves either the previous complete file or the new complete file;
 * a leftover {@code .tmp} from a crashed write is ignored and removed on startup.
 */
public final class JsonFileFunctionStore implements FunctionStore {

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
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var root = mapper.createObjectNode();
            root.put("version", 1);
            var arr = root.putArray("functions");
            for (FunctionDefinition f : functions) {
                var node = arr.addObject();
                node.put("name", f.name());
                writeParams(node.putArray("inputs"), f);
                writeParams(node.putArray("outputs"), f, true);
            }
            byte[] bytes = mapper.writeValueAsBytes(root);

            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    tmp,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            } catch (IOException ignored) {
                // fsync is best effort; atomic move still guarantees no torn state.
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (var dir = Files.newDirectoryStream(parent == null ? Path.of(".") : parent)) {
                // touching the directory stream ensures it was opened; fsync of dir is not portable in JDK
            }
        } catch (IOException e) {
            throw new CodecException(ErrorCode.PERSISTENCE_ERROR,
                    "failed to persist registry to " + file + ": " + e.getMessage(), e);
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
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
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
        } catch (CodecException e) {
            throw e;
        } catch (IOException e) {
            throw new CodecException(ErrorCode.CORRUPTED_STATE,
                    "registry file is unreadable/corrupt: " + file + ": " + e.getMessage(), e);
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
