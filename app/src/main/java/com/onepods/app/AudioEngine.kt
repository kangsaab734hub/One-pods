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
            applyEffects()
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

            // DHAKKAN FIX: Android EQ on karte hi volume drop karta hai. 
            // Hum LoudnessEnhancer se automatically wo aawaz wapas loud kar rahe hain.
            loudnessEnhancer?.enabled = true
            val baseCompensation = 400 // Natural volume wapas laane ke liye
            val userBoost = (currentVolumeBoost * 20).toInt()
            loudnessEnhancer?.setTargetGain(baseCompensation + userBoost)

            // Natural Bass Boost (Bina dusri frequencies ko dabaye)
            if (currentBass > 0f) {
                bassBoost?.enabled = true
                bassBoost?.setStrength((currentBass * 10).toInt().toShort())
            } else {
                bassBoost?.enabled = false
            }

            // Treble Control (Hum baaki frequencies ko flat nahi karenge ab)
            val bands = equalizer?.numberOfBands ?: 0
            if (currentTreble != 50f && bands > 0) {
                equalizer?.enabled = true
                val maxEq = equalizer?.bandLevelRange?.get(1) ?: 0
                val trebleGain = (((currentTreble - 50f) / 50f) * maxEq).toInt().toShort()
                equalizer?.setBandLevel((bands - 1).toShort(), trebleGain)
            } else {
                // Agar Treble 50% hai toh EQ off rakho taaki original sound bachi rahe
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
