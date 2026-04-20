package com.jme3.renderer.vulkan.reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SetBindingPlan {

    public final int setIndex;
    public final List<BindingPlanEntry> bindings;
    public final CacheClass cacheClass;

    public SetBindingPlan(int setIndex,
                          List<BindingPlanEntry> bindings,
                          CacheClass cacheClass) {
        this.setIndex = setIndex;
        this.bindings = bindings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(bindings));
        this.cacheClass = cacheClass != null ? cacheClass : CacheClass.NONE;
    }

    public boolean hasDynamicBinding() {
        for (BindingPlanEntry e : bindings) {
            if (e != null && e.dynamic) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSemantic(ResourceSemantic semantic) {
        if (semantic == null) return false;
        for (BindingPlanEntry e : bindings) {
            if (e != null && e.semantic == semantic) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "SetBindingPlan{setIndex=" + setIndex
                + ", cacheClass=" + cacheClass
                + ", bindings=" + bindings + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SetBindingPlan)) return false;
        SetBindingPlan that = (SetBindingPlan) o;
        return setIndex == that.setIndex
                && Objects.equals(bindings, that.bindings)
                && cacheClass == that.cacheClass;
    }

    @Override
    public int hashCode() {
        return Objects.hash(setIndex, bindings, cacheClass);
    }
}
