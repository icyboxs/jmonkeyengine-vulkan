package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.reflection.VkReflectionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 描述 UBO slice 内部成员的 byte offset。
 * 全面支持任意着色器自定义变量（全动态数据驱动）。
 */
public final class VkUboLayout {

    public static final class UboField {
        public final String name;
        public final int offset;
        public final int size;

        public UboField(String name, int offset, int size) {
            this.name = name;
            this.offset = offset;
            this.size = size;
        }
    }

    public final UboField[] fields;
    public final int sliceSize;     // aligned slice size (bytes)

    // 系统保留高频变量偏移量
    public final int offResolution;
    public final int offTime;

    public VkUboLayout(UboField[] fields, int sliceSize, int offResolution, int offTime) {
        this.fields = fields;
        this.sliceSize = sliceSize;
        this.offResolution = offResolution;
        this.offTime = offTime;
    }

    public static VkUboLayout empty() {
        return new VkUboLayout(new UboField[0], 16, -1, -1);
    }

    public static VkUboLayout fixedStage1() {
        return new VkUboLayout(
            new UboField[]{
                new UboField("g_WorldViewProjectionMatrix", 0, 64),
                new UboField("m_Color", 64, 16),
                new UboField("g_Resolution", 80, 16),
                new UboField("g_Mouse", 96, 16),
                new UboField("g_Time", 112, 16)
            },
            256, 80, 112
        );
    }

    /**
     * 将 Shader 中的任意 UBO 成员收集为动态蓝图。
     */
    public static VkUboLayout fromReflection(VkReflectionResult rr) {
        if (rr == null || rr.uboMembers == null || rr.uboMembers.isEmpty()) {
            return fixedStage1();
        }

        int maxEnd = 0;
        int offRes = -1;
        int offTime = -1;
        boolean hasWvp = false;
        
        List<UboField> fieldList = new ArrayList<>();

        for (VkReflectionResult.UboMember m : rr.uboMembers) {
            if (m == null || m.memberName == null) continue;

            String n = m.memberName;
            int size = (m.size > 0) ? m.size : 16;
            
            if ("g_WorldViewProjectionMatrix".equals(n)) hasWvp = true;
            else if ("g_Resolution".equals(n)) offRes = m.offset;
            else if ("g_Time".equals(n)) offTime = m.offset;

            // 无差别记录所有反射出的变量坐标
            fieldList.add(new UboField(n, m.offset, size));

            maxEnd = Math.max(maxEnd, m.offset + size);
        }

        if (!hasWvp) {
            return fixedStage1();
        }

        int rawSize = Math.max(maxEnd, 256);
        int slice = alignUp(rawSize, 256);

        return new VkUboLayout(fieldList.toArray(new UboField[0]), slice, offRes, offTime);
    }

    private static int alignUp(int v, int a) {
        if (a <= 0) return v;
        return ((v + a - 1) / a) * a;
    }

    @Override
    public String toString() {
        return "VkUboLayout{fields=" + fields.length + ", sliceSize=" + sliceSize + "}";
    }
}