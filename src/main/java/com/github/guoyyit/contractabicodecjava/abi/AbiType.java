package com.github.guoyyit.contractabicodecjava.abi;

import java.util.List;

/**
 * ABI 类型模型。密封层级，仅允许下列规范类型。
 */
public sealed interface AbiType {

    /** 无符号整数 uintN，8 <= N <= 256 且 N % 8 == 0 */
    record UIntType(int bits) implements AbiType {}

    /** 有符号整数 intN，8 <= N <= 256 且 N % 8 == 0 */
    record IntType(int bits) implements AbiType {}

    /** 布尔 */
    record BoolType() implements AbiType {}

    /** 地址（160 位，按 uint160 编码） */
    record AddressType() implements AbiType {}

    /** 定长字节 bytesN，1 <= N <= 32 */
    record FixedBytesType(int size) implements AbiType {}

    /** 动态字节串 bytes */
    record DynamicBytesType() implements AbiType {}

    /** 字符串 string（UTF-8） */
    record StringType() implements AbiType {}

    /** 静态数组 T[k]，k >= 1 */
    record StaticArrayType(AbiType elementType, int length) implements AbiType {}

    /** 动态数组 T[] */
    record DynamicArrayType(AbiType elementType) implements AbiType {}

    /** 元组 (T1,...,Tn)，n >= 1 */
    record TupleType(List<AbiType> components) implements AbiType {}
}
