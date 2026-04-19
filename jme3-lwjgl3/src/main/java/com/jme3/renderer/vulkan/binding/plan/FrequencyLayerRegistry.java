package com.jme3.renderer.vulkan.binding.plan;

import java.util.EnumMap;
import java.util.Map;

/**
 * 分层注册表（默认 + 可自定义覆盖）。
 * @author icyboxs
 */
public final class FrequencyLayerRegistry {

    private final EnumMap<UpdateFrequency, FrequencyLayerConfig> cfg =
            new EnumMap<>(UpdateFrequency.class);

    public FrequencyLayerRegistry() {
        // 默认映射（可按需改）
        cfg.put(UpdateFrequency.LOW,
                new FrequencyLayerConfig(UpdateFrequency.LOW, 0, DescriptorCachePolicy.PERSISTENT));
        cfg.put(UpdateFrequency.MEDIUM,
                new FrequencyLayerConfig(UpdateFrequency.MEDIUM, 1, DescriptorCachePolicy.PER_FRAME));
        cfg.put(UpdateFrequency.HIGH,
                new FrequencyLayerConfig(UpdateFrequency.HIGH, 2, DescriptorCachePolicy.PER_FRAME));
    }

    public FrequencyLayerConfig get(UpdateFrequency f) {
        return cfg.get(f);
    }

    public void register(FrequencyLayerConfig c) {
        if (c == null) {
            throw new IllegalArgumentException("config is null");
        }
        cfg.put(c.frequency, c);
    }

    public Map<UpdateFrequency, FrequencyLayerConfig> snapshot() {
        return new EnumMap<>(cfg);
    }
}
