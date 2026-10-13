package com.karoocameracontrol

import android.app.Application
import com.karoocameracontrol.diagnostics.DiagnosticLog
import com.karoocameracontrol.diagnostics.DiagnosticReports

class DiagnosticApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        DiagnosticReports.get(this)
    }
}
