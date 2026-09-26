package com.github.guoyyit.contractabicodecjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 编解码服务配置项。
 */
@ConfigurationProperties(prefix = "codec")
public class CodecProperties {

    /** 单次调用输入字节数上限 */
    private int maxInputBytes = 64 * 1024;

    /** 类型/值嵌套深度上限 */
    private int maxDepth = 32;

    /** 布局与选择器缓存容量上限 */
    private int cacheCapacity = 1024;

    /** 注册表持久化文件路径 */
    private String storeFile = "data/registry.bin";

    /** 是否在注册表变更时自动持久化 */
    private boolean persistOnChange = true;

    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int v) { this.maxInputBytes = v; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int v) { this.maxDepth = v; }
    public int getCacheCapacity() { return cacheCapacity; }
    public void setCacheCapacity(int v) { this.cacheCapacity = v; }
    public String getStoreFile() { return storeFile; }
    public void setStoreFile(String v) { this.storeFile = v; }
    public boolean isPersistOnChange() { return persistOnChange; }
    public void setPersistOnChange(boolean v) { this.persistOnChange = v; }
}
