package com.gameroulette.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

const val RELEASES_PAGE = "https://github.com/TygerBDevTeam/cartwheel-android/releases"
private const val RELEASE_PATH = "/TygerBDevTeam/cartwheel-android/releases/"

data class AppVersion(val numbers: List<BigInteger>, val preview: List<String> = emptyList()) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (index in 0..2) {
            val compared = numbers[index].compareTo(other.numbers[index])
            if (compared != 0) return compared
        }
        if (preview.isEmpty() && other.preview.isNotEmpty()) return 1
        if (preview.isNotEmpty() && other.preview.isEmpty()) return -1
        for (index in 0 until minOf(preview.size, other.preview.size)) {
            val a = preview[index]; val b = other.preview[index]
            val numericA = a.toBigIntegerOrNull(); val numericB = b.toBigIntegerOrNull()
            val compared = when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                numericA != null -> -1
                numericB != null -> 1
                else -> a.compareTo(b)
            }
            if (compared != 0) return compared
        }
        return preview.size.compareTo(other.preview.size)
    }
    companion object {
        fun parse(value: String): AppVersion? {
            val matched = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(value.trim()) ?: return null
            return AppVersion((1..3).map { matched.groupValues[it].toBigInteger() }, matched.groupValues[4].split('.').filter(String::isNotEmpty))
        }
    }
}

data class AppRelease(val tag: String, val title: String, val notes: String, val pageUrl: String,
    val apkName: String, val apkUrl: String, val sha256: String, val bytes: Long, val preview: Boolean) {
    fun json(): String = JSONObject().apply {
        put("tag", tag); put("title", title); put("notes", notes); put("pageUrl", pageUrl)
        put("apkName", apkName); put("apkUrl", apkUrl); put("sha256", sha256); put("bytes", bytes); put("preview", preview)
    }.toString()
    companion object {
        fun restore(json: String): AppRelease? = runCatching {
            val o = JSONObject(json)
            AppRelease(o.getString("tag"), o.getString("title"), o.getString("notes"), o.getString("pageUrl"),
                o.getString("apkName"), o.getString("apkUrl"), o.getString("sha256"), o.getLong("bytes"), o.getBoolean("preview"))
                .takeIf { validRelease(it) }
        }.getOrNull()
    }
}

private fun githubUrl(url: String, path: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null && uri.port == -1 &&
        uri.query == null && uri.fragment == null && uri.path == path
}.getOrDefault(false)

private fun validRelease(release: AppRelease): Boolean = AppVersion.parse(release.tag) != null &&
    Regex("Cartwheel-[A-Za-z0-9.+-]+\\.apk").matches(release.apkName) &&
    Regex("[a-f0-9]{64}").matches(release.sha256) && release.bytes in 1..(150L * 1024 * 1024) &&
    githubUrl(release.pageUrl, RELEASE_PATH + "tag/${release.tag}") &&
    githubUrl(release.apkUrl, RELEASE_PATH + "download/${release.tag}/${release.apkName}")

object ReleaseParser {
    fun newer(json: String, installed: String, includePreviews: Boolean): AppRelease? {
        val current = AppVersion.parse(installed) ?: throw IOException("The installed version could not be identified.")
        val releases = JSONArray(json)
        return (0 until releases.length()).mapNotNull { index ->
            val item = releases.getJSONObject(index)
            val tag = item.optString("tag_name")
            val version = AppVersion.parse(tag) ?: return@mapNotNull null
            val preview = item.optBoolean("prerelease") || version.preview.isNotEmpty()
            if (item.optBoolean("draft") || (preview && !includePreviews) || version <= current) return@mapNotNull null
            val assets = item.optJSONArray("assets") ?: return@mapNotNull null
            (0 until assets.length()).firstNotNullOfOrNull { assetIndex ->
                val asset = assets.getJSONObject(assetIndex)
                if (asset.optString("state", "uploaded") != "uploaded") return@firstNotNullOfOrNull null
                AppRelease(tag, item.optString("name").ifBlank { "Cartwheel $tag" }, item.optString("body").take(20_000),
                    item.optString("html_url"), asset.optString("name"), asset.optString("browser_download_url"),
                    asset.optString("digest").removePrefix("sha256:").lowercase(), asset.optLong("size"), preview)
                    .takeIf(::validRelease)
            }
        }.maxByOrNull { AppVersion.parse(it.tag)!! }
    }
}

class ReleaseService(private val request: () -> String = ::fetchReleases) {
    fun check(installed: String, includePreviews: Boolean): AppRelease? = ReleaseParser.newer(request(), installed, includePreviews)
}

private fun fetchReleases(): String {
    val connection = URI("https://api.github.com/repos/TygerBDevTeam/cartwheel-android/releases?per_page=30").toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Cartwheel/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        val code = connection.responseCode
        if (code == 403 || code == 429) throw IOException("GitHub is limiting update checks. Please try again later.")
        if (code != 200) throw IOException("Could not check GitHub releases (HTTP $code).")
        return connection.inputStream.bufferedReader().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            var count = reader.read(buffer)
            while (count != -1) {
                if (result.length + count > 2_000_000) throw IOException("The release response was too large.")
                result.append(buffer, 0, count)
                count = reader.read(buffer)
            }
            result.toString()
        }
    } finally { connection.disconnect() }
}

fun sha256(file: File): String = file.inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = input.read(buffer)
    while (count != -1) { digest.update(buffer, 0, count); count = input.read(buffer) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

@Suppress("DEPRECATION")
fun verifyUpdate(context: Context, file: File, release: AppRelease) {
    if (file.length() != release.bytes || sha256(file) != release.sha256) throw IOException("The download did not pass its integrity check. Please download it again.")
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val manager = context.packageManager
    val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("The downloaded APK could not be read.")
    val installed = manager.getPackageInfo(context.packageName, flags)
    fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
    if (archive.packageName != context.packageName || code(archive) <= code(installed) || AppVersion.parse(archive.versionName.orEmpty()) != AppVersion.parse(release.tag))
        throw IOException("This APK is not a newer Cartwheel update.")
    if ((archive.applicationInfo?.minSdkVersion ?: 26) > Build.VERSION.SDK_INT) throw IOException("This update needs a newer version of Android.")
    if (signers(installed).isEmpty() || signers(archive) != signers(installed)) throw IOException("This APK uses a different signing key and cannot update this installation. Please use a compatible Cartwheel release.")
}
