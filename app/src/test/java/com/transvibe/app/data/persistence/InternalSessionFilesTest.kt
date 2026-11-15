package com.transvibe.app.data.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeNoException
import java.nio.file.Files

class InternalSessionFilesTest {
    @Test fun deleteOnlyOwnJobFiles() {
        val base = Files.createTempDirectory("transvibe-cleanup").toFile()
        try {
            val sessions = base.resolve("sessions").apply { mkdirs() }
            val job = sessions.resolve("job-123").apply { mkdirs() }
            job.resolve("a.srt").writeText("subtitle")
            job.resolve("b.vtt").writeText("subtitle")
            val other = sessions.resolve("job-456").apply { mkdirs() }
            other.resolve("keep.srt").writeText("keep")
            val legacy = sessions.resolve("legacy.json").apply { writeText("keep") }
            val exported = base.resolve("exported.srt").apply { writeText("keep") }

            assertTrue(InternalSessionFiles(sessions).deleteJobDirectory("job-123"))
            assertFalse(job.exists())
            assertTrue(other.resolve("keep.srt").exists())
            assertTrue(legacy.exists())
            assertTrue(exported.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun refuseTraversalAndInvalidIds() {
        val base = Files.createTempDirectory("transvibe-safe-cleanup").toFile()
        try {
            val sessions = base.resolve("sessions").apply { mkdirs() }
            val outside = base.resolve("important.txt").apply { writeText("keep") }
            val files = InternalSessionFiles(sessions)
            assertFalse(files.deleteJobDirectory("../important.txt"))
            assertFalse(files.deleteJobDirectory("/tmp"))
            assertFalse(files.deleteJobDirectory("."))
            assertTrue(outside.exists())
        } finally { base.deleteRecursively() }
    }

    @Test fun refuseSymlinkOutsideRoot() {
        val base = Files.createTempDirectory("transvibe-link-cleanup").toFile()
        try {
            val sessions = base.resolve("sessions").apply { mkdirs() }
            val outside = base.resolve("private").apply { mkdirs() }
            val retained = outside.resolve("keep.srt").apply { writeText("keep") }
            try {
                Files.createSymbolicLink(sessions.resolve("job-link").toPath(), outside.toPath())
            } catch (unsupported: Exception) {
                assumeNoException(unsupported)
            }
            assertFalse(InternalSessionFiles(sessions).deleteJobDirectory("job-link"))
            assertTrue(retained.exists())
            // A link to another real job *inside* the root must not delete that job.
            val otherJob = sessions.resolve("job-actual").apply { mkdirs() }
            val otherOutput = otherJob.resolve("keep.srt").apply { writeText("keep") }
            try {
                Files.createSymbolicLink(sessions.resolve("job-inside").toPath(), otherJob.toPath())
            } catch (unsupported: Exception) {
                assumeNoException(unsupported)
            }
            assertFalse(InternalSessionFiles(sessions).deleteJobDirectory("job-inside"))
            assertTrue(otherOutput.exists())
        } finally { base.deleteRecursively() }
    }
}
