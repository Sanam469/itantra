package com.itantra.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * ViewModel for main screen state.
 * TODO [Layer 2]: Add STT state, TTS state, connection state
 */
class MainViewModel : ViewModel() {

    enum class AppMode { SEND, RECEIVE, BOTH, OFF }
    enum class Language { ENGLISH, HINDI }

    private val _currentMode = MutableLiveData(AppMode.BOTH)
    val currentMode: LiveData<AppMode> = _currentMode

    private val _currentLanguage = MutableLiveData(Language.ENGLISH)
    val currentLanguage: LiveData<Language> = _currentLanguage

    private val _isListening = MutableLiveData(false)
    val isListening: LiveData<Boolean> = _isListening

    fun setMode(mode: AppMode) { _currentMode.value = mode }
    fun setLanguage(lang: Language) { _currentLanguage.value = lang }
    fun setListening(listening: Boolean) { _isListening.value = listening }
}
