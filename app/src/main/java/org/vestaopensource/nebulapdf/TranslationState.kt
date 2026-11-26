package org.vestaopensource.nebulapdf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vestaopensource.nebulapdf.core.Summary
import java.io.File

sealed interface JobState {
    data object Idle : JobState
    data class Running(val fileName: String, val page: Int, val total: Int, val message: String) : JobState
    data class Done(val fileName: String, val output: File, val summary: Summary) : JobState
    data class Failed(val fileName: String, val message: String) : JobState
}

/** Estado compartido entre el servicio de traducción y la interfaz. */
object TranslationState {
    internal val mutable = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = mutable

    fun reset() {
        if (mutable.value !is JobState.Running) mutable.value = JobState.Idle
    }
}
