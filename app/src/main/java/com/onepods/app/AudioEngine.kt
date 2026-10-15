package com.onepods.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

object AudioEngine {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    
    private var masterEnabled = false
    private var currentBass = 0f
    private var currentTreble = 50f
    private var currentVolumeBoost = 0f

    fun initAudioEffects() {
        try {
            equalizer = Equalizer(0, 0)
            bassBoost = BassBoost(0, 0)
            loudnessEnhancer = LoudnessEnhancer(0)
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error: ${e.message}")
        }
    }

    fun setMasterEnable(enabled: Boolean) { masterEnabled = enabled; applyEffects() }
    fun setBass(level: Float) { currentBass = level; applyEffects() }
    fun setTreble(level: Float) { currentTreble = level; applyEffects() }
    fun setVolumeBoost(level: Float) { currentVolumeBoost = level; applyEffects() }

    private fun applyEffects() {
        try {
            if (!masterEnabled) {
                equalizer?.enabled = false
                bassBoost?.enabled = false
                loudnessEnhancer?.enabled = false
                return
            }

            // 1. PURE OLD BASS (Purana best system, koi dhakkan nahi)
            if (currentBass > 0f) {
                bassBoost?.enabled = true
                bassBoost?.setStrength((currentBass * 10).toInt().toShort())
            } else {
                bassBoost?.enabled = false
            }

            // 2. VOLUME BOOST
            if (currentVolumeBoost > 0f) {
                loudnessEnhancer?.enabled = true
                loudnessEnhancer?.setTargetGain((currentVolumeBoost * 20).toInt())
            } else {
                loudnessEnhancer?.enabled = false
            }

            // 3. TREBLE
            val bands = equalizer?.numberOfBands ?: 0
            if (currentTreble != 50f && bands > 0) {
                equalizer?.enabled = true
                val maxEq = equalizer?.bandLevelRange?.get(1) ?: 0
                val trebleGain = (((currentTreble - 50f) / 50f) * maxEq).toInt().toShort()
                equalizer?.setBandLevel((bands - 1).toShort(), trebleGain)
            } else {
                equalizer?.enabled = false
            }

        } catch (e: Exception) {}
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            loudnessEnhancer?.release()
        } catch (e: Exception) {}
    }
}
