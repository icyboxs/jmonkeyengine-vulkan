package com.jme3.renderer.vulkan.resource;

import com.jme3.texture.Texture;
import java.util.Objects;

public final class VkSamplerKey {
    public final int magFilter;   // jME enum ordinal
    public final int minFilter;
    public final int wrapS;
    public final int wrapT;
    public final int aniso;

    public VkSamplerKey(Texture tex) {
        Texture.MagFilter mag = tex != null ? tex.getMagFilter() : Texture.MagFilter.Bilinear;
        Texture.MinFilter min = tex != null ? tex.getMinFilter() : Texture.MinFilter.BilinearNoMipMaps;
        Texture.WrapMode ws = tex != null ? tex.getWrap(Texture.WrapAxis.S) : Texture.WrapMode.EdgeClamp;
        Texture.WrapMode wt = tex != null ? tex.getWrap(Texture.WrapAxis.T) : Texture.WrapMode.EdgeClamp;
        int a = tex != null ? tex.getAnisotropicFilter() : 0;

        this.magFilter = mag != null ? mag.ordinal() : 0;
        this.minFilter = min != null ? min.ordinal() : 0;
        this.wrapS = ws != null ? ws.ordinal() : 0;
        this.wrapT = wt != null ? wt.ordinal() : 0;
        this.aniso = Math.max(0, a);
    }

    @Override public boolean equals(Object o){
        if (this == o) return true;
        if (!(o instanceof VkSamplerKey)) return false;
        VkSamplerKey k = (VkSamplerKey)o;
        return magFilter==k.magFilter && minFilter==k.minFilter && wrapS==k.wrapS && wrapT==k.wrapT && aniso==k.aniso;
    }
    @Override public int hashCode(){ return Objects.hash(magFilter,minFilter,wrapS,wrapT,aniso); }
}
