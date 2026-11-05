package com.gameroulette.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
@Suppress("DEPRECATION")
class UpdateVerificationTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var release: AppRelease

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "update-test.apk").apply { writeText("APK test data") }
        release = AppRelease("v0.2.0", "New release", "", "$RELEASES_PAGE/tag/v0.2.0", "Cartwheel-0.2.0.apk",
            "$RELEASES_PAGE/download/v0.2.0/Cartwheel-0.2.0.apk", sha256(file), file.length(), true)
        shadowOf(context.packageManager).installPackage(info(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME))
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info())
    }

    private fun info(code: Int = BuildConfig.VERSION_CODE + 1, name: String = "0.2.0", signer: Byte = 1, pkg: String = context.packageName, minimum: Int = 26): PackageInfo {
        return PackageInfo().apply {
            packageName = pkg
            versionName = name
            if (Build.VERSION.SDK_INT >= 28) setLongVersionCode(code.toLong()) else versionCode = code
            applicationInfo = ApplicationInfo().apply { packageName = pkg; minSdkVersion = minimum }
            val certificates = arrayOf(Signature(byteArrayOf(signer, 2, 3)))
            signatures = certificates
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(certificates) }
            }
        }
    }

    @Test fun acceptsNewerPackageWithMatchingHashAndSigningKey() {
        verifyUpdate(context, file, release)
    }

    @Test fun rejectsCorruptedDownload() {
        file.appendText("corruption")
        assertTrue(runCatching { verifyUpdate(context, file, release) }.exceptionOrNull() is IOException)
    }

    @Test fun rejectsAnotherAppsPackage() {
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info(pkg = "other.app"))
        assertTrue(runCatching { verifyUpdate(context, file, release) }.isFailure)
    }

    @Test fun rejectsDifferentSigningKey() {
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info(signer = 7))
        val error = runCatching { verifyUpdate(context, file, release) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("different signing key"))
    }

    @Test fun rejectsDowngradesAndMislabeledVersions() {
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info(code = BuildConfig.VERSION_CODE))
        assertTrue(runCatching { verifyUpdate(context, file, release) }.isFailure)
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info(name = "0.1.9"))
        assertTrue(runCatching { verifyUpdate(context, file, release) }.isFailure)
    }

    @Test fun rejectsUpdatesNeedingNewerAndroid() {
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info(minimum = Build.VERSION.SDK_INT + 1))
        assertTrue(runCatching { verifyUpdate(context, file, release) }.exceptionOrNull()!!.message!!.contains("newer version of Android"))
    }
}
