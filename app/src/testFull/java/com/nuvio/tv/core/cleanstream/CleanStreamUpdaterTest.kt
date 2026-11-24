package com.nuvio.tv.core.cleanstream

import com.nuvio.tv.updater.ReshapedApkAssets
import com.nuvio.tv.updater.VersionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app updater must recognise NuvioTv-Clean releases and pick the right file. */
class CleanStreamUpdaterTest {
    private val release = listOf(
        "NuvioTv-Clean-7-any.apk",
        "NuvioTv-Clean-7-arm32.apk",
        "NuvioTv-Clean-7-arm64.apk",
        "NuvioTv-Clean.apk",
    )

    @Test
    fun picksTheFileForTheTv() {
        assertEquals("NuvioTv-Clean-7-arm64.apk", ReshapedApkAssets.choose(release, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("NuvioTv-Clean-7-arm32.apk", ReshapedApkAssets.choose(release, listOf("armeabi-v7a")))
        assertEquals("NuvioTv-Clean-7-any.apk", ReshapedApkAssets.choose(release, listOf("x86_64")))
    }

    @Test
    fun newerBuildsAreRecognised() {
        assertTrue(VersionUtils.isRemoteNewer("v1.0.7", "1.0.6"))
        assertTrue(VersionUtils.isRemoteNewer("v1.0.10", "1.0.9"))
    }
}
