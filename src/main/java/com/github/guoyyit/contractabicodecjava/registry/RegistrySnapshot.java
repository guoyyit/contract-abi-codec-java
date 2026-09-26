package com.github.guoyyit.contractabicodecjava.registry;

import java.util.List;
import java.util.Optional;

/**
 * 注册表的不可变一致快照。执行中的调用始终基于快照，
 * 不会观察到注册到一半的定义。
 */
public interface RegistrySnapshot {

    long version();

    List<FunctionDefinition> list();

    /** 按选择器 + 可选 alias 解析。无 alias 且选择器唯一时直接返回；
     *  有碰撞且未指定 alias 时抛 SELECTOR_COLLISION。 */
    Optional<FunctionDefinition> resolve(byte[] selector, String alias);

    /** 按规范签名精确解析。 */
    Optional<FunctionDefinition> resolveBySignature(String canonicalSignature);

    List<SelectorCollision> collisions();
}
