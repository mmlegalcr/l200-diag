package cr.andres.l200diag

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.huiying.cameramjpeg.UvcCamera
import com.serenegiant.usb.IEventCallback
import com.serenegiant.usb.IFrameCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Prueba de la dashcam "USB CAR DVR" (la de Traffic safety guard) con su propia librería nativa.
 *
 * Cómo funciona la cámara (sacado del APK de FVision):
 *  - Se conecta como memoria USB. En la raíz expone un archivo especial "VSFILE":
 *    leyéndolo se obtiene el video en vivo (JPEG) y el estado de la cámara.
 *  - Los comandos (grabar, bloquear, foto, hora…) se escriben en
 *    "<disco>/Android/data/com.fvision.camera/HWC".
 */
class DashcamTestActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var stateView: TextView
    private lateinit var gl: GLSurfaceView
    private val log = StringBuilder()

    private var cam: UvcCamera? = null
    private var devPath: String? = null
    private var cmdPath: String? = null
    private var videoFd = -1
    private var cmdFd = -1
    private var previewing = false

    @Volatile private var lastState: ByteArray? = null
    @Volatile private var stateFrames = 0L
    @Volatile private var drawCalls = 0L
    @Volatile private var drawOk = 0L

    private val renderTick = object : Runnable {
        override fun run() {
            if (previewing) gl.requestRender()
            main.postDelayed(this, 40)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateState()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111418"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // Columna izquierda: video + estado
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(g: GL10?, config: EGLConfig?) {
                    safe("initGles") { cam?.initGles(1280, 720) }
                }

                override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) {
                    safe("changeESLayout") { cam?.changeESLayout(width, height) }
                }

                override fun onDrawFrame(g: GL10?) {
                    val c = cam ?: return
                    drawCalls++
                    try {
                        if (c.drawESFrame() >= 0) drawOk++
                    } catch (_: Throwable) {
                    }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        videoFrame.addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        left.addView(videoFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        stateView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        left.addView(stateView)
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))

        // Columna derecha: botones + registro
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(vararg b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        buttons.addView(row(btn("1. Buscar") { scan() }, btn("2. Conectar") { connect() }))
        buttons.addView(row(btn("Grabar on/off") { toggle(1, 0x4000, "grabación") }, btn("Bloquear clip") { toggle(2, 0x2000, "bloqueo") }))
        buttons.addView(row(btn("Foto") { send(7, byteArrayOf(1), "foto") }, btn("Micrófono") { toggle(6, 0x0400, "mute") }))
        buttons.addView(row(btn("Sincronizar hora") { syncTime() }, btn("Desconectar") { disconnect() }))
        buttons.addView(row(btn("Copiar registro") { copyLog() }))
        right.addView(buttons)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#C9D4DF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        right.addView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#1A1F26"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(logView)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        )
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)

        val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, 2)

        line("Prueba de dashcam. Antes: Ajustes → Apps → Traffic safety guard → Forzar detención.")
        line("Luego toca 1. Buscar y 2. Conectar.")
        loadLibrary()
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        main.post(renderTick)
        main.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(renderTick)
        main.removeCallbacks(uiTick)
        gl.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    // ------------------------------------------------------------------ Pasos

    private fun loadLibrary() {
        try {
            cam = UvcCamera()
            line("✓ Librería nativa cargada (proceso ${if (android.os.Process.is64Bit()) "64" else "32"} bits)")
        } catch (t: Throwable) {
            line("✗ No cargó la librería: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun scan() {
        devPath = null
        cmdPath = null
        val roots = linkedSetOf<String>()
        try {
            File("/proc/mounts").readLines().forEach { l ->
                val p = l.split(" ").getOrNull(1) ?: return@forEach
                if ((p.contains("usb", true) || p.contains("udisk", true)) && File(p).isDirectory) roots += p
            }
        } catch (e: Exception) {
            line("No se pudo leer /proc/mounts: ${e.message}")
        }
        roots += listOf("/storage/usbdisk0", "/mnt/media_rw/usbdisk0")
        line("— Buscando la cámara en: ${roots.joinToString()}")

        for (r in roots) {
            val dir = File(r)
            if (!dir.exists()) continue
            val listing = dir.list()?.joinToString() ?: "(sin permiso para listar)"
            line("  $r → $listing")
            for (name in listOf("VSFILE", "avc")) {
                val f = File(dir, name)
                if (f.exists()) {
                    line("  ✓ ${f.path}  lectura=${f.canRead()} escritura=${f.canWrite()} tamaño=${f.length()}")
                    if (devPath == null) devPath = f.path
                }
            }
            val pkgDir = File(dir, "Android/data/com.fvision.camera")
            val hwc = File(pkgDir, "HWC")
            line("  ${pkgDir.path}: existe=${pkgDir.exists()} contenido=${pkgDir.list()?.joinToString() ?: "(no listable)"}")
            line("  HWC: existe=${hwc.exists()} lectura=${hwc.canRead()} escritura=${hwc.canWrite()}")
            if (devPath != null && devPath!!.startsWith(r) && cmdPath == null) cmdPath = hwc.path
        }
        if (devPath == null) line("✗ No encontré VSFILE. ¿Está conectada la cámara y concedido el permiso de almacenamiento?")
        else line("→ Video: $devPath\n→ Comandos: $cmdPath")
    }

    private fun connect() {
        val c = cam ?: return line("✗ Librería no cargada")
        if (devPath == null) scan()
        val dp = devPath ?: return

        safe("callbacks") {
            c.nativeEventCallback(IEventCallback { ev -> main.post { line("evento de la cámara: $ev") } })
            c.nativeStateFrameCallback(IFrameCallback { frame ->
                stateFrames++
                if (frame != null && frame.size >= 8) lastState = frame.copyOf()
            })
        }

        safe("initUvcCamera") {
            val r = c.initUvcCamera(dp)
            videoFd = r.substringBefore('_').toIntOrNull() ?: -1
            line("initUvcCamera → \"$r\"  ${if (videoFd > 0) "✓" else "✗"}")
        }
        cmdPath?.let { cp ->
            safe("initUvcCmd") {
                val r = c.initUvcCmd(cp)
                cmdFd = r.substringBefore('_').toIntOrNull() ?: -1
                line("initUvcCmd → \"$r\"  ${if (cmdFd > 0) "✓" else "✗ (sin comandos: solo video)"}")
            }
        }
        if (videoFd <= 0) {
            line("✗ No se abrió el video.")
            return
        }
        safe("setMainRuning") { c.nativeSetMainRuning(true) }
        Thread {
            safe("stopPreview") { c.stopPreview(videoFd) }
            SystemClock.sleep(500)
            safe("startPreview") {
                val r = c.startPreview(videoFd)
                previewing = r >= 0
                main.post { line("startPreview → $r ${if (r >= 0) "✓ transmitiendo" else "✗"}") }
            }
        }.start()
        main.postDelayed({ syncTime() }, 1500)
    }

    private fun disconnect() {
        val c = cam ?: return
        if (videoFd > 0) {
            previewing = false
            safe("stopPreview") { c.stopPreview(videoFd) }
            safe("releaseUvcCamera") { c.releaseUvcCamera(videoFd) }
            line("Desconectado")
        }
        videoFd = -1
        cmdFd = -1
    }

    // ------------------------------------------------------------------ Comandos

    private fun send(cmd: Int, data: ByteArray, label: String) {
        val c = cam ?: return
        if (cmdFd <= 0) return line("✗ $label: no hay archivo de comandos abierto")
        safe("sendCmd") {
            val r = c.nativeSendCmd(cmdFd, -1, cmd, -1, -1, data.size, data)
            line("comando $label (#$cmd ${data.joinToString(",")}) → $r ${if (r >= 0) "✓" else "✗"}")
        }
    }

    /** Invierte un estado: si el bit está activo manda 0, si no manda 1 (igual que la app original). */
    private fun toggle(cmd: Int, bit: Int, label: String) {
        val on = flags()?.let { it and bit != 0 } ?: false
        send(cmd, byteArrayOf(if (on) 0 else 1), label)
    }

    private fun syncTime() {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val data = byteArrayOf(
            (y and 0xff).toByte(), (y shr 8 and 0xff).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(), cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(), cal.get(Calendar.MINUTE).toByte(), cal.get(Calendar.SECOND).toByte()
        )
        send(11, data, "hora")
    }

    // ------------------------------------------------------------------ Estado

    /** Bytes 0-1 (little-endian) del cuadro de estado = banderas. */
    private fun flags(): Int? = lastState?.let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) }

    private fun updateState() {
        val f = flags()
        val s = lastState
        val sb = StringBuilder()
        sb.append("Video fd=$videoFd  Comandos fd=$cmdFd  Dibujo: $drawOk/$drawCalls  Estados: $stateFrames\n")
        if (f == null || s == null) {
            sb.append("Sin datos de estado todavía")
        } else {
            fun b(bit: Int) = if (f and bit != 0) "SÍ" else "no"
            val curTime = (s[4].toInt() and 0xff) or ((s[5].toInt() and 0xff) shl 8)
            sb.append("Grabando: ${b(0x4000)}  Bloqueado: ${b(0x2000)}  Mute: ${b(0x0400)}  SD: ${b(0x0200)}  ")
            sb.append("Reproducción: ${b(0x8000)}  Movimiento: ${b(0x0080)}  t=$curTime\n")
            if (s.size > 50) sb.append("ADAS byte[50]=${s[50]}  ")
            sb.append("Crudo: ${s.take(16).joinToString(" ") { "%02X".format(it) }}  (largo ${s.size})")
        }
        stateView.text = sb.toString()
    }

    // ------------------------------------------------------------------ Utilidades

    private fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            main.post { line("✗ $what: ${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    private fun line(s: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.append("[$ts] ").append(s).append('\n')
        if (::logView.isInitialized) logView.text = log
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dashcam", stateView.text.toString() + "\n\n" + log))
        Toast.makeText(this, "Registro copiado", Toast.LENGTH_SHORT).show()
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
