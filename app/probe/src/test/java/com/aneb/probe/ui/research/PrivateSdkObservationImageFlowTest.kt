package com.aneb.probe.ui.research

import com.aneb.probe.research.PrivateSdkObservationStore
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real controller/store with Android codec under Robolectric; not SAF/Compose/phone acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateSdkObservationImageFlowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun explicitlySelectedPngMatchesTheCurrentDeclarationWithoutWritingFiles() {
        val directory = temporary.newFolder()
        val png = anonymousPng()
        val store = PrivateSdkObservationStore(directory)
        val bytes = visibleSdkTestInput(png.size, sha(png))
        store.attachPng(store.preview(bytes), png)
        val reader = PrivateSdkObservationReaderController(store)
        reader.preview { ByteArrayInputStream(bytes) }
        val selected = reader.attachPng { ByteArrayInputStream(png) }
        assertNotNull("explicit matching PNG must be attached", selected.document!!.image)
        assertArrayEquals(png, selected.document.image!!.originalBytes())
        assertEquals(2, selected.document.image.width)
        assertEquals(2, selected.document.image.height)
        assertFalse(selected.saved)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun confirmedJsonAndPngReopenAsOriginalBytesFromAFreshReader() {
        val directory = temporary.newFolder()
        val png = anonymousPng()
        val json = visibleSdkTestInput(png.size, sha(png))
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        reader.preview { ByteArrayInputStream(json) }
        reader.attachPng { ByteArrayInputStream(png) }
        val saved = reader.confirmSave()
        assertTrue(saved.saved)
        val freshReader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        val entry = freshReader.showList().entries.single()
        val reopened = freshReader.open(entry.id)
        assertTrue(reopened.saved)
        assertNotNull("fresh disk reopen must retain the selected PNG", reopened.document!!.image)
        assertArrayEquals(json, reopened.document.originalBytes())
        assertArrayEquals(png, reopened.document.image!!.originalBytes())
    }

    @Test fun wrongOrCancelledPictureSelectionClearsTheOldImageAndClosesTheInput() {
        val directory = temporary.newFolder()
        val png = anonymousPng()
        val json = visibleSdkTestInput(png.size, sha(png))
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        reader.preview { ByteArrayInputStream(json) }
        assertNotNull(reader.attachPng { ByteArrayInputStream(png) }.document!!.image)
        var closed = false
        val wrong = png.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val stream = object : ByteArrayInputStream(wrong) {
            override fun close() { closed = true; super.close() }
        }
        val rejected = reader.attachPng { stream }
        assertTrue(closed)
        assertNull(rejected.document!!.image)
        assertArrayEquals(json, rejected.document.originalBytes())
        assertFalse(rejected.saved)
        assertTrue(rejected.notice.contains("不匹配"))
        assertNotNull(reader.attachPng { ByteArrayInputStream(png) }.document!!.image)
        val cancelled = reader.attachPng(null)
        assertNull(cancelled.document!!.image)
        assertTrue(cancelled.notice.contains("取消"))
        assertFalse(cancelled.saved)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun damagedOrMissingDiskPictureNeverReusesAnOlderSuccessfulImage() {
        val directory = temporary.newFolder()
        val png = anonymousPng()
        val json = visibleSdkTestInput(png.size, sha(png))
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        reader.preview { ByteArrayInputStream(json) }
        reader.attachPng { ByteArrayInputStream(png) }
        val id = reader.confirmSave().document!!.id
        assertNotNull(reader.open(id).document!!.image)
        val bundle = directory.listFiles()!!.single { it.isDirectory }
        val picture = File(bundle, "original.png")
        val damaged = "TEST_ONLY_DAMAGED_PNG".toByteArray()
        picture.writeBytes(damaged)
        val failed = reader.open(id)
        assertNull(failed.document)
        assertFalse(failed.saved)
        assertTrue(failed.notice.contains("PNG"))
        assertArrayEquals(damaged, picture.readBytes())
        assertArrayEquals(json, File(bundle, "original.json").readBytes())
        assertNotNull(reader.showList().entries.single().error)
        assertTrue(picture.delete())
        assertNull(reader.open(id).document)
        assertArrayEquals(json, File(bundle, "original.json").readBytes())
    }

    @Test fun failedConfirmedSaveDoesNotClaimSuccessOrReplaceTheBlockedStorageFile() {
        val blocked = temporary.newFile("TEST_ONLY_BLOCKED_STORAGE")
        val original = "TEST_ONLY_EXISTING_CONTENT".toByteArray()
        blocked.writeBytes(original)
        val png = anonymousPng()
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(blocked))
        reader.preview { ByteArrayInputStream(visibleSdkTestInput(png.size, sha(png))) }
        reader.attachPng { ByteArrayInputStream(png) }
        val failed = reader.confirmSave()
        assertFalse(failed.saved)
        assertNull(failed.document)
        assertTrue(failed.notice.contains("保存失败"))
        assertArrayEquals(original, blocked.readBytes())
        assertTrue(reader.showList().entries.isEmpty())
    }

    @Test fun aMatchingDeclarationCannotMakeAnUndecodablePngDisplayableOrSaveable() {
        val directory = temporary.newFolder()
        val invalidPng = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        reader.preview { ByteArrayInputStream(visibleSdkTestInput(invalidPng.size, sha(invalidPng))) }
        val rejected = reader.attachPng { ByteArrayInputStream(invalidPng) }
        assertNotNull(rejected.document)
        assertNull(rejected.document!!.image)
        assertFalse(rejected.saved)
        assertTrue(rejected.notice.contains("不可读"))
        assertFalse(reader.confirmSave().saved)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private fun anonymousPng(): ByteArray = ByteArrayOutputStream().also {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        finally { bitmap.recycle() }
    }.toByteArray()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02X".format(it.toInt() and 255) }
}
