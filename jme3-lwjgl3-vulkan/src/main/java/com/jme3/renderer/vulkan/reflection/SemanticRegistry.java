package com.jme3.renderer.vulkan.reflection;

import java.util.HashMap;
import java.util.Map;

/**
 * 资源语义注册表 (SemanticRegistry)。
 * * 作用：
 * 负责将着色器 (Shader) 中反射出来的变量名或描述符类型，映射为引擎能够理解的“资源语义 (ResourceSemantic)”。
 * 渲染管线需要知道一个变量代表什么数据（比如是相机矩阵 UBO，还是普通的材质贴图），才能正确地为其绑定显存。
 * * 设计机制：
 * 1. 字典匹配 (Exact Match)：优先通过变量名进行精准映射。
 * 2. 启发式推断 (Heuristic Fallback)：如果不认识这个名字，则根据变量的类型和所在槽位 (Set/Binding) 进行合理猜测。
 */
public final class SemanticRegistry {

    // 内部存储规范化名称到语义映射的字典表，初始容量设为 64
    private static final Map<String, ResourceSemantic> DICT = new HashMap<>(64);

    // 静态初始化块：预先注册 JME3 引擎中常见的内置 Uniform Block 命名规则
    static {
        // jME3 原生默认的全局/每绘制对象数据块
        register("jmeuniforms", ResourceSemantic.PER_DRAW_UBO);
        // 自定义管线中常用的对象数据块命名
        register("perdraw",     ResourceSemantic.PER_DRAW_UBO);
        // 特定功能的参数块
        register("alphaparams", ResourceSemantic.ALPHA_PARAMS);
        register("desaturationparams", ResourceSemantic.DESATURATION_PARAMS);
    }

    /**
     * 动态注册新的语义映射。
     * 允许开发者在引擎初始化时，注入自定义的 Shader 变量命名规范，打破默认规则。
     *
     * @param normalizedName 规范化后的变量名（通常是转小写、去除了 "m_" 前缀等形式）
     * @param semantic       对应的资源语义枚举
     */
    public static void register(String normalizedName, ResourceSemantic semantic) {
        if (normalizedName != null && semantic != null) {
            DICT.put(normalizedName, semantic);
        }
    }

    /**
     * 解析并推断给定变量的最终语义。
     * * @param normalizedName 规范化后的变量名（如 "jmeuniforms", "diffusemap"）
     * @param descriptorType Vulkan 描述符类型（如 "UNIFORM_BUFFER", "COMBINED_IMAGE_SAMPLER"）
     * @param set            该变量所在的 Descriptor Set 索引
     * @param binding        该变量在 Set 中的 Binding 索引
     * @return 推断出的资源语义
     */
    public static ResourceSemantic resolveSemantic(String normalizedName, String descriptorType, int set, int binding) {
        // 第 1 步：查表精准匹配。如果字典里有记录（例如 "jmeuniforms"），直接返回明确的语义
        ResourceSemantic semantic = DICT.get(normalizedName);
        if (semantic != null) {
            return semantic;
        }

        // 第 2 步：启发式推断。如果字典里没有（比如开发者在 Shader 里随便起的名字），则通过类型和槽位来猜测
        if (descriptorType != null) {
            String typeUpper = descriptorType.toUpperCase();
            
            // 处理 UBO (Uniform Buffer Object) 类型
            if (typeUpper.contains("UNIFORM_BUFFER")) {
                // 启发式规则：通常 Set 0 的 Binding 0 被约定俗成地用来存放 MVP 矩阵等高频对象数据
                if (set == 0 && binding == 0) return ResourceSemantic.PER_DRAW_UBO;
                
                // 其他位置的 UBO 如果名字不认识，先标记为未知，交由后续的默认逻辑兜底处理
                return ResourceSemantic.UNKNOWN_UBO;
            }
            
            // 处理纹理采样器类型
            // 【核心修改】：废除了以前将采样器死板地细分为 COLOR_MAP, LIGHT_MAP 等枚举的做法。
            // 现在只要它是采样器，全部一视同仁地抽象为 SAMPLED_IMAGE。
            // 具体这张图该绑定哪个材质属性，已经移交到了 PlanDrivenSetResolver 中根据动态数组和物理槽位去精确匹配，大幅提升了灵活性。
            if (typeUpper.contains("COMBINED_IMAGE_SAMPLER")) {
                return ResourceSemantic.SAMPLED_IMAGE;
            }
        }
        
        // 既不是已知的 UBO，也不是采样器，返回未知采样器兜底，防止管线崩溃
        return ResourceSemantic.UNKNOWN_SAMPLER;
    }
}