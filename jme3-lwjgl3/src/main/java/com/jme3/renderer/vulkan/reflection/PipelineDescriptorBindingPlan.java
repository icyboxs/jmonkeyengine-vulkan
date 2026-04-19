package com.jme3.renderer.vulkan.reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PipelineDescriptorBindingPlan {

    public final List<SetBindingPlan> sets;
    public final List<DynamicBindingRef> dynamicBindingsInOrder;

    public PipelineDescriptorBindingPlan(List<SetBindingPlan> sets,
                                         List<DynamicBindingRef> dynamicBindingsInOrder) {
        this.sets = sets == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(sets));
        this.dynamicBindingsInOrder = dynamicBindingsInOrder == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(dynamicBindingsInOrder));
    }

    public static PipelineDescriptorBindingPlan empty() {
        return new PipelineDescriptorBindingPlan(
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public SetBindingPlan getSetPlan(int setIndex) {
        for (SetBindingPlan s : sets) {
            if (s != null && s.setIndex == setIndex) {
                return s;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "PipelineDescriptorBindingPlan{sets=" + sets
                + ", dynamicBindingsInOrder=" + dynamicBindingsInOrder + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineDescriptorBindingPlan)) return false;
        PipelineDescriptorBindingPlan that = (PipelineDescriptorBindingPlan) o;
        return Objects.equals(sets, that.sets)
                && Objects.equals(dynamicBindingsInOrder, that.dynamicBindingsInOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sets, dynamicBindingsInOrder);
    }
}
