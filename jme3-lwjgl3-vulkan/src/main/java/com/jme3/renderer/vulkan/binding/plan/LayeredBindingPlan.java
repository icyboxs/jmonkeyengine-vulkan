package com.jme3.renderer.vulkan.binding.plan;

/**
 * 
 * @author icyboxs
 */
public final class LayeredBindingPlan {
    public final int lowSetIndex;
    public final int mediumSetIndex;
    public final int highSetIndex; // 可为 -1 表示未启用

    public LayeredBindingPlan(int low, int medium, int high) {
        if (low < 0) throw new IllegalArgumentException("low < 0");
        if (medium < 0) throw new IllegalArgumentException("medium < 0");
        if (high < -1) throw new IllegalArgumentException("high < -1");
        this.lowSetIndex = low;
        this.mediumSetIndex = medium;
        this.highSetIndex = high;
    }

    public static LayeredBindingPlan from(FrequencyLayerRegistry r) {
        if (r == null) throw new IllegalArgumentException("registry is null");

        FrequencyLayerConfig low = r.get(UpdateFrequency.LOW);
        FrequencyLayerConfig med = r.get(UpdateFrequency.MEDIUM);
        FrequencyLayerConfig high = r.get(UpdateFrequency.HIGH);

        if (low == null) throw new IllegalStateException("LOW config missing");
        if (med == null) throw new IllegalStateException("MEDIUM config missing");

        int h = (high != null) ? high.setIndex : -1;
        return new LayeredBindingPlan(low.setIndex, med.setIndex, h);
    }
}
