package com.jme3.system;

import com.jme3.audio.AudioData;
import com.jme3.audio.AudioParam;
import com.jme3.audio.AudioRenderer;
import com.jme3.audio.AudioSource;
import com.jme3.audio.Environment;
import com.jme3.audio.Listener;
import com.jme3.audio.ListenerParam;
import java.util.logging.Filter;

public final class NullAudioRenderer implements AudioRenderer {

    @Override public void setListener(Listener listener) { }
    @Override public void setEnvironment(Environment env) { }

    @Override public void playSourceInstance(AudioSource src) { }
    @Override public void playSource(AudioSource src) { }
    @Override public void pauseSource(AudioSource src) { }
    @Override public void stopSource(AudioSource src) { }

    @Override public void updateSourceParam(AudioSource src, AudioParam param) { }
    @Override public void updateListenerParam(Listener listener, ListenerParam param) { }
    @Override public float getSourcePlaybackTime(AudioSource src) { return 0f; }
    @Override public void deleteAudioData(AudioData ad) { }

    @Override public void initialize() { }
    @Override public void update(float tpf) { }
    @Override public void pauseAll() { }
    @Override public void resumeAll() { }
    @Override public void cleanup() { }

    @Override
    public void deleteFilter(com.jme3.audio.Filter filter) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
}
