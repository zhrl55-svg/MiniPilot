package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-approval-overlay] 本地写操作审批的进程级出口。
 *
 * WHY: 审批卡原来由 ChatScreen 渲染，只属于「正在打开的那个会话」。用户在
 * 设置页、会话列表，或者干脆退到后台时看不到这张卡，而助理一直在等，
 * 超时后自动取消 —— 对方看到的就是「用户拒绝」。
 *
 * ChatViewModel 弹卡时把展示数据与确认/取消回调注册到这里：
 *  - MainActivity 的根布局渲染对话框，任何页面都能看到；
 *  - [com.openminis.app.notification.LocalActionNotifier] 负责 App 在后台
 *    时的系统通知。
 *
 * 回调仍落在原 ViewModel 上（闭包捕获该实例），所以这里的按钮与卡内
 * 按钮走的是同一条确认路径，不存在第二套审批机制。
 */
object LocalActionApprovalCenter {

    data class Pending(
        val sessionId: String,
        val action: LocalActionConfirmation,
        val confirm: (choiceId: String?) -> Unit,
        val cancel: () -> Unit,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun register(pending: Pending) {
        _pending.value = pending
    }

    /** Only the card that is actually showing may clear itself. */
    fun clear(actionId: String) {
        val current = _pending.value ?: return
        if (current.action.id == actionId) _pending.value = null
    }
}
