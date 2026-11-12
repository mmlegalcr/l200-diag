package cr.andres.l200diag

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var reportView: TextView
    private lateinit var cameraButtons: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val cameraStatus = linkedMapOf<String, Boolean>()
    private var lastReport = ""

    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }

    private val refreshRunnable = Runnable { refresh() }

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            cameraStatus[cameraId] = true
            scheduleRefresh()
        }

        override fun onCameraUnavailable(cameraId: String) {
            cameraStatus[cameraId] = false
            scheduleRefresh()
        }
    }

    private val interestingKeywords = listOf(
        "waze", "spotify", "traffic", "safety", "dvr", "camera", "cam", "avm",
        "reverse", "backcar", "back_car", "rearview", "carsetting", "fyt", "syu",
        "ts18", "launcher", "obd", "torque", "carmate", "bluetooth", "ahd", "cvbs"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111418"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "L200 Diagnóstico"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        left.addView(button("Volver a escanear") { refresh() })
        left.addView(button("Compartir reporte") { shareReport() })
        left.addView(button("Guardar en Descargas") { saveReport() })
        left.addView(button("Copiar reporte") { copyReport() })
        left.addView(button("Probar dashcam (USB DVR)") {
            startActivity(Intent(this, DashcamTestActivity::class.java))
        })
        left.addView(TextView(this).apply {
            text = "Probar cámaras:"
            setTextColor(Color.parseColor("#9FB3C8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(16), 0, dp(6))
        })
        cameraButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(cameraButtons)

        val leftScroll = ScrollView(this).apply { addView(left) }
        root.addView(leftScroll, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT))

        reportView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val rightScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1A1F26"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(reportView)
        }
        root.addView(
            rightScroll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) }
        )

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        try {
            cameraManager.registerAvailabilityCallback(availability, handler)
        } catch (_: Exception) {
        }
        scheduleRefresh()
    }

    override fun onPause() {
        super.onPause()
        try {
            cameraManager.unregisterAvailabilityCallback(availability)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 400)
    }

    private fun refresh() {
        lastReport = try {
            buildReport()
        } catch (e: Exception) {
            "Error generando el reporte: ${e.javaClass.simpleName}: ${e.message}"
        }
        reportView.text = lastReport
        rebuildCameraButtons()
    }

    // ---------------------------------------------------------------- Reporte

    private fun buildReport(): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("REPORTE L200 DIAGNÓSTICO v0.4 — $now")
        sb.appendLine("Tip: corre el escaneo una vez con el DVR abierto y otra con el DVR cerrado.")

        section(sb, "1. SISTEMA") { appendSystem(it) }
        section(sb, "2. FUNCIONES DEL SISTEMA") { appendFeatures(it) }
        section(sb, "3. CÁMARAS (Camera2)") { appendCameras(it) }
        section(sb, "4. NODOS DE VIDEO (/dev/video*)") { appendVideoNodes(it) }
        section(sb, "5. DISPOSITIVOS USB") { appendUsb(it) }
        section(sb, "6. LAUNCHER") { appendLauncher(it) }
        section(sb, "7. APPS CON ÍCONO") { appendLauncherApps(it) }
        section(sb, "8. PAQUETES DEL SISTEMA RELEVANTES") { appendSystemPackages(it) }
        section(sb, "9. PROPIEDADES DEL SISTEMA RELEVANTES") { appendProps(it) }
        section(sb, "10. USB A NIVEL KERNEL (/sys/bus/usb)") { appendUsbSysfs(it) }
        section(sb, "11. VOLÚMENES DE ALMACENAMIENTO") { appendVolumes(it) }
        section(sb, "12. ANÁLISIS DE APPS DE CÁMARA") {
            appendAppAnalysis(it, "com.fvision.camera")
            appendAppAnalysis(it, "com.tw.reverse")
        }

        sb.appendLine()
        sb.appendLine("— fin del reporte —")
        return sb.toString()
    }

    private fun section(sb: StringBuilder, title: String, body: (StringBuilder) -> Unit) {
        sb.appendLine()
        sb.appendLine("=== $title ===")
        try {
            body(sb)
        } catch (e: Exception) {
            sb.appendLine("  (error: ${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun appendSystem(sb: StringBuilder) {
        val dm = resources.displayMetrics
        sb.appendLine("Fabricante:   ${Build.MANUFACTURER}")
        sb.appendLine("Modelo:       ${Build.MODEL}")
        sb.appendLine("Dispositivo:  ${Build.DEVICE}")
        sb.appendLine("Producto:     ${Build.PRODUCT}")
        sb.appendLine("Placa:        ${Build.BOARD}")
        sb.appendLine("Hardware:     ${Build.HARDWARE}")
        sb.appendLine("Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build:        ${Build.DISPLAY}")
        sb.appendLine("Pantalla:     ${dm.widthPixels}x${dm.heightPixels} px, densidad ${dm.densityDpi} dpi")
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        sb.appendLine("Permiso cámara concedido: ${if (granted) "sí" else "NO"}")
    }

    private fun appendFeatures(sb: StringBuilder) {
        val pm = packageManager
        val features = listOf(
            PackageManager.FEATURE_CAMERA_ANY to "Alguna cámara",
            PackageManager.FEATURE_CAMERA_EXTERNAL to "Cámaras externas USB (UVC)",
            PackageManager.FEATURE_USB_HOST to "USB host",
            PackageManager.FEATURE_BLUETOOTH to "Bluetooth clásico",
            PackageManager.FEATURE_BLUETOOTH_LE to "Bluetooth LE",
            PackageManager.FEATURE_LOCATION_GPS to "GPS",
            PackageManager.FEATURE_PICTURE_IN_PICTURE to "Picture-in-picture",
            PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT to "Ventanas flotantes (freeform)",
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS to "Apps en pantallas secundarias"
        )
        for ((key, label) in features) {
            sb.appendLine("%-34s %s".format(label, if (pm.hasSystemFeature(key)) "SÍ" else "no"))
        }
        val globals = listOf("enable_freeform_support", "force_resizable_activities", "development_settings_enabled")
        for (k in globals) {
            val v = try {
                Settings.Global.getString(contentResolver, k)
            } catch (_: Exception) {
                null
            }
            sb.appendLine("Global.$k = ${v ?: "(sin valor)"}")
        }
    }

    private fun appendCameras(sb: StringBuilder) {
        val ids = cameraManager.cameraIdList
        sb.appendLine("Cámaras detectadas por Camera2: ${ids.size}")
        if (ids.isEmpty()) {
            sb.appendLine("  Ninguna. Las cámaras no están expuestas a apps normales por Camera2.")
        }
        for (id in ids) {
            val c = cameraManager.getCameraCharacteristics(id)
            val status = when (cameraStatus[id]) {
                true -> "LIBRE"
                false -> "EN USO por otra app"
                null -> "desconocido"
            }
            sb.appendLine()
            sb.appendLine("  Cámara id=$id  [$status]")
            sb.appendLine("    Orientación del lente: ${facingText(c.get(CameraCharacteristics.LENS_FACING))}")
            sb.appendLine("    Nivel de hardware:     ${levelText(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
            sb.appendLine("    Rotación del sensor:   ${c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: "?"}°")
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.sortedByDescending { it.width * it.height }
                ?.take(6)
                ?.joinToString { "${it.width}x${it.height}" }
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.sortedByDescending { it.width * it.height }
                ?.take(4)
                ?.joinToString { "${it.width}x${it.height}" }
            sb.appendLine("    Tamaños de vista previa: ${previewSizes ?: "?"}")
            sb.appendLine("    Tamaños YUV:             ${yuvSizes ?: "?"}")
            val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.joinToString { "${it.lower}-${it.upper}" }
            sb.appendLine("    Rangos FPS:              ${fps ?: "?"}")
            if (Build.VERSION.SDK_INT >= 28) {
                val phys = c.physicalCameraIds
                if (phys.isNotEmpty()) sb.appendLine("    Cámaras físicas: ${phys.joinToString()}")
            }
        }
        @Suppress("DEPRECATION")
        val legacy = try {
            android.hardware.Camera.getNumberOfCameras().toString()
        } catch (e: Exception) {
            "error (${e.message})"
        }
        sb.appendLine()
        sb.appendLine("API antigua (android.hardware.Camera): $legacy cámaras")
    }

    private fun appendVideoNodes(sb: StringBuilder) {
        val files = File("/dev").listFiles()
        if (files == null) {
            sb.appendLine("Sin acceso a /dev (bloqueado por el sistema).")
            return
        }
        val nodes = files.filter { it.name.startsWith("video") || it.name.startsWith("v4l") }.sortedBy { it.name }
        if (nodes.isEmpty()) {
            sb.appendLine("Ningún nodo de video visible.")
        }
        for (f in nodes) {
            sb.appendLine("  /dev/${f.name}  lectura=${if (f.canRead()) "sí" else "no"}  escritura=${if (f.canWrite()) "sí" else "no"}")
        }
    }

    private fun appendUsb(sb: StringBuilder) {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values
        if (devices.isEmpty()) {
            sb.appendLine("Ningún dispositivo USB visible para apps.")
            return
        }
        for (d in devices) {
            val name = try {
                "${d.manufacturerName ?: "?"} ${d.productName ?: ""}".trim()
            } catch (_: SecurityException) {
                "(nombre requiere permiso USB)"
            }
            sb.appendLine("  ${d.deviceName}  VID=0x%04X PID=0x%04X  clase=%d  %s".format(d.vendorId, d.productId, d.deviceClass, name))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                val tag = when (itf.interfaceClass) {
                    14 -> "  <-- VIDEO (UVC)"
                    1 -> "  (audio)"
                    3 -> "  (HID)"
                    8 -> "  (almacenamiento)"
                    9 -> "  (hub)"
                    224 -> "  (inalámbrico/BT)"
                    255 -> "  (propietario del fabricante)"
                    else -> ""
                }
                sb.appendLine("      interfaz $i: clase=${itf.interfaceClass} subclase=${itf.interfaceSubclass}$tag")
            }
        }
    }

    private fun appendLauncher(sb: StringBuilder) {
        val pm = packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        sb.appendLine("Launcher actual: ${current?.packageName ?: "?"} / ${current?.name ?: "?"}")
        val all = pm.queryIntentActivities(home, 0)
        sb.appendLine("Launchers instalados:")
        for (r in all) {
            sb.appendLine("  - ${r.loadLabel(pm)}  (${r.activityInfo.packageName}/${r.activityInfo.name})")
        }
    }

    private fun appendLauncherApps(sb: StringBuilder) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.first.lowercase() }
        sb.appendLine("Total: ${apps.size}   (★ = relevante)")
        for ((label, pkg, act) in apps) {
            val star = if (isInteresting(label) || isInteresting(pkg)) "★" else " "
            sb.appendLine(" $star $label  ->  $pkg/$act")
        }
    }

    private fun appendSystemPackages(sb: StringBuilder) {
        val pm = packageManager
        @Suppress("DEPRECATION")
        val pkgs = pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { isInteresting(it) }
            .sorted()
        sb.appendLine("Coincidencias: ${pkgs.size} (de ${pm.getInstalledPackages(0).size} paquetes)")
        for (p in pkgs) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
            } catch (_: Exception) {
                "?"
            }
            sb.appendLine("  $p  ($label)")
        }
    }

    private fun appendProps(sb: StringBuilder) {
        val keys = listOf(
            "camera", "avm", "reverse", "backcar", "back_car", "cvbs", "ahd", "dvr",
            "carsetting", "fyt", "syu", "ts18", "uis", "unisoc", "sprd", "ro.board",
            "ro.product", "ro.build.display", "ccd", "video", "launcher", "mcu", "canbus"
        )
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val lines = out.lineSequence()
            .filter { line -> val l = line.lowercase(); keys.any { l.contains(it) } }
            .take(150)
            .toList()
        if (lines.isEmpty()) sb.appendLine("Sin coincidencias (o getprop bloqueado).")
        lines.forEach { sb.appendLine("  $it") }
    }

    private fun appendUsbSysfs(sb: StringBuilder) {
        val root = File("/sys/bus/usb/devices")
        val entries = root.listFiles()
        if (entries == null) {
            sb.appendLine("Sin acceso a /sys/bus/usb/devices (bloqueado por SELinux).")
            return
        }
        sb.appendLine("Entradas: ${entries.size}")
        for (d in entries.sortedBy { it.name }) {
            fun r(name: String): String = try {
                File(d, name).takeIf { it.canRead() }?.readText()?.trim() ?: ""
            } catch (_: Exception) {
                ""
            }
            val vid = r("idVendor")
            val pid = r("idProduct")
            val ifClass = r("bInterfaceClass")
            if (vid.isNotEmpty()) {
                sb.appendLine("  ${d.name}: VID=$vid PID=$pid  ${r("manufacturer")} ${r("product")}  clase=${r("bDeviceClass")} velocidad=${r("speed")}Mbps")
            } else if (ifClass.isNotEmpty()) {
                val tag = when (ifClass.lowercase()) {
                    "0e" -> "  <-- VIDEO (UVC)"
                    "08" -> "  (almacenamiento masivo)"
                    "ff" -> "  (propietario)"
                    "01" -> "  (audio)"
                    "09" -> "  (hub)"
                    else -> ""
                }
                val drv = try {
                    File(d, "driver").canonicalFile.name
                } catch (_: Exception) {
                    "?"
                }
                sb.appendLine("      ${d.name}: interfaz clase=$ifClass sub=${r("bInterfaceSubClass")} driver=$drv$tag")
            }
        }
    }

    private fun appendVolumes(sb: StringBuilder) {
        val sm = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
        for (v in sm.storageVolumes) {
            val path = if (Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath else null
            sb.appendLine("  ${v.getDescription(this)}  estado=${v.state}  removible=${v.isRemovable}  uuid=${v.uuid ?: "-"}  ${path ?: ""}")
        }
        try {
            val mounts = File("/proc/mounts").readLines()
                .filter { it.contains("/mnt/media_rw") || it.contains("/storage/") && !it.contains("emulated") }
                .take(20)
            if (mounts.isNotEmpty()) sb.appendLine("Montajes externos:")
            mounts.forEach { sb.appendLine("  $it") }
        } catch (_: Exception) {
        }
    }

    private fun appendAppAnalysis(sb: StringBuilder, pkg: String) {
        val pm = packageManager
        sb.appendLine()
        sb.appendLine("--- $pkg ---")
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
        val info = try {
            pm.getPackageInfo(pkg, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            sb.appendLine("No instalado.")
            return
        }
        val ai = info.applicationInfo
        sb.appendLine("Versión: ${info.versionName} (${info.longVersionCode})  app de sistema: ${(ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0}")
        sb.appendLine("APK: ${ai.sourceDir}")

        val perms = info.requestedPermissions?.map { it.removePrefix("android.permission.") }?.sorted()
        sb.appendLine("Permisos: ${perms?.joinToString() ?: "-"}")

        // Librerías nativas: sus nombres delatan el protocolo (libusb, libuvc, jpeg, h264…)
        val libs = mutableSetOf<String>()
        ai.nativeLibraryDir?.let { dir -> File(dir).listFiles()?.forEach { libs += it.name } }
        try {
            java.util.zip.ZipFile(ai.sourceDir).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .forEach { libs += it.name.substringAfterLast('/') }
            }
        } catch (_: Exception) {
        }
        sb.appendLine("Librerías nativas: ${if (libs.isEmpty()) "ninguna" else libs.sorted().joinToString()}")

        // Filtros USB declarados en el manifiesto (meta-data android.hardware.usb.action.USB_DEVICE_ATTACHED)
        val usbIntent = Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED").setPackage(pkg)
        val usbActs = pm.queryIntentActivities(usbIntent, PackageManager.GET_META_DATA)
        sb.appendLine("Reacciona a USB conectado: ${if (usbActs.isEmpty()) "no" else usbActs.joinToString { it.activityInfo.name }}")
        for (r in usbActs) {
            val xmlRes = r.activityInfo.metaData?.getInt("android.hardware.usb.action.USB_DEVICE_ATTACHED") ?: 0
            if (xmlRes != 0) {
                try {
                    val res = pm.getResourcesForApplication(ai)
                    val parser = res.getXml(xmlRes)
                    var ev = parser.eventType
                    while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "usb-device") {
                            val attrs = (0 until parser.attributeCount).joinToString { "${parser.getAttributeName(it)}=${parser.getAttributeValue(it)}" }
                            sb.appendLine("    filtro usb-device: $attrs")
                        }
                        ev = parser.next()
                    }
                } catch (e: Exception) {
                    sb.appendLine("    (no se pudo leer el filtro: ${e.message})")
                }
            }
        }

        info.services?.let { s -> sb.appendLine("Servicios: ${s.joinToString { it.name.substringAfterLast('.') }}") }
        info.receivers?.let { s -> sb.appendLine("Receptores: ${s.joinToString { it.name.substringAfterLast('.') }}") }
        info.providers?.let { s -> sb.appendLine("Proveedores: ${s.joinToString { "${it.name.substringAfterLast('.')} (${it.authority})" }}") }
        info.activities?.let { s -> sb.appendLine("Actividades: ${s.joinToString { it.name.substringAfterLast('.') }}") }
    }

    private fun isInteresting(s: String): Boolean {
        val l = s.lowercase()
        return interestingKeywords.any { l.contains(it) }
    }

    private fun facingText(v: Int?): String = when (v) {
        CameraMetadata.LENS_FACING_FRONT -> "FRONT (hacia el conductor)"
        CameraMetadata.LENS_FACING_BACK -> "BACK"
        CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL (USB/UVC)"
        null -> "?"
        else -> "valor $v"
    }

    private fun levelText(v: Int?): String = when (v) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        4 -> "EXTERNAL"
        null -> "?"
        else -> "valor $v"
    }

    // ---------------------------------------------------------------- Botones de cámara

    private fun rebuildCameraButtons() {
        cameraButtons.removeAllViews()
        val ids = try {
            cameraManager.cameraIdList.toList()
        } catch (_: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) {
            cameraButtons.addView(TextView(this).apply {
                text = "No hay cámaras accesibles."
                setTextColor(Color.parseColor("#FF8A80"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }
        if (ids.size > 1) {
            cameraButtons.addView(button("Ver TODAS a la vez") {
                startActivity(Intent(this, MultiPreviewActivity::class.java))
            })
        }
        for (id in ids) {
            val facing = try {
                when (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_FRONT -> "front"
                    CameraMetadata.LENS_FACING_BACK -> "back"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "USB"
                    else -> "?"
                }
            } catch (_: Exception) {
                "?"
            }
            val busy = if (cameraStatus[id] == false) " · en uso" else ""
            cameraButtons.addView(button("Ver cámara $id ($facing)$busy") {
                startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_ID, id))
            })
        }
    }

    // ---------------------------------------------------------------- Exportar

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Reporte L200 Diagnóstico")
            putExtra(Intent.EXTRA_TEXT, lastReport)
        }
        try {
            startActivity(Intent.createChooser(send, "Compartir reporte"))
        } catch (e: Exception) {
            toast("No hay apps para compartir: ${e.message}")
        }
    }

    private fun saveReport() {
        val name = "l200_diagnostico_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no se pudo crear el archivo")
                contentResolver.openOutputStream(uri)?.use { it.write(lastReport.toByteArray()) }
                toast("Guardado en Descargas/$name")
            } else {
                val f = File(getExternalFilesDir(null), name)
                f.writeText(lastReport)
                toast("Guardado en ${f.absolutePath}")
            }
        } catch (e: Exception) {
            toast("Error al guardar: ${e.message}")
        }
    }

    private fun copyReport() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Reporte L200", lastReport))
        toast("Reporte copiado")
    }

    // ---------------------------------------------------------------- Utilidades UI

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
