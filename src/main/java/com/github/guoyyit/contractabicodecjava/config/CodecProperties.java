package com.github.guoyyit.contractabicodecjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration knobs: {@code codec.*}. */
@ConfigurationProperties(prefix = "codec")
public final class CodecProperties {
    private int maxInputBytes = 1 << 20; // 1 MiB
    private int maxNestingDepth = 32;
    private int layoutCacheCapacity = 1024;
    private String registryFile = "data/registry.json";
    private boolean persistenceEnabled = true;

    public int getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    public int getMaxNestingDepth() { return maxNestingDepth; }
    public void setMaxNestingDepth(int maxNestingDepth) { this.maxNestingDepth = maxNestingDepth; }
    public int getLayoutCacheCapacity() { return layoutCacheCapacity; }
    public void setLayoutCacheCapacity(int layoutCacheCapacity) { this.layoutCacheCapacity = layoutCacheCapacity; }
    public String getRegistryFile() { return registryFile; }
    public void setRegistryFile(String registryFile) { this.registryFile = registryFile; }
    public boolean isPersistenceEnabled() { return persistenceEnabled; }
    public void setPersistenceEnabled(boolean persistenceEnabled) { this.persistenceEnabled = persistenceEnabled; }
}
