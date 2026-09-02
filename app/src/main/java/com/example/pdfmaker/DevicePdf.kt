package com.example.pdfmaker

import android.net.Uri

data class DevicePdf(
    val uri: Uri,
    val name: String,
    val sizeFmt: String,
    val dateFmt: String,
    val lastModified: Long,
)
