/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
#version 450

layout(location=0) out vec4 color;

layout(location=0) in vec3 outColor;

// Step2-C: 必须实际采样 set=0,binding=1 才能验证 descriptor/texture 链路
layout(set = 0, binding = 1) uniform sampler2D u_Tex0;

void main(void) {
    // 用屏幕坐标生成 UV（无需额外 vertex attribute）
    // gl_FragCoord.xy 是像素坐标；fract 让它形成重复图案，便于观察
    vec2 uv = fract(gl_FragCoord.xy / 64.0);

    vec4 tex = texture(u_Tex0, uv);

    // 纹理 * 顶点色（你原先的 outColor）
    color = vec4(outColor, 1.0) * tex;
}
