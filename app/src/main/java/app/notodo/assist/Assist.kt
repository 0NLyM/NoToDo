package app.notodo.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import app.notodo.ui.CaptureActivity

/**
 * Assistente digitale di sistema (pressione lunga del tasto power, se configurata).
 * Nessun microfono, nessuna lettura dello schermo: apre solo la cattura.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Activity dell'assistente sopra l'app corrente: stessa UI di widget, scorciatoia e MacroDroid.
        startAssistantActivity(CaptureActivity.intent(context, "assistente"))
        hide()
    }
}
