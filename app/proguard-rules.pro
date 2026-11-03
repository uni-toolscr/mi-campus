# MiCampus release rules

# ML Kit Structured Output reflects over this KSP-backed response type in release builds.
-keep class cr.micampus.app.data.ai.NanoEventOutput { *; }

# Gson maps JSON keys to Kotlin property names by reflection, so stored and API models keep their field names.
-keep class cr.micampus.app.core.model.** { <fields>; <init>(...); }
-keep class cr.micampus.app.data.moodle.** { <fields>; <init>(...); }
-keep class cr.micampus.app.data.diagnostics.DiagnosticTrace { <fields>; <init>(...); }
-keep class cr.micampus.app.data.diagnostics.ImportDiagnosticEvent { <fields>; <init>(...); }
