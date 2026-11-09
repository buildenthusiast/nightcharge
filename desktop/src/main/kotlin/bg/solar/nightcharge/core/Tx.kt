package bg.solar.nightcharge.core

import java.util.Locale

/** Избраните от потребителя език и тема. "auto" следва телефона. */
object AppPrefs {
    /** "auto", "bg" или "en". */
    @Volatile var language: String = "auto"

    /** "auto", "light" или "dark". */
    @Volatile var theme: String = "auto"
}

/** Текст на български или на английски според избрания език в приложението. */
fun pick(bg: String, en: String): String {
    val useBg = when (AppPrefs.language) {
        "bg" -> true
        "en" -> false
        else -> Locale.getDefault().language == "bg"
    }
    return if (useBg) bg else en
}
