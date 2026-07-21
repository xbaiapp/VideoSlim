package com.videoslim.videoslim

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPackagingPolicyTest {
    @Test
    fun `release remains arm64 only while debug also supports x86 64`() {
        val buildScript = locateAppBuildScript().readText()
        val defaultConfig = extractBlock(buildScript, "defaultConfig")
        val debug = extractBlock(extractBlock(buildScript, "buildTypes"), "debug")
        val release = extractBlock(extractBlock(buildScript, "buildTypes"), "release")

        assertEquals(setOf("arm64-v8a"), abiFilters(defaultConfig))
        assertEquals(setOf("arm64-v8a", "x86_64"), abiFilters(debug))
        assertFalse(
            "Release must inherit the arm64-only default and never add another ABI",
            release.contains("abiFilters"),
        )
    }

    private fun locateAppBuildScript(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory: File? = File(workingDirectory).canonicalFile
        while (directory != null) {
            sequenceOf(
                File(directory, "build.gradle.kts"),
                File(directory, "app/build.gradle.kts"),
                File(directory, "android/app/build.gradle.kts"),
            ).firstOrNull { candidate ->
                candidate.isFile && candidate.readText().contains("id(\"com.android.application\")")
            }?.let { return it }
            directory = directory.parentFile
        }
        error("Could not locate android/app/build.gradle.kts")
    }

    private fun abiFilters(buildTypeBlock: String): Set<String> {
        val ndk = extractBlock(buildTypeBlock, "ndk")
        assertTrue(
            "ABI policy must clear Flutter's preconfigured filters before adding the allowlist",
            Regex("""abiFilters\.clear\(\)""").containsMatchIn(ndk),
        )
        val assignment =
            Regex("""abiFilters\s*\+=\s*setOf\(([^)]*)\)""")
                .find(ndk)
                ?.groupValues
                ?.get(1)
                ?: error("Build type must declare an explicit abiFilters set")
        return Regex(""""([^\"]+)"""")
            .findAll(assignment)
            .map { match -> match.groupValues[1] }
            .toSet()
    }

    private fun extractBlock(source: String, name: String): String {
        val nameIndex = Regex("""\b${Regex.escape(name)}\s*\{""").find(source)?.range?.first
            ?: error("Missing $name block")
        val openBrace = source.indexOf('{', nameIndex)
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openBrace + 1, index)
                }
            }
        }
        error("Unterminated $name block")
    }
}
