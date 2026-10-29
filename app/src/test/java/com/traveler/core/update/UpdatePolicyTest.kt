package com.traveler.core.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val release = UpdateRelease(applicationId = "com.traveler.next", versionCode = 4,
        versionName = "1.1.0", minSdk = 26,
        apkUrl = "https://github.com/HyeokjaeKwon26/My-Travel-Diary/releases/download/v1.1.0/app.apk",
        apkBytes = 200, sha256 = "a".repeat(64))

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Must reject invalid update") } catch (_: IllegalArgumentException) { }
    }
    @Test fun officialRepositoryIsBoundToEdition() {
        UpdatePolicy.validate(release, "com.traveler.next")
        rejects { UpdatePolicy.validate(release, "com.traveler.threed.next") }
        rejects { UpdatePolicy.validate(release.copy(apkUrl = release.apkUrl.replace("My-Travel-Diary/", "My-Travel-Diary-3D/")), "com.traveler.next") }
    }
    @Test fun rejectsUntrustedAndAmbiguousDownloadUrls() {
        val repo = UpdatePolicy.repository("com.traveler.next")
        listOf("http://github.com/$repo/releases/download/x/a.apk", "https://github.com.evil.test/$repo/releases/download/x/a.apk",
            "https://user@github.com/$repo/releases/download/x/a.apk", "https://github.com:8443/$repo/releases/download/x/a.apk",
            "https://github.com/$repo/releases/download/../a.apk", "https://github.com/$repo/releases/download/x%2fa.apk",
            "https://github.com/$repo/releases/download/x/a.apk?redirect=evil").forEach { assertFalse(it, UpdatePolicy.assetUrl(it, repo)) }
    }
    @Test fun requiresBoundedFileAndSha256() {
        rejects { UpdatePolicy.validate(release.copy(apkBytes = 0), "com.traveler.next") }
        rejects { UpdatePolicy.validate(release.copy(apkBytes = UpdatePolicy.MAX_APK_BYTES + 1), "com.traveler.next") }
        rejects { UpdatePolicy.validate(release.copy(sha256 = "abc"), "com.traveler.next") }
        rejects { UpdatePolicy.validate(release.copy(schemaVersion = 2), "com.traveler.next") }
    }
    @Test fun installedIdentityMustMatchAndIncrease() {
        fun verify(pkg: String = "com.traveler.next", code: Long = 4, installed: Long = 3,
                   current: Set<String> = setOf("old"), candidate: Set<String> = setOf("old")) =
            UpdatePolicy.verifyIdentity(release, pkg, code, installed, current, candidate)
        verify()
        rejects { verify(pkg = "com.traveler.threed.next") }
        rejects { verify(code = 5) }
        rejects { verify(installed = 4) }
        rejects { verify(installed = 5) }
        rejects { verify(candidate = setOf("other")) }
        rejects { verify(current = emptySet(), candidate = emptySet()) }
    }
    @Test fun checksEveryStartUnlessDisabledOrAlreadyInteracting() {
        repeat(3) { assertTrue(UpdatePolicy.shouldCheckOnStart(true, false, false)) }
        assertFalse(UpdatePolicy.shouldCheckOnStart(false, false, false))
        assertFalse(UpdatePolicy.shouldCheckOnStart(true, true, false))
        assertFalse(UpdatePolicy.shouldCheckOnStart(true, false, true))
        assertEquals("update-next.json", UpdatePolicy.MANIFEST_ASSET)
        rejects { UpdatePolicy.validate(release.copy(applicationId = "com.traveler"), "com.traveler.next") }
    }
    @Test fun futureExtraMetadataIsIgnored() {
        val encoded = UpdatePolicy.json.encodeToString(UpdateRelease.serializer(), release)
        assertEquals(release, UpdatePolicy.json.decodeFromString<UpdateRelease>(encoded.dropLast(1) + ",\"extra\":true}"))
    }
}
