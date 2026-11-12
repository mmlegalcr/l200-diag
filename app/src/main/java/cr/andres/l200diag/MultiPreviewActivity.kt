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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Abre TODAS las cámaras a la vez, cada una en su recuadro.
 * Responde la pregunta clave para el launcher: ¿se pueden mostrar varias cámaras
 * simultáneamente como widgets, y qué pasa con el DVR mientras tanto?
 */
class MultiPreviewActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private val tiles = mutableListOf<Tile>()

    private val ticker = object : Runnable {
        override fun run() {
            tiles.forEach { it.updateStatus() }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val ids = try {
            manager.cameraIdList.toList()
        } catch (_: Exception) {
            emptyList()
        }

        for (id in ids) {
            val cell = FrameLayout(this)
            val texture = TextureView(this)
            cell.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#AA000000"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            cell.addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            )
            row.addView(
                cell,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
            )
            tiles += Tile(id, texture, label)
        }

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
        // Abrir de forma escalonada: algunos HAL fallan si se abren todas en el mismo instante.
        tiles.forEachIndexed { i, t -> handler.postDelayed({ t.start() }, 300L * i) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        tiles.forEach { it.stop() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------------

    private inner class Tile(
        val id: String,
        val texture: TextureView,
        val label: TextView
    ) : TextureView.SurfaceTextureListener {

        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var surface: Surface? = null
        private var state = "en espera"
        private var sizeText = "?"
        private var frames = 0L
        private var streamStart = 0L
        private var wantOpen = false

        init {
            texture.surfaceTextureListener = this
        }

        fun start() {
            wantOpen = true
            if (texture.isAvailable && device == null) texture.surfaceTexture?.let { open(it) }
        }

        fun stop() {
            wantOpen = false
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { device?.close() } catch (_: Exception) {}
            device = null
            surface?.release()
            surface = null
        }

        fun updateStatus() {
            val fps = if (streamStart > 0) {
                val secs = (SystemClock.elapsedRealtime() - streamStart) / 1000.0
                if (secs > 0.5) "%.1f".format(frames / secs) else "…"
            } else "-"
            label.text = "Cámara $id · $sizeText\n$state\ncuadros: $frames · FPS: $fps"
        }

        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            if (wantOpen && device == null) open(st)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            stop()
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            frames++
        }

        @SuppressLint("MissingPermission")
        private fun open(st: SurfaceTexture) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                state = "sin permiso de cámara"
                return
            }
            // Para widgets basta 720p: menos carga y más probable que el HAL permita varias a la vez.
            val size = try {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.filter { it.width <= 1280 && it.height <= 720 }
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
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        if (!wantOpen) {
                            cam.close()
                            return
                        }
                        device = cam
                        state = "abierta, iniciando…"
                        val s = Surface(st)
                        surface = s
                        startSession(cam, s)
                    }

                    override fun onDisconnected(cam: CameraDevice) {
                        state = "DESCONECTADA (otra app la tomó)"
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
                state = "no abre: ${e.javaClass.simpleName}: ${e.message}"
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
                            state = "error al iniciar: ${e.message}"
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        state = "falló la configuración"
                    }
                }, handler)
            } catch (e: Exception) {
                state = "error de sesión: ${e.javaClass.simpleName}"
            }
        }

        private fun errorText(code: Int): String = when (code) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "en uso por otra app"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "límite de cámaras simultáneas"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "deshabilitada"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "falla del dispositivo"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "falla del servicio"
            else -> "desconocido"
        }
    }
}
