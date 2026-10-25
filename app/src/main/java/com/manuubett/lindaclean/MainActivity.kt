package com.manuubett.lindaclean

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val teal = Color.parseColor("#0F766E")
    private val tealLight = Color.parseColor("#CCFBF1")
    private val ink = Color.parseColor("#0F172A")
    private val grey = Color.parseColor("#64748B")
    private val red = Color.parseColor("#DC2626")
    private val bg = Color.parseColor("#F1F5F9")

    private lateinit var prefs: SharedPreferences
    private lateinit var gate: PayGate
    private lateinit var root: LinearLayout

    private var allowed = false
    private var checking = true
    private var downloadId = -1L
    private var updateText = "Check for updates"

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == downloadId && id != -1L) installDownloaded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("lindaclean_settings", Context.MODE_PRIVATE)
        gate = PayGate(this, appId = "lindaclean")

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))

        scroll.addView(root)
        setContentView(scroll)

        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
        } catch (e: Exception) {
            // The download still happens; the user opens it from notifications
        }

        render()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Not registered
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        runGate()
    }

    // ---- Subscription ----

    private fun runGate() {
        checking = true
        render()

        gate.check(
            onDenied = {
                allowed = false
                checking = false
                render()
            },
            onAllowed = {
                allowed = true
                checking = false
                prefs.edit().putLong("sub_ok_at", System.currentTimeMillis()).apply()
                render()
            }
        )
    }

    // ---- Screen ----

    private fun render() {
        root.removeAllViews()

        title()

        if (checking && !allowed) {
            note("Checking your subscription…")
            footer()
            return
        }

        if (!allowed) {
            card().apply {
                addView(heading("Subscription needed"))
                addView(body("Your free trial or subscription has ended, so pop-up blocking is paused."))
                addView(button("Subscribe or renew", teal) { runGate() })
            }
            footer()
            return
        }

        statusCard()
        reportCard()
        switchesCard()
        cleanupCard()
        moreCard()
        footer()
    }

    private fun title() {
        val t = TextView(this)
        t.text = "Linda Clean"
        t.textSize = 26f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(teal)
        root.addView(t)

        val s = TextView(this)
        s.text = "Stops phone pop-ups and shows who is behind them"
        s.textSize = 14f
        s.setTextColor(grey)
        s.setPadding(0, 0, 0, dp(12))
        root.addView(s)
    }

    private fun statusCard() {
        val on = watchEnabled()
        val c = card()

        c.addView(heading(if (on) "Pop-up Watch is ON" else "Pop-up Watch is OFF"))

        if (on) {
            val n = PopupLog.closedToday(prefs)
            c.addView(body("Pop-ups closed today: $n"))
        } else {
            c.addView(
                body(
                    "Turn it on in Accessibility settings: find Linda Clean Pop-up Watch " +
                        "and switch it on. It only sees which app opened. It never reads " +
                        "your screen, messages or passwords."
                )
            )
            c.addView(button("Open Accessibility settings", teal) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            c.addView(
                body(
                    "On Tecno, Infinix and itel: if it switches itself off, also open " +
                        "Phone Master / Settings > Battery and allow Linda Clean to run in " +
                        "the background."
                )
            )
        }

        if (!isIgnoringBattery()) {
            c.addView(button("Keep it running (battery)", Color.parseColor("#475569")) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            })
        }
    }

    private fun reportCard() {
        val c = card()
        c.addView(heading("Who is popping up?"))

        val list = PopupLog.entries(prefs)
        val blocked = PopupLog.set(prefs, "blocked_apps")
        val ignored = PopupLog.set(prefs, "ignored_apps")

        if (list.isEmpty()) {
            c.addView(
                body(
                    "Nothing yet. Use your phone as usual. When an app opens by itself, " +
                        "it will appear here with a count."
                )
            )
            return
        }

        c.addView(body("Tap an app to close its pop-ups, ignore it, or remove it."))

        for (e in list.take(15)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))

            val name = TextView(this)
            name.text = labelOf(e.pkg) + if (blocked.contains(e.pkg)) "  · blocked" else if (ignored.contains(e.pkg)) "  · ignored" else ""
            name.textSize = 16f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(ink)
            row.addView(name)

            val parts = ArrayList<String>()
            if (e.ads > 0) parts.add("${e.ads} ad screens closed")
            if (e.unlock > 0) parts.add("${e.unlock} after unlock")
            if (e.self > 0) parts.add("${e.self} opened by itself")
            if (e.blocked > 0) parts.add("${e.blocked} blocked")

            val detail = TextView(this)
            detail.text = parts.joinToString(" · ")
            detail.textSize = 13f
            detail.setTextColor(grey)
            row.addView(detail)

            row.setOnClickListener { appDialog(e.pkg) }
            c.addView(row)
        }

        c.addView(button("Clear the list", Color.parseColor("#475569")) {
            PopupLog.clear(prefs)
            render()
        })
    }

    private fun appDialog(pkg: String) {
        val blocked = PopupLog.set(prefs, "blocked_apps").contains(pkg)
        val ignored = PopupLog.set(prefs, "ignored_apps").contains(pkg)

        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (blocked) {
            items.add("Stop closing this app")
            actions.add { PopupLog.removeFrom(prefs, "blocked_apps", pkg); render() }
        } else {
            items.add("Close its pop-ups automatically")
            actions.add {
                PopupLog.removeFrom(prefs, "ignored_apps", pkg)
                PopupLog.addTo(prefs, "blocked_apps", pkg)
                render()
            }
        }

        if (ignored) {
            items.add("Watch this app again")
            actions.add { PopupLog.removeFrom(prefs, "ignored_apps", pkg); render() }
        } else {
            items.add("Ignore (it is fine)")
            actions.add {
                PopupLog.removeFrom(prefs, "blocked_apps", pkg)
                PopupLog.addTo(prefs, "ignored_apps", pkg)
                render()
            }
        }

        items.add("App settings (permissions)")
        actions.add {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            )
        }

        items.add("Uninstall this app")
        actions.add {
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            } catch (e: Exception) {
                toast("Could not open the uninstall screen")
            }
        }

        items.add("Remove from this list")
        actions.add { PopupLog.forget(prefs, pkg); render() }

        AlertDialog.Builder(this)
            .setTitle(labelOf(pkg))
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchesCard() {
        val c = card()
        c.addView(heading("Settings"))

        c.addView(
            toggleRow(
                "Pop-up Watch",
                "Master switch for watching and closing",
                prefs.getBoolean("watch_on", true)
            ) { prefs.edit().putBoolean("watch_on", it).apply() }
        )

        c.addView(
            toggleRow(
                "Close known ad screens",
                "Closes full-screen ads from ad networks (Google, Meta, Unity, AppLovin and more)",
                prefs.getBoolean("auto_close", true)
            ) { prefs.edit().putBoolean("auto_close", it).apply() }
        )
    }

    private fun toggleRow(
        label: String,
        sub: String,
        on: Boolean,
        change: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL

        val a = TextView(this)
        a.text = label
        a.textSize = 16f
        a.setTextColor(ink)
        texts.addView(a)

        val b = TextView(this)
        b.text = sub
        b.textSize = 12f
        b.setTextColor(grey)
        texts.addView(b)

        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val sw = android.widget.Switch(this)
        sw.isChecked = on
        sw.setOnCheckedChangeListener { _, checked -> change(checked) }
        row.addView(sw)

        return row
    }

    private fun cleanupCard() {
        val c = card()
        c.addView(heading("Clean up the source"))
        c.addView(body("Blocking works best when you also remove what causes the pop-ups."))

        c.addView(button("Apps allowed to show over other apps", teal) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
                toast("Open an app, then turn off 'Display over other apps'")
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })

        c.addView(button("Tecno / Infinix / itel ads guide", Color.parseColor("#475569")) {
            AlertDialog.Builder(this)
                .setTitle("Switch off system ads")
                .setMessage(
                    "1. Settings > Apps > App management (or Phone Master) > turn off " +
                        "recommendations and promotions.\n\n" +
                        "2. Settings > Apps > three dots > Special access > Display over " +
                        "other apps: turn off for apps you do not trust.\n\n" +
                        "3. Long-press an ad notification > Settings > turn off that channel.\n\n" +
                        "4. Settings > Privacy > Ads > Delete advertising ID.\n\n" +
                        "5. Uninstall games or tools you do not use. They are the usual cause."
                )
                .setPositiveButton("OK", null)
                .show()
        })
    }

    private fun moreCard() {
        val c = card()
        c.addView(heading("More"))

        c.addView(button(updateText, Color.parseColor("#475569")) { checkUpdate() })

        c.addView(button("How it works", Color.parseColor("#475569")) {
            AlertDialog.Builder(this)
                .setTitle("How it works")
                .setMessage(
                    "Pop-up Watch sees which app opened, never what is on the screen.\n\n" +
                        "It closes known ad screens, and any app you choose to block, when " +
                        "they open by themselves. Apps you open yourself are never touched.\n\n" +
                        "Limits: it cannot remove ads inside an app you are using, and an app " +
                        "can still show its own custom pop-ups. Block or uninstall the app " +
                        "listed in the report."
                )
                .setPositiveButton("OK", null)
                .show()
        })
    }

    private fun footer() {
        val f = TextView(this)
        f.text = "Linda Clean build ${UpdateChecker.installedVersion(this)}\nPowered by dehemanuelssolutions.co.ke"
        f.textSize = 12f
        f.gravity = Gravity.CENTER
        f.setTextColor(grey)
        f.setPadding(0, dp(20), 0, 0)
        f.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dehemanuelssolutions.co.ke/")))
        }
        root.addView(f)
    }

    // ---- Updates ----

    private fun checkUpdate() {
        updateText = "Checking…"
        render()

        thread {
            try {
                val latest = UpdateChecker.fetchLatest()
                val mine = UpdateChecker.installedVersion(this)

                runOnUiThread {
                    if (latest != null && UpdateChecker.isNewer(latest.version, mine)) {
                        updateText = "Update ${latest.version} available"
                        render()

                        AlertDialog.Builder(this)
                            .setTitle("Update ${latest.version}")
                            .setMessage(if (latest.notes.isEmpty()) "A new version is ready." else latest.notes)
                            .setPositiveButton("Download") { _, _ -> startDownload(latest.docId) }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        updateText = "Up to date (${mine})"
                        render()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateText = "Check for updates"
                    render()
                    toast("Could not check. Try again with data on.")
                }
            }
        }
    }

    private fun startDownload(docId: String) {
        toast("Downloading…")

        thread {
            try {
                val url = UpdateChecker.requestDownloadUrl(docId)

                runOnUiThread {
                    val dir = getExternalFilesDir(null)
                    val file = File(dir, "LindaClean-update.apk")
                    if (file.exists()) file.delete()

                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle("Linda Clean update")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(this, null, "LindaClean-update.apk")

                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadId = dm.enqueue(req)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed. Try again.") }
            }
        }
    }

    private fun installDownloaded() {
        val file = File(getExternalFilesDir(null), "LindaClean-update.apk")
        if (!file.exists()) return

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow Linda Clean to install apps, then download again")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // targetSdk 28 lets us use a plain file URI
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(i)
    }

    // ---- Helpers ----

    private fun watchEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val me = ComponentName(this, PopupGuardService::class.java)

            enabled.split(":").any {
                ComponentName.unflattenFromString(it) == me
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isIgnoringBattery(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            true
        }
    }

    private fun labelOf(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))

        val shape = GradientDrawable()
        shape.setColor(Color.WHITE)
        shape.cornerRadius = dp(14).toFloat()
        c.background = shape

        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        root.addView(c, lp)

        return c
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(ink)
        t.setPadding(0, 0, 0, dp(6))
        return t
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(grey)
        t.setPadding(0, dp(2), 0, dp(6))
        return t
    }

    private fun note(text: String) {
        val c = card()
        c.addView(body(text))
    }

    private fun button(text: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 15f

        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape

        val lp = LinearLayout.LayoutParams(-1, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { click() }

        return b
    }
}
