package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.reflection.VkReflectionResult;

/**
 * 描述 UBO slice 内部成员的 byte offset。
 */
public final class VkUboLayout {

    public final int offWvp;        // mat4
    public final int offColor;      // vec4
    public final int offResolution; // vec4
    public final int offMouse;      // vec4
    public final int offTime;       // vec4

    public final int sliceSize;     // aligned slice size (bytes)

    public VkUboLayout(int offWvp,
                       int offColor,
                       int offResolution,
                       int offMouse,
                       int offTime,
                       int sliceSize) {
        this.offWvp = offWvp;
        this.offColor = offColor;
        this.offResolution = offResolution;
        this.offMouse = offMouse;
        this.offTime = offTime;
        this.sliceSize = sliceSize;
    }

    public static VkUboLayout empty() {
        return new VkUboLayout(-1, -1, -1, -1, -1, 16);
    }

    public static VkUboLayout fixedStage1() {
        return new VkUboLayout(
                0,    // g_WorldViewProjectionMatrix
                64,   // m_Color
                80,   // g_Resolution
                96,   // g_Mouse
                112,  // g_Time
                256
        );
    }

    /**
     * 
     * 尽量从反射结果推导 UBO layout。如果找不到关键的 WVP，必须回退到 fixedStage1，
     * 否则 MVP 矩阵无法写入，会导致黑屏。
     */
    public static VkUboLayout fromReflection(VkReflectionResult rr) {
        if (rr == null || rr.uboMembers == null || rr.uboMembers.isEmpty()) {
            return fixedStage1();
        }

        int wvp = -1, color = -1, res = -1, mouse = -1, time = -1;
        int maxEnd = 0;

        for (VkReflectionResult.UboMember m : rr.uboMembers) {
            if (m == null || m.memberName == null) continue;

            String n = m.memberName;
            if ("g_WorldViewProjectionMatrix".equals(n)) wvp = m.offset;
            else if ("m_Color".equals(n)) color = m.offset;
            else if ("g_Resolution".equals(n)) res = m.offset;
            else if ("g_Mouse".equals(n)) mouse = m.offset;
            else if ("g_Time".equals(n)) time = m.offset;

            int size = (m.size > 0) ? m.size : 16;
            maxEnd = Math.max(maxEnd, m.offset + size);
        }

        //如果没找到 WVP 或 Color，说明反射库丢信息了，强制用兜底偏移
        if (wvp == -1 || color == -1) {
            return fixedStage1();
        }

        int rawSize = Math.max(maxEnd, 256);
        int slice = alignUp(rawSize, 256);

        return new VkUboLayout(wvp, color, res, mouse, time, slice);
    }

    private static int alignUp(int v, int a) {
        if (a <= 0) return v;
        return ((v + a - 1) / a) * a;
    }

    @Override
    public String toString() {
        return "VkUboLayout{WVP=" + offWvp
                + ", Color=" + offColor
                + ", Res=" + offResolution
                + ", Mouse=" + offMouse
                + ", Time=" + offTime
                + ", sliceSize=" + sliceSize
                + "}";
    }
}
