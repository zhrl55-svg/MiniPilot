package com.openminis.app.data

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ChatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-chat-mode] 进程级的会话模式缓存与切换通道。
 *
 * 模式本身持久化在 `sessions.chat_mode` 里，由 ChatViewModel 负责加载。
 * 这个对象只做两件事：
 *
 *  - 给不持有 ViewModel 的语音层（按住说话、唤醒对话）一个读模式的入口；
 *  - 提供唯一的切换出口：写缓存、广播变化、异步落库。
 *
 * `changes` 让正在显示该会话的界面立刻刷新 chip——语音口令切换时，
 * 前台界面不需要自己轮询。
 */
object ChatModeStore {

    private val cached = ConcurrentHashMap<String, ChatMode>()

    private val _changes = MutableSharedFlow<Pair<String, ChatMode>>(extraBufferCapacity = 8)
    val changes: SharedFlow<Pair<String, ChatMode>> = _changes.asSharedFlow()

    /** 未加载过的会话按助手处理，与升级前的行为一致。 */
    fun modeFor(sessionId: String?): ChatMode =
        sessionId?.let { cached[it] } ?: ChatMode.ASSISTANT

    /** ChatViewModel 加载会话后同步进缓存，语音层随后就能读到。 */
    fun remember(sessionId: String, mode: ChatMode) {
        cached[sessionId] = mode
    }

    /** 切换并持久化；UI chip 与语音口令共用这一条路径。 */
    fun apply(context: Context, sessionId: String, mode: ChatMode) {
        remember(sessionId, mode)
        _changes.tryEmit(sessionId to mode)
        val repo = (context.applicationContext as? MinisApp)?.chatRepositoryOrNull ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repo.dao.updateChatMode(sessionId, mode.wireValue) }
        }
    }
}
