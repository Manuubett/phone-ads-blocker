package com.manuubett.lindaclean

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Pop-up Watch.
 *
 * It looks only at which app's window came to the front (package and
 * screen name). It cannot read what is on the screen.
 *
 *  1. A known ad-network screen opens: close it (Back) and count it.
 *  2. An app comes to the front by itself, with no tap from the user
 *     and not shortly after the app was already in use: write it in the
 *     list so the user can see who is behind the pop-ups.
 *  3. An app the user put on the block list opens by itself: close it.
 */
class PopupGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences("lindaclean_settings", Context.MODE_PRIVATE)
    }

    private var lastClickAt = 0L
    private var lastClickPkg = ""
    private var lastUnlockAt = 0L
    private var currentPkg: String? = null

    // When each app was last in front (to ignore the same app reappearing)
    private val recentFront = HashMap<String, Long>()

    private var systemPkgs: Set<String> = emptySet()
    private var systemPkgsAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastUnlockAt = System.currentTimeMillis()
        }
    }

    // Screens that ad networks use for full-screen ads
    private val knownAdScreens = setOf(
        "com.google.android.gms.ads.AdActivity",
        "com.facebook.ads.AudienceNetworkActivity",
        "com.unity3d.ads.adunit.AdUnitActivity",
        "com.unity3d.ads.adunit.AdUnitSoftwareActivity",
        "com.unity3d.services.ads.adunit.AdUnitActivity",
        "com.unity3d.services.ads.adunit.AdUnitTransparentActivity",
        "com.unity3d.services.ads.adunit.AdUnitSoftwareActivity",
        "com.applovin.adview.AppLovinFullscreenActivity",
        "com.applovin.adview.AppLovinInterstitialActivity",
        "com.vungle.warren.ui.VungleActivity",
        "com.vungle.ads.internal.ui.VungleActivity",
        "com.ironsource.sdk.controller.ControllerActivity",
        "com.ironsource.sdk.controller.InterstitialActivity",
        "com.mbridge.msdk.interstitial.view.MBInterstitialActivity",
        "com.inmobi.rendering.InMobiAdActivity",
        "com.adcolony.sdk.AdColonyInterstitialActivity",
        "com.bytedance.sdk.openadsdk.activity.TTInterstitialActivity",
        "com.bytedance.sdk.openadsdk.activity.TTFullScreenVideoActivity",
        "com.startapp.sdk.adsbase.activities.OverlayActivity",
        "com.startapp.sdk.adsbase.activities.FullScreenActivity",
        "com.mopub.mobileads.MoPubActivity",
        "com.mopub.mobileads.MraidActivity",
        "com.smaato.sdk.interstitial.InterstitialAdActivity"
    )

    // System screens that open by themselves for good reasons
    private val alwaysSystem = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.deskclock",
        "com.google.android.deskclock"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        prefs.edit()
            .putLong("watch_connected_at", System.currentTimeMillis())
            .apply()

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }

            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            // Without it, the "right after unlock" label is simply not used
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // Not registered
        }

        super.onDestroy()
    }

    override fun onInterrupt() {
        // Nothing to clean up
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                lastClickAt = now
                lastClickPkg = event.packageName?.toString() ?: ""
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindow(event, now)
        }
    }

    private fun entitled(now: Long): Boolean {
        // The app checks the subscription whenever it is opened. The watch
        // keeps working for 14 days after the last successful check.
        val since = now - prefs.getLong("sub_ok_at", 0L)

        return since in 0L..(14L * 86_400_000L)
    }

    private fun handleWindow(event: AccessibilityEvent, now: Long) {
        val pkg = event.packageName?.toString() ?: return
        val screen = event.className?.toString() ?: ""

        if (pkg == packageName) return
        if (!prefs.getBoolean("watch_on", true)) return
        if (!entitled(now)) return
        if (isSystem(pkg, now)) return

        currentPkg = pkg

        val ignored = PopupLog.set(prefs, "ignored_apps")

        // 1) A known ad screen: close it
        if (
            prefs.getBoolean("auto_close", true) &&
            !ignored.contains(pkg) &&
            isAdScreen(screen)
        ) {
            PopupLog.record(prefs, pkg, PopupLog.KIND_AD_SCREEN)
            PopupLog.addClosed(prefs)
            recentFront[pkg] = now

            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // 2) Did the user open this app (a tap on the launcher, a
        //    notification, or inside the app itself)?
        val tappedRecently = now - lastClickAt < 6000L
        val tapWasHandOff = tappedRecently &&
            (lastClickPkg == pkg || isSystem(lastClickPkg, now))

        val lastSeen = recentFront[pkg] ?: 0L
        recentFront[pkg] = now

        if (recentFront.size > 80) {
            val cutoff = now - 600_000L
            recentFront.entries.removeAll { it.value < cutoff }
        }

        if (tapWasHandOff) return
        if (now - lastSeen < 120_000L) return
        if (ignored.contains(pkg)) return

        // 3) The user chose to block this app
        if (PopupLog.set(prefs, "blocked_apps").contains(pkg)) {
            PopupLog.record(prefs, pkg, PopupLog.KIND_BLOCKED)
            PopupLog.addClosed(prefs)
            closeBlocked(pkg)
            return
        }

        // Otherwise only write it in the list
        val kind = if (now - lastUnlockAt < 15_000L) {
            PopupLog.KIND_UNLOCK
        } else {
            PopupLog.KIND_SELF
        }

        PopupLog.record(prefs, pkg, kind)
    }

    private fun isAdScreen(screen: String): Boolean {
        if (screen.isEmpty()) return false

        return knownAdScreens.contains(screen) ||
            screen.endsWith(".AdActivity") ||
            screen.endsWith("InterstitialActivity")
    }

    // Back first. If the app is still in front a moment later, go Home.
    private fun closeBlocked(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_BACK)

        handler.postDelayed({
            if (currentPkg == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
        }, 900L)
    }

    private fun isSystem(pkg: String, now: Long): Boolean {
        if (pkg.isEmpty()) return true
        if (alwaysSystem.contains(pkg)) return true

        if (now - systemPkgsAt > 60_000L) {
            systemPkgs = homeAndKeyboardPackages()
            systemPkgsAt = now
        }

        return systemPkgs.contains(pkg)
    }

    // The launcher (home screen) and the keyboards are never pop-ups
    private fun homeAndKeyboardPackages(): Set<String> {
        val found = HashSet<String>()

        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

            packageManager.queryIntentActivities(home, 0).forEach {
                it.activityInfo?.packageName?.let { name -> found.add(name) }
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.enabledInputMethodList.forEach { found.add(it.packageName) }
        } catch (e: Exception) {
            // Ignore
        }

        return found
    }
}
