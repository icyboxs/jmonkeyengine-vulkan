#version 450
#define VULKAN_NATIVE 1

// 【关键修复】显式定义内置变量块，解决 undeclared identifier 报错
out gl_PerVertex {
    vec4 gl_Position;
    float gl_PointSize;
};

layout(set=0, binding=0, std140) uniform JmeGlobals {
    mat4 g_WorldViewProjectionMatrix;
    vec4 g_Resolution;
    vec4 g_Mouse;
    vec4 g_Time;
};

layout(location=0) in vec3 inPosition;
layout(location=1) in vec2 inTexCoord;

layout(location=0) out vec2 texCoord1;

void main(){
    texCoord1 = inTexCoord;
    
    // 1. 确保赋值目标是 gl_Position (vec4)
    // 2. 矩阵乘法顺序必须是 Matrix * Vector
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    
    // 显式给 gl_PointSize 赋值，消除某些编译器的 undeclared 警告
    gl_PointSize = 1.0;
}