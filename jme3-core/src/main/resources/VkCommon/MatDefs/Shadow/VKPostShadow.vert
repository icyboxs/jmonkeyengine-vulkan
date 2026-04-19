#import "Common/ShaderLib/VKGLSLCompat.glsllib"
#import "Common/ShaderLib/VKInstancing.glsllib"
#import "Common/ShaderLib/VKSkinning.glsllib"
#import "Common/ShaderLib/VKMorphAnim.glsllib"

// -------- Vertex inputs --------
layout(location = 0) in vec3 inPosition;

#ifndef BACKFACE_SHADOWS
layout(location = 1) in vec3 inNormal;
#endif

#ifdef DISCARD_ALPHA
layout(location = 2) in vec2 inTexCoord;
#endif

// -------- Vertex outputs --------
layout(location = 0) out vec4 projCoord0;
layout(location = 1) out vec4 projCoord1;
layout(location = 2) out vec4 projCoord2;
layout(location = 3) out vec4 projCoord3;

#ifdef POINTLIGHT
layout(location = 4) out vec4 projCoord4;
layout(location = 5) out vec4 projCoord5;
layout(location = 6) out vec4 worldPos;
#else
#ifndef PSSM
layout(location = 6) out float lightDot;
#endif
#endif

#if defined(PSSM) || defined(FADE)
layout(location = 7) out float shadowPosition;
#endif

#ifdef DISCARD_ALPHA
layout(location = 8) out vec2 texCoord;
#endif

#ifndef BACKFACE_SHADOWS
layout(location = 9) out float nDotL;
#endif

// -------- Light uniforms (non-opaque -> UBO) --------
// set/binding请按你的引擎descriptor布局调整
layout(set = 2, binding = 0) uniform ShadowLightParams {
    mat4 m_LightViewProjectionMatrix0;
    mat4 m_LightViewProjectionMatrix1;
    mat4 m_LightViewProjectionMatrix2;
    mat4 m_LightViewProjectionMatrix3;

#ifdef POINTLIGHT
    mat4 m_LightViewProjectionMatrix4;
    mat4 m_LightViewProjectionMatrix5;
    vec3 m_LightPos;
#else
    vec3 m_LightDir;
    #ifndef PSSM
    vec3 m_LightPos;
    #endif
#endif
};

const mat4 biasMat = mat4(
    0.5, 0.0, 0.0, 0.0,
    0.0, 0.5, 0.0, 0.0,
    0.0, 0.0, 0.5, 0.0,
    0.5, 0.5, 0.5, 1.0
);

void main() {
    vec4 modelSpacePos = vec4(inPosition, 1.0);

#ifdef NUM_MORPH_TARGETS
    Morph_Compute(modelSpacePos);
#endif

#ifdef NUM_BONES
    Skinning_Compute(modelSpacePos);
#endif

    gl_Position = TransformWorldViewProjection(modelSpacePos);

#if defined(PSSM) || defined(FADE)
    shadowPosition = gl_Position.z;
#endif

    vec3 lightDir;
    vec4 wPos = TransformWorld(modelSpacePos);

#ifdef POINTLIGHT
    worldPos = wPos;
#endif

#ifdef DISCARD_ALPHA
    texCoord = inTexCoord;
#endif

    projCoord0 = biasMat * m_LightViewProjectionMatrix0 * wPos;
    projCoord1 = biasMat * m_LightViewProjectionMatrix1 * wPos;
    projCoord2 = biasMat * m_LightViewProjectionMatrix2 * wPos;
    projCoord3 = biasMat * m_LightViewProjectionMatrix3 * wPos;

#ifdef POINTLIGHT
    projCoord4 = biasMat * m_LightViewProjectionMatrix4 * wPos;
    projCoord5 = biasMat * m_LightViewProjectionMatrix5 * wPos;
#else
    #ifndef PSSM
    lightDir = wPos.xyz - m_LightPos;        // spot
    lightDot = dot(m_LightDir, lightDir);
    #endif
#endif

#ifndef BACKFACE_SHADOWS
    // 更合理写法：用 TransformWorldNormal；若你只想保持原逻辑可继续用TransformWorld(vec4(...,0))
    vec3 nrm = normalize(TransformWorldNormal(inNormal));

    #ifdef POINTLIGHT
        lightDir = wPos.xyz - m_LightPos;
    #else
        #ifdef PSSM
            lightDir = m_LightDir;
        #endif
    #endif

    nDotL = dot(nrm, lightDir);
#endif
}
