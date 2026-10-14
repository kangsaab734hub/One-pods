package com.onepods.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log

object AudioEngine {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null

    fun initAudioEffects() {
        try {
            equalizer = Equalizer(0, 0).apply { enabled = true }
            bassBoost = BassBoost(0, 0).apply { enabled = true }
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            // Virtualizer creates a 3D Hall/Concert effect and works on global audio
            virtualizer = Virtualizer(0, 0).apply { enabled = true }
            Log.d("AudioEngine", "Audio effects on!")
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error: ${e.message}")
        }
    }

    fun setBass(level: Float) {
        try {
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
            virtualizer?.setStrength((level * 10).toInt().toShort())
        } catch (e: Exception) {}
    }

    fun setVolumeBoost(level: Float) {
        try {
            loudnessEnhancer?.setTargetGain((level * 20).toInt())
        } catch (e: Exception) {}
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            loudnessEnhancer?.release()
            virtualizer?.release()
        } catch (e: Exception) {}
    }
}
