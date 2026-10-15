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
    
    private var masterEnabled = false
    private var currentBass = 0f
    private var currentTreble = 50f
    private var currentHall = 0f
    private var currentVolumeBoost = 0f

    fun initAudioEffects() {
        try {
            equalizer = Equalizer(0, 0)
            bassBoost = BassBoost(0, 0)
            loudnessEnhancer = LoudnessEnhancer(0)
            virtualizer = Virtualizer(0, 0)
            applyEffects()
            Log.d("AudioEngine", "Audio engine ready!")
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error: ${e.message}")
        }
    }

    fun setMasterEnable(enabled: Boolean) {
        masterEnabled = enabled
        applyEffects()
    }

    fun setBass(level: Float) { currentBass = level; applyEffects() }
    fun setTreble(level: Float) { currentTreble = level; applyEffects() }
    fun setHallEffect(level: Float) { currentHall = level; applyEffects() }
    fun setVolumeBoost(level: Float) { currentVolumeBoost = level; applyEffects() }

    private fun applyEffects() {
        try {
            if (!masterEnabled) {
                equalizer?.enabled = false
                bassBoost?.enabled = false
                loudnessEnhancer?.enabled = false
                virtualizer?.enabled = false
                return
            }

            // Bass
            if (currentBass > 0f) {
                bassBoost?.enabled = true
                bassBoost?.setStrength((currentBass * 10).toInt().toShort())
            } else {
                bassBoost?.enabled = false
            }

            // Hall (Virtualizer)
            if (currentHall > 0f) {
                virtualizer?.enabled = true
                virtualizer?.setStrength((currentHall * 10).toInt().toShort())
            } else {
                virtualizer?.enabled = false
            }

            // Volume Boost
            if (currentVolumeBoost > 0f) {
                loudnessEnhancer?.enabled = true
                loudnessEnhancer?.setTargetGain((currentVolumeBoost * 20).toInt())
            } else {
                loudnessEnhancer?.enabled = false
            }

            // Treble / EQ
            val bands = equalizer?.numberOfBands ?: 0
            if (bands > 0) {
                if (currentTreble == 50f) {
                    equalizer?.enabled = false
                } else {
                    equalizer?.enabled = true
                    val highestBand = (bands - 1).toShort()
                    val minEq = equalizer?.bandLevelRange?.get(0) ?: 0
                    val maxEq = equalizer?.bandLevelRange?.get(1) ?: 0
                    val targetLevel = minEq + ((maxEq - minEq) * (currentTreble / 100f)).toInt()
                    equalizer?.setBandLevel(highestBand, targetLevel.toShort())
                }
            }
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
