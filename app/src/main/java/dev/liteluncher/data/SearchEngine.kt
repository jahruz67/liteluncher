package dev.liteluncher.data

import java.math.BigDecimal
import java.math.MathContext
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class SettingsTarget(val title: String, val action: String)

object SearchEngine {
    private const val MAX_EXPRESSION_LENGTH = 1_024
    private const val MAX_DEPTH = 48
    private const val MAX_OPERATIONS = 512
    private const val MAX_QUERY_LENGTH = 4_096
    internal const val DEFAULT_CUSTOM_URL = "https://www.google.com/search?q={query}"

    /** Angles are in radians; log is base 10, ln is the natural logarithm. */
    fun calculate(expression: String): String? {
        if (expression.length > MAX_EXPRESSION_LENGTH) return null
        val source = expression.trim().removePrefix("=").trim()
        if (source.isEmpty()) return null
        return try {
            val result = Parser(source).parse()
            if (result == 0.0) "0" else {
                val decimal = BigDecimal.valueOf(result).round(MathContext(12)).stripTrailingZeros()
                if (abs(result) >= 1e-9 && abs(result) < 1e12) decimal.toPlainString()
                else decimal.toString()
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun webUrl(query: String, prefs: SearchPrefs): String {
        val encoded = encodeQuery(query)
        val template = when (prefs.engine) {
            WebEngine.GOOGLE -> DEFAULT_CUSTOM_URL
            WebEngine.BING -> "https://www.bing.com/search?q={query}"
            WebEngine.DUCKDUCKGO -> "https://duckduckgo.com/?q={query}"
            WebEngine.YAHOO -> "https://search.yahoo.com/search?p={query}"
            WebEngine.WIKIPEDIA -> "https://en.wikipedia.org/w/index.php?search={query}"
            WebEngine.BAIDU -> "https://www.baidu.com/s?wd={query}"
            WebEngine.ECOSIA -> "https://www.ecosia.org/search?q={query}"
            WebEngine.STARTPAGE -> "https://www.startpage.com/sp/search?query={query}"
            WebEngine.CUSTOM -> validCustomUrl(prefs.customUrl) ?: DEFAULT_CUSTOM_URL
        }
        return template.replace("{query}", encoded)
    }

    fun playStoreUrl(query: String): String =
        "https://play.google.com/store/search?q=${encodeQuery(query)}&c=apps"

    /** These actions need no package/URI extras. Callers must resolve them before launching. */
    val settingTargets: List<SettingsTarget> = listOf(
        SettingsTarget("Settings", "android.settings.SETTINGS"),
        SettingsTarget("Wi-Fi", "android.settings.WIFI_SETTINGS"),
        SettingsTarget("Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
        SettingsTarget("Mobile network", "android.settings.DATA_ROAMING_SETTINGS"),
        SettingsTarget("Wireless and networks", "android.settings.WIRELESS_SETTINGS"),
        SettingsTarget("Airplane mode", "android.settings.AIRPLANE_MODE_SETTINGS"),
        SettingsTarget("VPN", "android.settings.VPN_SETTINGS"),
        SettingsTarget("NFC", "android.settings.NFC_SETTINGS"),
        SettingsTarget("Display", "android.settings.DISPLAY_SETTINGS"),
        SettingsTarget("Sound", "android.settings.SOUND_SETTINGS"),
        SettingsTarget("Storage", "android.settings.INTERNAL_STORAGE_SETTINGS"),
        SettingsTarget("Security", "android.settings.SECURITY_SETTINGS"),
        SettingsTarget("Privacy", "android.settings.PRIVACY_SETTINGS"),
        SettingsTarget("Location", "android.settings.LOCATION_SOURCE_SETTINGS"),
        SettingsTarget("Accessibility", "android.settings.ACCESSIBILITY_SETTINGS"),
        SettingsTarget("Keyboard and input", "android.settings.INPUT_METHOD_SETTINGS"),
        SettingsTarget("Language", "android.settings.LOCALE_SETTINGS"),
        SettingsTarget("Date and time", "android.settings.DATE_SETTINGS"),
        SettingsTarget("Applications", "android.settings.APPLICATION_SETTINGS"),
        SettingsTarget("Default home app", "android.settings.HOME_SETTINGS"),
        SettingsTarget("Battery saver", "android.settings.BATTERY_SAVER_SETTINGS"),
        SettingsTarget("Accounts and sync", "android.settings.SYNC_SETTINGS"),
        SettingsTarget("Printing", "android.settings.ACTION_PRINT_SETTINGS"),
        SettingsTarget("Cast", "android.settings.CAST_SETTINGS"),
        SettingsTarget("Screen saver", "android.settings.DREAM_SETTINGS"),
        SettingsTarget("About device", "android.settings.DEVICE_INFO_SETTINGS")
    )

    internal fun validCustomUrl(value: String): String? {
        val template = value.trim()
        if (template.length !in 1..2_048 || !template.contains("{query}")) return null
        if (template.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
        if (template.windowed("{query}".length).count { it == "{query}" } > 4) return null
        return try {
            val uri = URI(template.replace("{query}", "liteluncherquery"))
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme !in setOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in -1..65_535
            ) return null
            if (listOf(uri.path, uri.query, uri.fragment).any { part ->
                    part?.any { it.isISOControl() || it == '\\' } == true
                }) return null
            // A query may be substituted in a path, query string, or fragment, never a host.
            val authorityStart = template.indexOf("://")
            if (authorityStart < 0) return null
            val authorityEnd = template.indexOfAny(charArrayOf('/', '?', '#'), authorityStart + 3)
                .let { if (it < 0) template.length else it }
            if (template.substring(0, authorityEnd).contains("{query}")) null
            else scheme + template.substring(template.indexOf(':'))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }

    private fun encodeQuery(query: String): String =
        URLEncoder.encode(query.trim().take(MAX_QUERY_LENGTH), "UTF-8").replace("+", "%20")

    private class Parser(private val source: String) {
        private var position = 0
        private var depth = 0
        private var operations = 0

        fun parse(): Double {
            val result = sum()
            whitespace()
            require(position == source.length)
            return finite(result)
        }

        private fun sum(): Double {
            var result = product()
            while (true) {
                result = when {
                    consume('+') -> finite(result + product())
                    consume('-') -> finite(result - product())
                    else -> return result
                }
                operation()
            }
        }

        private fun product(): Double {
            var result = unary()
            while (true) {
                result = when {
                    consume('*') -> finite(result * unary())
                    consume('/') -> {
                        val divisor = unary()
                        require(divisor != 0.0)
                        finite(result / divisor)
                    }
                    consume('%') -> {
                        val divisor = unary()
                        require(divisor != 0.0)
                        finite(result % divisor)
                    }
                    else -> return result
                }
                operation()
            }
        }

        // Unary minus binds less tightly than exponentiation; powers associate to the right.
        private fun unary(): Double = nested {
            when {
                consume('+') -> { operation(); unary() }
                consume('-') -> { operation(); finite(-unary()) }
                else -> power()
            }
        }

        private fun power(): Double {
            val base = primary()
            return if (consume('^')) {
                operation()
                finite(base.pow(unary()))
            } else base
        }

        private fun primary(): Double {
            whitespace()
            if (consume('(')) {
                val value = sum()
                require(consume(')'))
                return value
            }
            require(position < source.length)
            if (source[position].isLetter()) {
                val start = position
                while (position < source.length && source[position].isLetter()) position++
                return when (val name = source.substring(start, position).lowercase(Locale.ROOT)) {
                    "pi", "\u03C0" -> Math.PI
                    "e" -> Math.E
                    "sqrt", "abs", "sin", "cos", "tan", "log", "ln" -> {
                        require(consume('('))
                        val argument = sum()
                        require(consume(')'))
                        operation()
                        finite(when (name) {
                            "sqrt" -> sqrt(argument)
                            "abs" -> abs(argument)
                            "sin" -> sin(argument)
                            "cos" -> cos(argument)
                            "tan" -> {
                                // Floating point cos(pi / 2) is tiny rather than exactly zero.
                                require(abs(cos(argument)) > 1e-15)
                                tan(argument)
                            }
                            "log" -> log10(argument)
                            else -> ln(argument)
                        })
                    }
                    else -> throw IllegalArgumentException("Unknown function or constant")
                }
            }
            return number()
        }

        private fun number(): Double {
            val start = position
            var digits = 0
            while (position < source.length && source[position] in '0'..'9') { position++; digits++ }
            if (position < source.length && source[position] == '.') {
                position++
                while (position < source.length && source[position] in '0'..'9') { position++; digits++ }
            }
            require(digits > 0)
            if (position < source.length && source[position] in "eE") {
                position++
                if (position < source.length && source[position] in "+-") position++
                val exponentStart = position
                while (position < source.length && source[position] in '0'..'9') position++
                require(position > exponentStart)
            }
            return finite(source.substring(start, position).toDouble())
        }

        private fun whitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        private fun consume(character: Char): Boolean {
            whitespace()
            if (position >= source.length || source[position] != character) return false
            position++
            return true
        }

        private fun operation() { require(++operations <= MAX_OPERATIONS) }
        private fun finite(value: Double): Double { require(value.isFinite()); return value }
        private inline fun nested(block: () -> Double): Double {
            require(++depth <= MAX_DEPTH)
            return try { block() } finally { depth-- }
        }
    }
}
