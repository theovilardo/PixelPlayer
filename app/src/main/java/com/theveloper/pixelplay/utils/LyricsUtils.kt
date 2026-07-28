package com.theveloper.pixelplay.utils

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.atilika.kuromoji.ipadic.Tokenizer
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import kotlinx.coroutines.flow.Flow

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

// Roman Multilang
object MultiLangRomanizer {
    private val kuromojiTokenizer: Tokenizer? by lazy {
        try {
            Thread.currentThread().contextClassLoader = MultiLangRomanizer::class.java.classLoader
            Tokenizer()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private val HANGUL_ROMAJA_MAP = mapOf(
        "cho" to mapOf("ᄀ" to "g", "ᄁ" to "kk", "ᄂ" to "n", "ᄃ" to "d", "ᄄ" to "tt", "ᄅ" to "r", "ᄆ" to "m", "ᄇ" to "b", "ᄈ" to "pp", "ᄉ" to "s", "ᄊ" to "ss", "ᄋ" to "", "ᄌ" to "j", "ᄍ" to "jj", "ᄎ" to "ch", "ᄏ" to "k", "ᄐ" to "t", "ᄑ" to "p", "ᄒ" to "h"),
        "jung" to mapOf("ᅡ" to "a", "ᅢ" to "ae", "ᅣ" to "ya", "ᅤ" to "yae", "ᅥ" to "eo", "ᅦ" to "e", "ᅧ" to "yeo", "ᅨ" to "ye", "ᅩ" to "o", "ᅪ" to "wa", "ᅫ" to "wae", "ᅬ" to "oe", "ᅭ" to "yo", "ᅮ" to "u", "ᅯ" to "wo", "ᅰ" to "we", "ᅱ" to "wi", "ᅲ" to "yu", "ᅳ" to "eu", "ᅴ" to "eui", "ᅵ" to "i"),
        "jong" to mapOf("ᆨ" to "k", "ᆨᄋ" to "g", "ᆨᄂ" to "ngn", "ᆨᄅ" to "ngn", "ᆨᄆ" to "ngm", "ᆨᄒ" to "kh", "ᆩ" to "kk", "ᆩᄋ" to "kg", "ᆩᄂ" to "ngn", "ᆩᄅ" to "ngn", "ᆩᄆ" to "ngm", "ᆩᄒ" to "kh", "ᆪ" to "k", "ᆪᄋ" to "ks", "ᆪᄂ" to "ngn", "ᆪᄅ" to "ngn", "ᆪᄆ" to "ngm", "ᆪᄒ" to "kch", "ᆫ" to "n", "ᆫᄅ" to "ll", "ᆬ" to "n", "ᆬᄋ" to "nj", "ᆬᄂ" to "nn", "ᆬᄅ" to "nn", "ᆬᄆ" to "nm", "ᆬㅎ" to "nch", "ᆭ" to "n", "ᆭᄋ" to "nh", "ᆭᄅ" to "nn", "ᆮ" to "t", "ᆮᄋ" to "d", "ᆮᄂ" to "nn", "ᆮᄅ" to "nn", "ᆮᄆ" to "nm", "ᆮᄒ" to "th", "ᆯ" to "l", "ᆯᄋ" to "r", "ᆯᄂ" to "ll", "ᆯᄅ" to "ll", "ᆰ" to "k", "ᆰᄋ" to "lg", "ᆰᄂ" to "ngn", "ᆰᄅ" to "ngn", "ᆰᄆ" to "ngm", "ᆰᄒ" to "lkh", "ᆱ" to "m", "ᆱᄋ" to "lm", "ᆱᄂ" to "mn", "ᆱᄅ" to "mn", "ᆱᄆ" to "mm", "ᆱᄒ" to "lmh", "ᆲ" to "p", "ᆲᄋ" to "lb", "ᆲᄂ" to "mn", "ᆲᄅ" to "mn", "ᆲᄆ" to "mm", "ᆲᄒ" to "lph", "ᆳ" to "t", "ᆳᄋ" to "ls", "ᆳᄂ" to "nn", "ᆳᄅ" to "nn", "ᆳᄆ" to "nm", "ᆳᄒ" to "lsh", "ᆴ" to "t", "ᆴᄋ" to "lt", "ᆴᄂ" to "nn", "ᆴᄅ" to "nn", "ᆴᄆ" to "nm", "ᆴᄒ" to "lth", "ᆵ" to "p", "ᆵᄋ" to "lp", "ᆵᄂ" to "mn", "ᆵᄅ" to "mn", "ᆵᄆ" to "mm", "ᆵᄒ" to "lph", "ᆶ" to "l", "ᆶᄋ" to "lh", "ᆶᄂ" to "ll", "ᆶᄅ" to "ll", "ᆶᄆ" to "lm", "ᆶᄒ" to "lh", "ᆷ" to "m", "ᆷᄅ" to "mn", "ᆸ" to "p", "ᆸᄋ" to "b", "ᆸᄂ" to "mn", "ᆸᄅ" to "mn", "ᆸᄆ" to "mm", "ᆸᄒ" to "ph", "ᆹ" to "p", "ᆹᄋ" to "ps", "ᆹᄂ" to "mn", "ᆹᄅ" to "mn", "ᆹᄆ" to "mm", "ᆹᄒ" to "psh", "ᆺ" to "t", "ᆺᄋ" to "s", "ᆺᄂ" to "nn", "ᆺᄅ" to "nn", "ᆺᄆ" to "nm", "ᆺᄒ" to "sh", "ᆻ" to "t", "ᆻᄋ" to "ss", "ᆻᄂ" to "tn", "ᆻᄅ" to "tn", "ᆻᄆ" to "nm", "ᆻᄒ" to "th", "ᆼ" to "ng", "ᆽ" to "t", "ᆽᄋ" to "j", "ᆽᄂ" to "nn", "ᆽᄅ" to "nn", "ᆽᄆ" to "nm", "ᆽᄒ" to "ch", "ᆾ" to "t", "ᆾᄋ" to "ch", "ᆾᄂ" to "nn", "ᆾᄅ" to "nn", "ᆾᄆ" to "nm", "ᆾᄒ" to "ch", "ᆿ" to "k", "ᆿᄋ" to "k", "ᆿᄂ" to "ngn", "ᆿᄅ" to "ngn", "ᆿᄆ" to "ngm", "ᆿᄒ" to "kh", "ᇀ" to "t", "ᇀᄋ" to "t", "ᇀᄂ" to "nn", "ᇀᄅ" to "nn", "ᇀᄆ" to "nm", "ᇀᄒ" to "th", "ᇁ" to "p", "ᇁᄋ" to "p", "ᇁᄂ" to "mn", "ᇁᄅ" to "mn", "ᇁᄆ" to "mm", "ᇁᄒ" to "ph", "ᇂ" to "t", "ᇂᄋ" to "h", "ᇂᄂ" to "nn", "ᇂᄅ" to "nn", "ᇂᄆ" to "mm", "ᇂᄒ" to "t", "ᇂᄀ" to "k")
    )

    private val DEVANAGARI_ROMAJI_MAP = mapOf(
        "अ" to "a", "आ" to "aa", "इ" to "i", "ई" to "ee", "उ" to "u", "ऊ" to "oo", "ऋ" to "ri", "ए" to "e", "ऐ" to "ai", "ओ" to "o", "औ" to "au", "क" to "k", "ख" to "kh", "ग" to "g", "घ" to "gh", "ङ" to "ng", "च" to "ch", "छ" to "chh", "ज" to "j", "झ" to "jh", "ञ" to "ny", "ट" to "t", "ठ" to "th", "ड" to "d", "ढ" to "dh", "ण" to "n", "त" to "t", "थ" to "th", "द" to "d", "ध" to "dh", "न" to "n", "प" to "p", "फ" to "ph", "ब" to "b", "भ" to "bh", "म" to "m", "य" to "y", "र" to "r", "ल" to "l", "व" to "v", "श" to "sh", "ष" to "sh", "स" to "s", "ह" to "h", "क्ष" to "ksh", "त्र" to "tr", "ज्ञ" to "gy", "श्र" to "shr", "ा" to "aa", "ि" to "i", "ी" to "ee", "ु" to "u", "ू" to "oo", "ृ" to "ri", "े" to "e", "ै" to "ai", "ो" to "o", "ौ" to "au", "ं" to "n", "ः" to "h", "ँ" to "n", "़" to "", "्" to "", "०" to "0", "१" to "1", "२" to "2", "३" to "3", "४" to "4", "५" to "5", "६" to "6", "७" to "7", "८" to "8", "९" to "9", "ॐ" to "Om", "ऽ" to "", "क़" to "q", "ख़" to "kh", "ग़" to "g", "ज़" to "z", "ड़" to "r", "ढ़" to "rh", "फ़" to "f", "य़" to "y", "क\u093C" to "q", "ख\u093C" to "kh", "ग\u093C" to "g", "ज\u093C" to "z", "ड\u093C" to "r", "ढ\u093C" to "rh", "फ\u093C" to "f", "य\u093C" to "y"
    )

    private val GURMUKHI_ROMAJI_MAP = mapOf(
        "ੳ" to "o", "ਅ" to "a", "ੲ" to "e", "ਸ" to "s", "ਹ" to "h", "ਕ" to "k", "ਖ" to "kh", "ਗ" to "g", "ਘ" to "gh", "ਙ" to "ng", "ਚ" to "ch", "ਛ" to "chh", "ਜ" to "j", "ਝ" to "jh", "ਞ" to "ny", "ਟ" to "t", "ਠ" to "th", "ਡ" to "d", "ਢ" to "dh", "ਣ" to "n", "ਤ" to "t", "ਥ" to "th", "ਦ" to "d", "ਧ" to "dh", "ਨ" to "n", "ਪ" to "p", "ਫ" to "ph", "ਬ" to "b", "ਭ" to "bh", "ਮ" to "m", "ਯ" to "y", "ਰ" to "r", "ਲ" to "l", "ਵ" to "v", "ੜ" to "r", "ਸ਼" to "sh", "ਖ਼" to "kh", "ਗ਼" to "g", "ਜ਼" to "z", "ਫ਼" to "f", "ਲ਼" to "l", "ਾ" to "aa", "ਿ" to "i", "ੀ" to "ee", "ੁ" to "u", "ੂ" to "oo", "ੇ" to "e", "ੈ" to "ai", "ੋ" to "o", "ੌ" to "au", "ੰ" to "n", "ਂ" to "n", "ੱ" to "", "੍" to "", "਼" to "", "ੴ" to "Ek Onkar", "੦" to "0", "੧" to "1", "੨" to "2", "੩" to "3", "੪" to "4", "੫" to "5", "੬" to "6", "੭" to "7", "੮" to "8", "੯" to "9"
    )

    private val GENERAL_CYRILLIC_ROMAJI_MAP = mapOf(
        "А" to "A", "Б" to "B", "В" to "V", "Г" to "G", "Ґ" to "G", "Д" to "D", "Ѓ" to "Ǵ", "Ђ" to "Đ", "Е" to "E", "Ё" to "Yo", "Є" to "Ye", "Ж" to "Zh", "З" to "Z", "Ѕ" to "Dz", "И" to "I", "І" to "I", "Ї" to "Yi", "Й" to "Y", "Ј" to "Y", "К" to "K", "Л" to "L", "Љ" to "Ly", "М" to "M", "Н" to "N", "Њ" to "Ny", "О" to "O", "П" to "P", "Р" to "R", "С" to "S", "Т" to "T", "Ћ" to "Ć", "У" to "U", "Ў" to "Ŭ", "Ф" to "F", "Х" to "Kh", "Ц" to "Ts", "Ч" to "Ch", "Џ" to "Dž", "Ш" to "Sh", "Щ" to "Shch", "Ъ" to "ʺ", "Ы" to "Y", "Ь" to "ʹ", "Э" to "E", "Ю" to "Yu", "Я" to "Ya",
        "а" to "a", "б" to "b", "в" to "v", "г" to "g", "ґ" to "g", "д" to "d", "ѓ" to "ǵ", "ђ" to "đ", "е" to "e", "ё" to "yo", "є" to "ye", "ж" to "zh", "з" to "z", "ѕ" to "dz", "и" to "i", "і" to "i", "ї" to "yi", "й" to "y", "ј" to "y", "к" to "k", "л" to "l", "љ" to "ly", "м" to "m", "н" to "n", "њ" to "ny", "о" to "o", "п" to "p", "р" to "r", "с" to "s", "т" to "t", "ћ" to "ć", "у" to "u", "ў" to "ŭ", "ф" to "f", "х" to "kh", "ц" to "ts", "ч" to "ch", "џ" to "dž", "ш" to "sh", "щ" to "shch", "ъ" to "ʺ", "ы" to "y", "ь" to "ʹ", "э" to "e", "ю" to "yu", "я" to "ya"
    )

    private val RUSSIAN_ROMAJI_MAP = mapOf("ого" to "ovo", "Ого" to "Ovo", "его" to "evo", "Его" to "Evo")
    private val UKRAINIAN_ROMAJI_MAP = mapOf("Г" to "H", "г" to "h", "Ґ" to "G", "ґ" to "g", "Є" to "Ye", "є" to "ye", "І" to "I", "і" to "i", "Ї" to "Yi", "ї" to "yi")
    private val SERBIAN_ROMAJI_MAP = mapOf("Ж" to "Ž", "Љ" to "Lj", "Њ" to "Nj", "Ц" to "C", "Ч" to "Č", "Џ" to "Dž", "Ш" to "Š", "Х" to "H", "ж" to "ž", "љ" to "lj", "њ" to "nj", "ц" to "c", "ч" to "č", "џ" to "dž", "ш" to "š", "х" to "h")
    private val BULGARIAN_ROMAJI_MAP = mapOf("Ж" to "Zh", "Ц" to "Ts", "Ч" to "Ch", "Ш" to "Sh", "Щ" to "Sht", "Ъ" to "A", "Ь" to "Y", "Ю" to "Yu", "Я" to "Ya", "ж" to "zh", "ц" to "ts", "ч" to "ch", "ш" to "sh", "щ" to "sht", "ъ" to "a", "ь" to "y", "ю" to "yu", "я" to "ya")
    private val BELARUSIAN_ROMAJI_MAP = mapOf("Г" to "H", "г" to "h", "Ў" to "W", "ў" to "w")
    private val KYRGYZ_ROMAJI_MAP = mapOf("Ү" to "Ü", "ү" to "ü", "Ы" to "Y", "ы" to "y")
    private val MACEDONIAN_ROMAJI_MAP = mapOf("Ѓ" to "Gj", "Ѕ" to "Dz", "И" to "I", "Ј" to "J", "Љ" to "Lj", "Њ" to "Nj", "Ќ" to "Kj", "Џ" to "Dž", "Ч" to "Č", "Ш" to "Sh", "Ж" to "Zh", "Ц" to "C", "Х" to "H", "ѓ" to "gj", "ѕ" to "dz", "и" to "i", "ј" to "j", "љ" to "lj", "њ" to "nj", "ќ" to "kj", "џ" to "dž", "ч" to "č", "ш" to "sh", "ж" to "zh", "ц" to "c", "х" to "h")

    private val RUSSIAN_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ы", "Ь", "Э", "Ю", "Я", "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я")
    private val UKRAINIAN_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Ґ", "Д", "Е", "Є", "Ж", "З", "И", "І", "Ї", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ь", "Ю", "Я", "а", "б", "в", "г", "ґ", "д", "е", "є", "ж", "з", "и", "і", "ї", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ь", "ю", "я")
    private val SERBIAN_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Д", "Ђ", "Е", "Ж", "З", "И", "Ј", "К", "Л", "Љ", "М", "Н", "Њ", "О", "П", "Р", "С", "Т", "Ћ", "У", "Ф", "Х", "Ц", "Ч", "Џ", "Ш", "а", "б", "в", "г", "д", "ђ", "е", "ж", "з", "и", "ј", "к", "л", "љ", "м", "н", "њ", "о", "п", "р", "с", "т", "ћ", "у", "ф", "х", "ц", "ч", "џ", "ш")
    private val BULGARIAN_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ь", "Ю", "Я", "а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ь", "ю", "я")
    private val BELARUSIAN_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "І", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ў", "Ф", "Х", "Ц", "Ч", "Ш", "Ь", "Ю", "Я", "Ы", "Э", "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "і", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ў", "ф", "х", "ц", "ч", "ш", "ь", "ю", "я", "ы", "э")
    private val KYRGYZ_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "Ң", "О", "Ө", "П", "Р", "С", "Т", "У", "Ү", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ы", "Ь", "Э", "Ю", "Я", "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н", "ң", "о", "ө", "п", "р", "с", "т", "у", "ү", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я")
    private val MACEDONIAN_CYRILLIC_LETTERS = setOf("А", "Б", "В", "Г", "Д", "Ѓ", "Е", "Ж", "З", "Ѕ", "И", "Ј", "К", "Л", "Љ", "М", "Н", "Њ", "О", "П", "Р", "С", "Т", "Ќ", "У", "Ф", "Х", "Ц", "Ч", "Џ", "Ш", "а", "б", "в", "г", "д", "ѓ", "е", "ж", "з", "ѕ", "и", "ј", "к", "л", "љ", "м", "н", "њ", "о", "п", "р", "с", "т", "ќ", "у", "ф", "х", "ц", "ч", "џ", "ш")

    private val UKRAINIAN_SPECIFIC_CYRILLIC_LETTERS = setOf("Ґ", "ґ", "Є", "є", "І", "і", "Ї", "ї")
    private val SERBIAN_SPECIFIC_CYRILLIC_LETTERS = setOf("Ђ", "ђ", "Ј", "ј", "Љ", "љ", "Њ", "њ", "Ћ", "ћ", "Џ", "џ")
    private val BELARUSIAN_SPECIFIC_CYRILLIC_LETTERS = setOf("Ў", "ў", "І", "і")
    private val KYRGYZ_SPECIFIC_CYRILLIC_LETTERS = setOf("Ң", "ң", "Ө", "ө", "Ү", "ү")
    private val MACEDONIAN_SPECIFIC_CYRILLIC_LETTERS = setOf("Ѓ", "ѓ", "Ѕ", "ѕ", "Ќ", "ќ")

    fun isJapanese(text: String, entireLyricsHasKana: Boolean = false): Boolean {
        // Detect Hiragana or Katakana
        if (text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }) return true
        
        // Treat Kanji-only lines as Japanese only if the entire song has Japanese kana
        return entireLyricsHasKana && text.any { it in '\u4E00'..'\u9FFF' }
    }
    fun isKorean(text: String) = text.any { it in '\uAC00'..'\uD7A3' }
    fun isHindi(text: String) = text.any { it in '\u0900'..'\u097F' }
    fun isPunjabi(text: String) = text.any { it in '\u0A00'..'\u0A7F' }
    fun isCyrillic(text: String) = text.any { it in '\u0400'..'\u04FF' }
    fun isChinese(text: String) = text.any { it in '\u4E00'..'\u9FFF' }
    fun isThai(text: String) = text.any { it in '\u0E00'..'\u0E7F' }
    fun isArabic(text: String) = text.any { it in '\u0600'..'\u06FF' }
    fun isGreek(text: String) = text.any { it in '\u0370'..'\u03FF' }
    fun isHebrew(text: String) = text.any { it in '\u0590'..'\u05FF' }
    fun isVietnamese(text: String): Boolean {
        return text.any { it in '\u1EA0'..'\u1EF9' } ||
                text.any { it in "\u0102\u0103\u0110\u0111\u01A0\u01A1\u01AF\u01B0" }
    }

    fun isScriptThatNeedsRomanization(text: String): Boolean {
        if (isVietnamese(text) || isThai(text) || isArabic(text) || isGreek(text) || isHebrew(text)) return true
        return text.any { char ->
            val code = char.code
            code in 0x3040..0x309F || // Hiragana
            code in 0x30A0..0x30FF || // Katakana
            code in 0x4E00..0x9FFF || // CJK Unified Ideographs (Chinese)
            code in 0xAC00..0xD7A3 || // Hangul (Korean)
            code in 0x0900..0x097F || // Devanagari (Hindi)
            code in 0x0A00..0x0A7F || // Gurmukhi (Punjabi)
            code in 0x0400..0x04FF    // Cyrillic
        }
    }

    internal fun isRussian(text: String) = text.any { RUSSIAN_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { RUSSIAN_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }
    internal fun isUkrainian(text: String) = text.any { UKRAINIAN_CYRILLIC_LETTERS.contains(it.toString()) || UKRAINIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { UKRAINIAN_CYRILLIC_LETTERS.contains(it.toString()) || UKRAINIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }
    private fun isSerbian(text: String) = text.any { SERBIAN_CYRILLIC_LETTERS.contains(it.toString()) || SERBIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { SERBIAN_CYRILLIC_LETTERS.contains(it.toString()) || SERBIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }
    private fun isBulgarian(text: String) = text.any { BULGARIAN_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { BULGARIAN_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }
    private fun isBelarusian(text: String) = text.any { BELARUSIAN_CYRILLIC_LETTERS.contains(it.toString()) || BELARUSIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { BELARUSIAN_CYRILLIC_LETTERS.contains(it.toString()) || BELARUSIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }
    private fun isKyrgyz(text: String) = text.any { KYRGYZ_CYRILLIC_LETTERS.contains(it.toString()) || KYRGYZ_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { KYRGYZ_CYRILLIC_LETTERS.contains(it.toString()) || KYRGYZ_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }
    private fun isMacedonian(text: String) = text.any { MACEDONIAN_CYRILLIC_LETTERS.contains(it.toString()) || MACEDONIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) } && text.all { MACEDONIAN_CYRILLIC_LETTERS.contains(it.toString()) || MACEDONIAN_SPECIFIC_CYRILLIC_LETTERS.contains(it.toString()) || !it.toString().matches("[\\u0400-\\u04FF]".toRegex()) }

    fun romanizeJapanese(japaneseText: String): String? {
        val tokenizer = kuromojiTokenizer ?: return null

        return try {
            val tokens = tokenizer.tokenize(japaneseText)

            // ── Pass 1: resolve readings ──────────────────────────────────────
            val readings = mutableListOf<Triple<String, String, String>>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                val next  = tokens.getOrNull(i + 1)

                // Irregular lexical compounds
                when {
                    token.surface == "一" && next?.surface == "人" -> {
                        readings += Triple("ヒトリ", "名詞", "一般"); i += 2; continue
                    }
                    token.surface == "二" && next?.surface == "人" -> {
                        readings += Triple("フタリ", "名詞", "一般"); i += 2; continue
                    }
                    token.surface == "今日" -> {
                        readings += Triple("キョウ", "名詞", "一般"); i++; continue
                    }
                    token.surface == "明日" -> {
                        readings += Triple("アシタ", "名詞", "一般"); i++; continue
                    }
                    token.surface == "昨日" -> {
                        readings += Triple("キノウ", "名詞", "一般"); i++; continue
                    }
                }

                val pos1    = token.partOfSpeechLevel1 ?: ""
                val pos2    = token.partOfSpeechLevel2 ?: ""
                val surface = token.surface ?: ""
                val reading = token.reading?.takeIf { it.isNotBlank() && it != "*" }

                val kata = when {
                    pos1 == "助詞" -> when (surface) {
                        "は" -> "ワ"
                        "へ" -> "エ"
                        "を" -> "オ"
                        else -> reading ?: surface
                    }
                    else -> reading ?: surface
                }
                readings += Triple(kata, pos1, pos2)
                i++
            }

            // ── Pass 2: build katakana string with spacing ────────────────────
            val kataBuf = StringBuilder()
            readings.forEachIndexed { idx, (kata, pos1, pos2) ->
                val prevKata = readings.getOrNull(idx - 1)?.first ?: ""
                val prevEndsWithSokuon = prevKata.endsWith("ッ") || prevKata.endsWith("っ")
                val noSpace = idx == 0
                    || prevEndsWithSokuon
                    || pos1 == "助動詞"
                    || pos2 == "接尾"
                    || pos1 == "記号"
                    || pos2 == "非自立"
                if (!noSpace) kataBuf.append(" ")
                kataBuf.append(kata)
            }

            val katakanaText = kataBuf.toString().replace("\\s+".toRegex(), " ").trim()

            // ── Pass 3: Katakana → Latin via ICU transliterator ───────────────
            val latin = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.icu.text.Transliterator
                    .getInstance("Katakana-Latin; Lower")
                    .transliterate(katakanaText)
            } else {
                katakanaText
            }

            // ── Pass 4: context-sensitive post-processing ─────────────────────
            // Fix ん before b/p → "m" (standard Hepburn).
            // Fix word-initial false "m" that is actually "n".
            latin
                .replace(Regex("n(?=[bp])"), "m")
                .replace(Regex("\\s+"), " ")
                .trim()

        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    // ── 多音字 (polyphone) override table ────────────────────────────────────
    // Pinyin4j always returns the statistically most common reading which is
    // wrong in many song-lyric contexts. This table provides the dominant
    // lyric-usage reading for the most frequent polyphonies.
    // Format: Char → preferred pinyin (no tone, lowercase, matches Pinyin4j output format)
    private val POLYPHONE_OVERRIDE = mapOf(
        // 中 zhōng (middle/China) vs zhòng (hit/heavy) — zhong far more common in lyrics
        '中' to "zhong",
        // 行 xíng (walk/OK) vs háng (row/profession)
        '行' to "xing",
        // 乐 lè (happy) vs yuè (music) — context: 音乐→yue, 快乐→le; default to le
        // (handled contextually below)
        // 长 cháng (long) vs zhǎng (grow/leader)
        '长' to "chang",
        // 重 zhòng (heavy/important) vs chóng (repeat/again)
        '重' to "zhong",
        // 好 hǎo (good) vs hào (like/hobby)
        '好' to "hao",
        // 觉 jué (feel/sense) vs jiào (sleep)
        '觉' to "jue",
        // 得 de (structural particle) vs dé (get) vs děi (must)
        '得' to "de",
        // 地 dì (earth) vs de (structural particle)
        // context: after adjective → de; standalone → di; default di
        '地' to "di",
        // 的 de (possessive particle) — always de in lyrics
        '的' to "de",
        // 了 le (completion particle) vs liǎo (finish/understand)
        '了' to "le",
        // 还 hái (still/yet) vs huán (return)
        '还' to "hai",
        // 只 zhǐ (only) vs zhī (measure word for animals)
        '只' to "zhi",
        // 着 zhe (progressive particle) vs zháo (touch) vs zhāo (trick)
        '着' to "zhe",
        // 没 méi (not have) vs mò (sink/drown)
        '没' to "mei",
        // 为 wèi (for/because) vs wéi (be/act as)
        '为' to "wei",
        // 难 nán (difficult) vs nàn (disaster)
        '难' to "nan",
        // 空 kōng (empty/sky) vs kòng (free time/gap)
        '空' to "kong",
        // 间 jiān (between/room) vs jiàn (interval)
        '间' to "jian",
        // 数 shù (number) vs shǔ (count) vs shuò (frequent)
        '数' to "shu",
        // 差 chā (difference) vs chà (bad/differ) vs chāi (dispatch)
        '差' to "cha",
        // 调 diào (tune/transfer) vs tiáo (adjust)
        '调' to "diao",
        // 传 chuán (pass/spread) vs zhuàn (biography)
        '传' to "chuan",
        // 弹 tán (play instrument/flick) vs dàn (bullet/bomb)
        '弹' to "tan",
        // 发 fā (send/emit) vs fà (hair)
        '发' to "fa",
        // 分 fēn (minute/divide) vs fèn (portion/share)
        '分' to "fen",
        // 过 guò (pass/experience) — always guo as particle
        '过' to "guo",
        // 和 hé (and/harmonious) vs hè (respond in singing) vs huó (mix)
        '和' to "he",
        // 会 huì (can/meeting) vs kuài (fast — dialectal)
        '会' to "hui",
        // 看 kàn (look) vs kān (watch over)
        '看' to "kan",
        // 乐 — handled contextually: 音乐 yuè, 快乐/高兴 lè
        // 男 nán — only one reading, but Pinyin4j sometimes fails
        '男' to "nan",
        // 女 nǚ
        '女' to "nu",
        // 儿 ér (child/Erhua marker)
        '儿' to "er",
        // 那 nà (that) vs nǎ (which) vs nèi (contraction)
        '那' to "na",
        // 哪 nǎ (which)
        '哪' to "na",
        // 啊 a (exclamation) — always a
        '啊' to "a",
        // 吗 ma (question particle)
        '吗' to "ma",
        // 呢 ne (particle)
        '呢' to "ne",
        // 吧 ba (particle)
        '吧' to "ba",
        // 嗯 ń/ňg — approximated
        '嗯' to "en",
        // 哦 ó/ò — approximated
        '哦' to "o",
        // 喔 ō
        '喔' to "o",
        // 哇 wā (wow)
        '哇' to "wa",
        // 咦 yí (surprise)
        '咦' to "yi",
        // 呀 ya (particle)
        '呀' to "ya",
        // 嘛 ma (obviously particle)
        '嘛' to "ma"
    )

    // Context-sensitive overrides: when character follows specific preceding characters
    // Map: prevChar → Map<thisChar, pinyin>
    private val CONTEXT_PINYIN = mapOf(
        '音' to mapOf('乐' to "yue"),   // 音乐 = yīnyuè (music)
        '快' to mapOf('乐' to "le"),    // 快乐 = kuàilè (happy)
        '欢' to mapOf('乐' to "le"),    // 欢乐 = huānlè
        '娱' to mapOf('乐' to "le"),    // 娱乐 = yúlè
        '生' to mapOf('长' to "zhang"), // 生长 = shēngzhǎng (grow)
        '成' to mapOf('长' to "zhang"), // 成长 = chéngzhǎng (grow up)
        '长' to mapOf('大' to "da"),    // 长大 = zhǎngdà
        '重' to mapOf('复' to "fu"),    // 重复 = chóngfù (repeat) — prev char triggers
        '再' to mapOf('重' to "chong"), // 再重 → chong context
        '严' to mapOf('重' to "zhong"), // 严重 = yánzhòng (serious)
        '地' to mapOf('方' to "di"),    // 地方 = dìfāng
        '大' to mapOf('地' to "di"),    // 大地 = dàdì
        '土' to mapOf('地' to "di")     // 土地 = tǔdì
    )

    fun romanizeChinese(text: String): String? {
        return try {
            val format = HanyuPinyinOutputFormat().apply {
                caseType = HanyuPinyinCaseType.LOWERCASE
                toneType = HanyuPinyinToneType.WITHOUT_TONE
            }

            val chars = text.toList()
            val sb = StringBuilder()
            var idx = 0

            while (idx < chars.size) {
                val c = chars[idx]
                val next = chars.getOrNull(idx + 1)

                when {
                    // ── Erhua: 儿 after a non-儿 Hanzi becomes an "r" suffix ──
                    next == '儿' && idx + 1 < chars.size
                        && c.toString().matches(Regex("[\u4E00-\u9FA5]"))
                        && c != '儿' -> {
                        val base = getPinyin(c, format, prevChar = chars.getOrNull(idx - 1))
                        // Append base pinyin with trailing 'r' (drop final -n/-ng if present)
                        val erhua = base
                            .replace(Regex("ng$"), "r")
                            .replace(Regex("n$"), "r")
                            .let { if (!it.endsWith("r")) it + "r" else it }
                        sb.append(erhua).append(" ")
                        idx += 2 // consume both 儿 and current char
                        continue
                    }

                    // ── Regular Hanzi ─────────────────────────────────────────
                    c.toString().matches(Regex("[\u4E00-\u9FA5]")) -> {
                        sb.append(getPinyin(c, format, prevChar = chars.getOrNull(idx - 1))).append(" ")
                        idx++
                        continue
                    }

                    // ── Non-Hanzi passthrough ─────────────────────────────────
                    else -> {
                        sb.append(c)
                        idx++
                        continue
                    }
                }
            }

            sb.toString().replace(Regex("""\s+"""), " ").trim()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Returns the preferred pinyin for [c], consulting in order:
     *  1. Context map (prevChar → thisChar → pinyin)
     *  2. Polyphone override table (dominant lyric-usage reading)
     *  3. Pinyin4j library (fallback)
     */
    private fun getPinyin(
        c: Char,
        format: HanyuPinyinOutputFormat,
        prevChar: Char? = null
    ): String {
        // 1. Context-sensitive override
        prevChar?.let { prev ->
            CONTEXT_PINYIN[prev]?.get(c)?.let { return it }
        }

        // 2. Polyphone override
        POLYPHONE_OVERRIDE[c]?.let { return it }

        // 3. Pinyin4j fallback
        return try {
            PinyinHelper.toHanyuPinyinStringArray(c, format)
                ?.firstOrNull()
                ?.trimEnd('0', '1', '2', '3', '4', '5')
                ?: c.toString()
        } catch (e: Exception) {
            c.toString()
        }
    }

    fun romanizeKorean(text: String): String {
        val romajaBuilder = StringBuilder()
        var prevFinal: String? = null
        for (i in text.indices) {
            val char = text[i]
            if (char in '\uAC00'..'\uD7A3') {
                val syllableIndex = char.code - 0xAC00
                val choIndex = syllableIndex / (21 * 28)
                val jungIndex = (syllableIndex % (21 * 28)) / 28
                val jongIndex = syllableIndex % 28

                val choChar = (0x1100 + choIndex).toChar().toString()
                val jungChar = (0x1161 + jungIndex).toChar().toString()
                val jongChar = if (jongIndex == 0) null else (0x11A7 + jongIndex).toChar().toString()

                if (prevFinal != null) {
                    val contextKey = prevFinal + choChar
                    val jong = HANGUL_ROMAJA_MAP["jong"]?.get(contextKey) ?: HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
                    romajaBuilder.append(jong)
                }

                val cho = HANGUL_ROMAJA_MAP["cho"]?.get(choChar) ?: choChar
                val jung = HANGUL_ROMAJA_MAP["jung"]?.get(jungChar) ?: jungChar
                romajaBuilder.append(cho).append(jung)
                prevFinal = jongChar
            } else {
                if (prevFinal != null) {
                    romajaBuilder.append(HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal)
                    prevFinal = null
                }
                romajaBuilder.append(char)
            }
        }
        if (prevFinal != null) romajaBuilder.append(HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal)
        return romajaBuilder.toString()
    }

    fun romanizeHindi(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var consumed = false
            if (i + 1 < text.length) {
                val twoCharCandidate = text.substring(i, i + 2)
                val mappedTwoChar = DEVANAGARI_ROMAJI_MAP[twoCharCandidate]
                if (mappedTwoChar != null) {
                    sb.append(mappedTwoChar)
                    i += 2
                    consumed = true
                }
            }
            if (!consumed) {
                val charStr = text[i].toString()
                sb.append(DEVANAGARI_ROMAJI_MAP[charStr] ?: charStr)
                i += 1
            }
        }
        return sb.toString()
    }

    fun romanizePunjabi(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val char = text[i]
            var consumed = false
            if (char == '\u0A71') {
                if (i + 1 < text.length) {
                    val nextCharStr = text[i+1].toString()
                    val nextMapped = GURMUKHI_ROMAJI_MAP[nextCharStr]
                    if (nextMapped != null && nextMapped.isNotEmpty()) {
                        sb.append(nextMapped[0])
                    }
                }
                i++
                continue
            }
            if (i + 1 < text.length) {
                val twoCharCandidate = text.substring(i, i + 2)
                val mappedTwoChar = GURMUKHI_ROMAJI_MAP[twoCharCandidate]
                if (mappedTwoChar != null) {
                    sb.append(mappedTwoChar)
                    i += 2
                    consumed = true
                }
            }
            if (!consumed) {
                val str = char.toString()
                sb.append(GURMUKHI_ROMAJI_MAP[str] ?: str)
                i++
            }
        }
        return sb.toString()
    }

    fun romanizeCyrillic(text: String): String? {
        if (text.isEmpty()) return null
        val cyrillicChars = text.filter { it in '\u0400'..'\u04FF' }
        if (cyrillicChars.isEmpty() || (cyrillicChars.length == 1 && (cyrillicChars[0] == 'е' || cyrillicChars[0] == 'Е'))) return null

        return when {
            isRussian(text) -> processCyrillicWordByWord(text, RUSSIAN_ROMAJI_MAP, isRussian = true)
            isUkrainian(text) -> processUkrainian(text)
            isSerbian(text) -> processCyrillicWordByWord(text, SERBIAN_ROMAJI_MAP)
            isBulgarian(text) -> processCyrillicWordByWord(text, BULGARIAN_ROMAJI_MAP)
            isBelarusian(text) -> processBelarusian(text)
            isKyrgyz(text) -> processCyrillicWordByWord(text, KYRGYZ_ROMAJI_MAP)
            isMacedonian(text) -> processCyrillicWordByWord(text, MACEDONIAN_ROMAJI_MAP)
            else -> processCyrillicWordByWord(text, emptyMap())
        }
    }

    fun romanizeVietnamese(text: String): String {
        if (text.isEmpty()) return text
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val sb = StringBuilder(text.length)
        for (char in normalized) {
            when {
                char == '\u0111' -> sb.append('d')
                char == '\u0110' -> sb.append('D')
                char in '\u0300'..'\u036F' || char in '\uFE20'..'\uFE2F' -> {}
                char.code > 0x7E && char in '\u00C0'..'\u024F' -> {}
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }

    private fun processCyrillicWordByWord(text: String, specificMap: Map<String, String>, isRussian: Boolean = false): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex()).filter { it.isNotEmpty() }

        words.forEach { word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
                return@forEach
            }

            var charIndex = 0
            while (charIndex < word.length) {
                val char = word[charIndex]
                val charStr = char.toString()
                val nextChar = word.getOrNull(charIndex + 1)
                val prevChar = word.getOrNull(charIndex - 1)

                // ── ъ hard sign: acts as separator (reset palatalization context) ──
                if (charStr == "ъ" || charStr == "Ъ") {
                    // Silent in modern Russian — skip but do not merge with next
                    charIndex++; continue
                }

                // ── ь soft sign: palatalize the *previous* output consonant ─────────
                // Rewrites last output character(s) if they form a known palatal pair.
                if (charStr == "ь" || charStr == "Ь") {
                    val built = romajiBuilder.toString()
                    val palatalized = when {
                        built.endsWith("t")  -> built.dropLast(1) + "tʲ"   // tʲ → "tʼ"
                        built.endsWith("d")  -> built.dropLast(1) + "dʲ"
                        built.endsWith("n")  -> built.dropLast(1) + "ny"
                        built.endsWith("l")  -> built.dropLast(1) + "ly"
                        built.endsWith("s")  -> built.dropLast(1) + "sy"
                        built.endsWith("z")  -> built.dropLast(1) + "zy"
                        built.endsWith("r")  -> built.dropLast(1) + "ry"
                        built.endsWith("p")  -> built.dropLast(1) + "py"
                        built.endsWith("b")  -> built.dropLast(1) + "by"
                        built.endsWith("m")  -> built.dropLast(1) + "my"
                        built.endsWith("v")  -> built.dropLast(1) + "vy"
                        built.endsWith("f")  -> built.dropLast(1) + "fy"
                        built.endsWith("k")  -> built.dropLast(1) + "ky"
                        built.endsWith("g")  -> built.dropLast(1) + "gy"
                        built.endsWith("kh") -> built.dropLast(2) + "khy"
                        else -> built // no change for already-soft consonants
                    }
                    romajiBuilder.clear(); romajiBuilder.append(palatalized)
                    charIndex++; continue
                }

                // ── Russian: е after consonant → "e", word-initial/after vowel → "ye" ──
                if (isRussian && (charStr == "е" || charStr == "Е")) {
                    val afterVowelOrStart = charIndex == 0
                        || prevChar == null
                        || prevChar.isWhitespace()
                        || "аеёиоуыэюяАЕЁИОУЫЭЮЯ".contains(prevChar)
                        || prevChar == 'ь' || prevChar == 'ъ' || prevChar == 'Ь' || prevChar == 'Ъ'
                    romajiBuilder.append(if (afterVowelOrStart) {
                        if (char.isUpperCase()) "Ye" else "ye"
                    } else {
                        if (char.isUpperCase()) "E" else "e"
                    })
                    charIndex++; continue
                }

                // ── ё always = "yo" (Russian) ──────────────────────────────────────
                if (charStr == "ё" || charStr == "Ё") {
                    romajiBuilder.append(if (char.isUpperCase()) "Yo" else "yo")
                    charIndex++; continue
                }

                // ── жи, ши, ци → always hard vowel "i" despite Cyrillic и ──────────
                // (already handled by base map, but double-check context)
                if ((charStr == "и" || charStr == "И") && prevChar != null) {
                    val prevOut = romajiBuilder.toString()
                    // After zh/sh/ts the "и" is always hard — map to "i" (already correct in base map)
                    romajiBuilder.append(specificMap[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr)
                    charIndex++; continue
                }

                // ── Multi-char lookups (3-char first, then 2-char) ──────────────────
                var consumed = false
                if (charIndex + 2 < word.length) {
                    val three = word.substring(charIndex, charIndex + 3)
                    specificMap[three]?.let { romajiBuilder.append(it); charIndex += 3; consumed = true }
                }
                if (!consumed && charIndex + 1 < word.length) {
                    val two = word.substring(charIndex, charIndex + 2)
                    specificMap[two]?.let { romajiBuilder.append(it); charIndex += 2; consumed = true }
                }
                if (!consumed) {
                    romajiBuilder.append(specificMap[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr)
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun processUkrainian(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex()).filter { it.isNotEmpty() }

        words.forEach { word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    var processed = false
                    if (charIndex > 0 && word[charIndex - 1].isLetter() && !"АаЕеЄєИиІіЇїОоУуЮюЯяЫыЭэ".contains(word[charIndex - 1])) {
                        if (charStr == "Ю") { romajiBuilder.append("Iu"); processed = true }
                        else if (charStr == "ю") { romajiBuilder.append("iu"); processed = true }
                        else if (charStr == "Я") { romajiBuilder.append("Ia"); processed = true }
                        else if (charStr == "я") { romajiBuilder.append("ia"); processed = true }
                    }
                    if (!processed) {
                        romajiBuilder.append(UKRAINIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr)
                    }
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun processBelarusian(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex()).filter { it.isNotEmpty() }

        words.forEach { word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    if ((charStr == "е" || charStr == "Е") && (charIndex == 0 || word[charIndex - 1].isWhitespace())) {
                        romajiBuilder.append(if (charStr == "е") "ye" else "Ye")
                    } else {
                        romajiBuilder.append(BELARUSIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr)
                    }
                    charIndex += 1
                }
            }
        }
        return romajiBuilder.toString()
    }
}

private fun String.capitalizeFirstLetter(): String {
    if (this.isEmpty()) return this
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

object LyricsUtils {

    private val LRC_LINE_REGEX = Pattern.compile("^\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})](.*)$")
    private val LRC_WORD_REGEX = Pattern.compile("<(\\d{2}):(\\d{2})[.:](\\d{2,3})>([^<]*)")
    private val LRC_WORD_TAG_REGEX = Regex("<\\d{2}:\\d{2}[.:]\\d{2,3}>")
    private val LRC_WORD_SPLIT_REGEX = Regex("(?=<\\d{2}:\\d{2}[.:]\\d{2,3}>)")
    private val LRC_TIMESTAMP_TAG_REGEX = Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]")
    private val TRANSLATION_CREDIT_REGEX = Regex("^\\s*by\\s*[:：].+", RegexOption.IGNORE_CASE)
    private val LRC_METADATA_PATTERN = Pattern.compile("^\\[[a-zA-Z]+:.*]$")

    // Kugou / Paxsenix word-by-word format:
    //   Line header : [lineStartMs,lineDurationMs]
    //   Word token  : <wordOffsetMs,wordDurationMs,flags>word
    // The line-start value is always > 999 ms, which distinguishes it from a
    // standard LRC minute value (max 99).
    private val KUGOU_LINE_REGEX = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$")
    private val KUGOU_WORD_PATTERN = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)")

    /**
     * Parses a String containing lyrics in LRC or plain-text format.
     * @param lyricsText The raw lyrics text to process.
     * @return A [Lyrics] object with either the 'plain' or 'synced' list populated.
     */
    fun parseLyrics(lyricsText: String?): Lyrics {
        if (lyricsText.isNullOrEmpty()) {
            return Lyrics(plain = emptyList(), synced = emptyList())
        }

        val entireLyricsHasKana = lyricsText.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }

        val normalizedInput = stripLeadingLyricsDocumentNoise(lyricsText)
        if (looksLikeTtmlDocument(normalizedInput)) {
            val converted = TtmlLyricsParser.parseToEnhancedLrc(normalizedInput)
                ?: return Lyrics(plain = emptyList(), synced = emptyList())
            return parseLyrics(converted)
        }

        // Kugou / Paxsenix word-by-word detection:
        // If any non-metadata line matches [number,number] where the first
        // number is > 999 (i.e. milliseconds, not minutes), treat the whole
        // file as Kugou format.
        if (looksLikeKugouFormat(lyricsText)) {
            return parseKugouLyrics(lyricsText)
        }

        val syncedLines = mutableListOf<SyncedLine>()
        val plainLines = mutableListOf<String>()
        var isSynced = false

        lyricsText.lines().forEach { rawLine ->
            val line = sanitizeLrcLine(rawLine)
            if (line.isEmpty() || LRC_METADATA_PATTERN.matcher(line).matches()) return@forEach

            val lineMatcher = LRC_LINE_REGEX.matcher(line)
            if (lineMatcher.matches()) {
                isSynced = true
                val minutes = lineMatcher.group(1)?.toLong() ?: 0
                val seconds = lineMatcher.group(2)?.toLong() ?: 0
                val fraction = lineMatcher.group(3)?.toLong() ?: 0
                val textWithTags = stripFormatCharacters(lineMatcher.group(4)?.trim() ?: "")
                val text = stripLrcTimestamps(textWithTags)

                val millis = if (lineMatcher.group(3)?.length == 2) fraction * 10 else fraction
                val lineTimestamp = minutes * 60 * 1000 + seconds * 1000 + millis

                // Enhanced word-by-word parsing
                if (text.contains(LRC_WORD_TAG_REGEX)) {
                    val words = mutableListOf<SyncedWord>()
                    val parts = text.split(LRC_WORD_SPLIT_REGEX)
                    val displayText = LRC_WORD_TAG_REGEX.replace(text, "")
                    var pendingWordBoundary = false

                    for (part in parts) {
                        if (part.isEmpty()) continue
                        val wordMatcher = LRC_WORD_REGEX.matcher(part)
                        if (wordMatcher.find()) {
                            val wordMinutes = wordMatcher.group(1)?.toLong() ?: 0
                            val wordSeconds = wordMatcher.group(2)?.toLong() ?: 0
                            val wordFraction = wordMatcher.group(3)?.toLong() ?: 0
                            val wordText = stripFormatCharacters(wordMatcher.group(4) ?: "")
                            val timedWordTextRaw = wordText
                                .substringBefore('\n')
                                .substringBefore('\r')
                                .substringBefore("\\n")
                                .substringBefore("\\r")
                            val startsNewWord = words.isEmpty() ||
                                pendingWordBoundary ||
                                timedWordTextRaw.firstOrNull()?.isWhitespace() == true
                            val timedWordText = timedWordTextRaw.trim()
                            pendingWordBoundary = timedWordTextRaw.lastOrNull()?.isWhitespace() == true
                            val wordMillis = if (wordMatcher.group(3)?.length == 2) wordFraction * 10 else wordFraction
                            val wordTimestamp = wordMinutes * 60 * 1000 + wordSeconds * 1000 + wordMillis
                            if (timedWordText.isNotEmpty()) {
                                words.add(
                                    SyncedWord(
                                        time = wordTimestamp.toInt(),
                                        word = timedWordText,
                                        startsNewWord = startsNewWord
                                    )
                                )
                            }
                        } else {
                            // Preserve only leading untagged text as a timed word.
                            // Trailing untagged chunks (e.g. inline translations) should remain visible in line text
                            // but must not steal word highlight timing.
                            if (words.isEmpty()) {
                                val leading = stripFormatCharacters(part)
                                val startsNewWord = pendingWordBoundary || leading.firstOrNull()?.isWhitespace() == true
                                val visibleLeading = leading.trim()
                                pendingWordBoundary = leading.lastOrNull()?.isWhitespace() == true
                                if (visibleLeading.isNotEmpty()) {
                                    words.add(
                                        SyncedWord(
                                            time = lineTimestamp.toInt(),
                                            word = visibleLeading,
                                            startsNewWord = words.isEmpty() || startsNewWord
                                        )
                                    )
                                }
                            } else if (part.any { it.isWhitespace() }) {
                                pendingWordBoundary = true
                            }
                        }
                    }

                    if (words.isNotEmpty()) {
                        syncedLines.add(SyncedLine(lineTimestamp.toInt(), displayText, words))
                    } else {
                        syncedLines.add(SyncedLine(lineTimestamp.toInt(), displayText))
                    }
                } else {
                    syncedLines.add(SyncedLine(lineTimestamp.toInt(), text))
                }
            } else {
                // Line WITHOUT timestamp
                val stripped = stripLrcTimestamps(stripFormatCharacters(line))
                // If the file was already detected as synced and at least one SyncedLine
                // exists, treat this line as a continuation of the previous one.
                if (isSynced && syncedLines.isNotEmpty()) {
                    val last = syncedLines.removeAt(syncedLines.lastIndex)
                    // Keep the previous text and append the new line with a newline break.
                    val mergedLineText = if (last.line.isEmpty()) {
                        stripped
                    } else {
                        last.line + "\n" + stripped
                    }
                    // Preserve the existing synced word list if present.
                    val merged = if (last.words?.isNotEmpty() == true) {
                        SyncedLine(last.time, mergedLineText, last.words)
                    } else {
                        SyncedLine(last.time, mergedLineText)
                    }

                    syncedLines.add(merged)
                } else {
                    // No sync markers found — treat as plain text.
                    plainLines.add(stripped)
                }
            }
        }

        return if (isSynced && syncedLines.isNotEmpty()) {
            val sortedSyncedLines = syncedLines.sortedBy { it.time }
            val pairedLines = pairTranslationLines(sortedSyncedLines).map { line ->

                val romanized = when {
                    MultiLangRomanizer.isJapanese(line.line, entireLyricsHasKana) -> MultiLangRomanizer.romanizeJapanese(line.line)
                    MultiLangRomanizer.isChinese(line.line) -> MultiLangRomanizer.romanizeChinese(line.line)
                    MultiLangRomanizer.isKorean(line.line) -> MultiLangRomanizer.romanizeKorean(line.line)
                    MultiLangRomanizer.isHindi(line.line) -> MultiLangRomanizer.romanizeHindi(line.line)
                    MultiLangRomanizer.isPunjabi(line.line) -> MultiLangRomanizer.romanizePunjabi(line.line)
                    MultiLangRomanizer.isCyrillic(line.line) -> MultiLangRomanizer.romanizeCyrillic(line.line)
                    else -> null
                }?.capitalizeFirstLetter()?.trim()

                line.copy(romanization = romanized)
            }
            val plainVersion = pairedLines.map { line ->
                buildString {
                    append(line.line)
                    if (!line.romanization.isNullOrEmpty()) append("\n").append(line.romanization)
                    if (!line.translation.isNullOrEmpty()) append("\n").append(line.translation)
                }
            }
            Lyrics(synced = pairedLines, plain = plainVersion)
        } else {
            val processedPlain = plainLines.map { line ->
                val romanized = when {
                    MultiLangRomanizer.isJapanese(line, entireLyricsHasKana) -> MultiLangRomanizer.romanizeJapanese(line)
                    MultiLangRomanizer.isChinese(line) -> MultiLangRomanizer.romanizeChinese(line)
                    MultiLangRomanizer.isKorean(line) -> MultiLangRomanizer.romanizeKorean(line)
                    MultiLangRomanizer.isHindi(line) -> MultiLangRomanizer.romanizeHindi(line)
                    MultiLangRomanizer.isPunjabi(line) -> MultiLangRomanizer.romanizePunjabi(line)
                    MultiLangRomanizer.isCyrillic(line) -> MultiLangRomanizer.romanizeCyrillic(line)
                    else -> null
                }?.capitalizeFirstLetter()?.trim()

                if (!romanized.isNullOrEmpty()) "$line\n$romanized" else line
            }
            Lyrics(plain = processedPlain)
        }
    }

    // ── Kugou / Paxsenix word-by-word helpers ─────────────────────────────

    /**
     * Returns true when the raw text contains at least one line in Kugou format:
     *   [lineStartMs,lineDurationMs]…
     * where lineStartMs > 999 (milliseconds, not an LRC minute value).
     */
    private fun looksLikeKugouFormat(text: String): Boolean {
        return text.lines().any { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@any false
            // Skip metadata tags like [ti:…] [ar:…] [offset:…]
            if (LRC_METADATA_PATTERN.matcher(line).matches()) return@any false
            val m = KUGOU_LINE_REGEX.matcher(line)
            m.matches() && (m.group(1)?.toLongOrNull() ?: 0L) > 999L
        }
    }

    /**
     * Parses a Kugou / Paxsenix word-by-word LRC file into [Lyrics].
     *
     * Line format:
     *   [lineStartMs,lineDurationMs]<wordOffset1Ms,dur,0>word1<wordOffset2Ms,dur,0>word2…
     *
     * Word offsets are *relative* to the line start.
     * A word token with offset 0 and no preceding tokens means the token is the
     * line itself; subsequent 0-offset tokens are continuations (startsNewWord = false).
     *
     * An optional [offset:N] header (milliseconds) shifts all timestamps.
     */
    private fun parseKugouLyrics(text: String): Lyrics {
        val globalOffsetMs = text.lines()
            .firstOrNull { it.trim().startsWith("[offset:", ignoreCase = true) }
            ?.trim()
            ?.removePrefix("[offset:")
            ?.removeSuffix("]")
            ?.trim()
            ?.toLongOrNull() ?: 0L

        val syncedLines = mutableListOf<SyncedLine>()

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || LRC_METADATA_PATTERN.matcher(line).matches()) continue

            val headerMatcher = KUGOU_LINE_REGEX.matcher(line)
            if (!headerMatcher.matches()) continue
            val lineStartMs = (headerMatcher.group(1)?.toLongOrNull() ?: continue) + globalOffsetMs
            if (lineStartMs <= 999L && globalOffsetMs == 0L) continue // guard: not a Kugou line
            val body = headerMatcher.group(3) ?: ""

            // Build word list
            val words = mutableListOf<SyncedWord>()
            val wordMatcher = KUGOU_WORD_PATTERN.matcher(body)
            var previousEndsWithSpace = true // first word always starts a new word

            while (wordMatcher.find()) {
                val wordOffsetMs = wordMatcher.group(1)?.toLongOrNull() ?: 0L
                // group(2) = duration, group(3) = flags — both unused for display
                val rawText = wordMatcher.group(4) ?: ""

                val startsNew = previousEndsWithSpace || rawText.firstOrNull()?.isWhitespace() == true
                val wordText = rawText.trim()
                previousEndsWithSpace = rawText.lastOrNull()?.isWhitespace() == true

                if (wordText.isEmpty()) continue

                words.add(
                    SyncedWord(
                        time = (lineStartMs + wordOffsetMs).toInt(),
                        word = wordText,
                        startsNewWord = startsNew
                    )
                )
            }

            // Plain text: strip all <…> tokens
            val plainText = KUGOU_WORD_PATTERN.toRegex().replace(body) { it.groupValues[4] }.trim()
            if (plainText.isEmpty() && words.isEmpty()) continue

            syncedLines.add(
                SyncedLine(
                    time = lineStartMs.toInt(),
                    line = plainText,
                    words = words.takeIf { it.isNotEmpty() }
                )
            )
        }

        if (syncedLines.isEmpty()) return Lyrics(plain = emptyList(), synced = emptyList())

        val sorted = syncedLines.sortedBy { it.time }
        val paired = pairTranslationLines(sorted)
        val plainVersion = paired.map { it.line }
        return Lyrics(synced = paired, plain = plainVersion, areFromRemote = false)
    }

    /**
     * Pairs consecutive synced lines that share the same timestamp.
     * The second line is treated as a translation of the first.
     * Only pairs one translation per original — a third line at the same timestamp stays separate.
     */
    internal fun pairTranslationLines(lines: List<SyncedLine>): List<SyncedLine> {
        if (lines.size < 2) return lines
        val result = mutableListOf<SyncedLine>()
        var i = 0
        while (i < lines.size) {
            val current = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null && next.time == current.time && current.translation == null && current.line.isNotBlank() && next.line.isNotBlank()) {
                val translationParts = mutableListOf(next.line)
                var consumed = 2
                while (true) {
                    val trailing = lines.getOrNull(i + consumed) ?: break
                    if (trailing.time != current.time || !isTranslationCreditLine(trailing.line)) break
                    translationParts.add(trailing.line)
                    consumed++
                }
                result.add(current.copy(translation = translationParts.joinToString("\n")))
                i += consumed
            } else {
                result.add(current)
                i++
            }
        }
        return result
    }

    internal fun stripLrcTimestamps(value: String): String {
        if (value.isEmpty()) return value
        val withoutTags = LRC_TIMESTAMP_TAG_REGEX.replace(value, "")
        return withoutTags.trimStart()
    }

    internal fun isTranslationCreditLine(line: String): Boolean {
        val normalized = stripLrcTimestamps(line).trim()
        return normalized.isNotEmpty() && TRANSLATION_CREDIT_REGEX.matches(normalized)
    }

    /**
     * Converts synced lyrics to LRC format string.
     * Each line is formatted as [mm:ss.xx]text
     * @param syncedLines The list of synced lines to convert.
     * @return A string in LRC format.
     */
    fun syncedToLrcString(syncedLines: List<SyncedLine>): String {
        return syncedLines.sortedBy { it.time }.flatMap { line ->
            val totalMs = line.time
            val minutes = totalMs / 60000
            val seconds = (totalMs % 60000) / 1000
            val hundredths = (totalMs % 1000) / 10
            val timestamp = "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
            buildList {
                add("$timestamp${line.line}")
                if (!line.translation.isNullOrBlank()) {
                    line.translation
                        .lines()
                        .filter { it.isNotBlank() }
                        .forEach { translationLine ->
                            add("$timestamp$translationLine")
                        }
                }
            }
        }.joinToString("\n")
    }

    /**
     * Converts plain lyrics (list of lines) to a plain text string.
     * Strips any auto-generated romanization suffix (after '\n') before storage.
     * @param plainLines The list of plain text lines.
     * @return A string with each line separated by a newline.
     */
    fun plainToString(plainLines: List<String>): String {
        // Strip auto-generated romanization (if present after \n) when converting back to string for storage.
        return plainLines.joinToString("\n") { it.substringBefore('\n') }
    }

    /**
     * Converts Lyrics object to LRC or plain text format based on available data.
     * Prefers synced lyrics if available.
     * @param lyrics The Lyrics object to convert.
     * @param preferSynced Whether to prefer synced lyrics over plain. Default true.
     * @return A string representation of the lyrics.
     */
    fun toLrcString(lyrics: Lyrics, preferSynced: Boolean = true): String {
        return if (preferSynced && !lyrics.synced.isNullOrEmpty()) {
            syncedToLrcString(lyrics.synced)
        } else if (!lyrics.plain.isNullOrEmpty()) {
            plainToString(lyrics.plain)
        } else if (!lyrics.synced.isNullOrEmpty()) {
            syncedToLrcString(lyrics.synced)
        } else {
            ""
        }
    }
}

private fun stripLeadingLyricsDocumentNoise(value: String): String {
    return value.trimStart { char ->
        char.isWhitespace() ||
            char == '\uFEFF' ||
            Character.getType(char).toByte() == Character.FORMAT
    }
}

private fun looksLikeTtmlDocument(value: String): Boolean {
    // Match bare <tt>, namespaced <tt:tt>, and SMIL wrappers (<smil>) used
    // by some Apple Music and broadcast TTML variants.
    fun String.startsWithTtmlRoot(): Boolean =
        startsWith("<tt", ignoreCase = true) ||
            startsWith("<smil", ignoreCase = true)

    if (value.startsWithTtmlRoot()) return true

    if (!value.startsWith("<?xml", ignoreCase = true)) return false

    return value
        .substringAfter("?>", missingDelimiterValue = "")
        .trimStart()
        .startsWithTtmlRoot()
}

private fun sanitizeLrcLine(rawLine: String): String {
    if (rawLine.isEmpty()) return rawLine

    val withoutTerminators = rawLine
        .trimEnd('\r', '\n')
        .filterNot { char ->
            Character.getType(char).toByte() == Character.FORMAT ||
                (Character.isISOControl(char) && char != '\t')
        }
        .trimEnd('\uFEFF')

    val trimmedPrefix = withoutTerminators.trimStart { it.isWhitespace() }
    val firstBracket = trimmedPrefix.indexOf('[')
    return if (firstBracket > 0) {
        trimmedPrefix.substring(firstBracket)
    } else {
        trimmedPrefix
    }
}

private fun stripFormatCharacters(value: String): String {
    val cleaned = value.filterNot { char ->
        char.category == CharCategory.FORMAT ||
            (char.isISOControl() && char != '\t')
    }

    return when (cleaned) {
        "\"", "'" -> ""
        else -> cleaned
    }
}

@Composable
fun ProviderText(
    providerText: String,
    uri: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    accentColor: Color? = null
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = accentColor ?: MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val annotatedString = buildAnnotatedString {
        withStyle(style = SpanStyle(color = textColor)) {
            append(providerText)
        }
        withLink(
            LinkAnnotation.Url(
                url = uri,
                styles = TextLinkStyles(style = SpanStyle(color = linkColor))
            )
        ) {
            append(" LRCLIB")
        }
    }

    val baseStyle = MaterialTheme.typography.bodySmall
    val finalStyle = textAlign?.let { baseStyle.copy(textAlign = it) } ?: baseStyle

    Text(
        text = annotatedString,
        style = finalStyle,
        modifier = modifier
    )
}

/**
 * A composable that displays a row of animated bubbles that morph into
 * musical notes as they rise and back into circles as they fall.
 *
 * @param positionFlow A flow emitting the current playback position in ms.
 * @param time The start time at which these bubbles become visible.
 * @param color The base colour for the bubbles and notes.
 * @param nextTime The end time at which these bubbles disappear.
 * @param modifier Modifier applied to this layout.
 */
@Composable
fun BubblesLine(
    positionFlow: Flow<Long>,
    time: Int,
    color: Color,
    nextTime: Int,
    modifier: Modifier = Modifier,
) {
    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val isCurrent = position in time until nextTime
    val transition = rememberInfiniteTransition(label = "bubbles_transition")

    // Animación ralentizada para apreciar mejor el efecto.
    val animatedValue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "bubble_animation_progress"
    )

    var show by remember { mutableStateOf(false) }
    LaunchedEffect(isCurrent) {
        show = isCurrent
    }

    if (show) {
        val density = LocalDensity.current
        // Smaller circles to accentuate the scale animation.
        val bubbleRadius = remember(density) { with(density) { 4.dp.toPx() } }

        val (morphableCircle, morphableNote) = remember(bubbleRadius) {
            val circleNodes = createCirclePathNodes(radius = bubbleRadius)
            val noteNodes = createVectorNotePathNodes(targetSize = bubbleRadius * 2.5f)

            makePathsCompatible(circleNodes, noteNodes)
            circleNodes to noteNodes
        }

        Canvas(modifier = modifier.size(64.dp, 48.dp)) {
            val bubbleCount = 3
            val bubbleColor = color.copy(alpha = 0.7f)

            for (i in 0 until bubbleCount) {
                val progress = (animatedValue + i * (1f / bubbleCount)) % 1f
                val yOffset = sin(progress * 2 * PI).toFloat() * 8.dp.toPx()

                val morphProgress = when {
                    progress in 0f..0.25f -> progress / 0.25f
                    progress in 0.25f..0.5f -> 1.0f - (progress - 0.25f) / 0.25f
                    else -> 0f
                }.coerceIn(0f, 1f)

                // Scale animation is more pronounced.
                val scale = lerpFloat(1.0f, 1.4f, morphProgress)

                // Dynamic horizontal offset that activates with morphing.
                val xOffsetCorrection = lerpFloat(0f, bubbleRadius * 1.8f, morphProgress)

                val morphedPath = lerpPath(
                    start = morphableCircle,
                    stop = morphableNote,
                    fraction = morphProgress
                ).toPath()

                // Position the animation container in its column.
                translate(left = (size.width / (bubbleCount + 1)) * (i + 1)) {
                    // Apply vertical offset (wave) and horizontal correction.
                    val drawOffset = Offset(x = xOffsetCorrection, y = size.height / 2 + yOffset)

                    translate(left = drawOffset.x, top = drawOffset.y) {
                        // Apply the scale transform before drawing.
                        scale(scale = scale, pivot = Offset.Zero) {
                            drawPath(
                                path = morphedPath,
                                color = bubbleColor
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Path Morphing Logic ---

private fun lerpPath(start: List<PathNode>, stop: List<PathNode>, fraction: Float): List<PathNode> {
    return start.mapIndexed { index, startNode ->
        val stopNode = stop[index]
        when (startNode) {
            is PathNode.MoveTo -> {
                val stopMoveTo = stopNode as PathNode.MoveTo
                PathNode.MoveTo(
                    lerpFloat(startNode.x, stopMoveTo.x, fraction),
                    lerpFloat(startNode.y, stopMoveTo.y, fraction)
                )
            }
            is PathNode.CurveTo -> {
                val stopCurveTo = stopNode as PathNode.CurveTo
                PathNode.CurveTo(
                    lerpFloat(startNode.x1, stopCurveTo.x1, fraction),
                    lerpFloat(startNode.y1, stopCurveTo.y1, fraction),
                    lerpFloat(startNode.x2, stopCurveTo.x2, fraction),
                    lerpFloat(startNode.y2, stopCurveTo.y2, fraction),
                    lerpFloat(startNode.x3, stopCurveTo.x3, fraction),
                    lerpFloat(startNode.y3, stopCurveTo.y3, fraction)
                )
            }
            else -> startNode
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun List<PathNode>.toPath(): Path = Path().apply {
    this@toPath.forEach { node ->
        when (node) {
            is PathNode.MoveTo -> moveTo(node.x, node.y)
            is PathNode.LineTo -> lineTo(node.x, node.y)
            is PathNode.CurveTo -> cubicTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
            is PathNode.Close -> close()
            else -> {}
        }
    }
}

private fun makePathsCompatible(nodes1: MutableList<PathNode>, nodes2: MutableList<PathNode>): Pair<MutableList<PathNode>, MutableList<PathNode>> {
    while (nodes1.size < nodes2.size) {
        nodes1.add(nodes1.size - 1, nodes1[nodes1.size - 2])
    }
    while (nodes2.size < nodes1.size) {
        nodes2.add(nodes2.size - 1, nodes2[nodes2.size - 2])
    }
    return nodes1 to nodes2
}

private fun createVectorNotePathNodes(targetSize: Float): MutableList<PathNode> {
    val pathData = "M239.5,1.9c-4.6,1.1 -8.7,3.6 -12.2,7.3 -6.7,6.9 -6.3,-2.5 -6.3,151.9 0,76.9 -0.3,140 -0.7,140.2 -0.5,0.3 -4.2,-0.9 -8.3,-2.5 -48.1,-19.3 -102.8,-8.3 -138.6,27.7 -35.8,36.1 -41.4,85.7 -13.6,120.7 18.6,23.4 52.8,37.4 86.2,35.3 34.8,-2.1 65.8,-16 89.5,-39.9 14.5,-14.6 24.9,-31.9 30.7,-50.6l2.3,-7.5 0.2,-133c0.2,-73.2 0.5,-133.6 0.8,-134.2 0.8,-2.4 62,28.5 84.3,42.4 22.4,14.1 34.1,30.4 37.2,51.9 2.4,16.5 -2.2,34.5 -13,50.9 -6,9.1 -7,12.1 -4.8,14.3 2.2,2.2 5.3,1.2 13.8,-4.5 26.4,-17.9 45.6,-48 50,-78.2 1.9,-12.9 0.8,-34.3 -2.4,-46.1 -8.7,-31.7 -30.4,-58 -64.1,-77.8 -64.3,-37.9 -116,-67.3 -119.6,-68.1 -5,-1.2 -7.1,-1.2 -11.4,-0.2z"
    val parser = PathParser().parsePathString(pathData)

    val groupScale = 0.253f
    val bounds = Path().apply { parser.toPath(this) }.getBounds()
    val maxDimension = max(bounds.width, bounds.height)
    val scale = if (maxDimension > 0f) targetSize / (maxDimension * groupScale) else 1f

    val matrix = Matrix()
    matrix.translate(x = -bounds.left, y = -bounds.top)
    matrix.scale(x = groupScale * scale, y = groupScale * scale)
    val finalWidth = bounds.width * groupScale * scale
    val finalHeight = bounds.height * groupScale * scale

    // Center the path at origin (0,0) without static corrections.
    matrix.translate(x = -finalWidth / 2f, y = -finalHeight / 2f)

    return parser.toNodes().toAbsolute().transform(matrix).toCurvesOnly()
}

private fun createCirclePathNodes(radius: Float): MutableList<PathNode> {
    val kappa = 0.552284749831f
    val rk = radius * kappa
    return mutableListOf(
        PathNode.MoveTo(0f, -radius),
        PathNode.CurveTo(rk, -radius, radius, -rk, radius, 0f),
        PathNode.CurveTo(radius, rk, rk, radius, 0f, radius),
        PathNode.CurveTo(-rk, radius, -radius, rk, -radius, 0f),
        PathNode.CurveTo(-radius, -rk, -rk, -radius, 0f, -radius),
        PathNode.Close
    )
}

// --- PathNode Extension Functions ---

private fun List<PathNode>.toAbsolute(): MutableList<PathNode> {
    val absoluteNodes = mutableListOf<PathNode>()
    var currentX = 0f
    var currentY = 0f
    this.forEach { node ->
        when (node) {
            is PathNode.MoveTo -> { currentX = node.x; currentY = node.y; absoluteNodes.add(node) }
            is PathNode.RelativeMoveTo -> { currentX += node.dx; currentY += node.dy; absoluteNodes.add(PathNode.MoveTo(currentX, currentY)) }
            is PathNode.LineTo -> { currentX = node.x; currentY = node.y; absoluteNodes.add(node) }
            is PathNode.RelativeLineTo -> { currentX += node.dx; currentY += node.dy; absoluteNodes.add(PathNode.LineTo(currentX, currentY)) }
            is PathNode.CurveTo -> { currentX = node.x3; currentY = node.y3; absoluteNodes.add(node) }
            is PathNode.RelativeCurveTo -> {
                absoluteNodes.add(PathNode.CurveTo(currentX + node.dx1, currentY + node.dy1, currentX + node.dx2, currentY + node.dy2, currentX + node.dx3, currentY + node.dy3))
                currentX += node.dx3; currentY += node.dy3
            }
            is PathNode.Close -> absoluteNodes.add(node)
            else -> {}
        }
    }
    return absoluteNodes
}

private fun MutableList<PathNode>.toCurvesOnly(): MutableList<PathNode> {
    val curveNodes = mutableListOf<PathNode>()
    var lastX = 0f
    var lastY = 0f

    this.forEach { node ->
        when(node) {
            is PathNode.MoveTo -> { curveNodes.add(node); lastX = node.x; lastY = node.y }
            is PathNode.LineTo -> { curveNodes.add(PathNode.CurveTo(lastX, lastY, node.x, node.y, node.x, node.y)); lastX = node.x; lastY = node.y }
            is PathNode.CurveTo -> { curveNodes.add(node); lastX = node.x3; lastY = node.y3 }
            is PathNode.Close -> curveNodes.add(node)
            else -> {}
        }
    }
    return curveNodes
}

private fun List<PathNode>.transform(matrix: Matrix): MutableList<PathNode> {
    return this.map { node ->
        when (node) {
            is PathNode.MoveTo -> {
                val p = matrix.map(Offset(node.x, node.y))
                PathNode.MoveTo(p.x, p.y)
            }
            is PathNode.LineTo -> {
                val p = matrix.map(Offset(node.x, node.y))
                PathNode.LineTo(p.x, p.y)
            }
            is PathNode.CurveTo -> {
                val p1 = matrix.map(Offset(node.x1, node.y1))
                val p2 = matrix.map(Offset(node.x2, node.y2))
                val p3 = matrix.map(Offset(node.x3, node.y3))
                PathNode.CurveTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
            }
            else -> node
        }
    }.toMutableList()
}
