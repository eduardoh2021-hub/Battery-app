package es.edu.saludbateria

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val liveKeys = listOf(
        "Nivel", "Estado", "Fuente", "Corriente", "Potencia", "Voltaje", "Temperatura",
        "Contador de carga", "Capacidad según el chip", "Ciclos de carga",
        "Salud (sistema)", "Salud % (sistema)", "Capacidad completa (kernel)", "Tecnología",
    )

    private lateinit var db: Db
    private lateinit var pal: Palette
    private lateinit var content: LinearLayout
    private lateinit var summaryCard: LinearLayout
    private lateinit var chart: ChartView
    private lateinit var historyCard: LinearLayout
    private lateinit var monitorButton: Button
    private lateinit var designButton: Button
    private val live = LinkedHashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    private val liveTick = object : Runnable {
        override fun run() {
            refreshLive()
            handler.postDelayed(this, 3_000)
        }
    }

    private val historyTick = object : Runnable {
        override fun run() {
            refreshHistory()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        pal = Palette.of(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)

        buildUi()
        if (Prefs.monitoring(this)) startMonitor()
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        handler.post(liveTick)
        handler.post(historyTick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---------- Construcción de la interfaz ----------

    private fun buildUi() {
        content.addView(tv("Salud de la batería", 26f, pal.text, bold = true))
        val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        content.addView(tv(model, 13f, pal.sub).apply { setPadding(0, dp(2), 0, dp(14)) })

        summaryCard = card()
        content.addView(summaryCard)

        val liveCard = card()
        liveCard.addView(cardTitle("Ahora mismo"))
        for (k in liveKeys) {
            val r = row(k, "…")
            live[k] = r.getChildAt(1) as TextView
            liveCard.addView(r)
        }
        content.addView(liveCard)

        val chartCard = card()
        chartCard.addView(cardTitle("Capacidad estimada en cada carga"))
        chart = ChartView(this, pal)
        chartCard.addView(chart)
        chartCard.addView(tv("Línea discontinua: capacidad de diseño.", 12f, pal.sub).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(chartCard)

        historyCard = card()
        content.addView(historyCard)

        val settings = card()
        settings.addView(cardTitle("Ajustes"))
        designButton = button("Capacidad de diseño") { editDesign() }
        settings.addView(designButton)
        monitorButton = button("") { toggleMonitor() }
        settings.addView(monitorButton)
        settings.addView(button("No optimizar batería (recomendado)") { batteryOptimization() })
        settings.addView(button("Ajustes de la app (inicio automático)") { openAppSettings() })
        settings.addView(button("Exportar historial (CSV)") { exportCsv() })
        settings.addView(
            tv(
                "MagicOS cierra las apps en segundo plano. Para que no se pierda ninguna carga: " +
                    "Ajustes > Batería > Inicio de aplicaciones > Salud Batería > Gestionar manualmente " +
                    "y activa las tres opciones. Mantén también la notificación activada.",
                13f, pal.sub
            ).apply { setPadding(0, dp(10), 0, 0) }
        )
        content.addView(settings)
        updateMonitorButton()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun tv(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun cardTitle(s: String) = tv(s, 17f, pal.text, bold = true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(pal.card)
            cornerRadius = dp(18).toFloat()
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun row(label: String, value: String, valueColor: Int = pal.text) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(5))
        addView(
            tv(label, 15f, pal.sub),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            tv(value, 15f, valueColor).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    // ---------- Datos en vivo ----------

    private fun setLive(key: String, value: String) {
        live[key]?.text = value
    }

    private fun refreshLive() {
        val s = BatteryReader.read(this) ?: return
        val na = "No disponible"
        setLive("Nivel", "${s.level} %")
        setLive("Estado", BatteryReader.statusText(s.status))
        setLive("Fuente", BatteryReader.plugText(s.plugged))
        setLive("Corriente", s.currentUa?.let { "${if (it > 0) "+" else ""}${Fmt.int(it / 1000)} mA" } ?: na)
        setLive("Potencia", s.currentUa?.let { Fmt.dec(abs(it) / 1e6 * s.voltageMv / 1000.0, 1) + " W" } ?: na)
        setLive("Voltaje", Fmt.dec(s.voltageMv / 1000.0, 2) + " V")
        setLive("Temperatura", Fmt.dec(s.tempDeci / 10.0, 1) + " °C")
        setLive("Contador de carga", s.counterUah?.let { Fmt.mah(it / 1000.0) } ?: na)
        setLive("Capacidad según el chip", s.capacityFromCounterMah?.let { Fmt.mah(it) } ?: na)
        setLive("Ciclos de carga", (s.cycles ?: s.kernelCycles)?.toString() ?: na)
        setLive("Salud (sistema)", BatteryReader.healthText(s.health))
        setLive("Salud % (sistema)", s.sohPercent?.let { "$it %" } ?: na)
        setLive("Capacidad completa (kernel)", s.kernelFullMah?.let { Fmt.mah(it) } ?: na)
        setLive("Tecnología", s.technology ?: na)
    }

    // ---------- Resumen, gráfica e historial ----------

    private fun refreshHistory() {
        val all = db.sessions(400)
        val done = all.filter { it.endTs != null }
        val design = Prefs.designCapacity(this)
        val stats = Stats.compute(done)
        val primary = stats.primary

        summaryCard.removeAllViews()
        summaryCard.addView(cardTitle("Salud estimada"))
        val big: String
        val color: Int
        if (primary != null && design != null) {
            val pct = primary / design.first * 100
            big = "${pct.roundToInt()} %"
            color = when {
                pct >= 85 -> pal.accent
                pct >= 70 -> pal.warn
                else -> pal.bad
            }
        } else if (primary != null) {
            big = Fmt.mah(primary)
            color = pal.text
        } else {
            big = "—"
            color = pal.sub
        }
        summaryCard.addView(tv(big, 44f, color, bold = true))
        val explain = when {
            primary == null ->
                "Todavía no hay cargas suficientes. Carga al menos un $MIN_DELTA_LEVEL % seguido con la app " +
                    "instalada y aparecerá la primera estimación. Con 5–10 cargas el dato se estabiliza."
            design == null -> "Indica la capacidad de diseño en Ajustes para calcular el porcentaje."
            else -> "Capacidad real estimada ${Fmt.mah(primary)} de ${Fmt.mah(design.first)} de diseño."
        }
        summaryCard.addView(tv(explain, 14f, pal.sub).apply { setPadding(0, 0, 0, dp(8)) })
        summaryCard.addView(row("Capacidad de diseño", design?.let { "${Fmt.mah(it.first)} (${it.second})" } ?: "Desconocida"))
        summaryCard.addView(row("Estimada · corriente medida", Fmt.mah(stats.byCurrent)))
        summaryCard.addView(row("Estimada · contador del chip", Fmt.mah(stats.byCounter)))
        summaryCard.addView(row("Cargas registradas", done.size.toString()))
        summaryCard.addView(row("Cargas útiles (≥ $MIN_DELTA_LEVEL %)", stats.used.toString()))

        designButton.text = "Capacidad de diseño: " + (design?.let { Fmt.mah(it.first) } ?: "sin indicar")
        chart.setData(stats.points, design?.first)

        historyCard.removeAllViews()
        historyCard.addView(cardTitle("Últimas cargas"))
        if (all.isEmpty()) {
            historyCard.addView(tv("Aún no hay cargas registradas. Enchufa el móvil y aparecerán aquí.", 14f, pal.sub))
        } else {
            historyCard.addView(tv("Mantén pulsada una carga para borrarla.", 12f, pal.sub).apply { setPadding(0, 0, 0, dp(4)) })
        }
        for (s in all.take(40)) historyCard.addView(sessionView(s))
    }

    private fun sessionView(s: Session): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val end = s.endTs
        val head = if (end == null) {
            "${Fmt.date(s.startTs)} · en curso (${Fmt.dur(s.lastTs - s.startTs)})"
        } else {
            "${Fmt.date(s.startTs)} → ${Fmt.time(end)} · ${Fmt.dur(end - s.startTs)}"
        }
        box.addView(tv(head, 15f, pal.text, bold = true))

        val parts = mutableListOf("${s.startLevel} % → ${s.endLevel} %")
        s.mahBest?.let { parts.add("+${Fmt.mah(it)}") }
        if (s.maxTempDeci > 0) parts.add("máx ${Fmt.dec(s.maxTempDeci / 10.0, 1)} °C")
        parts.add(BatteryReader.plugText(s.plug))
        box.addView(tv(parts.joinToString(" · "), 14f, pal.sub))

        val est = s.estimate
        val (msg, col) = when {
            est != null -> "Capacidad estimada: ${Fmt.mah(est)}" to pal.accent
            end == null -> "Midiendo…" to pal.sub
            else -> "Carga corta: no sirve para estimar" to pal.sub
        }
        box.addView(tv(msg, 14f, col))

        if (end != null) {
            box.setOnLongClickListener {
                confirmDelete(s)
                true
            }
        }
        return box
    }

    // ---------- Acciones ----------

    private fun startMonitor() {
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (t: Throwable) {
            Toast.makeText(this, "No se pudo iniciar la monitorización", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleMonitor() {
        val on = !Prefs.monitoring(this)
        Prefs.setMonitoring(this, on)
        if (on) startMonitor() else stopService(Intent(this, MonitorService::class.java))
        updateMonitorButton()
    }

    private fun updateMonitorButton() {
        monitorButton.text = if (Prefs.monitoring(this)) {
            "Monitorización activada · tocar para pausar"
        } else {
            "Monitorización en pausa · tocar para activar"
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun editDesign() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "p. ej. 5000"
            val cur = Prefs.designOverride(this@MainActivity)
            if (cur > 0) setText(cur.toString())
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Capacidad de diseño (mAh)")
            .setMessage("Búscala en la ficha técnica de tu modelo. Déjalo vacío para usar la que informe el sistema.")
            .setView(wrap)
            .setPositiveButton("Guardar") { _, _ ->
                Prefs.setDesignOverride(this, input.text.toString().trim().toIntOrNull() ?: 0)
                refreshHistory()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(s: Session) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar la carga del ${Fmt.date(s.startTs)} del historial?")
            .setPositiveButton("Borrar") { _, _ ->
                db.delete(s.id)
                refreshHistory()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun batteryOptimization() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Ya está excluida de la optimización de batería", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        } catch (t: Throwable) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (t: Throwable) {
            Toast.makeText(this, "No se pudieron abrir los ajustes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportCsv() {
        val rows = db.sessions(5_000).filter { it.endTs != null }.reversed()
        if (rows.isEmpty()) {
            Toast.makeText(this, "Aún no hay cargas que exportar", Toast.LENGTH_SHORT).show()
            return
        }
        fun n(v: Double?) = v?.let { Fmt.dec(it, 0) } ?: ""
        val sb = StringBuilder(
            "inicio;fin;minutos;nivel_inicio;nivel_fin;mah_contador;mah_corriente;" +
                "capacidad_est_contador;capacidad_est_corriente;temp_max_c;ciclos;fuente\n"
        )
        for (s in rows) {
            val end = s.endTs ?: s.lastTs
            sb.append(Fmt.full(s.startTs)).append(';')
                .append(Fmt.full(end)).append(';')
                .append((end - s.startTs) / 60_000).append(';')
                .append(s.startLevel).append(';')
                .append(s.endLevel).append(';')
                .append(n(s.mahCounter)).append(';')
                .append(n(s.mahCurrent)).append(';')
                .append(n(s.estCounter)).append(';')
                .append(n(s.estCurrent)).append(';')
                .append(Fmt.dec(s.maxTempDeci / 10.0, 1)).append(';')
                .append(s.cycles?.toString() ?: "").append(';')
                .append(BatteryReader.plugText(s.plug)).append('\n')
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Historial de cargas de la batería")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "Exportar historial"))
    }
}
