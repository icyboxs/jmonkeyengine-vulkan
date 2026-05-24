#version 450
#define VULKAN_NATIVE 1

#import "Common/ShaderLib/VKGLSLCompat.glsllib"
#if defined(HAS_GLOWMAP) || defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

// ==========================================
// 1. 片元输入
// ==========================================
layout(location = 0) in vec2 texCoord1;
layout(location = 1) in vec2 texCoord2;
layout(location = 2) in vec4 vertColor;

// ==========================================
// 2. 片元输出 
// ==========================================
layout(location = 0) out vec4 outFragColor;

// ==========================================
// 3. 统一的材质参数 UBO 
// 【修复】: 添加实例名 `mat`
// ==========================================
layout(set = 1, binding = 0) uniform MaterialParameters {
    vec4 m_Color;
    float m_AlphaDiscardThreshold;
    float m_DesaturationValue;
    float m_PointSize; 
} mat;

// ==========================================
// 4. 纹理采样器
// ==========================================
#ifdef HAS_COLORMAP
    layout(set = 1, binding = 1) uniform sampler2D m_ColorMap;
#endif

#ifdef HAS_LIGHTMAP
    layout(set = 1, binding = 2) uniform sampler2D m_LightMap;
#endif

void main() {
    vec4 color = vec4(1.0);

    #ifdef HAS_COLORMAP
        color *= texture(m_ColorMap, texCoord1);
    #endif

    #ifdef HAS_VERTEXCOLOR
        color *= vertColor;
    #endif

    #ifdef HAS_COLOR
        // 使用 mat.m_Color
        color *= mat.m_Color;
    #endif

    #ifdef HAS_LIGHTMAP
        #ifdef SEPARATE_TEXCOORD
            color.rgb *= texture(m_LightMap, texCoord2).rgb;
        #else
            color.rgb *= texture(m_LightMap, texCoord1).rgb;
        #endif
    #endif

    #if defined(DISCARD_ALPHA)
        // 使用 mat.m_AlphaDiscardThreshold
        if(color.a < mat.m_AlphaDiscardThreshold){
           discard;
        }
    #endif
    
    #ifdef DESATURATION
        vec3 gray = vec3(dot(vec3(0.2126, 0.7152, 0.0722), color.rgb));
        // 使用 mat.m_DesaturationValue
        color.rgb = mix(color.rgb, gray, mat.m_DesaturationValue);       
    #endif

    outFragColor = color;
}