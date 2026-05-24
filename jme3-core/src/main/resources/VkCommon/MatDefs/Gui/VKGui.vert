#import "VkCommon/ShaderLib/VKGLSLCompat.glsllib"
#version 450 core

// 1. 将矩阵和颜色打包进 UBO (对应 j3md 中的 WorldViewProjectionMatrix 和 Color)
layout(set = 0, binding = 0) uniform PerDrawUniforms {
    mat4 g_WorldViewProjectionMatrix;
    vec4 m_Color;
};

// 2. 顶点输入 (Locations 必须按顺序排列)
layout(location = 0) in vec3 inPosition;

#ifdef TEXTURE
    layout(location = 1) in vec2 inTexCoord;
#endif

#ifdef VERTEX_COLOR
    // 对应 j3md 中的 VertexColor (UseVertexColor)
    layout(location = 2) in vec4 inColor;
#endif

// 3. 传给片段着色器的输出 (Varying)
layout(location = 0) out vec4 outColor;

#ifdef TEXTURE
    layout(location = 1) out vec2 outTexCoord;
#endif

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    
    #ifdef TEXTURE
        outTexCoord = inTexCoord;
    #endif
    
    #ifdef VERTEX_COLOR
        outColor = m_Color * inColor;
    #else
        outColor = m_Color;
    #endif
}