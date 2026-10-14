package com.onepods.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.util.Log

object AudioEngine {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    fun initAudioEffects() {
        try {
            // Session 0 phone ke global audio output ke liye hota hai
            equalizer = Equalizer(0, 0).apply { enabled = true }
            bassBoost = BassBoost(0, 0).apply { enabled = true }
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            reverb = PresetReverb(0, 0).apply { enabled = true }
            Log.d("AudioEngine", "Audio effects on!")
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error: ${e.message}")
        }
    }

    fun setBass(level: Float) {
        try {
            // 0 se 100 ko 0 se 1000 ki power mein convert karta hai
            bassBoost?.setStrength((level * 10).toInt().toShort())
        } catch (e: Exception) {}
    }

    fun setTreble(level: Float) {
        try {
            val bands = equalizer?.numberOfBands ?: return
            if (bands > 0) {
                val highestBand = (bands - 1).toShort()
                val minEq = equalizer?.bandLevelRange?.get(0) ?: 0
                val maxEq = equalizer?.bandLevelRange?.get(1) ?: 0
                val targetLevel = minEq + ((maxEq - minEq) * (level / 100f)).toInt()
                equalizer?.setBandLevel(highestBand, targetLevel.toShort())
            }
        } catch (e: Exception) {}
    }

    fun setHallEffect(level: Float) {
        try {
            if (level > 20f) {
                reverb?.preset = PresetReverb.PRESET_LARGEHALL
            } else {
                reverb?.preset = PresetReverb.PRESET_NONE
            }
        } catch (e: Exception) {}
    }

    fun setVolumeBoost(level: Float) {
        try {
            // Aawaz ko safe limit tak amplify karega
            loudnessEnhancer?.setTargetGain((level * 20).toInt())
        } catch (e: Exception) {}
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            loudnessEnhancer?.release()
            reverb?.release()
        } catch (e: Exception) {}
    }
}
