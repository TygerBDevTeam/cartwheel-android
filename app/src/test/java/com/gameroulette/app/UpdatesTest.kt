package com.gameroulette.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import android.app.Application
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdatesTest {
    private fun release(tag: String, preview: Boolean = true, digest: String = "sha256:" + "a".repeat(64)): JSONObject {
        val name = "Cartwheel-${tag.removePrefix("v")}-preview.apk"
        return JSONObject().put("tag_name", tag).put("name", "Cartwheel $tag").put("body", "Release notes")
            .put("prerelease", preview).put("draft", false)
            .put("html_url", "$RELEASES_PAGE/tag/$tag")
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", 17000).put("state", "uploaded")
                .put("digest", digest).put("browser_download_url", "$RELEASES_PAGE/download/$tag/$name")))
    }

    @Test fun comparesNumericVersionsAndPreviewIdentifiersCorrectly() {
        assertTrue(AppVersion.parse("v0.1.10")!! > AppVersion.parse("0.1.9")!!)
        assertTrue(AppVersion.parse("0.2.0")!! > AppVersion.parse("0.2.0-rc.10")!!)
        assertTrue(AppVersion.parse("0.2.0-rc.10")!! > AppVersion.parse("0.2.0-rc.2")!!)
        assertEquals(AppVersion.parse("v1.2.3+build.4"), AppVersion.parse("1.2.3"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("0.1"))
        assertNull(AppVersion.parse("1.2.3/evil"))
    }

    @Test fun selectsNewestInstallableReleaseRatherThanServerOrder() {
        val json = JSONArray().put(release("v0.1.9")).put(release("v0.1.10")).put(release("v0.1.8")).toString()
        assertEquals("v0.1.10", ReleaseParser.newer(json, "0.1.2", true)!!.tag)
        assertNull(ReleaseParser.newer(json, "0.1.10", true))
    }

    @Test fun previewOptOutAndDraftsAreRespected() {
        val draft = release("v9.0.0").put("draft", true)
        val json = JSONArray().put(draft).put(release("v0.2.0")).put(release("v0.1.3", false)).toString()
        assertEquals("v0.2.0", ReleaseParser.newer(json, "0.1.2", true)!!.tag)
        assertEquals("v0.1.3", ReleaseParser.newer(json, "0.1.2", false)!!.tag)
        assertNull(ReleaseParser.newer(JSONArray().put(release("v0.2.0-rc.1", false)).toString(), "0.1.2", false))
    }

    @Test fun rejectsExternalApksInvalidHashesAndUnfinishedAssets() {
        val wrongHost = release("v0.2.0").apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/Cartwheel.apk") }
        val wrongRepo = release("v0.2.1").apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "$RELEASES_PAGE-evil/download/v0.2.1/Cartwheel-0.2.1-preview.apk") }
        val unfinished = release("v0.3.0").apply { getJSONArray("assets").getJSONObject(0).put("state", "new") }
        val noApk = release("v0.4.0").put("assets", JSONArray())
        val invalidHash = release("v0.5.0", digest = "sha256:invalid")
        assertNull(ReleaseParser.newer(JSONArray(listOf(wrongHost, wrongRepo, unfinished, noApk, invalidHash)).toString(), "0.1.2", true))
    }

    @Test fun validatesCachedMetadataAndPreservesReleaseDetails() {
        val found = ReleaseParser.newer(JSONArray().put(release("v0.2.0")).toString(), "0.1.2", true)!!
        assertEquals(found, AppRelease.restore(found.json()))
        assertNull(AppRelease.restore(found.copy(apkUrl = "file:///tmp/update.apk").json()))
        assertNull(AppRelease.restore("not JSON"))
        assertEquals("Release notes", found.notes)
    }

    @Test fun networkFailureIsReportedInsteadOfClaimingUpToDate() {
        val error = runCatching { ReleaseService { throw IOException("Offline") }.check("0.1.2", true) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("Offline", error!!.message)
        assertNull(ReleaseService { "[]" }.check("0.1.2", true))
    }

    @Test fun cachedPreviewStaysHiddenAfterOptingOutAndReopening() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val found = ReleaseParser.newer(JSONArray().put(release("v0.2.0")).toString(), "0.1.2", true)!!
        app.getSharedPreferences("cartwheel_updates", 0).edit().clear().putBoolean("previews", false).putString("release", found.json()).commit()
        val model = UpdateViewModel(app, ReleaseService { error("No automatic network request expected") })
        assertNull(model.state.value.release)
        assertFalse(model.state.value.automatic)
        app.getSharedPreferences("cartwheel_updates", 0).edit().clear().commit()
    }
}
