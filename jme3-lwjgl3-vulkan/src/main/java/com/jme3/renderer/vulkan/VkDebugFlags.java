package com.jme3.renderer.vulkan;

/**
 * Vulkan backend debug feature flags.
 *
 * 约定：
 * - 默认全部 false（生产/常规开发更安全）
 * - 临时开关集中维护，避免散落在各个 manager
 * @author icyboxs
 */
public final class VkDebugFlags {

    private VkDebugFlags() {
    }

    /** 关闭 material cache：每次 draw 都分配/写 descriptor set（高开销，仅用于诊断） */
    public static final boolean DISABLE_MATERIAL_CACHE = false;

    /** 强制覆盖 reflection write plan（用于验证错绑场景） */
    public static final boolean OVERRIDE_WRITE_PLAN = false;

    /** 反射兼容路径（hash-only）是否输出详细日志 */
    public static final boolean REFLECTION_COMPAT_VERBOSE_LOG = true;
    
    /** 开关：是否在控制台打印完整 shader 源码（带行号） */
    public static final boolean SHADER_PRINT_SOURCE = true;

    /** 开关：是否仅在编译失败时打印（true=失败才打印，false=编译前也打印） */
    public static final boolean SHADER_PRINT_ONLY_ON_ERROR = false;

    /** 开关：编译前做 #if/#endif 配平检查 */
    public static final boolean SHADER_CHECK_PP_BALANCE = true;
}
