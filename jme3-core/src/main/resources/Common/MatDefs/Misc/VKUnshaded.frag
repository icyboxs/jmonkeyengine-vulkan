#version 450
#define VULKAN_NATIVE 1

#import "Common/ShaderLib/VKGLSLCompat.glsllib"
#if defined(HAS_GLOWMAP) || defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

// ==========================================
// 1. 片元输入 (Location 与 Vertex Shader 的 out 必须一一对应)
// ==========================================
layout(location = 0) in vec2 texCoord1;
layout(location = 1) in vec2 texCoord2;
layout(location = 2) in vec4 vertColor;

// ==========================================
// 2. 片元输出 (自定义 Color Attachment)
// ==========================================
layout(location = 0) out vec4 outFragColor;

// ==========================================
// 3. 统一的材质参数 UBO (必须与 Vertex Shader 完全一致！)
// 我们把 m_PointSize 也加进来，即使片元着色器不用它，
// 也要占位以保证内存布局与 Vertex Shader 完美对齐。
// ==========================================
layout(set = 1, binding = 0) uniform MaterialParameters {
    vec4 m_Color;
    float m_AlphaDiscardThreshold;
    float m_DesaturationValue;
    float m_PointSize;  // 从顶点着色器借过来的占位符
};

// ==========================================
// 4. 纹理采样器 (物理槽位指定)
// ==========================================
#ifdef HAS_COLORMAP
    layout(set = 1, binding = 1) uniform sampler2D m_ColorMap;
#endif

#ifdef HAS_LIGHTMAP
    layout(set = 1, binding = 2) uniform sampler2D m_LightMap;
#endif

void main() {
    vec4 color = vec4(1.0);

    // Vulkan 推荐使用 texture()
    #ifdef HAS_COLORMAP
        color *= texture(m_ColorMap, texCoord1);     
    #endif

    #ifdef HAS_VERTEXCOLOR
        color *= vertColor;
    #endif

    #ifdef HAS_COLOR
        color *= m_Color;
    #endif

    #ifdef HAS_LIGHTMAP
        #ifdef SEPARATE_TEXCOORD
            color.rgb *= texture(m_LightMap, texCoord2).rgb;
        #else
            color.rgb *= texture(m_LightMap, texCoord1).rgb;
        #endif
    #endif

    // 透明度剔除
    #if defined(DISCARD_ALPHA)
        if(color.a < m_AlphaDiscardThreshold){
           discard;
        }
    #endif
    
    // 去色效果
    #ifdef DESATURATION
        vec3 gray = vec3(dot(vec3(0.2126, 0.7152, 0.0722), color.rgb));
        color.rgb = mix(color.rgb, gray, m_DesaturationValue);       
    #endif

    // 输出最终颜色
    outFragColor = color;
}