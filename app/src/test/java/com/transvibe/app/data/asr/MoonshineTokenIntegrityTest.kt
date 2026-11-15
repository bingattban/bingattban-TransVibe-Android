package com.transvibe.app.data.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MoonshineTokenIntegrityTest {
    @Test fun plausibleTextAndFingerprintDetectsChanges() {
        val file = Files.createTempFile("moonshine-tokens", ".txt").toFile()
        try {
            file.writeText((0..12_000).joinToString("\n") { "token$it $it" })
            assertTrue(MoonshineTokenIntegrity.plausible(file))
            val checksum = MoonshineTokenIntegrity.sha256(file)
            assertTrue(MoonshineTokenIntegrity.matchesRecorded(file, checksum))
            file.appendText("\nextra-token 12001")
            assertFalse(MoonshineTokenIntegrity.matchesRecorded(file, checksum))
        } finally { file.delete() }
    }

    @Test fun suspiciousOrNonTokenPayloadIsRejected() {
        val file = Files.createTempFile("moonshine-bogus", ".txt").toFile()
        try {
            file.writeText("<html>" + "x".repeat(150_000))
            assertFalse(MoonshineTokenIntegrity.plausible(file))
            file.writeText("x".repeat(150_000))
            assertFalse(MoonshineTokenIntegrity.plausible(file))
        } finally { file.delete() }
    }
}
