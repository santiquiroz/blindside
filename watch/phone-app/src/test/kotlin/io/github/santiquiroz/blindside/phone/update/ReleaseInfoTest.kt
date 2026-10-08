package io.github.santiquiroz.blindside.phone.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseInfoTest {

    @Test
    fun `a realistic release picks the phone and watch apks and skips other assets`() {
        val info = parseLatestRelease(REALISTIC_JSON)!!

        assertEquals("v1.3.0", info.version)
        assertEquals("https://github.com/santiquiroz/blindside/releases/tag/v1.3.0", info.releaseUrl)
        assertEquals(
            "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/blindside-celular-v1.3.0.apk",
            info.apkUrl,
        )
        assertEquals(61556078L, info.apkBytes)
        assertEquals(
            "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/blindside-reloj-v1.3.0.apk",
            info.watchApkUrl,
        )
        assertEquals("## Novedades\n\n- Radar más rápido\n- Menos batería", info.notes)
    }

    @Test
    fun `long release notes are cut to 500 chars`() {
        val body = "n".repeat(600)
        val info = parseLatestRelease("""{"tag_name":"v1.3.0","html_url":"","body":"$body","assets":[]}""")!!

        assertEquals("n".repeat(500), info.notes)
    }

    @Test
    fun `a release without tag is null`() {
        assertNull(parseLatestRelease("""{"html_url":"https://github.com/x","assets":[]}"""))
        assertNull(parseLatestRelease("not json"))
    }

    @Test
    fun `a release without phone asset leaves the apk url empty`() {
        val info = parseLatestRelease(NO_PHONE_JSON)!!

        assertEquals("v1.3.0", info.version)
        assertNull(info.apkUrl)
        assertNull(info.apkBytes)
        assertEquals("https://github.com/santiquiroz/blindside/releases/download/v1.3.0/blindside-reloj-v1.3.0.apk", info.watchApkUrl)
    }

    @Test
    fun `only https github hosts are trusted`() {
        assertTrue(isTrustedDownloadUrl("https://github.com/santiquiroz/blindside/releases/download/v1.3.0/app.apk"))
        assertTrue(isTrustedDownloadUrl("https://objects.githubusercontent.com/abc123?token=xyz"))
        assertFalse(isTrustedDownloadUrl("http://github.com/santiquiroz/blindside/app.apk"))
        assertFalse(isTrustedDownloadUrl("https://evil-github.com/app.apk"))
        assertFalse(isTrustedDownloadUrl("https://github.com.evil.io/app.apk"))
        assertFalse(isTrustedDownloadUrl("not a url"))
    }

    private companion object {
        const val REALISTIC_JSON = """{
  "tag_name": "v1.3.0",
  "html_url": "https://github.com/santiquiroz/blindside/releases/tag/v1.3.0",
  "body": "## Novedades\n\n- Radar más rápido\n- Menos batería",
  "assets": [
    {
      "name": "blindside-celular-v1.3.0.apk",
      "browser_download_url": "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/blindside-celular-v1.3.0.apk",
      "size": 61556078
    },
    {
      "name": "blindside-reloj-v1.3.0.apk",
      "browser_download_url": "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/blindside-reloj-v1.3.0.apk",
      "size": 18934562
    },
    {
      "name": "firmware.bin",
      "browser_download_url": "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/firmware.bin",
      "size": 262144
    }
  ]
}"""

        const val NO_PHONE_JSON = """{
  "tag_name": "v1.3.0",
  "html_url": "https://github.com/santiquiroz/blindside/releases/tag/v1.3.0",
  "body": "",
  "assets": [
    {
      "name": "blindside-reloj-v1.3.0.apk",
      "browser_download_url": "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/blindside-reloj-v1.3.0.apk",
      "size": 18934562
    },
    {
      "name": "firmware.bin",
      "browser_download_url": "https://github.com/santiquiroz/blindside/releases/download/v1.3.0/firmware.bin",
      "size": 262144
    }
  ]
}"""
    }
}
