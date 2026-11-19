package com.openminis.app.data.model

import java.util.Locale

/**
 * [T-android-voice-synthesis-settings] MiniMax T2A system voices.
 *
 * MiniMax is the one cloud TTS vendor where the MODEL and the VOICE are
 * separate namespaces (`speech-2.8-hd` vs `female-shaonv`). The provider
 * templates seed one ModelEntry per MODEL, so the normal "entry id doubles as
 * the voice id" convention cannot express a voice choice here — the adapter
 * deliberately ignores a voice that equals the model id and falls back to
 * [DEFAULT_VOICE_ID].
 *
 * The Settings › Speech Synthesis screen therefore offers this curated list
 * and stores the picked id per provider instance
 * ([com.openminis.app.data.repository.ProviderRepository.voiceOutputCloudVoice]);
 * read-aloud forwards it as `voice_setting.voice_id`.
 *
 * Only long-standing system voice ids are listed: an unknown id is rejected
 * upstream with error 2054 ("voice id not exist"), so a curated list is safer
 * than guessing at newer beta voices.
 */
object MiniMaxVoices {

    data class Voice(val id: String, val label: String)

    /** Matches MiniMaxVoiceProvider.defaultVoiceOutputVoice(). */
    const val DEFAULT_VOICE_ID = "female-shaonv"

    val all: List<Voice> = listOf(
        Voice("female-shaonv", "少女（女）"),
        Voice("female-yujie", "御姐（女）"),
        Voice("female-chengshu", "成熟女性（女）"),
        Voice("female-tianmei", "甜美女性（女）"),
        Voice("male-qn-qingse", "青涩青年（男）"),
        Voice("male-qn-jingying", "精英青年（男）"),
        Voice("male-qn-badao", "霸道青年（男）"),
        Voice("male-qn-daxuesheng", "青年大学生（男）"),
        Voice("presenter_male", "男性主持人（男）"),
        Voice("presenter_female", "女性主持人（女）"),
        Voice("audiobook_male_1", "男性有声书 1（男）"),
        Voice("audiobook_male_2", "男性有声书 2（男）"),
        Voice("audiobook_female_1", "女性有声书 1（女）"),
        Voice("audiobook_female_2", "女性有声书 2（女）"),
        Voice("clever_boy", "聪明男童（童）"),
        Voice("cute_boy", "可爱男童（童）"),
        Voice("lovely_girl", "萌萌女童（童）"),
    )

    /** Human label for an id, or the raw id when it is not in the catalog. */
    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    /**
     * Same routing test VoiceProviderFactory uses: MiniMax is reached through
     * an OpenAI/Anthropic-compatible instance whose base URL names it.
     */
    fun isMiniMax(instance: ProviderInstance): Boolean =
        instance.customBaseURL
            ?.lowercase(Locale.ROOT)
            ?.contains("minimax") == true
}
