#import "Common/ShaderLib/VKGLSLCompat.glsllib"
#import "Common/ShaderLib/VKInstancing.glsllib"
#import "Common/ShaderLib/VKSkinning.glsllib"
#import "Common/ShaderLib/VKMorphAnim.glsllib"

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(location = 0) out vec2 texCoord;

void main() {
    vec4 modelSpacePos = vec4(inPosition, 1.0);

#ifdef NUM_MORPH_TARGETS
    // 你原代码里 modelSpaceNorm 未定义，这里只做位置形变
    Morph_Compute(modelSpacePos, modelSpaceNorm);
#endif

#ifdef NUM_BONES
    Skinning_Compute(modelSpacePos);
#endif

    gl_Position = TransformWorldViewProjection(modelSpacePos);
    texCoord = inTexCoord;
}
