package com.onepods.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.media.audiofx.PresetReverb
import android.util.Log

object AudioEngine {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null
    private var reverb: PresetReverb? = null // Naya Reverb Engine
    
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
            reverb = PresetReverb(0, 0)
            applyEffects()
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error: ${e.message}")
        }
    }

    fun setMasterEnable(enabled: Boolean) { masterEnabled = enabled; applyEffects() }
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
                reverb?.enabled = false
                return
            }

            // Volume Boost
            if (currentVolumeBoost > 0f) {
                loudnessEnhancer?.enabled = true
                loudnessEnhancer?.setTargetGain((currentVolumeBoost * 20).toInt())
            } else {
                loudnessEnhancer?.enabled = false
            }

            // DOUBLE ENGINE FIX: Hall Effect (3D Surround + Concert Reverb)
            if (currentHall > 0f) {
                // 1. Virtualizer for 3D width
                virtualizer?.enabled = true
                virtualizer?.setStrength((currentHall * 10).toInt().toShort())
                
                // 2. Real Reverb for Hall Echo
                reverb?.preset = if (currentHall > 50f) PresetReverb.PRESET_LARGEHALL else PresetReverb.PRESET_MEDIUMHALL
                reverb?.enabled = true
            } else {
                virtualizer?.enabled = false
                reverb?.enabled = false
            }

            // Deep Bass (Booooooo) & Treble EQ
            val bands = equalizer?.numberOfBands ?: 0
            val isEqNeeded = currentBass > 0f || currentTreble != 50f
            
            if (isEqNeeded && bands > 0) {
                equalizer?.enabled = true
                val minEq = equalizer?.bandLevelRange?.get(0) ?: 0
                val maxEq = equalizer?.bandLevelRange?.get(1) ?: 0
                
                for (i in 0 until bands) {
                    equalizer?.setBandLevel(i.toShort(), 0)
                }

                if (currentBass > 0f) {
                    bassBoost?.enabled = true
                    bassBoost?.setStrength((currentBass * 4).toInt().toShort())

                    val bassGain = ((currentBass / 100f) * maxEq).toInt().toShort()
                    equalizer?.setBandLevel(0.toShort(), bassGain) 
                    
                    if (bands > 1) {
                        equalizer?.setBandLevel(1.toShort(), (bassGain / 2).toShort())
                    }
                } else {
                    bassBoost?.enabled = false
                }

                if (currentTreble != 50f) {
                    val trebleGain = (((currentTreble - 50f) / 50f) * maxEq).toInt().toShort()
                    equalizer?.setBandLevel((bands - 1).toShort(), trebleGain)
                }

            } else {
                equalizer?.enabled = false
                bassBoost?.enabled = false
            }

        } catch (e: Exception) {}
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            loudnessEnhancer?.release()
            virtualizer?.release()
            reverb?.release()
        } catch (e: Exception) {}
    }
}
