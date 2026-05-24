#version 450
#define VULKAN_NATIVE 1

#import "Common/ShaderLib/VKGLSLCompat.glsllib"
#import "Common/ShaderLib/VKSkinning.glsllib"
#import "Common/ShaderLib/VKInstancing.glsllib"
#import "Common/ShaderLib/VKMorphAnim.glsllib"

#if defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

// ==========================================
// 1. 顶点输入 
// ==========================================
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec4 inColor;      
layout(location = 3) in vec2 inTexCoord2;  

// ==========================================
// 2. 顶点输出
// ==========================================
layout(location = 0) out vec2 texCoord1;
layout(location = 1) out vec2 texCoord2;
layout(location = 2) out vec4 vertColor;

// ==========================================
// 3. 核心空间变换矩阵 
// 【修复】: 添加实例名 `transform` 以隔离全局命名空间
// ==========================================
layout(set = 0, binding = 0) uniform TransformData {
    mat4 g_WorldViewProjectionMatrix;
} transform; 

// ==========================================
// 4. 材质参数 
// 【修复】: 添加实例名 `mat` 
// ==========================================
layout(set = 1, binding = 0) uniform MaterialParameters {
    vec4 m_Color;
    float m_AlphaDiscardThreshold;
    float m_DesaturationValue;
    float m_PointSize;
} mat; 

void main() {
    texCoord1 = vec2(0.0);
    texCoord2 = vec2(0.0);
    vertColor = vec4(1.0);

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
        // 使用 mat.m_PointSize
        gl_PointSize = mat.m_PointSize;
    #endif

    vec4 modelSpacePos = vec4(inPosition, 1.0);

    #ifdef NUM_MORPH_TARGETS
        Morph_Compute(modelSpacePos);
    #endif

    #ifdef NUM_BONES
        Skinning_Compute(modelSpacePos);
    #endif

    // 使用 transform.g_WorldViewProjectionMatrix
    gl_Position = transform.g_WorldViewProjectionMatrix * modelSpacePos;
}