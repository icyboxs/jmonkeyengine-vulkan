package com.jme3.renderer.vulkan.binding.plan;
/**
 * 
 * @author icyboxs
 */
public final class FrequencyLayerConfig {
    public final UpdateFrequency frequency;
    public final int setIndex;
    public final DescriptorCachePolicy cachePolicy;

    public FrequencyLayerConfig(UpdateFrequency frequency, int setIndex, DescriptorCachePolicy cachePolicy) {
        if (frequency == null) throw new IllegalArgumentException("frequency is null");
        if (setIndex < 0) throw new IllegalArgumentException("setIndex < 0");
        if (cachePolicy == null) throw new IllegalArgumentException("cachePolicy is null");
        this.frequency = frequency;
        this.setIndex = setIndex;
        this.cachePolicy = cachePolicy;
    }
}
