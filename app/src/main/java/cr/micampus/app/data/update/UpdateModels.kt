package cr.micampus.app.data.update

data class AppUpdate(
    val version: String,       // e.g. "1.4.2", leading "v" stripped from the tag
    val downloadUrl: String,   // browser_download_url of the .apk asset
    val releaseUrl: String,    // html_url of the release
    val notes: String,         // release body, may be blank
)
