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
            result.stripTrailingZeros().let { if (it.signum() == 0) "0" else if (it.scale() in -20..40) it.toPlainString() else it.toString() }
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
            WebEngine.NAVER -> "https://search.naver.com/search.naver?query={query}"
            WebEngine.QWANT -> "https://www.qwant.com/?q={query}"
            WebEngine.BRAVE -> "https://search.brave.com/search?q={query}"
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

        fun parse(): BigDecimal {
            val result = sum()
            whitespace()
            require(position == source.length)
            return finite(result)
        }

        private fun sum(): BigDecimal {
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

        private fun product(): BigDecimal {
            var result = unary()
            while (true) {
                result = when {
                    consume('*') -> finite(result * unary())
                    consume('/') -> {
                        val divisor = unary()
                        require(divisor.signum() != 0)
                        finite(result.divide(divisor, MathContext.DECIMAL128))
                    }
                    consume('%') -> {
                        val divisor = unary()
                        require(divisor.signum() != 0)
                        finite(result % divisor)
                    }
                    else -> return result
                }
                operation()
            }
        }

        // Unary minus binds less tightly than exponentiation; powers associate to the right.
        private fun unary(): BigDecimal = nested {
            when {
                consume('+') -> { operation(); unary() }
                consume('-') -> { operation(); finite(-unary()) }
                else -> power()
            }
        }

        private fun power(): BigDecimal {
            val base = primary()
            return if (consume('^')) {
                operation()
                val exponent = unary()
                val integer = runCatching { exponent.intValueExact() }.getOrNull()
                if (integer != null && integer in -1024..1024) {
                    if (integer >= 0) finite(base.pow(integer))
                    else finite(BigDecimal.ONE.divide(base.pow(-integer), MathContext.DECIMAL128))
                } else approximate(base.toDouble().pow(exponent.toDouble()))
            } else base
        }

        private fun primary(): BigDecimal {
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
                    "pi", "\u03C0" -> BigDecimal.valueOf(Math.PI)
                    "e" -> BigDecimal.valueOf(Math.E)
                    "sqrt", "abs", "sin", "cos", "tan", "log", "ln" -> {
                        require(consume('('))
                        val decimalArgument = sum()
                        val argument = decimalArgument.toDouble()
                        require(consume(')'))
                        operation()
                        if (name == "abs") return decimalArgument.abs()
                        approximate(when (name) {
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

        private fun number(): BigDecimal {
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
            return finite(source.substring(start, position).toBigDecimal())
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
        private fun finite(value: BigDecimal): BigDecimal {
            require(value.precision() <= 4096 && value.scale() in -4096..4096)
            return value
        }
        private fun approximate(value: Double): BigDecimal { require(value.isFinite()); return finite(BigDecimal.valueOf(value)) }
        private inline fun nested(block: () -> BigDecimal): BigDecimal {
            require(++depth <= MAX_DEPTH)
            return try { block() } finally { depth-- }
        }
    }
}
