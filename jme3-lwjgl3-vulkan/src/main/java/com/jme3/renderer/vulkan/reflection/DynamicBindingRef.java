package com.jme3.renderer.vulkan.reflection;

import java.util.Objects;

public final class DynamicBindingRef {

    public final int setIndex;
    public final int binding;

    public DynamicBindingRef(int setIndex, int binding) {
        this.setIndex = setIndex;
        this.binding = binding;
    }

    @Override
    public String toString() {
        return "DynamicBindingRef{setIndex=" + setIndex + ", binding=" + binding + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DynamicBindingRef)) return false;
        DynamicBindingRef that = (DynamicBindingRef) o;
        return setIndex == that.setIndex && binding == that.binding;
    }

    @Override
    public int hashCode() {
        return Objects.hash(setIndex, binding);
    }
}
