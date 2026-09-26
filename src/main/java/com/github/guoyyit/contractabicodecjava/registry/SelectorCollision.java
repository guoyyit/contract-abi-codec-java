package com.github.guoyyit.contractabicodecjava.registry;

import java.util.List;

/**
 * 选择器碰撞诊断信息：同一 4 字节选择器映射到的全部函数签名。
 */
public record SelectorCollision(String selectorHex, List<String> signatures) {

    public boolean isCollision() {
        return signatures.size() > 1;
    }
}
