package dev.crumble.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidRegexTests {
    // Android's ICU regex engine throws on these flags, while the JVM running this suite accepts them.
    @Test
    fun appRegexesAvoidFlagsThatCrashOnAndroid() {
        val unsupported = Regex("""\(\?[a-zA-Z]*U|UNICODE_CHARACTER_CLASS""")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .filter { unsupported.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
