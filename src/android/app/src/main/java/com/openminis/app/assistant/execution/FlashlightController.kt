package com.openminis.app.assistant.execution

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger

/**
 * [T-local-flashlight] Torch control for the on-device assistant.
 *
 * Uses the system camera service (`CameraManager.setTorchMode`) — the same
 * source of truth as the quick-settings torch tile. State is mirrored from
 * `TorchCallback`, so a tile tap or another app's change is reflected in the
 * status answer instead of leaving a stale flag.
 *
 * Lifecycle: the torch handle belongs to this process. If the app is killed,
 * the camera service releases it and the LED turns off; a device shutdown
 * powers the LED off with the rest of the hardware. We deliberately do NOT
 * turn it off when the app goes to the background — a flashlight is expected
 * to keep shining while the user does something else.
 */
class FlashlightController private constructor(private val context: Context) {

    data class TorchResult(val success: Boolean, val message: String)

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    @Volatile
    private var torchCameraId: String? = null

    @Volatile
    private var supported = false

    @Volatile
    private var on = false

    init {
        resolveCamera()
        registerCallback()
    }

    /** Prefer a back camera that reports a flash unit; fall back to any. */
    private fun resolveCamera() {
        val manager = cameraManager ?: return
        try {
            var back: String? = null
            var any: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (any == null) any = id
                if (chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
                ) {
                    back = id
                    break
                }
            }
            torchCameraId = back ?: any
            supported = torchCameraId != null
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "camera enumeration failed: ${t.message}")
        }
    }

    private fun registerCallback() {
        val manager = cameraManager ?: return
        try {
            manager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == torchCameraId) on = enabled
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId == torchCameraId) on = false
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (t: Throwable) {
            // Registration is best-effort: control still works, only the
            // external-change mirror is lost.
            AppLogger.warning(TAG, "torch callback registration failed: ${t.message}")
        }
    }

    fun setTorch(enable: Boolean): TorchResult {
        if (!supported) return TorchResult(false, "这台设备没有可用的闪光灯。")
        if (!hasCameraPermission()) return permissionDenied()
        val id = torchCameraId ?: return TorchResult(false, "这台设备没有可用的闪光灯。")
        return try {
            cameraManager?.setTorchMode(id, enable)
            on = enable
            TorchResult(true, if (enable) "手电筒已打开。" else "手电筒已关闭。")
        } catch (e: CameraAccessException) {
            AppLogger.warning(TAG, "setTorchMode($enable) failed: ${e.reason}")
            TorchResult(
                false,
                when (e.reason) {
                    CameraAccessException.CAMERA_IN_USE,
                    CameraAccessException.MAX_CAMERAS_IN_USE,
                    -> "相机正被其他应用占用，暂时无法控制手电筒。"
                    else -> "手电筒暂时不可用（相机服务返回 ${e.reason}）。"
                },
            )
        } catch (e: SecurityException) {
            permissionDenied()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "setTorchMode($enable) failed: ${t.message}")
            TorchResult(false, "手电筒操作失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun status(): TorchResult {
        if (!supported) return TorchResult(false, "这台设备没有可用的闪光灯。")
        if (!hasCameraPermission()) return permissionDenied()
        return TorchResult(
            true,
            if (on) "手电筒当前是打开的。" else "手电筒当前是关闭的。",
        )
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun permissionDenied() = TorchResult(
        false,
        "没有相机权限，无法控制手电筒。请在系统设置里授予 MiniPilot 相机权限后重试。",
    )

    companion object {
        private const val TAG = "Flashlight"

        @Volatile
        private var instance: FlashlightController? = null

        fun get(context: Context): FlashlightController =
            instance ?: synchronized(this) {
                instance ?: FlashlightController(context.applicationContext).also { instance = it }
            }
    }
}
