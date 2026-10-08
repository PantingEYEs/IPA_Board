package com.example.ipa_board

import android.content.Intent
import android.net.Uri

/** Accept both single-file and multi-file picker responses, preserving selection order. */
internal fun Intent.selectedImportUris(): List<Uri> = buildList {
    clipData?.let { clips ->
        for (index in 0 until clips.itemCount) clips.getItemAt(index).uri?.let { add(it) }
    }
    data?.let { add(it) }
}.distinct()
