package com.haloapps.guard

import android.content.pm.PackageManager

/**
 * Detects tracking / advertising / data-broker SDKs bundled inside an app by looking at the
 * component class names it declares in its manifest (activities, services, receivers, providers).
 * Most of these SDKs must register at least one component, so this catches the common ones
 * without needing to unpack the APK. It is a heuristic, not a full code scan.
 */
object Trackers {

    private val SIGNATURES: List<Pair<String, String>> = listOf(
        "com.facebook." to "Meta / Facebook SDK",
        "com.google.android.gms.measurement." to "Google Firebase Analytics",
        "com.google.firebase.analytics." to "Google Firebase Analytics",
        "com.google.android.gms.ads." to "Google AdMob",
        "com.google.android.ads." to "Google Ads",
        "com.appsflyer." to "AppsFlyer",
        "com.adjust.sdk." to "Adjust",
        "io.branch." to "Branch",
        "com.applovin." to "AppLovin",
        "com.unity3d.ads." to "Unity Ads",
        "com.unity3d.services." to "Unity Ads",
        "com.ironsource." to "ironSource",
        "com.mbridge." to "Mintegral",
        "com.mintegral." to "Mintegral",
        "com.bytedance.sdk." to "Pangle (ByteDance)",
        "com.tiktok." to "TikTok SDK",
        "com.vungle." to "Vungle / Liftoff",
        "com.chartboost." to "Chartboost",
        "com.inmobi." to "InMobi",
        "com.startapp." to "Start.io",
        "com.mixpanel." to "Mixpanel",
        "com.amplitude." to "Amplitude",
        "com.onesignal." to "OneSignal",
        "com.clevertap." to "CleverTap",
        "com.moengage." to "MoEngage",
        "com.braze." to "Braze",
        "com.appboy." to "Braze",
        "com.segment." to "Segment",
        "com.kochava." to "Kochava",
        "com.huawei.hms.analytics." to "Huawei Analytics",
        "com.yandex.metrica." to "Yandex AppMetrica",
        "io.appmetrica." to "Yandex AppMetrica",
        "com.smartlook." to "Smartlook (screen recording)",
        "com.uxcam." to "UXCam (screen recording)",
        "com.huq." to "Huq (location data broker)",
        "io.huq." to "Huq (location data broker)",
        "com.cuebiq." to "Cuebiq (location data broker)",
        "io.xmode." to "X-Mode (location data broker)",
        "com.xmode." to "X-Mode (location data broker)",
        "com.safegraph." to "SafeGraph (location data broker)",
        "com.inlocomedia." to "In Loco (location)",
        "com.google.firebase.crashlytics." to "Crashlytics (crash reports)"
    )

    private val FLAG_SETS = intArrayOf(
        PackageManager.GET_ACTIVITIES,
        PackageManager.GET_SERVICES,
        PackageManager.GET_RECEIVERS,
        PackageManager.GET_PROVIDERS
    )

    @Suppress("DEPRECATION")
    fun detect(pm: PackageManager, pkg: String): List<String> {
        val names = HashSet<String>()
        // Query each component type separately: huge apps can overflow a single binder call.
        for (flag in FLAG_SETS) {
            try {
                val pi = pm.getPackageInfo(pkg, flag or PackageManager.MATCH_DISABLED_COMPONENTS)
                pi.activities?.forEach { names.add(it.name) }
                pi.services?.forEach { names.add(it.name) }
                pi.receivers?.forEach { names.add(it.name) }
                pi.providers?.forEach { names.add(it.name) }
            } catch (e: Exception) {
                // ignore and keep going
            }
        }
        val found = LinkedHashSet<String>()
        for (name in names) {
            for ((prefix, label) in SIGNATURES) {
                // Don't flag a company's SDK inside that company's own app (e.g. Facebook app).
                if (name.startsWith(prefix) && !pkg.startsWith(prefix.trimEnd('.'))) {
                    found.add(label)
                }
            }
        }
        return found.toList()
    }
}
