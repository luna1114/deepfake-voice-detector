package com.example.mainactivity

import java.io.File

object FileUtil {
    fun createTempWav(cacheDir: File): File =
        File.createTempFile("rec_", ".wav", cacheDir)
}



