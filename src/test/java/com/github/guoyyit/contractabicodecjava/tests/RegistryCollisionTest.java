package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.registry.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.RegistrySnapshot;
import com.github.guoyyit.contractabicodecjava.registry.SelectorCollision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RegistryCollisionTest {

    // 真实碰撞对：两者 keccak256 前 4 字节均为 0x77dbd42e
    private static final String SIG_A = "f38491(uint256)";
    private static final String SIG_B = "f116643(uint256)";

    @Test
    void selectorMatchesKnownVector() {
        FunctionRegistry reg = new FunctionRegistry();
        FunctionDefinition def = reg.register("transfer",
                List.of("address", "uint256"), List.of("bool"), null);
        assertEquals("transfer(address,uint256)", def.canonicalSignature());
        assertEquals("0xa9059cbb", def.selectorHex());
        // 默认 alias 为函数名
        assertEquals("transfer", def.alias());
    }

    @Test
    void detectsCollisionAndReportsStably() {
        FunctionRegistry reg = new FunctionRegistry();
        reg.register("f38491", List.of("uint256"), List.of(), "alpha");
        reg.register("f116643", List.of("uint256"), List.of(), "beta");

        RegistrySnapshot snap = reg.snapshot();
        List<SelectorCollision> collisions = snap.collisions();
        assertEquals(1, collisions.size());
        SelectorCollision c = collisions.get(0);
        assertEquals("0x77dbd42e", c.selectorHex());
        assertTrue(c.isCollision());
        // 诊断输出稳定：签名排序固定
        assertEquals(List.of(SIG_A, SIG_B).stream().sorted().toList(), c.signatures());

        // 无 alias 解析碰撞选择器 -> 结构化错误
        byte[] selector = java.util.Arrays.copyOf(
                com.github.guoyyit.contractabicodecjava.abi.Keccak256.digest(
                        SIG_A.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 4);
        AbiException e = assertThrows(AbiException.class, () -> snap.resolve(selector, null));
        assertEquals(AbiException.ErrorCode.SELECTOR_COLLISION, e.code());
        assertTrue(e.getMessage().contains(SIG_A));
        assertTrue(e.getMessage().contains(SIG_B));

        // 手动消歧入口：按 alias 解析成功
        Optional<FunctionDefinition> a = snap.resolve(null, "alpha");
        assertTrue(a.isPresent());
        assertEquals(SIG_A, a.get().canonicalSignature());
        Optional<FunctionDefinition> b = snap.resolve(null, "beta");
        assertTrue(b.isPresent());
        assertEquals(SIG_B, b.get().canonicalSignature());
    }

    @Test
    void snapshotIsConsistentDuringRegistration() {
        FunctionRegistry reg = new FunctionRegistry();
        reg.register("a", List.of("uint256"), List.of(), null);
        RegistrySnapshot before = reg.snapshot();
        reg.register("b", List.of("bool"), List.of(), null);
        // 旧快照不受后续注册影响
        assertEquals(1, before.list().size());
        assertTrue(before.resolve(null, "b").isEmpty());
        assertEquals(2, reg.snapshot().list().size());
        assertNotEquals(before.version(), reg.snapshot().version());
    }

    @Test
    void duplicateRegistrationIsIdempotentUpdate() {
        FunctionRegistry reg = new FunctionRegistry();
        reg.register("f", List.of("uint256"), List.of(), null);
        long v1 = reg.version();
        FunctionDefinition again = reg.register("f", List.of("uint256"), List.of("bool"), null);
        assertEquals(1, reg.snapshot().list().size());
        assertEquals(1, again.outputs().size());
        assertTrue(reg.version() > v1);
    }

    @Test
    void aliasConflictRejected() {
        FunctionRegistry reg = new FunctionRegistry();
        reg.register("f", List.of(), List.of(), "shared");
        AbiException e = assertThrows(AbiException.class,
                () -> reg.register("g", List.of(), List.of(), "shared"));
        assertEquals(AbiException.ErrorCode.INVALID_SIGNATURE, e.code());
    }

    @Test
    void removeWorks() {
        FunctionRegistry reg = new FunctionRegistry();
        reg.register("f", List.of("uint256"), List.of(), null);
        assertTrue(reg.remove("f(uint256)"));
        assertFalse(reg.remove("f(uint256)"));
        assertEquals(0, reg.snapshot().list().size());
    }
}
