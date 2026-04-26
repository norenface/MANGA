package com.manga.translator

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {

    private const val FILE = "crash_log.txt"

    fun init(context: Context) {
        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = "Thread: ${thread.name}\n${throwable.javaClass.name}: ${throwable.message}\n\n$sw"
                File(appContext.filesDir, FILE).writeText(report)
                Log.e("CRASH_REPORT", report)
            } catch (_: Throwable) {}
            prev?.uncaughtException(thread, throwable)
        }
    }

    fun readAndClear(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val text = f.readText()
        f.delete()
        return text
    }
}
