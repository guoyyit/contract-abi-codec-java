package com.github.guoyyit.contractabicodecjava.service;

import com.github.guoyyit.contractabicodecjava.abi.AbiCodec;
import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.abi.AbiType;
import com.github.guoyyit.contractabicodecjava.abi.Keccak256;
import com.github.guoyyit.contractabicodecjava.abi.TypeParser;
import com.github.guoyyit.contractabicodecjava.cache.LayoutCache;
import com.github.guoyyit.contractabicodecjava.config.CodecProperties;
import com.github.guoyyit.contractabicodecjava.persist.RegistryStore;
import com.github.guoyyit.contractabicodecjava.registry.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.RegistrySnapshot;
import com.github.guoyyit.contractabicodecjava.registry.SelectorCollision;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 进程内编解码调用入口。
 *
 * 所有请求先经过资源上限校验（输入字节数、嵌套深度），超限快速失败，
 * 不进入编解码路径，因而不影响其他请求。执行中的调用基于注册表一致快照，
 * 缓存键携带快照版本，注册表更新后旧缓存自然失效。
 */
public class CodecService {

    private final CodecProperties props;
    private final FunctionRegistry registry;
    private final AbiCodec codec;
    private final LayoutCache cache;
    private final RegistryStore store;

    public CodecService(CodecProperties props) {
        this(props, new FunctionRegistry(),
                new LayoutCache(props.getCacheCapacity()),
                new RegistryStore(Path.of(props.getStoreFile())));
    }

    public CodecService(CodecProperties props, FunctionRegistry registry,
                        LayoutCache cache, RegistryStore store) {
        this.props = props;
        this.registry = registry;
        this.codec = new AbiCodec(props.getMaxDepth());
        this.cache = cache;
        this.store = store;
    }

    /** 从持久化文件恢复注册表（启动时调用一次）。 */
    public void recover() {
        store.cleanupTemp();
        List<FunctionDefinition> defs = store.load();
        if (!defs.isEmpty()) {
            registry.replaceAll(defs);
        }
    }

    // ---------------------------------------------------------------- 注册

    /** 注册函数定义，返回带选择器的完整定义。 */
    public FunctionDefinition register(String name, List<String> inputs, List<String> outputs, String alias) {
        // 快速失败：类型字符串本身不能超长，嵌套深度不能超限 —— 先校验再注册
        checkTypeStrings(inputs);
        checkTypeStrings(outputs);
        for (String t : inputs) {
            checkTypeDepth(TypeParser.parse(t), 1);
        }
        for (String t : outputs) {
            checkTypeDepth(TypeParser.parse(t), 1);
        }
        FunctionDefinition def = registry.register(name, inputs, outputs, alias);
        persistIfEnabled();
        return def;
    }

    public boolean remove(String canonicalSignature) {
        boolean removed = registry.remove(canonicalSignature);
        if (removed) {
            persistIfEnabled();
        }
        return removed;
    }

    // ---------------------------------------------------------------- 编码

    /** 按 alias 或规范签名编码一次函数调用（selector + 参数编码）。 */
    public byte[] encodeCall(String aliasOrSignature, List<Object> args) {
        RegistrySnapshot snap = registry.snapshot();
        FunctionDefinition def = resolve(snap, aliasOrSignature);
        long[] layout = layoutOf(def.inputs(), snap.version());
        byte[] argsEncoded = codec.encodeTuple(def.inputs(), args, layout);
        byte[] selector = selectorOf(def, snap.version());
        if ((long) selector.length + argsEncoded.length > props.getMaxInputBytes()) {
            throw new AbiException(AbiException.ErrorCode.INPUT_LIMIT_EXCEEDED,
                    "encoded call of " + (selector.length + argsEncoded.length)
                            + " bytes exceeds limit " + props.getMaxInputBytes());
        }
        byte[] out = new byte[selector.length + argsEncoded.length];
        System.arraycopy(selector, 0, out, 0, selector.length);
        System.arraycopy(argsEncoded, 0, out, selector.length, argsEncoded.length);
        return out;
    }

    /** 解码调用数据（前 4 字节选择器 + 参数），返回解析出的参数。 */
    public List<Object> decodeCall(byte[] calldata, String alias) {
        checkInputSize(calldata);
        if (calldata.length < 4) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS,
                    "calldata shorter than 4-byte selector: " + calldata.length);
        }
        RegistrySnapshot snap = registry.snapshot();
        byte[] selector = Arrays.copyOf(calldata, 4);
        FunctionDefinition def = snap.resolve(selector, alias)
                .orElseThrow(() -> new AbiException(AbiException.ErrorCode.UNKNOWN_SELECTOR,
                        "no function registered for selector " + Keccak256.toHex(selector)));
        long[] layout = layoutOf(def.inputs(), snap.version());
        byte[] argsData = Arrays.copyOfRange(calldata, 4, calldata.length);
        return codec.decodeTuple(def.inputs(), argsData, layout);
    }

    /** 按函数输出类型解码返回值。 */
    public List<Object> decodeResult(String aliasOrSignature, byte[] data) {
        checkInputSize(data);
        RegistrySnapshot snap = registry.snapshot();
        FunctionDefinition def = resolve(snap, aliasOrSignature);
        long[] layout = layoutOf(def.outputs(), snap.version());
        return codec.decodeTuple(def.outputs(), data, layout);
    }

    // ---------------------------------------------------------------- 查询

    public RegistrySnapshot snapshot() {
        return registry.snapshot();
    }

    public List<SelectorCollision> collisions() {
        return registry.snapshot().collisions();
    }

    public LayoutCache cache() {
        return cache;
    }

    public FunctionRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------- 内部

    private FunctionDefinition resolve(RegistrySnapshot snap, String aliasOrSignature) {
        if (aliasOrSignature == null || aliasOrSignature.isBlank()) {
            throw new AbiException(AbiException.ErrorCode.UNKNOWN_SELECTOR, "blank function reference");
        }
        if (aliasOrSignature.indexOf('(') > 0) {
            return snap.resolveBySignature(aliasOrSignature)
                    .orElseThrow(() -> new AbiException(AbiException.ErrorCode.UNKNOWN_SELECTOR,
                            "no function registered with signature " + aliasOrSignature));
        }
        return snap.resolve(null, aliasOrSignature)
                .orElseThrow(() -> new AbiException(AbiException.ErrorCode.UNKNOWN_SELECTOR,
                        "no function registered with alias " + aliasOrSignature));
    }

    private byte[] selectorOf(FunctionDefinition def, long version) {
        return cache.selector(def.canonicalSignature(), version).orElseGet(() -> {
            byte[] hash = Keccak256.digest(def.canonicalSignature().getBytes(StandardCharsets.UTF_8));
            byte[] selector = Arrays.copyOf(hash, 4);
            cache.putSelector(def.canonicalSignature(), version, selector);
            return selector;
        });
    }

    private long[] layoutOf(List<AbiType> types, long version) {
        return cache.headLayout(types, version).orElseGet(() -> {
            long[] layout = AbiCodec.computeHeadLayout(types);
            cache.putHeadLayout(types, version, layout);
            return layout;
        });
    }

    private void checkInputSize(byte[] data) {
        if (data == null) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS, "null input");
        }
        if (data.length > props.getMaxInputBytes()) {
            throw new AbiException(AbiException.ErrorCode.INPUT_LIMIT_EXCEEDED,
                    "input of " + data.length + " bytes exceeds limit " + props.getMaxInputBytes());
        }
    }

    private void checkTypeStrings(List<String> types) {
        for (String t : types) {
            if (t == null || t.length() > 4096) {
                throw new AbiException(AbiException.ErrorCode.INPUT_LIMIT_EXCEEDED,
                        "type string too long or null");
            }
        }
    }

    private void checkTypeDepth(AbiType t, int depth) {
        if (depth > props.getMaxDepth()) {
            throw new AbiException(AbiException.ErrorCode.DEPTH_LIMIT_EXCEEDED,
                    "type nesting exceeds limit " + props.getMaxDepth());
        }
        if (t instanceof AbiType.StaticArrayType a) {
            checkTypeDepth(a.elementType(), depth + 1);
        } else if (t instanceof AbiType.DynamicArrayType a) {
            checkTypeDepth(a.elementType(), depth + 1);
        } else if (t instanceof AbiType.TupleType tuple) {
            for (AbiType c : tuple.components()) {
                checkTypeDepth(c, depth + 1);
            }
        }
    }

    private void persistIfEnabled() {
        if (props.isPersistOnChange()) {
            store.save(registry.snapshot().list());
        }
    }
}
