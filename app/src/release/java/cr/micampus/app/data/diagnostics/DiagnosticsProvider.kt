package cr.micampus.app.data.diagnostics

import android.content.Context

fun createImportDiagnostics(context: Context): ImportDiagnosticsRecorder = NoOpImportDiagnostics
