package com.jme3.renderer.vulkan.reflection;

import java.util.Objects;

public final class BindingPlanEntry {

    public final int binding;
    public final String descriptorType;
    public final ResourceSemantic semantic;
    public final boolean dynamic;
    public final int stageFlags;
    public final String name;

    public BindingPlanEntry(int binding,
                            String descriptorType,
                            ResourceSemantic semantic,
                            boolean dynamic,
                            int stageFlags,
                            String name) {
        this.binding = binding;
        this.descriptorType = descriptorType;
        this.semantic = semantic;
        this.dynamic = dynamic;
        this.stageFlags = stageFlags;
        this.name = name;
    }

    @Override
    public String toString() {
        return "BindingPlanEntry{binding=" + binding
                + ", descriptorType=" + descriptorType
                + ", semantic=" + semantic
                + ", dynamic=" + dynamic
                + ", stageFlags=" + stageFlags
                + ", name=" + name + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BindingPlanEntry)) return false;
        BindingPlanEntry that = (BindingPlanEntry) o;
        return binding == that.binding
                && dynamic == that.dynamic
                && stageFlags == that.stageFlags
                && Objects.equals(descriptorType, that.descriptorType)
                && semantic == that.semantic
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(binding, descriptorType, semantic, dynamic, stageFlags, name);
    }
}
