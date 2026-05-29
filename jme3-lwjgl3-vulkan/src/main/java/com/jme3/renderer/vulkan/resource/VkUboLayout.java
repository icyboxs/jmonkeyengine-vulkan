package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.reflection.VkReflectionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Vulkan UBO (Uniform Buffer Object) 内存布局蓝图。
 * <p>
 * 核心作用：
 * 在 Vulkan 中，向 Shader 传递数据不能像 OpenGL 那样根据变量名直接赋值，
 * 而是需要向一块连续的显存（UBO）中按严格的字节偏移量（Byte Offset）写入二进制数据。
 * 这个类充当了“翻译字典”，记录了 Shader 中每个变量在内存块中的确切起始位置和大小。
 * <p>
 * 特性：
 * 全面支持任意着色器自定义变量（全动态数据驱动），底层数据由 SPIR-V 反射系统提供。
 */
public final class VkUboLayout {

    /**
     * 描述 UBO 中单个变量（字段）的元数据。
     */
    public static final class UboField {
        /** 变量在 Shader 中的名称，例如 "m_Color" 或 "g_WorldViewProjectionMatrix" */
        public final String name;
        /** 该变量在 UBO 内存块中的起始字节偏移量 */
        public final int offset;
        /** 该变量占用的字节大小 */
        public final int size;

        public UboField(String name, int offset, int size) {
            this.name = name;
            this.offset = offset;
            this.size = size;
        }
    }

    /** 当前 UBO 中包含的所有变量的映射表 */
    public final UboField[] fields;
    
    /** * 经过 Vulkan 硬件对齐后的 UBO 切片总大小（字节）。
     * 对于 Dynamic UBO，Vulkan 通常要求内存偏移量必须是 `minUniformBufferOffsetAlignment`
     * (通常是 256 字节) 的整数倍。
     */
    public final int sliceSize;

    // ==========================================
    // 高频变量的 O(1) 极速访问缓存 (Fast Path)
    // 渲染循环中每帧都会更新时间或分辨率，缓存偏移量可以避免高频的字符串查找开销
    // ==========================================
    
    /** 内置变量 "g_Resolution" 的字节偏移量，如果 Shader 未声明则为 -1 */
    public final int offResolution;
    /** 内置变量 "g_Time" 的字节偏移量，如果 Shader 未声明则为 -1 */
    public final int offTime;

    /**
     * 内部构造函数，外部请使用静态工厂方法创建。
     */
    public VkUboLayout(UboField[] fields, int sliceSize, int offResolution, int offTime) {
        this.fields = fields;
        this.sliceSize = sliceSize;
        this.offResolution = offResolution;
        this.offTime = offTime;
    }

    /**
     * 创建一个空的布局蓝图。
     * 用于兜底处理那些不需要任何 Uniform 输入的着色器。
     */
    public static VkUboLayout empty() {
        return new VkUboLayout(new UboField[0], 16, -1, -1);
    }

    /**
     * 创建 JME3 标准的固定基础布局（兜底方案）。
     * <p>
     * 当 SPIR-V 反射系统未命中或出错时，退回到这个硬编码的布局。
     * 这保证了即使反射失败，基本的模型渲染（包含 WVP 矩阵、颜色、时间等）依然能正常工作。
     */
    public static VkUboLayout fixedStage1() {
        return new VkUboLayout(
            new UboField[]{
                new UboField("g_WorldViewProjectionMatrix", 0, 64), // Matrix4f 占 64 字节
                new UboField("m_Color", 64, 16),                    // Vector4f / ColorRGBA 占 16 字节
                new UboField("g_Resolution", 80, 16),
                new UboField("g_Mouse", 96, 16),
                new UboField("g_Time", 112, 16)
            },
            256, // 强制对齐到 256 字节
            80,  // offResolution 的偏移
            112  // offTime 的偏移
        );
    }

    /**
     * 【核心方法】基于 SPIR-V 反射结果，动态生成内存布局蓝图。
     * <p>
     * 无论开发者在材质 Shader 中写了多少自定义的 uniform 变量，
     * 该方法都能准确捕获它们的内存偏移和布局。
     *
     * @param rr SPIR-V 字节码的反射解析结果
     * @return 匹配当前着色器的精确 UBO 内存布局
     */
    public static VkUboLayout fromReflection(VkReflectionResult rr) {
        // 如果反射结果为空或没有 UBO 成员，退回到默认的硬编码布局
        if (rr == null || rr.uboMembers == null || rr.uboMembers.isEmpty()) {
            return fixedStage1();
        }

        int maxEnd = 0;
        int offRes = -1;
        int offTime = -1;
        boolean hasWvp = false; // 用于校验是否是一个合法的 3D 渲染 Shader
        
        List<UboField> fieldList = new ArrayList<>();

        // 遍历反射出的所有 UBO 成员
        for (VkReflectionResult.UboMember m : rr.uboMembers) {
            if (m == null || m.memberName == null) continue;

            String n = m.memberName;
            // 如果反射未能获取大小，安全兜底假定为 16 字节 (vec4)
            int size = (m.size > 0) ? m.size : 16;
            
            // 拦截并缓存高频内置变量的地址
            if ("g_WorldViewProjectionMatrix".equals(n)) hasWvp = true;
            else if ("g_Resolution".equals(n)) offRes = m.offset;
            else if ("g_Time".equals(n)) offTime = m.offset;

            // 无差别记录所有反射出的变量名称和内存坐标
            fieldList.add(new UboField(n, m.offset, size));

            // 追踪当前 UBO 数据块占用的最大内存边界
            maxEnd = Math.max(maxEnd, m.offset + size);
        }

        // 如果连 WVP 矩阵都没有，说明可能解析错误或不是常规管线 Shader，触发兜底
        if (!hasWvp) {
            return fixedStage1();
        }

        // 计算 UBO 切片大小：确保最小分配 256 字节
        int rawSize = Math.max(maxEnd, 256);
        // 【关键】Vulkan 规范：Dynamic UBO 的偏移切片必须对齐到显卡规定的倍数（绝大多数为 256）
        int slice = alignUp(rawSize, 256);

        return new VkUboLayout(fieldList.toArray(new UboField[0]), slice, offRes, offTime);
    }

    /**
     * 将给定的数值 v 向上对齐到 a 的整数倍。
     * 例如：alignUp(130, 256) 将返回 256；alignUp(300, 256) 将返回 512。
     */
    private static int alignUp(int v, int a) {
        if (a <= 0) return v;
        return ((v + a - 1) / a) * a;
    }

    @Override
    public String toString() {
        return "VkUboLayout{fields=" + fields.length + ", sliceSize=" + sliceSize + "}";
    }
}