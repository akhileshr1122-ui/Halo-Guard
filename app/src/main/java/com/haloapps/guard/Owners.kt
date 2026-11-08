package com.haloapps.guard

/**
 * Maps internet domains to the company that runs them (and where that company is based),
 * and flags known tracking / advertising domains. Matching is by domain suffix.
 */
object Owners {

    class Owner(val company: String, val country: String) {
        override fun toString(): String = if (country.isEmpty()) company else "$company ($country)"
    }

    private val COMPANIES: List<Pair<String, Owner>> = run {
        val meta = Owner("Meta", "USA")
        val google = Owner("Google", "USA")
        val bytedance = Owner("ByteDance / TikTok", "China")
        val amazon = Owner("Amazon / AWS", "USA")
        val microsoft = Owner("Microsoft", "USA")
        val apple = Owner("Apple", "USA")
        val samsung = Owner("Samsung", "South Korea")
        val snap = Owner("Snap", "USA")
        val x = Owner("X / Twitter", "USA")
        val tencent = Owner("Tencent", "China")
        val alibaba = Owner("Alibaba", "China")
        val pdd = Owner("PDD / Temu", "China")
        val baidu = Owner("Baidu", "China")
        val xiaomi = Owner("Xiaomi", "China")
        val huawei = Owner("Huawei", "China")
        val yandex = Owner("Yandex", "Russia")
        val cloudflare = Owner("Cloudflare (CDN)", "USA")
        val akamai = Owner("Akamai (CDN)", "USA")
        listOf(
            // Meta
            "facebook.com" to meta, "facebook.net" to meta, "fbcdn.net" to meta, "fbsbx.com" to meta,
            "instagram.com" to meta, "cdninstagram.com" to meta, "whatsapp.net" to meta,
            "whatsapp.com" to meta, "threads.net" to meta, "messenger.com" to meta, "meta.com" to meta,
            "oculus.com" to meta,
            // Google
            "google.com" to google, "googleapis.com" to google, "gstatic.com" to google,
            "googleusercontent.com" to google, "googlevideo.com" to google, "youtube.com" to google,
            "ytimg.com" to google, "ggpht.com" to google, "doubleclick.net" to google,
            "googlesyndication.com" to google, "googleadservices.com" to google,
            "google-analytics.com" to google, "app-measurement.com" to google,
            "firebaseio.com" to google, "firebase.com" to google, "crashlytics.com" to google,
            "gvt1.com" to google, "gvt2.com" to google, "android.com" to google, "1e100.net" to google,
            "admob.com" to google, "goo.gl" to google, "googletagmanager.com" to google,
            // ByteDance
            "tiktok.com" to bytedance, "tiktokv.com" to bytedance, "tiktokcdn.com" to bytedance,
            "tiktokcdn-us.com" to bytedance, "tiktokv.us" to bytedance, "byteoversea.com" to bytedance,
            "ibytedtos.com" to bytedance, "ibyteimg.com" to bytedance, "bytedance.com" to bytedance,
            "byteintl.com" to bytedance, "capcut.com" to bytedance, "pangle.io" to bytedance,
            "pangolin-sdk-toutiao.com" to bytedance, "musical.ly" to bytedance, "lemon8-app.com" to bytedance,
            // Amazon
            "amazonaws.com" to amazon, "amazon.com" to amazon, "amazon.ca" to amazon,
            "cloudfront.net" to amazon, "media-amazon.com" to amazon, "ssl-images-amazon.com" to amazon,
            "amazon-adsystem.com" to amazon, "a2z.com" to amazon,
            // Microsoft
            "microsoft.com" to microsoft, "live.com" to microsoft, "live.net" to microsoft,
            "msn.com" to microsoft, "office.com" to microsoft, "office.net" to microsoft,
            "onedrive.com" to microsoft, "sharepoint.com" to microsoft, "azure.com" to microsoft,
            "azureedge.net" to microsoft, "windows.net" to microsoft, "bing.com" to microsoft,
            "skype.com" to microsoft, "linkedin.com" to microsoft, "licdn.com" to microsoft,
            "msftncsi.com" to microsoft, "outlook.com" to microsoft,
            // Apple
            "apple.com" to apple, "icloud.com" to apple, "mzstatic.com" to apple, "cdn-apple.com" to apple,
            // Samsung
            "samsung.com" to samsung, "samsungcloud.com" to samsung, "samsungosp.com" to samsung,
            "samsungapps.com" to samsung, "samsungqbe.com" to samsung, "samsungdm.com" to samsung,
            "samsungads.com" to samsung, "samsungcloudsolution.com" to samsung,
            "samsungcloudsolution.net" to samsung, "secb2b.com" to samsung,
            // Others
            "snapchat.com" to snap, "sc-cdn.net" to snap, "snapkit.com" to snap, "snap-dev.net" to snap,
            "twitter.com" to x, "x.com" to x, "twimg.com" to x, "t.co" to x,
            "qq.com" to tencent, "wechat.com" to tencent, "weixin.qq.com" to tencent,
            "tencent.com" to tencent, "tencent-cloud.net" to tencent, "myqcloud.com" to tencent,
            "gtimg.com" to tencent,
            "alibaba.com" to alibaba, "aliexpress.com" to alibaba, "alicdn.com" to alibaba,
            "aliyuncs.com" to alibaba, "alibabacloud.com" to alibaba, "alipay.com" to alibaba,
            "temu.com" to pdd, "kwcdn.com" to pdd, "pinduoduo.com" to pdd, "yangkeduo.com" to pdd,
            "baidu.com" to baidu, "bdstatic.com" to baidu,
            "xiaomi.com" to xiaomi, "miui.com" to xiaomi, "mi.com" to xiaomi,
            "huawei.com" to huawei, "hicloud.com" to huawei, "dbankcloud.com" to huawei,
            "yandex.ru" to yandex, "yandex.net" to yandex, "yandex.com" to yandex, "appmetrica.yandex.com" to yandex,
            "shein.com" to Owner("Shein", "Singapore/China"), "ltwebstatic.com" to Owner("Shein", "Singapore/China"),
            "telegram.org" to Owner("Telegram", "UAE"), "t.me" to Owner("Telegram", "UAE"),
            "spotify.com" to Owner("Spotify", "Sweden"), "scdn.co" to Owner("Spotify", "Sweden"),
            "netflix.com" to Owner("Netflix", "USA"), "nflxvideo.net" to Owner("Netflix", "USA"),
            "dropbox.com" to Owner("Dropbox", "USA"), "dropboxapi.com" to Owner("Dropbox", "USA"),
            "reddit.com" to Owner("Reddit", "USA"), "redd.it" to Owner("Reddit", "USA"),
            "redditmedia.com" to Owner("Reddit", "USA"),
            "pinterest.com" to Owner("Pinterest", "USA"), "pinimg.com" to Owner("Pinterest", "USA"),
            "discord.com" to Owner("Discord", "USA"), "discordapp.com" to Owner("Discord", "USA"),
            "discord.gg" to Owner("Discord", "USA"),
            "signal.org" to Owner("Signal", "USA"),
            "cloudflare.com" to cloudflare, "cloudflare.net" to cloudflare, "cloudflare-dns.com" to cloudflare,
            "akamai.net" to akamai, "akamaiedge.net" to akamai, "akamaized.net" to akamai,
            "akamaihd.net" to akamai, "edgekey.net" to akamai, "edgesuite.net" to akamai,
            "fastly.net" to Owner("Fastly (CDN)", "USA"), "fastly.com" to Owner("Fastly (CDN)", "USA"),
            // Tracking / ad companies
            "appsflyer.com" to Owner("AppsFlyer", "Israel/USA"), "appsflyersdk.com" to Owner("AppsFlyer", "Israel/USA"),
            "adjust.com" to Owner("Adjust", "Germany"), "adjust.world" to Owner("Adjust", "Germany"),
            "branch.io" to Owner("Branch", "USA"), "app.link" to Owner("Branch", "USA"),
            "applovin.com" to Owner("AppLovin", "USA"), "applvn.com" to Owner("AppLovin", "USA"),
            "unity3d.com" to Owner("Unity", "USA"), "unityads.unity3d.com" to Owner("Unity Ads", "USA"),
            "ironsrc.com" to Owner("ironSource / Unity", "Israel"), "ironsrc.mobi" to Owner("ironSource / Unity", "Israel"),
            "supersonicads.com" to Owner("ironSource / Unity", "Israel"),
            "mbridge.com" to Owner("Mintegral", "China"), "rayjump.com" to Owner("Mintegral", "China"),
            "mintegral.com" to Owner("Mintegral", "China"), "mintegral.net" to Owner("Mintegral", "China"),
            "vungle.com" to Owner("Liftoff / Vungle", "USA"), "liftoff.io" to Owner("Liftoff / Vungle", "USA"),
            "chartboost.com" to Owner("Chartboost", "USA"), "inmobi.com" to Owner("InMobi", "India"),
            "startappservice.com" to Owner("Start.io", "Israel"), "startapp.com" to Owner("Start.io", "Israel"),
            "mixpanel.com" to Owner("Mixpanel", "USA"), "amplitude.com" to Owner("Amplitude", "USA"),
            "onesignal.com" to Owner("OneSignal", "USA"), "clevertap-prod.com" to Owner("CleverTap", "USA/India"),
            "wzrkt.com" to Owner("CleverTap", "USA/India"), "moengage.com" to Owner("MoEngage", "USA/India"),
            "braze.com" to Owner("Braze", "USA"), "appboy.com" to Owner("Braze", "USA"),
            "segment.io" to Owner("Segment", "USA"), "segment.com" to Owner("Segment", "USA"),
            "kochava.com" to Owner("Kochava", "USA"), "adcolony.com" to Owner("AdColony", "USA"),
            "smartlook.com" to Owner("Smartlook (screen recording)", "Czechia"),
            "smartlook.cloud" to Owner("Smartlook (screen recording)", "Czechia"),
            "uxcam.com" to Owner("UXCam (screen recording)", "USA"),
            "sentry.io" to Owner("Sentry (crash reports)", "USA"),
            "bugsnag.com" to Owner("Bugsnag (crash reports)", "USA"),
            "criteo.com" to Owner("Criteo", "France"), "criteo.net" to Owner("Criteo", "France"),
            "taboola.com" to Owner("Taboola", "Israel/USA"), "outbrain.com" to Owner("Outbrain", "Israel/USA"),
            "scorecardresearch.com" to Owner("Comscore", "USA"), "quantserve.com" to Owner("Quantcast", "USA"),
            "huq.io" to Owner("Huq (location broker)", "UK"), "cuebiq.com" to Owner("Cuebiq (location broker)", "USA"),
            "xmode.io" to Owner("X-Mode (location broker)", "USA"), "safegraph.com" to Owner("SafeGraph (location broker)", "USA")
        ).sortedByDescending { it.first.length } // longest suffix wins
    }

    /** Domains that exist only to track or show ads. Blocking these doesn't break normal app features. */
    private val TRACKER_SUFFIXES = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "app-measurement.com", "googletagmanager.com", "admob.com", "an.facebook.com",
        "appsflyer.com", "appsflyersdk.com", "adjust.com", "adjust.world",
        "applovin.com", "applvn.com", "unityads.unity3d.com", "ironsrc.com", "ironsrc.mobi",
        "supersonicads.com", "mbridge.com", "rayjump.com", "mintegral.com", "mintegral.net",
        "pangle.io", "pangolin-sdk-toutiao.com", "vungle.com", "liftoff.io", "chartboost.com",
        "inmobi.com", "startappservice.com", "startapp.com", "mixpanel.com", "amplitude.com",
        "clevertap-prod.com", "wzrkt.com", "segment.io", "kochava.com", "adcolony.com",
        "smartlook.com", "smartlook.cloud", "uxcam.com", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "scorecardresearch.com", "quantserve.com", "amazon-adsystem.com",
        "samsungads.com", "huq.io", "cuebiq.com", "xmode.io", "safegraph.com",
        "appmetrica.yandex.com", "ads.tiktok.com", "analytics.tiktok.com", "log.byteoversea.com",
        "ads-api.tiktok.com", "business-api.tiktok.com", "ads.linkedin.com", "ads-twitter.com",
        "analytics.twitter.com", "tr.snapchat.com", "sc-analytics.appspot.com"
    )

    private fun matches(domain: String, suffix: String): Boolean =
        domain == suffix || domain.endsWith(".$suffix")

    fun owner(domain: String): Owner? {
        val d = domain.lowercase().trimEnd('.')
        for ((suffix, o) in COMPANIES) if (matches(d, suffix)) return o
        return null
    }

    fun ownerLabel(domain: String): String = owner(domain)?.toString() ?: rootDomain(domain)

    fun isTracker(domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        return TRACKER_SUFFIXES.any { matches(d, it) }
    }

    /** Hints that a domain is used for receiving uploads (photos, videos, files, backups). */
    fun looksLikeUpload(domain: String): Boolean {
        val d = domain.lowercase()
        return listOf("upload", "rupload", "media-upload", "photos", "backup", "storage", "sync", "drive")
            .any { d.contains(it) }
    }

    fun rootDomain(domain: String): String {
        val parts = domain.trimEnd('.').split('.')
        if (parts.size <= 2) return domain
        val two = parts.takeLast(2).joinToString(".")
        // handle things like co.uk, com.au
        return if (parts[parts.size - 2].length <= 3 && parts.size >= 3 && parts.last().length == 2)
            parts.takeLast(3).joinToString(".") else two
    }
}
