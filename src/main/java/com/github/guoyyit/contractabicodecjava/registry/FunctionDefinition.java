package com.github.guoyyit.contractabicodecjava.registry;

import com.github.guoyyit.contractabicodecjava.abi.AbiType;

import java.util.List;

/**
 * 已注册的函数定义。selector 为 4 字节函数选择器。
 * alias 为手动消歧名：同一选择器下多个定义共存时，调用方用 alias 指定。
 */
public record FunctionDefinition(
        String name,
        String canonicalSignature,
        List<AbiType> inputs,
        List<AbiType> outputs,
        byte[] selector,
        String alias) {

    public String selectorHex() {
        StringBuilder sb = new StringBuilder(10);
        sb.append("0x");
        for (byte b : selector) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
