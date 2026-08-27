package com.theveloper.pixelplay.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsUtilsTest {

    @Test
    fun parseLyrics_handlesBomAtStartOfSyncedLine() {
        val lrc = "\uFEFF[00:03.80]Time is standing still\n[00:09.86]Tracing my body"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = lyrics.synced

        assertNotNull(synced)
        val syncedLines = requireNotNull(synced)
        assertEquals(2, syncedLines.size)
        assertEquals(3_800, syncedLines[0].time)
        assertEquals("Time is standing still", syncedLines[0].line)
        assertEquals(9_860, syncedLines[1].time)
        assertEquals("Tracing my body", syncedLines[1].line)
    }

    @Test
    fun parseLyrics_handlesWhitespacesBeforeTimestamp() {
        val lrc = "\uFEFF   [00:03.80]Time is standing still\r\n\t[00:09.86]Tracing my body"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(2, synced.size)
        assertEquals(3_800, synced[0].time)
        assertEquals("Time is standing still", synced[0].line)
        assertEquals(9_860, synced[1].time)
        assertEquals("Tracing my body", synced[1].line)
    }

    @Test
    fun parseLyrics_parsesFullSampleWithBom() {
        val lrc = "\uFEFF[00:03.80]Time is standing still and I don't wanna leave your lips\n" +
            "[00:09.86]Tracing my body with your fingertips\n" +
            "[00:16.53]I know what you're feeling and I know you wanna say it (yeah, say it)\n" +
            "[00:22.76]I do too, but we gotta be patient (gotta be patient)\n" +
            "[00:28.32]'Cause someone like me (and someone like you)\n" +
            "[00:31.65]Really shouldn't work, yeah, the history is proof\n" +
            "[00:34.75]Damned if I don't (damned if I do)\n" +
            "[00:38.08]You know, by now, we've seen it all\n" +
            "[00:41.67]Said, oh, we should fall in love with our eyes closed\n" +
            "[00:46.76]Better if we keep it where we don't know\n" +
            "[00:49.97]The beds we've been in, the names and the faces of who we were with\n" +
            "[00:54.36]And, oh, ain't nobody perfect, but it's all good\n" +
            "[00:59.52]The past can't hurt us if we don't look\n" +
            "[01:02.71]Let's let it go, better if we fall in love with our eyes closed\n" +
            "[01:09.05](Oh, oh, oh)\n" +
            "[01:13.94]I got tunnel vision every second that you're with me\n" +
            "[01:19.88]No, I don't care what anybody says, just kiss me (oh)\n" +
            "[01:26.05]'Cause you look like trouble, but it could be good\n" +
            "[01:29.09]I've been the same, kind of misunderstood\n" +
            "[01:32.32]Whatever you've done, trust, it ain't nothing new\n" +
            "[01:35.48]You know by now, we've seen it all\n" +
            "[01:39.23]Said, oh, we should fall in love with our eyes closed\n" +
            "[01:44.48]Better if we keep it where we don't know\n" +
            "[01:47.55]The beds we've been in, the names and the faces of who we were with\n" +
            "[01:52.13]And, oh, ain't nobody perfect, but it's all good\n" +
            "[01:57.17]The past can't hurt us if we don't look\n" +
            "[02:00.27]Let's let it go, better if we fall in love with our eyes closed\n" +
            "[02:06.25](Oh, oh, keep your eyes closed)\n" +
            "[02:10.76]'Cause someone like me and someone like you\n" +
            "[02:13.86]Really shouldn't work, yeah, the history is proof\n" +
            "[02:17.25]Damned if I don't, damned if I do\n" +
            "[02:20.26]You know by now, we've seen it all\n" +
            "[02:24.13]Said, oh, we should fall in love with our eyes closed\n" +
            "[02:29.09]Better if we keep it where we don't know\n" +
            "[02:32.13]The beds we've been in, the names and the faces of who we were with\n" +
            "[02:36.95]And, oh, ain't nobody perfect, but it's all good\n" +
            "[02:41.75]The past can't hurt us if we don't look\n" +
            "[02:45.08]Let's let it go, better if we fall in love with our eyes closed (oh)\n" +
            "[02:54.13]With our eyes closed\n" +
            "[02:58.92]"

        val lyrics = LyricsUtils.parseLyrics(lrc)

        val synced = requireNotNull(lyrics.synced)
        assertEquals(40, synced.size)
        assertEquals(3_800, synced.first().time)
        assertEquals("Time is standing still and I don't wanna leave your lips", synced.first().line)
        assertEquals(178_920, synced.last().time)
        assertEquals("", synced.last().line)
    }

    @Test
    fun parseLyrics_ignoresFormatCharactersInsideTimestamp() {
        val lrc = "\u202a[00:03.80\u202c]Time is standing still\n[00:09.86]Tracing my body"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(2, synced.size)
        assertEquals(3_800, synced[0].time)
        assertEquals("Time is standing still", synced[0].line)
        assertEquals(9_860, synced[1].time)
        assertEquals("Tracing my body", synced[1].line)
    }

    @Test
    fun parseLyrics_parsesSampleWrappedInQuotes() {
        val lrc = buildString {
            append('"')
            appendLine("[00:03.80]Time is standing still and I don't wanna leave your lips")
            appendLine("[00:09.86]Tracing my body with your fingertips")
            appendLine("[00:16.53]I know what you're feeling and I know you wanna say it (yeah, say it)")
            appendLine("[00:22.76]I do too, but we gotta be patient (gotta be patient)")
            appendLine("[00:28.32]'Cause someone like me (and someone like you)")
            appendLine("[00:31.65]Really shouldn't work, yeah, the history is proof")
            appendLine("[00:34.75]Damned if I don't (damned if I do)")
            appendLine("[00:38.08]You know, by now, we've seen it all")
            appendLine("[00:41.67]Said, oh, we should fall in love with our eyes closed")
            appendLine("[00:46.76]Better if we keep it where we don't know")
            appendLine("[00:49.97]The beds we've been in, the names and the faces of who we were with")
            appendLine("[00:54.36]And, oh, ain't nobody perfect, but it's all good")
            appendLine("[00:59.52]The past can't hurt us if we don't look")
            appendLine("[01:02.71]Let's let it go, better if we fall in love with our eyes closed")
            appendLine("[01:09.05](Oh, oh, oh)")
            appendLine("[01:13.94]I got tunnel vision every second that you're with me")
            appendLine("[01:19.88]No, I don't care what anybody says, just kiss me (oh)")
            appendLine("[01:26.05]'Cause you look like trouble, but it could be good")
            appendLine("[01:29.09]I've been the same, kind of misunderstood")
            appendLine("[01:32.32]Whatever you've done, trust, it ain't nothing new")
            appendLine("[01:35.48]You know by now, we've seen it all")
            appendLine("[01:39.23]Said, oh, we should fall in love with our eyes closed")
            appendLine("[01:44.48]Better if we keep it where we don't know")
            appendLine("[01:47.55]The beds we've been in, the names and the faces of who we were with")
            appendLine("[01:52.13]And, oh, ain't nobody perfect, but it's all good")
            appendLine("[01:57.17]The past can't hurt us if we don't look")
            appendLine("[02:00.27]Let's let it go, better if we fall in love with our eyes closed")
            appendLine("[02:06.25](Oh, oh, keep your eyes closed)")
            appendLine("[02:10.76]'Cause someone like me and someone like you")
            appendLine("[02:13.86]Really shouldn't work, yeah, the history is proof")
            appendLine("[02:17.25]Damned if I don't, damned if I do")
            appendLine("[02:20.26]You know by now, we've seen it all")
            appendLine("[02:24.13]Said, oh, we should fall in love with our eyes closed")
            appendLine("[02:29.09]Better if we keep it where we don't know")
            appendLine("[02:32.13]The beds we've been in, the names and the faces of who we were with")
            appendLine("[02:36.95]And, oh, ain't nobody perfect, but it's all good")
            appendLine("[02:41.75]The past can't hurt us if we don't look")
            appendLine("[02:45.08]Let's let it go, better if we fall in love with our eyes closed (oh)")
            appendLine("[02:54.13]With our eyes closed")
            append("[02:58.92]")
            append('"')
        }

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(40, synced.size)
        assertEquals(3_800, synced.first().time)
        assertEquals("Time is standing still and I don't wanna leave your lips", synced.first().line)
        assertEquals(178_920, synced.last().time)
        assertEquals("", synced.last().line)
    }

    @Test
    fun parseLyrics_convertsAppleTtmlWithXmlDeclarationAndNamespaces() {
        val ttml = """
            <?xml version='1.0' encoding='utf-8'?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="en">
              <head>
                <metadata>
                  <ttm:agent type="person" xml:id="v1">
                    <ttm:name type="full">Chase Atlantic</ttm:name>
                  </ttm:agent>
                </metadata>
              </head>
              <body dur="0:15.000">
                <div begin="7.531" end="12.005" itunes:songPart="Verse">
                  <p begin="7.531" end="12.005" itunes:key="L1" ttm:agent="v1"><span begin="7.531" end="7.782">Yeah,</span> <span begin="9.208" end="9.443">I</span> <span begin="9.443" end="9.675">bet</span></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = LyricsUtils.parseLyrics(ttml)
        val synced = requireNotNull(lyrics.synced)
        val first = synced.first()

        assertEquals(1, synced.size)
        assertEquals(7_530, first.time)
        assertEquals("Yeah, I bet", first.line)
        assertEquals(listOf("Yeah,", "I", "bet"), requireNotNull(first.words).map { it.word })
    }

    @Test
    fun parseLyrics_doesNotExposeBrokenTtmlAsPlainText() {
        val malformedTtml = """
            <?xml version='1.0' encoding='utf-8'?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000">Hello
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = LyricsUtils.parseLyrics(malformedTtml)

        assertTrue(lyrics.synced.isNullOrEmpty())
        assertTrue(lyrics.plain.isNullOrEmpty())
    }

    @Test
    fun parseLyrics_stripsAdditionalLrcTimestampsFromLines() {
        val lrc = """
            [00:12.57] Sinking under
            [00:26.42][01:12.34] Three in the morning, I ain't slept all weekend
            [00:41.71][00:52.96][01:27.42] My heart keeps breaking
        """.trimIndent()

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(
            listOf(
                "Sinking under",
                "Three in the morning, I ain't slept all weekend",
                "My heart keeps breaking"
            ),
            synced.map { it.line }
        )
    }

    @Test
    fun parseLyrics_wordByWord_ignoresTrailingUntaggedTranslationInWordTiming() {
        val lrc = "[00:10.00]<00:10.00>To <00:10.30>fall <00:10.60>in <00:10.90>love\\n怦然心动"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)
        val first = synced.first()
        val words = requireNotNull(first.words)

        assertEquals("To fall in love\\n怦然心动", first.line)
        assertEquals(listOf("To", "fall", "in", "love"), words.map { it.word })
        assertEquals(listOf(true, true, true, true), words.map { it.startsNewWord })
        assertTrue(words.none { it.word.contains("怦然心动") })
    }

    @Test
    fun parseLyrics_pairsSameTimestampLinesAsTranslation() {
        val lrc = "[00:10.00]Hello world\n[00:10.00]你好世界\n[00:20.00]Goodbye"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(2, synced.size)
        assertEquals("Hello world", synced[0].line)
        assertEquals("你好世界", synced[0].translation)
        assertEquals("Goodbye", synced[1].line)
        assertNull(synced[1].translation)
    }

    @Test
    fun parseLyrics_singleLineWithoutDuplicate_translationIsNull() {
        val lrc = "[00:10.00]Hello world\n[00:20.00]Goodbye\n[00:30.00]See you"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(3, synced.size)
        synced.forEach { assertNull(it.translation) }
    }

    @Test
    fun parseLyrics_threeLinesAtSameTimestamp_pairsFirstTwoOnly() {
        val lrc = "[00:10.00]Hello world\n[00:10.00]你好世界\n[00:10.00]Hola mundo"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(2, synced.size)
        assertEquals("Hello world", synced[0].line)
        assertEquals("你好世界", synced[0].translation)
        assertEquals("Hola mundo", synced[1].line)
        assertNull(synced[1].translation)
    }

    @Test
    fun parseLyrics_translationCreditAtSameTimestamp_isGroupedIntoTranslation() {
        val lrc = "[00:10.00]Hello world\n[00:10.00]你好世界\n[00:10.00]by: translator\n[00:20.00]Goodbye"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(2, synced.size)
        assertEquals("Hello world", synced[0].line)
        assertEquals("你好世界\nby: translator", synced[0].translation)
        assertEquals("Goodbye", synced[1].line)
        assertNull(synced[1].translation)
    }

    @Test
    fun parseLyrics_nonTimestampedLinePreservedAsMerge_notTranslation() {
        // Non-timestamped lines after a synced line are merged into line text (existing behavior)
        val lrc = "[00:10.00]Hello world\ncontinuation line\n[00:20.00]Next"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(2, synced.size)
        assertEquals("Hello world\ncontinuation line", synced[0].line)
        assertNull(synced[0].translation)
    }

    @Test
    fun parseLyrics_colonSubSecondSeparator_parsedAndPairedWithTranslation() {
        // Some LRC files use [mm:ss:xx] (colon) instead of [mm:ss.xx] (dot)
        val lrc = "[00:00.000]作词: イマニシ\n" +
            "[00:01.000]作曲: イマニシ\n" +
            "[00:22:43]愛情なんて忘れて\n" +
            "[00:25:39]一人ワルツを踊る\n" +
            "[00:22.43]忘掉爱情什么的\n" +
            "[00:25.39]一个人舞动华尔兹"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        // Credits + 2 paired lines = 4 lines
        assertEquals(4, synced.size)

        // The Japanese originals should be separate synced lines, not merged into credits
        val line22 = synced.first { it.line == "愛情なんて忘れて" }
        assertEquals(22_430, line22.time)
        assertEquals("忘掉爱情什么的", line22.translation)

        val line25 = synced.first { it.line == "一人ワルツを踊る" }
        assertEquals(25_390, line25.time)
        assertEquals("一个人舞动华尔兹", line25.translation)
    }

    @Test
    fun parseLyrics_wordByWord_colonSubSecondSeparator_supported() {
        val lrc = "[00:10:00]<00:10:00>Hello <00:10:50>world"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)
        val words = requireNotNull(synced.single().words)

        assertEquals("Hello world", synced.single().line)
        assertEquals(listOf("Hello", "world"), words.map { it.word })
        assertEquals(listOf(10_000, 10_500), words.map { it.time })
        assertEquals(listOf(true, true), words.map { it.startsNewWord })
    }

    @Test
    fun parseLyrics_wordByWord_marksSyllablesAsSameVisualWord() {
        val lrc = "[00:10.00]<00:10.00>to<00:10.20>geth<00:10.40>er <00:10.60>now"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)
        val words = requireNotNull(synced.single().words)

        assertEquals("together now", synced.single().line)
        assertEquals(listOf("to", "geth", "er", "now"), words.map { it.word })
        assertEquals(listOf(true, false, false, true), words.map { it.startsNewWord })
    }

    @Test
    fun parseLyrics_wordByWord_standaloneWhitespaceStillStartsNextWord() {
        val lrc = "[00:10.00]<00:10.00>To<00:10.20> <00:10.40>fall"

        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)
        val words = requireNotNull(synced.single().words)

        assertEquals("To fall", synced.single().line)
        assertEquals(listOf("To", "fall"), words.map { it.word })
        assertEquals(listOf(true, true), words.map { it.startsNewWord })
    }

    @Test
    fun syncedToLrcString_preservesPairedTranslations() {
        val lrc = LyricsUtils.syncedToLrcString(
            listOf(
                com.theveloper.pixelplay.data.model.SyncedLine(
                    time = 10_000,
                    line = "Hello world",
                    translation = "你好世界"
                ),
                com.theveloper.pixelplay.data.model.SyncedLine(
                    time = 20_000,
                    line = "Goodbye"
                )
            )
        )

        assertEquals(
            "[00:10.00]Hello world\n[00:10.00]你好世界\n[00:20.00]Goodbye",
            lrc
        )
    }

    @Test
    fun syncedToLrcString_expandsMultilineTranslationWithTimestampPerLine() {
        val lrc = LyricsUtils.syncedToLrcString(
            listOf(
                com.theveloper.pixelplay.data.model.SyncedLine(
                    time = 10_000,
                    line = "Hello world",
                    translation = "你好世界\nby: translator"
                )
            )
        )

        assertEquals(
            "[00:10.00]Hello world\n[00:10.00]你好世界\n[00:10.00]by: translator",
            lrc
        )
    }

    @Test
    fun parseLyrics_supportsOverOneHour_minutesFormat() {
        // 70 minutes = 70 * 60 * 1000 = 4,200,000 ms
        val lrc = "[70:00.00]Long track line"
        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(1, synced.size)
        assertEquals(4_200_000, synced[0].time)
        assertEquals("Long track line", synced[0].line)
    }

    @Test
    fun parseLyrics_supportsOverOneHour_hoursFormat() {
        // 01:10:00.00 = 1h 10m = 70 minutes = 4,200,000 ms
        val lrc = "[01:10:00.00]Long track line with hours"
        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)

        assertEquals(1, synced.size)
        assertEquals(4_200_000, synced[0].time)
        assertEquals("Long track line with hours", synced[0].line)
    }

    @Test
    fun parseLyrics_wordByWord_supportsOverOneHour() {
        // [01:00:00.00] = 3,600,000 ms
        // <01:00:05.00> = 3,605,000 ms
        val lrc = "[01:00:00.00]<01:00:00.00>Start <01:00:05.00>Later"
        val lyrics = LyricsUtils.parseLyrics(lrc)
        val synced = requireNotNull(lyrics.synced)
        val words = requireNotNull(synced.single().words)

        assertEquals(3_600_000, synced[0].time)
        assertEquals(3_600_000, words[0].time)
        assertEquals(3_605_000, words[1].time)
    }

    @Test
    fun syncedToLrcString_usesHourFormatWhenNeeded() {
        val synced = listOf(
            com.theveloper.pixelplay.data.model.SyncedLine(
                time = 4_200_000, // 70 minutes
                line = "Over one hour"
            )
        )
        val lrc = LyricsUtils.syncedToLrcString(synced)
        assertEquals("[01:10:00.00]Over one hour", lrc)
    }
}
