package cr.micampus.app

import android.app.Application

class MiCampusApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
