package eu.kanade.tachiyomi.animeextension.all.yandexdisk

import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen

const val HELP_TITLE = "How to organize videos"

private val HELP_TEXT = """
    WHERE TO LOOK
    • Your own disk: set an OAuth token or WebDAV login and app password, then set "Folders to browse" to your library folder, e.g. /Videos. Several folders go one per line or are separated by ";".
    • Public links: add disk.yandex.ru/d/… links to "Public links" or paste one into search. A link that holds videos or season folders is one title; otherwise every folder inside it is a separate title.

    OAUTH TOKEN
    1. On oauth.yandex.ru create an app for web services with the permission "Yandex.Disk REST API: Read all of Disk" (cloud_api:disk.read).
    2. As its Redirect URI enter https://oauth.yandex.ru/verification_code
    3. Open oauth.yandex.ru/authorize?response_type=token&client_id=YOUR_CLIENT_ID in a browser and allow access.
    4. Copy the token from the page (or the whole page address) into "OAuth token". The Client secret isn't needed. The token lasts a year.

    TITLES
    • Every folder in the library is a separate title (series, anime).
    • Every video directly in the library is a separate movie.

    EPISODES
    • All videos in the title folder, including subfolders (Season 1, Season 2…).
    • The number is read from the file name: S01E05, E05, ep05, "Title - 05". Without a number, files are sorted by name, with ep2 before ep10.
    • The same episode in several qualities (1080p and 720p in different subfolders of the title) is one episode with a quality choice.
    • Sample and Screenshots folders and *-sample files are skipped.

    COVER (optional)
    • A jpg, png, webp, gif or bmp image in the title folder. With several images, poster, cover and folder win.
    • For a movie, an image with the same name as the video: Movie.mkv and Movie.jpg.
    • Without an image, a frame of the first episode is used.

    DESCRIPTION (optional)
    • Any .txt in the title folder except MediaInfo.txt, e.g. "Description.txt". Lines like "Genre:", "Director:" and "Studio:" fill the matching fields. UTF-8 or Windows-1251.
    • Or a Kodi tvshow.nfo / movie.nfo.
    • For an episode, an .nfo with the same name as the video: title, plot and air date.
    • For a movie, Movie.txt or Movie.nfo next to Movie.mkv.

    DUBS AND SUBTITLES (optional)
    • .mka, .ac3, .aac, .ass, .srt or .vtt files with the same name as the video (extra suffixes are fine: Episode01.eng.ass), next to it or in subfolders such as "Sound/Studio" or "Subs". Works with an OAuth token and public links.

    EXAMPLE
    /Videos
      Movie.mkv
      Movie.jpg
      Movie.txt
      My Show/
        poster.png
        Description.txt
        Season 1/
          Show.S01E01.mkv
          Show.S01E01.nfo
          Show.S01E02.mkv
        Season 2/
          Show.S02E01.mkv
""".trimIndent()

/**
 * The stubbed [androidx.preference.Preference] has no Context constructor, so an
 * EditTextPreference is used. A click listener can't replace its dialog (androidx opens the
 * dialog before calling the listener), so the help is shown as that dialog's message instead,
 * with the input field hidden and nothing saved.
 */
internal fun PreferenceScreen.addHelpPreference() {
    EditTextPreference(context).apply {
        key = "help_layout"
        title = HELP_TITLE
        summary = "Folders, covers, descriptions, episodes and dubs. Tap to open"
        dialogTitle = HELP_TITLE
        dialogMessage = HELP_TEXT
        setOnBindEditTextListener { it.visibility = View.GONE }
        setOnPreferenceChangeListener { _, _ -> false }
    }.also(::addPreference)
}
