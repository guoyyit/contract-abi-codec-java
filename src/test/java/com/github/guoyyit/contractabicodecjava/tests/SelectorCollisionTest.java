package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import com.github.guoyyit.contractabicodecjava.model.SelectorCollision;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.FunctionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectorCollisionTest {

    private FunctionRegistry registry;

    /** Hash that collides: two chosen signatures share a forced 4-byte selector. */
    static SelectorHasher collisionHasher() {
        return sig -> {
            byte[] real = com.github.guoyyit.contractabicodecjava.crypto.Keccak256
                    .hash(sig.getBytes(StandardCharsets.UTF_8));
            String name = sig.substring(0, sig.indexOf('('));
            if (name.equals("aaa") || name.equals("bbb")) {
                return new byte[]{0x12, 0x34, 0x56, 0x78};
            }
            return new byte[]{real[0], real[1], real[2], real[3]};
        };
    }

    static final FunctionStore NOOP_STORE = new FunctionStore() {
        @Override public void persist(List<FunctionDefinition> functions) { }
        @Override public List<FunctionDefinition> load() { return List.of(); }
    };

    private FunctionDefinition fn(String name) {
        return new FunctionDefinition(name,
                List.of(new Parameter("to", new AbiType.Address())), List.of());
    }

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry(
                new SelectorCalculator(collisionHasher()),
                new TypeLayoutCache(16),
                NOOP_STORE);
    }

    @Test
    void computesTransferSelectorWithRealKeccak() {
        FunctionRegistry r = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(16), NOOP_STORE);
        FunctionDefinition transfer = new FunctionDefinition("transfer",
                List.of(new Parameter("to", new AbiType.Address()),
                        new Parameter("amount", new AbiType.UInt(256))),
                List.of(new Parameter("ok", new AbiType.Bool())));
        FunctionDefinition registered = r.register(transfer);
        assertEquals("0xa9059cbb", registered.selector());
    }

    @Test
    void detectsAndStablyReportsCollision() {
        registry.register(fn("aaa"));
        registry.register(fn("bbb"));
        List<SelectorCollision> collisions = registry.collisions();
        assertEquals(1, collisions.size());
        SelectorCollision c = collisions.get(0);
        assertEquals("0x12345678", c.selector());
        // Stable, lexicographic ordering.
        assertEquals(List.of("aaa(address)", "bbb(address)"), c.signatures());
    }

    @Test
    void collisionIsStableAcrossRegistrations() {
        registry.register(fn("bbb"));
        registry.register(fn("aaa"));
        List<String> first = registry.collisions().get(0).signatures();
        registry.register(fn("zzz")); // non-colliding
        List<String> second = registry.collisions().get(0).signatures();
        assertEquals(first, second);
    }

    @Test
    void resolvingCollidingSelectorThrows() {
        registry.register(fn("aaa"));
        registry.register(fn("bbb"));
        CodecException e = assertThrows(CodecException.class,
                () -> registry.resolveBySelector("0x12345678"));
        assertEquals(ErrorCode.SELECTOR_COLLISION, e.code());
        assertTrue(e.getMessage().contains("aaa(address)"));
        assertTrue(e.getMessage().contains("bbb(address)"));
    }

    @Test
    void manualAliasDisambiguatesCollision() {
        registry.register(fn("aaa"));
        registry.register(fn("bbb"));
        registry.assignAlias("send-via-aaa", "aaa(address)");
        FunctionDefinition resolved = registry.resolveBySelector("send-via-aaa");
        assertEquals("aaa", resolved.name());
        // The other function is still reachable by its own unambiguous alias.
        registry.assignAlias("send-via-bbb", "bbb(address)");
        assertEquals("bbb", registry.resolveBySelector("send-via-bbb").name());
    }

    @Test
    void sameSignatureReRegistersWithoutCollision() {
        registry.register(fn("aaa"));
        registry.register(fn("aaa")); // idempotent upsert
        assertEquals(0, registry.collisions().size());
    }
}
