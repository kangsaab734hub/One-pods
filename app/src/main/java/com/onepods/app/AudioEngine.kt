package com.onepods.app

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

object AudioEngine {
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    
    private var masterEnabled = false
    private var currentBass = 0f
    private var currentTreble = 50f
    private var currentVolumeBoost = 0f

    fun initAudioEffects() {
        try {
            equalizer = Equalizer(0, 0)
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
                loudnessEnhancer?.enabled = false
                return
            }

            // VOLUME BOOST
            if (currentVolumeBoost > 0f) {
                loudnessEnhancer?.enabled = true
                loudnessEnhancer?.setTargetGain((currentVolumeBoost * 20).toInt())
            } else {
                loudnessEnhancer?.enabled = false
            }

            // PURE NATURAL EQ (Ghatiya BassBoost engine delete kar diya)
            val bands = equalizer?.numberOfBands ?: 0
            if ((currentBass > 0f || currentTreble != 50f) && bands > 0) {
                equalizer?.enabled = true
                val maxEq = equalizer?.bandLevelRange?.get(1) ?: 0

                // Sabse pehle saare bands ko neutral (0) rakho taaki original song kharab na ho
                for (i in 0 until bands) {
                    equalizer?.setBandLevel(i.toShort(), 0)
                }

                // 1. PURE BASS BOOST (Sirf Base frequency ko natural tareeke se badhayenge)
                if (currentBass > 0f) {
                    val bassGain = ((currentBass / 100f) * maxEq).toInt().toShort()
                    // Band 0 (Deep Sub-bass)
                    equalizer?.setBandLevel(0.toShort(), bassGain) 
                    // Band 1 (Mid-bass / Punch) - Isey thoda kam rakhenge taaki awaaz fate na
                    if (bands > 1) {
                        equalizer?.setBandLevel(1.toShort(), (bassGain * 0.6).toInt().toShort()) 
                    }
                }

                // 2. NATURAL TREBLE
                if (currentTreble != 50f) {
                    val trebleGain = (((currentTreble - 50f) / 50f) * maxEq).toInt().toShort()
                    equalizer?.setBandLevel((bands - 1).toShort(), trebleGain)
                    if (bands > 2) {
                        equalizer?.setBandLevel((bands - 2).toShort(), (trebleGain * 0.5).toInt().toShort())
                    }
                }

            } else {
                equalizer?.enabled = false
            }

        } catch (e: Exception) {}
    }

    fun release() {
        try {
            equalizer?.release()
            loudnessEnhancer?.release()
        } catch (e: Exception) {}
    }
}
