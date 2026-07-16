package cr.micampus.app

import android.content.Context
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.local.*
import cr.micampus.app.data.ai.ApiKeyStore
import cr.micampus.app.data.ai.GeminiCloudEngine
import cr.micampus.app.data.document.DocumentImporter
import cr.micampus.app.data.document.PdfDocumentExtractor
import cr.micampus.app.platform.calendar.CalendarExporter
import cr.micampus.app.platform.reminders.ReminderScheduler

class AppContainer(context: Context) {
    val database: MiCampusDatabase = DatabaseProvider.get(context)
    val events: EventRepository = EventRepository(database.eventDao())
    val settings: SettingsStore = SettingsStore(context)
    val transport: AssetTransportRepository = AssetTransportRepository.fromContext(context)
    val keyStore: ApiKeyStore = ApiKeyStore(context)
    val cloud: GeminiCloudEngine = GeminiCloudEngine(keyStore)
    val documents: DocumentImporter = DocumentImporter(PdfDocumentExtractor(context))
    val calendar: CalendarExporter = CalendarExporter(context)
    val reminders: ReminderScheduler = ReminderScheduler(context)
}
