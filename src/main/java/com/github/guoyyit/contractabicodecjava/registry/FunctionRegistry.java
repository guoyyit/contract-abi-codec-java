package com.github.guoyyit.contractabicodecjava.registry;

import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.abi.AbiType;
import com.github.guoyyit.contractabicodecjava.abi.Keccak256;
import com.github.guoyyit.contractabicodecjava.abi.TypeParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 并发安全的函数注册表。
 *
 * 写操作（注册/移除/整体替换）在内部锁内原子完成并递增版本号；
 * 读操作通过 {@link #snapshot()} 获得不可变一致快照 —— 执行中的调用
 * 基于快照解析，不会观察到注册到一半的定义。
 */
public class FunctionRegistry {

    private final Object lock = new Object();

    /** 规范签名 -> 定义，保持插入序，保证诊断输出稳定。 */
    private LinkedHashMap<String, FunctionDefinition> bySignature = new LinkedHashMap<>();
    /** alias -> 规范签名 */
    private HashMap<String, String> aliasIndex = new HashMap<>();
    private long version = 0;

    public FunctionRegistry() {
    }

    /** 用给定定义集合整体替换（用于持久化恢复）。 */
    public void replaceAll(List<FunctionDefinition> definitions) {
        synchronized (lock) {
            LinkedHashMap<String, FunctionDefinition> sigs = new LinkedHashMap<>();
            HashMap<String, String> aliases = new HashMap<>();
            for (FunctionDefinition def : definitions) {
                if (sigs.put(def.canonicalSignature(), def) != null) {
                    throw new AbiException(AbiException.ErrorCode.INVALID_SIGNATURE,
                            "duplicate signature in replacement set: " + def.canonicalSignature());
                }
                if (def.alias() != null && aliases.put(def.alias(), def.canonicalSignature()) != null) {
                    throw new AbiException(AbiException.ErrorCode.INVALID_SIGNATURE,
                            "duplicate alias in replacement set: " + def.alias());
                }
            }
            bySignature = sigs;
            aliasIndex = aliases;
            version++;
        }
    }

    /**
     * 注册一个函数定义。同签名重复注册视为幂等更新（可更换 alias 与输出类型）。
     *
     * @param name        函数名（标识符）
     * @param inputTypes  入参规范类型字符串列表
     * @param outputTypes 返回值规范类型字符串列表
     * @param alias       手动消歧别名；null 时默认使用函数名。别名必须全局唯一。
     */
    public FunctionDefinition register(String name, List<String> inputTypes,
                                       List<String> outputTypes, String alias) {
        if (name == null || name.isBlank() || !name.chars().allMatch(c ->
                Character.isLetterOrDigit(c) || c == '_')) {
            throw new AbiException(AbiException.ErrorCode.INVALID_SIGNATURE,
                    "invalid function name: " + name);
        }
        List<AbiType> inputs = new ArrayList<>();
        for (String t : inputTypes) {
            inputs.add(TypeParser.parse(t));
        }
        List<AbiType> outputs = new ArrayList<>();
        for (String t : outputTypes) {
            outputs.add(TypeParser.parse(t));
        }
        StringBuilder sig = new StringBuilder(name).append('(');
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) sig.append(',');
            sig.append(TypeParser.canonical(inputs.get(i)));
        }
        sig.append(')');
        String canonicalSignature = sig.toString();
        byte[] hash = Keccak256.digest(canonicalSignature.getBytes(StandardCharsets.UTF_8));
        byte[] selector = Arrays.copyOf(hash, 4);
        String effectiveAlias = (alias == null || alias.isBlank()) ? name : alias;

        synchronized (lock) {
            String aliasOwner = aliasIndex.get(effectiveAlias);
            if (aliasOwner != null && !aliasOwner.equals(canonicalSignature)) {
                throw new AbiException(AbiException.ErrorCode.INVALID_SIGNATURE,
                        "alias '" + effectiveAlias + "' already bound to " + aliasOwner);
            }
            FunctionDefinition old = bySignature.get(canonicalSignature);
            if (old != null && old.alias() != null && !old.alias().equals(effectiveAlias)) {
                aliasIndex.remove(old.alias());
            }
            FunctionDefinition def = new FunctionDefinition(
                    name, canonicalSignature,
                    List.copyOf(inputs), List.copyOf(outputs),
                    selector, effectiveAlias);
            bySignature.put(canonicalSignature, def);
            aliasIndex.put(effectiveAlias, canonicalSignature);
            version++;
            return def;
        }
    }

    public boolean remove(String canonicalSignature) {
        synchronized (lock) {
            FunctionDefinition removed = bySignature.remove(canonicalSignature);
            if (removed == null) {
                return false;
            }
            if (removed.alias() != null) {
                aliasIndex.remove(removed.alias());
            }
            version++;
            return true;
        }
    }

    /** 获取当前一致快照；快照内容在获取后不受后续注册影响。 */
    public RegistrySnapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(version, bySignature, aliasIndex);
        }
    }

    public long version() {
        synchronized (lock) {
            return version;
        }
    }

    // ---------------------------------------------------------------- 快照

    private static final class Snapshot implements RegistrySnapshot {
        private final long version;
        private final Map<String, FunctionDefinition> bySignature;
        private final Map<String, String> aliasIndex;
        /** 选择器 hex -> 该选择器下的签名列表（排序，保证诊断稳定）。 */
        private final Map<String, List<String>> bySelector;

        Snapshot(long version, Map<String, FunctionDefinition> sigs, Map<String, String> aliases) {
            this.version = version;
            this.bySignature = Collections.unmodifiableMap(new LinkedHashMap<>(sigs));
            this.aliasIndex = Collections.unmodifiableMap(new HashMap<>(aliases));
            Map<String, List<String>> grouped = new TreeMap<>();
            for (FunctionDefinition def : sigs.values()) {
                grouped.computeIfAbsent(def.selectorHex(), k -> new ArrayList<>())
                        .add(def.canonicalSignature());
            }
            Map<String, List<String>> immutable = new TreeMap<>();
            grouped.forEach((k, v) -> {
                Collections.sort(v);
                immutable.put(k, Collections.unmodifiableList(v));
            });
            this.bySelector = Collections.unmodifiableMap(immutable);
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public List<FunctionDefinition> list() {
            return List.copyOf(bySignature.values());
        }

        @Override
        public Optional<FunctionDefinition> resolve(byte[] selector, String alias) {
            if (alias != null && !alias.isBlank()) {
                String sig = aliasIndex.get(alias);
                return sig == null ? Optional.empty() : Optional.of(bySignature.get(sig));
            }
            if (selector == null || selector.length != 4) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "selector must be exactly 4 bytes");
            }
            String hex = Keccak256.toHex(selector);
            List<String> sigs = bySelector.get(hex);
            if (sigs == null || sigs.isEmpty()) {
                return Optional.empty();
            }
            if (sigs.size() > 1) {
                throw new AbiException(AbiException.ErrorCode.SELECTOR_COLLISION,
                        "selector " + hex + " is ambiguous across " + sigs
                                + "; pass an alias to disambiguate");
            }
            return Optional.of(bySignature.get(sigs.get(0)));
        }

        @Override
        public Optional<FunctionDefinition> resolveBySignature(String canonicalSignature) {
            return Optional.ofNullable(bySignature.get(canonicalSignature));
        }

        @Override
        public List<SelectorCollision> collisions() {
            List<SelectorCollision> out = new ArrayList<>();
            bySelector.forEach((hex, sigs) -> {
                if (sigs.size() > 1) {
                    out.add(new SelectorCollision(hex, sigs));
                }
            });
            return List.copyOf(out);
        }
    }
}
