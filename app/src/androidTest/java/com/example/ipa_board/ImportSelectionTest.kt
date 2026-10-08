package com.example.ipa_board

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportSelectionTest {
    @Test fun acceptsSingleFileAndEmptyResponses() {
        val uri = Uri.parse("content://test/single.json")
        assertEquals(listOf(uri), Intent().setData(uri).selectedImportUris())
        assertEquals(emptyList<Uri>(), Intent().selectedImportUris())
    }

    @Test fun preservesMultiSelectionOrderWithoutImportingDuplicateUrisTwice() {
        val first = Uri.parse("content://test/first.json")
        val second = Uri.parse("content://test/second.json")
        val clips = ClipData.newRawUri("files", first).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(first))
            addItem(ClipData.Item("Non-file content"))
        }
        val result = Intent().setData(first).apply { clipData = clips }
        assertEquals(listOf(first, second), result.selectedImportUris())
    }
}
