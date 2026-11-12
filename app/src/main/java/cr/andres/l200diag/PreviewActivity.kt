package cr.andres.l200diag

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Abre una cámara por Camera2 y la muestra en pantalla completa.
 * Indica si la cámara entrega cuadros, a cuántos FPS, y qué error devuelve si no abre.
 */
class PreviewActivity : Activity(), TextureView.SurfaceTextureListener {

    companion object {
        const val EXTRA_ID = "camera_id"
    }

    private lateinit var texture: TextureView
    private lateinit var statusView: TextView
    private lateinit var cameraId: String

    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    private var state = "esperando superficie…"
    private var sizeText = "?"
    private var frames = 0L
    private var streamStart = 0L

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraId = intent.getStringExtra(EXTRA_ID) ?: run {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        texture = TextureView(this)
        texture.surfaceTextureListener = this
        root.addView(
            texture,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) }
        )

        val close = Button(this).apply {
            text = "Cerrar"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        if (texture.isAvailable && device == null) {
            texture.surfaceTexture?.let { openCamera(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        closeCamera()
    }

    // ---------------------------------------------------------------- TextureView

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (device == null) openCamera(st)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        frames++
    }

    // ---------------------------------------------------------------- Cámara

    @SuppressLint("MissingPermission")
    private fun openCamera(st: SurfaceTexture) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state = "Falta el permiso de cámara (concédelo en la pantalla principal)"
            return
        }

        val size = try {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 }
                ?.maxByOrNull { it.width * it.height }
        } catch (_: Exception) {
            null
        }
        if (size != null) {
            st.setDefaultBufferSize(size.width, size.height)
            sizeText = "${size.width}x${size.height}"
        }

        state = "abriendo…"
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    state = "abierta, iniciando video…"
                    val s = Surface(st)
                    surface = s
                    startSession(cam, s)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    state = "DESCONECTADA (otra app con prioridad la tomó, o se desconectó)"
                    cam.close()
                    device = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    state = "ERROR $error: ${errorText(error)}"
                    cam.close()
                    device = null
                }
            }, handler)
        } catch (e: Exception) {
            state = "No se pudo abrir: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun startSession(cam: CameraDevice, target: Surface) {
        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(target)
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(builder.build(), null, handler)
                        frames = 0
                        streamStart = SystemClock.elapsedRealtime()
                        state = "transmitiendo"
                    } catch (e: Exception) {
                        state = "Error al iniciar el video: ${e.message}"
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    state = "Falló la configuración de la sesión de captura"
                }
            }, handler)
        } catch (e: Exception) {
            state = "Error de sesión: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        surface?.release()
        surface = null
    }

    private fun updateStatus() {
        val fps = if (streamStart > 0) {
            val secs = (SystemClock.elapsedRealtime() - streamStart) / 1000.0
            if (secs > 0.5) "%.1f".format(frames / secs) else "…"
        } else {
            "-"
        }
        statusView.text = "Cámara $cameraId · $sizeText\nEstado: $state\nCuadros recibidos: $frames · FPS: $fps"
    }

    private fun errorText(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "en uso por otra app (p. ej. el DVR grabando)"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "límite de cámaras abiertas a la vez"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "deshabilitada por el sistema"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "falla del dispositivo de cámara"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "falla del servicio de cámara"
        else -> "desconocido"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
