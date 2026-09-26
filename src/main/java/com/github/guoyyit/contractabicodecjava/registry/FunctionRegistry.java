package com.github.guoyyit.contractabicodecjava.registry;

import com.github.guoyyit.contractabicodecjava.encoding.CanonicalSignature;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.RegistrySnapshot;
import com.github.guoyyit.contractabicodecjava.model.SelectorCollision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent function registry.
 *
 * <p>All state lives in an immutable {@link RegistrySnapshot}, published through a
 * volatile reference and swapped atomically under an exclusive lock. Readers take the
 * current snapshot once per call and therefore can never observe a partially
 * registered state, even when many registrations run concurrently.
 *
 * <p>Selectors are indexed per 4-byte value; distinct canonical signatures sharing a
 * selector are reported as stable, sorted collisions. Manual aliases provide a
 * deterministic disambiguation entry point for colliding selectors.
 */
public final class FunctionRegistry {

    /** signature -> definition (selector attached). */
    private final Map<String, FunctionDefinition> bySignature = new ConcurrentHashMap<>();
    /** alias -> canonical signature (manual disambiguation), user provided. */
    private final Map<String, String> userAliases = new ConcurrentHashMap<>();

    private final SelectorCalculator selectorCalculator;
    private final TypeLayoutCache layoutCache;
    private final FunctionStore store;

    private volatile RegistrySnapshot snapshot;

    public FunctionRegistry(SelectorCalculator selectorCalculator,
                            TypeLayoutCache layoutCache,
                            FunctionStore store) {
        this.selectorCalculator = selectorCalculator;
        this.layoutCache = layoutCache;
        this.store = store;
        this.snapshot = RegistrySnapshot.empty(0);
        restore();
    }

    private void restore() {
        List<FunctionDefinition> loaded = store.load();
        if (loaded.isEmpty()) {
            return;
        }
        Map<String, FunctionDefinition> rebuilt = new TreeMap<>();
        for (FunctionDefinition f : loaded) {
            FunctionDefinition withSelector = f.withSelector(selectorCalculator.selector(f));
            rebuilt.put(CanonicalSignature.of(withSelector), withSelector);
        }
        bySignature.putAll(rebuilt);
        this.snapshot = rebuild(1L);
    }

    public FunctionDefinition register(FunctionDefinition function) {
        return registerAll(List.of(function)).get(0);
    }

    /** Registers a batch atomically: readers see none of it until all functions are stored. */
    public List<FunctionDefinition> registerAll(List<FunctionDefinition> functions) {
        if (functions == null || functions.isEmpty()) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE, "registerAll requires at least one function");
        }
        synchronized (this) {
            List<FunctionDefinition> assigned = new ArrayList<>(functions.size());
            for (FunctionDefinition f : functions) {
                CanonicalSignature.validateName(f.name());
                FunctionDefinition withSelector = f.withSelector(selectorCalculator.selector(f));
                bySignature.put(CanonicalSignature.of(withSelector), withSelector);
                assigned.add(withSelector);
            }
            persistOrThrow();
            layoutCache.invalidate();
            snapshot = rebuild(snapshot.version() + 1);
            return List.copyOf(assigned);
        }
    }

    private void persistOrThrow() {
        store.persist(List.copyOf(bySignature.values()));
    }

    /** Manually disambiguate a colliding selector (or declare any shortcut) for a signature. */
    public void assignAlias(String alias, String canonicalSignature) {
        if (alias == null || alias.isBlank()) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE, "alias is required");
        }
        synchronized (this) {
            FunctionDefinition target = bySignature.get(canonicalSignature);
            if (target == null) {
                throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND,
                        "cannot alias unknown signature: " + canonicalSignature);
            }
            userAliases.put(alias, canonicalSignature);
            persistOrThrow();
            snapshot = rebuild(snapshot.version() + 1);
        }
    }

    /** Resolve by canonical signature; for non-overloaded functions the bare name works too. */
    public FunctionDefinition resolveByName(String nameOrSignature) {
        RegistrySnapshot snap = snapshot;
        FunctionDefinition direct = snap.byName().get(nameOrSignature);
        if (direct != null) {
            return direct;
        }
        FunctionDefinition candidate = null;
        for (FunctionDefinition f : snap.byName().values()) {
            if (f.name().equals(nameOrSignature)) {
                if (candidate != null) {
                    throw new CodecException(ErrorCode.SELECTOR_COLLISION,
                            "overloaded function name '" + nameOrSignature
                                    + "' requires a full canonical signature");
                }
                candidate = f;
            }
        }
        if (candidate == null) {
            throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND,
                    "no function named: " + nameOrSignature);
        }
        return candidate;
    }

    /** Resolve by alias first, then by 0x-selector (colliding selectors must use an alias). */
    public FunctionDefinition resolveBySelector(String selectorOrAlias) {
        RegistrySnapshot snap = snapshot;
        String aliased = snap.aliases().get(selectorOrAlias);
        if (aliased != null) {
            FunctionDefinition f = snap.byName().get(aliased);
            if (f == null) {
                throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND,
                        "alias '" + selectorOrAlias + "' points to missing function " + aliased);
            }
            return f;
        }
        List<FunctionDefinition> collision = snap.collisions().get(normalizeSelector(selectorOrAlias));
        if (collision != null) {
            List<String> sigs = collision.stream()
                    .map(CanonicalSignature::of)
                    .sorted()
                    .toList();
            throw new CodecException(ErrorCode.SELECTOR_COLLISION,
                    "selector " + selectorOrAlias + " collides across " + sigs.size()
                            + " functions: " + sigs + "; use assignAlias to disambiguate");
        }
        FunctionDefinition f = snap.bySelector().get(normalizeSelector(selectorOrAlias));
        if (f == null) {
            throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND,
                    "no function with selector/alias: " + selectorOrAlias);
        }
        return f;
    }

    public List<SelectorCollision> collisions() {
        return snapshot.collisionList();
    }

    public RegistrySnapshot snapshot() {
        return snapshot;
    }

    private static String normalizeSelector(String selector) {
        if (selector == null) {
            throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND, "null selector");
        }
        return selector.startsWith("0x") || selector.startsWith("0X") ? selector.toLowerCase() : selector;
    }

    /** Builds a new immutable snapshot from the current signature/alias maps. */
    private RegistrySnapshot rebuild(long version) {
        Map<String, FunctionDefinition> byName = new TreeMap<>();
        Map<String, List<FunctionDefinition>> bySelectorGroup = new TreeMap<>();

        for (Map.Entry<String, FunctionDefinition> e : bySignature.entrySet()) {
            byName.put(e.getKey(), e.getValue());
            bySelectorGroup
                    .computeIfAbsent(e.getValue().selector(), k -> new ArrayList<>())
                    .add(e.getValue());
        }

        Map<String, FunctionDefinition> bySelector = new TreeMap<>();
        Map<String, List<FunctionDefinition>> collisions = new TreeMap<>();
        List<SelectorCollision> collisionList = new ArrayList<>();

        for (Map.Entry<String, List<FunctionDefinition>> e : bySelectorGroup.entrySet()) {
            List<FunctionDefinition> group = e.getValue();
            if (group.size() > 1) {
                group.sort(Comparator.comparing(CanonicalSignature::of));
                collisions.put(e.getKey(), List.copyOf(group));
                collisionList.add(new SelectorCollision(e.getKey(),
                        group.stream().map(CanonicalSignature::of).sorted().toList()));
            }
            // Deterministic representative (lexicographically smallest signature).
            FunctionDefinition representative = group.stream()
                    .min(Comparator.comparing(CanonicalSignature::of))
                    .orElseThrow();
            bySelector.put(e.getKey(), representative);
        }
        collisionList.sort(Comparator.comparing(SelectorCollision::selector));

        // Effective aliases: user aliases plus the bare function name when unambiguous.
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> bareNameToSignature = new LinkedHashMap<>();
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (String sig : byName.keySet()) {
            String bare = sig.substring(0, sig.indexOf('('));
            nameCounts.merge(bare, 1, Integer::sum);
            bareNameToSignature.put(bare, sig);
        }
        for (Map.Entry<String, String> e : userAliases.entrySet()) {
            if (byName.containsKey(e.getValue())) {
                aliases.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, Integer> e : nameCounts.entrySet()) {
            if (e.getValue() == 1) {
                aliases.putIfAbsent(e.getKey(), bareNameToSignature.get(e.getKey()));
            }
        }

        return new RegistrySnapshot(
                Map.copyOf(byName),
                Map.copyOf(bySelector),
                Map.copyOf(collisions),
                Map.copyOf(aliases),
                List.copyOf(collisionList),
                version);
    }
}
