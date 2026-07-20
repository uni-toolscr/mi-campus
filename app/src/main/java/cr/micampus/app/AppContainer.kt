package cr.micampus.app

import android.content.Context
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.knowledge.AssetKnowledgeRepository
import cr.micampus.app.data.local.*
import cr.micampus.app.data.ai.ApiKeyStore
import cr.micampus.app.data.ai.GeminiCloudEngine
import cr.micampus.app.data.document.DocumentImporter
import cr.micampus.app.data.document.DocumentTranscriptionRepository
import cr.micampus.app.data.document.ImportedDocumentRepository
import cr.micampus.app.data.document.PdfDocumentExtractor
import cr.micampus.app.data.diagnostics.createImportDiagnostics
import cr.micampus.app.data.moodle.HttpUrlConnectionMoodleTransport
import cr.micampus.app.data.moodle.MoodleAccountStore
import cr.micampus.app.data.moodle.MoodleClient
import cr.micampus.app.data.moodle.MoodleContentRepository
import cr.micampus.app.data.moodle.MoodleSyncManager
import cr.micampus.app.data.moodle.MoodleSyncScheduler
import cr.micampus.app.data.banner.AcademicProgressStore
import cr.micampus.app.data.banner.BannerProgressClient
import cr.micampus.app.data.banner.BannerProgressManager
import cr.micampus.app.platform.calendar.CalendarExporter
import cr.micampus.app.platform.reminders.ReminderScheduler
import cr.micampus.app.platform.widgets.WidgetRefresher
import cr.micampus.app.platform.widgets.BusWidgetRefreshCoordinator

class AppContainer(context: Context) {
    val database: MiCampusDatabase = DatabaseProvider.get(context)
    val events: EventRepository = EventRepository(database.eventDao())
    val settings: SettingsStore = SettingsStore(context)
    val transport: AssetTransportRepository = AssetTransportRepository.fromContext(context)
    val knowledge: AssetKnowledgeRepository = AssetKnowledgeRepository.fromContext(context)
    val keyStore: ApiKeyStore = ApiKeyStore(context)
    val diagnostics = createImportDiagnostics(context.applicationContext)
    val cloud: GeminiCloudEngine = GeminiCloudEngine(keyStore, diagnostics = diagnostics)
    val documents: DocumentImporter = DocumentImporter(PdfDocumentExtractor(context))
    val importedDocuments: ImportedDocumentRepository = ImportedDocumentRepository(
        context.applicationContext, database.importedDocumentDao(), database.eventDao(), database.documentTranscriptionDao(),
    )
    val transcriptions: DocumentTranscriptionRepository = DocumentTranscriptionRepository(
        database.documentTranscriptionDao(), importedDocuments, documents,
    )
    val calendar: CalendarExporter = CalendarExporter(context)
    val reminders: ReminderScheduler = ReminderScheduler(context)
    val busWidgetRefresh: BusWidgetRefreshCoordinator = BusWidgetRefreshCoordinator(context.applicationContext, transport)
    val widgets: WidgetRefresher = WidgetRefresher(context.applicationContext, busWidgetRefresh)
    val moodleAccounts: MoodleAccountStore = MoodleAccountStore(context)
    val moodleSyncScheduler: MoodleSyncScheduler = MoodleSyncScheduler(context.applicationContext)
    val moodleClient: MoodleClient = MoodleClient(
        HttpUrlConnectionMoodleTransport(),
        logger = { android.util.Log.w("MoodleSync", it) },
    )
    val moodleContents: MoodleContentRepository = MoodleContentRepository(
        context.applicationContext,
        database.moodleContentDao(),
    )
    val moodle: MoodleSyncManager = MoodleSyncManager(
        client = moodleClient,
        accounts = moodleAccounts,
        events = events,
        settings = settings,
        reminders = reminders,
        scheduler = moodleSyncScheduler,
        contents = moodleContents,
        onDataChanged = widgets::refreshAll,
    )
    val academicProgressStore = AcademicProgressStore(context)
    val bannerProgress = BannerProgressManager(BannerProgressClient(), academicProgressStore)

    init {
        if (moodleAccounts.read() != null) moodleSyncScheduler.schedule()
    }
}
