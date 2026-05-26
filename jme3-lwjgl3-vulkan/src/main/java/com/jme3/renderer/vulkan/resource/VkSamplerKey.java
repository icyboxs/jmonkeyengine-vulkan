package com.jme3.renderer.vulkan.resource;

import com.jme3.texture.Texture;
import java.util.Objects;

public final class VkSamplerKey {
    public final int magFilter;   
    public final int minFilter;
    public final int wrapS;
    public final int wrapT;
    public final int aniso;


    public VkSamplerKey(Texture tex, int defaultAniso) {
        Texture.MagFilter mag = tex != null ? tex.getMagFilter() : Texture.MagFilter.Bilinear;
        Texture.MinFilter min = tex != null ? tex.getMinFilter() : Texture.MinFilter.BilinearNoMipMaps;
        Texture.WrapMode ws = tex != null ? tex.getWrap(Texture.WrapAxis.S) : Texture.WrapMode.EdgeClamp;
        Texture.WrapMode wt = tex != null ? tex.getWrap(Texture.WrapAxis.T) : Texture.WrapMode.EdgeClamp;
        
        //如果纹理过滤等级为 0 或未设置，说明它希望使用全局默认配置
        int a = tex != null ? tex.getAnisotropicFilter() : 0;
        if (a < 1) {
            a = defaultAniso;
        }

        this.magFilter = mag != null ? mag.ordinal() : 0;
        this.minFilter = min != null ? min.ordinal() : 0;
        this.wrapS = ws != null ? ws.ordinal() : 0;
        this.wrapT = wt != null ? wt.ordinal() : 0;
        this.aniso = Math.max(1, a);
    }

    @Override public boolean equals(Object o){
        if (this == o) return true;
        if (!(o instanceof VkSamplerKey)) return false;
        VkSamplerKey k = (VkSamplerKey)o;
        return magFilter==k.magFilter && minFilter==k.minFilter && wrapS==k.wrapS && wrapT==k.wrapT && aniso==k.aniso;
    }
    @Override public int hashCode(){ return Objects.hash(magFilter,minFilter,wrapS,wrapT,aniso); }
}