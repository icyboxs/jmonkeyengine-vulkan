#version 450
#define VULKAN_NATIVE 1

#import "Common/ShaderLib/VKGLSLCompat.glsllib"
#import "Common/ShaderLib/VKSkinning.glsllib"
#import "Common/ShaderLib/VKInstancing.glsllib"   // 先禁用，避免矩阵UBO冲突
#import "Common/ShaderLib/VKMorphAnim.glsllib"

#if defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

// ==========================================
// 1. 顶点输入 (Vulkan 必须指定 Location 索引)
// ==========================================
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec2 inTexCoord2;
layout(location = 3) in vec4 inColor;

// ==========================================
// 2. 顶点输出 (传递给 Fragment Shader，必须按顺序排 Location)
// ==========================================
layout(location = 0) out vec2 texCoord1;
layout(location = 1) out vec2 texCoord2;
layout(location = 2) out vec4 vertColor;

// ==========================================
// 3. 核心空间变换矩阵 (对应 PER_DRAW_UBO)
// Vulkan 引擎通常约定 Set 0, Binding 0 为逐物体的数据块
// ==========================================
layout(set = 0, binding = 0) uniform TransformData {
    mat4 g_WorldViewProjectionMatrix;
};

// ==========================================
// 4. 材质参数 (对应 MATERIAL_UBO)
// ==========================================
#ifdef HAS_POINTSIZE
layout(set = 1, binding = 0) uniform MaterialParameters {
    float m_PointSize;
};
#endif

void main() {
    #ifdef NEED_TEXCOORD1
        texCoord1 = inTexCoord;
    #endif

    #ifdef SEPARATE_TEXCOORD
        texCoord2 = inTexCoord2;
    #endif

    #ifdef HAS_VERTEXCOLOR
        vertColor = inColor;
    #endif

    #ifdef HAS_POINTSIZE
        gl_PointSize = m_PointSize;
    #endif

    vec4 modelSpacePos = vec4(inPosition, 1.0);

    #ifdef NUM_MORPH_TARGETS
        Morph_Compute(modelSpacePos);
    #endif

    #ifdef NUM_BONES
        Skinning_Compute(modelSpacePos);
    #endif

    // [修改点 1] 直接使用原生的矩阵乘法，废弃旧的 OpenGL 宏
    gl_Position = g_WorldViewProjectionMatrix * modelSpacePos;
    
    // [修改点 2] Vulkan 的 Y 轴与 OpenGL 是相反的 (Vulkan 向下为正)
    // 如果你的 Java 端投影矩阵 (ProjectionMatrix) 没有做 Vulkan 专用的 Y 轴翻转，
    // 请取消注释下面这行代码来修正画面倒置的问题：
    // gl_Position.y = -gl_Position.y;
}