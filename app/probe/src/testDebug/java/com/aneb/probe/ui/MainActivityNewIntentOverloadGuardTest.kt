package com.aneb.probe.ui

import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Test

class MainActivityNewIntentOverloadGuardTest {
    @Test
    fun `safe base origin and callback surface are sealed`() {
        val loader = javaClass.classLoader
            ?: throw AssertionError("S1_TEST_CLASSLOADER_ABSENT")
        val safeBase = try {
            Class.forName("com.aneb.probe.ui.ReleaseSafeComponentActivity", false, loader)
        } catch (_: ClassNotFoundException) {
            throw AssertionError("S1_NEW_INTENT_BASE_ABSENT")
        }
        requireFact(safeBase.classLoader === loader, "S1_SAFE_BASE_CLASSLOADER_MISMATCH")

        val authorityRoot = authorityRoot()
        val expectedTestOutput = authorityRoot.resolve(TEST_OUTPUT_SUFFIX)
        val expectedSafeDirectOutput = authorityRoot.resolve(SAFE_DIRECT_OUTPUT_SUFFIX)
        val expectedSafeJar = authorityRoot.resolve(SAFE_JAR_SUFFIX)
        val testCodeSource = codeSourcePath(javaClass, expectedDirectory = true, "S1_OVERLOAD_GUARD_TEST")
        requireFact(
            testCodeSource == expectedTestOutput,
            "S1_OVERLOAD_GUARD_TEST_CODE_SOURCE_MISMATCH",
        )
        val safeCodeSource = codeSourcePath(safeBase, expectedDirectory = null, "S1_SAFE_BASE")
        val safeMode = when (safeCodeSource) {
            expectedSafeDirectOutput -> SafeOrigin.DIRECT
            expectedSafeJar -> SafeOrigin.RUNTIME_JAR
            else -> throw AssertionError("S1_SAFE_BASE_CODE_SOURCE_NOT_ALLOWED")
        }
        requireFact(
            safeMode != SafeOrigin.DIRECT || Files.isDirectory(safeCodeSource, LinkOption.NOFOLLOW_LINKS),
            "S1_SAFE_BASE_DIRECT_CODE_SOURCE_TYPE_MISMATCH",
        )
        requireFact(
            safeMode != SafeOrigin.RUNTIME_JAR || Files.isRegularFile(safeCodeSource, LinkOption.NOFOLLOW_LINKS),
            "S1_SAFE_BASE_JAR_CODE_SOURCE_TYPE_MISMATCH",
        )

        val testResourceUrl = uniqueResource(loader, TEST_CLASS_NAME, "S1_OVERLOAD_GUARD_TEST")
        val testResource = localFilePath(
            testResourceUrl,
            expectedDirectory = false,
            codePrefix = "S1_OVERLOAD_GUARD_TEST_CLASS_RESOURCE",
        )
        requireFact(
            testResource == expectedTestOutput.resolve(TEST_CLASS_SUFFIX),
            "S1_OVERLOAD_GUARD_TEST_CLASS_RESOURCE_MISMATCH",
        )
        val safeResourceUrl = uniqueResource(loader, SAFE_CLASS_NAME, "S1_SAFE_BASE")
        when (safeMode) {
            SafeOrigin.DIRECT -> {
                val safeResource = localFilePath(
                    safeResourceUrl,
                    expectedDirectory = false,
                    codePrefix = "S1_SAFE_BASE_CLASS_RESOURCE",
                )
                requireFact(
                    safeResource == expectedSafeDirectOutput.resolve(SAFE_CLASS_SUFFIX),
                    "S1_SAFE_BASE_DIRECT_CLASS_RESOURCE_MISMATCH",
                )
            }
            SafeOrigin.RUNTIME_JAR -> requireJarResource(
                safeResourceUrl,
                expectedSafeJar,
                SAFE_CLASS_NAME,
                "S1_SAFE_BASE",
            )
        }

        val declaredCallbacks = safeBase.declaredMethods.filter { it.name == "onNewIntent" }
        requireFact(
            declaredCallbacks.size == 1,
            "S1_SAFE_BASE_ALTERNATE_ON_NEW_INTENT",
        )
        val callback = declaredCallbacks.single()
        requireFact(
            callback.parameterTypes.map(Class<*>::getName) == listOf("android.content.Intent"),
            "S1_SAFE_BASE_OLD_ON_NEW_INTENT_DESCRIPTOR",
        )
        requireFact(
            Modifier.isProtected(callback.modifiers),
            "S1_SAFE_BASE_OLD_ON_NEW_INTENT_NOT_PROTECTED",
        )
        requireFact(
            Modifier.isFinal(callback.modifiers),
            "S1_SAFE_BASE_OLD_ON_NEW_INTENT_NOT_FINAL",
        )
        requireFact(
            !Modifier.isStatic(callback.modifiers),
            "S1_SAFE_BASE_OLD_ON_NEW_INTENT_STATIC",
        )
        requireFact(
            callback.returnType == Void.TYPE,
            "S1_SAFE_BASE_OLD_ON_NEW_INTENT_RETURN_TYPE",
        )
        requireFact(
            safeBase.declaredMethods.none { method ->
                method.name == "onNewIntent" &&
                    method.parameterTypes.map(Class<*>::getName) == listOf(
                        "android.content.Intent",
                        "android.app.ComponentCaller",
                    )
            },
            "S1_SAFE_BASE_API35_ON_NEW_INTENT_PRESENT",
        )
    }

    private fun authorityRoot(): Path {
        val raw = System.getenv(AUTHORITY_ROOT_ENV)
            ?: throw AssertionError("S1_AUTHORITY_ROOT_ENV_ABSENT")
        requireFact(raw.isNotEmpty() && raw == raw.trim(), "S1_AUTHORITY_ROOT_ENV_INVALID")
        val supplied = try {
            Paths.get(raw)
        } catch (_: Exception) {
            throw AssertionError("S1_AUTHORITY_ROOT_ENV_INVALID")
        }
        requireFact(supplied.isAbsolute, "S1_AUTHORITY_ROOT_NOT_ABSOLUTE")
        return canonicalOrdinaryPath(supplied, expectedDirectory = true, "S1_AUTHORITY_ROOT")
    }

    private fun codeSourcePath(
        type: Class<*>,
        expectedDirectory: Boolean?,
        codePrefix: String,
    ): Path {
        val location = try {
            type.protectionDomain?.codeSource?.location
        } catch (_: SecurityException) {
            null
        } ?: throw AssertionError("${codePrefix}_CODE_SOURCE_ABSENT")
        return localFilePath(location, expectedDirectory, "${codePrefix}_CODE_SOURCE")
    }

    private fun uniqueResource(loader: ClassLoader, name: String, codePrefix: String): URL {
        val resources = try {
            loader.getResources(name)
        } catch (_: Exception) {
            throw AssertionError("${codePrefix}_CLASS_RESOURCE_ENUMERATION_FAILED")
        }
        val found = ArrayList<URL>()
        while (resources.hasMoreElements()) found.add(resources.nextElement())
        requireFact(found.size == 1, "${codePrefix}_CLASS_RESOURCE_CARDINALITY")
        return found.single()
    }

    private fun requireJarResource(
        resourceUrl: URL,
        expectedJar: Path,
        expectedEntry: String,
        codePrefix: String,
    ) {
        requireFact(resourceUrl.protocol == "jar", "${codePrefix}_CLASS_RESOURCE_NOT_JAR")
        val connection = try {
            resourceUrl.openConnection() as? JarURLConnection
        } catch (_: Exception) {
            null
        } ?: throw AssertionError("${codePrefix}_JAR_CONNECTION_FAILED")
        connection.useCaches = false
        requireFact(connection.entryName == expectedEntry, "${codePrefix}_JAR_ENTRY_MISMATCH")
        val jarPath = localFilePath(
            connection.jarFileURL,
            expectedDirectory = false,
            codePrefix = "${codePrefix}_JAR_FILE",
        )
        requireFact(jarPath == expectedJar, "${codePrefix}_JAR_FILE_MISMATCH")
        val entry = try {
            connection.jarEntry
        } catch (_: Exception) {
            null
        } ?: throw AssertionError("${codePrefix}_JAR_ENTRY_ABSENT")
        requireFact(!entry.isDirectory, "${codePrefix}_JAR_ENTRY_NOT_FILE")
        try {
            connection.jarFile.close()
        } catch (_: Exception) {
            throw AssertionError("${codePrefix}_JAR_CLOSE_FAILED")
        }
    }

    private fun localFilePath(url: URL, expectedDirectory: Boolean?, codePrefix: String): Path {
        val uri = try {
            url.toURI()
        } catch (_: Exception) {
            throw AssertionError("${codePrefix}_URL_INVALID")
        }
        requireFact(
            uri.scheme == "file" &&
                !uri.isOpaque &&
                uri.rawAuthority == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
            "${codePrefix}_NOT_LOCAL_FILE",
        )
        val path = try {
            Paths.get(uri)
        } catch (_: Exception) {
            throw AssertionError("${codePrefix}_PATH_INVALID")
        }
        return canonicalOrdinaryPath(path, expectedDirectory, codePrefix)
    }

    private fun canonicalOrdinaryPath(
        path: Path,
        expectedDirectory: Boolean?,
        codePrefix: String,
    ): Path {
        val absolute = path.toAbsolutePath().normalize()
        val root = absolute.root
            ?: throw AssertionError("${codePrefix}_NOT_ABSOLUTE")
        val rootText = root.toString()
        requireFact(
            rootText.length == 3 &&
                rootText[0].isLetter() &&
                rootText[1] == ':' &&
                rootText[2] == '\\',
            "${codePrefix}_NOT_LOCAL_DRIVE",
        )
        requireOrdinary(root, expectedDirectory = true, codePrefix)
        var current = root
        for (name in absolute) {
            current = current.resolve(name)
            requireOrdinary(
                current,
                expectedDirectory = if (current == absolute) expectedDirectory else true,
                codePrefix = codePrefix,
            )
        }
        val real = try {
            absolute.toRealPath()
        } catch (_: Exception) {
            throw AssertionError("${codePrefix}_REAL_PATH_FAILED")
        }
        requireFact(real == absolute, "${codePrefix}_NOT_CANONICAL")
        requireOrdinary(real, expectedDirectory, codePrefix)
        return real
    }

    private fun requireOrdinary(path: Path, expectedDirectory: Boolean?, codePrefix: String) {
        val attributes = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            throw AssertionError("${codePrefix}_ATTRIBUTES_UNREADABLE")
        }
        requireFact(
            !attributes.isSymbolicLink && !attributes.isOther,
            "${codePrefix}_REPARSE_OR_NONORDINARY",
        )
        val typeMatches = when (expectedDirectory) {
            true -> attributes.isDirectory
            false -> attributes.isRegularFile
            null -> attributes.isDirectory || attributes.isRegularFile
        }
        requireFact(typeMatches, "${codePrefix}_TYPE_MISMATCH")
    }

    private fun requireFact(condition: Boolean, code: String) {
        if (!condition) throw AssertionError(code)
    }

    private enum class SafeOrigin {
        DIRECT,
        RUNTIME_JAR,
    }

    private companion object {
        const val AUTHORITY_ROOT_ENV = "ANEB_ROUTE_A_AUTHORITY_ROOT"
        const val SAFE_CLASS_NAME = "com/aneb/probe/ui/ReleaseSafeComponentActivity.class"
        const val TEST_CLASS_NAME = "com/aneb/probe/ui/MainActivityNewIntentOverloadGuardTest.class"
        val TEST_OUTPUT_SUFFIX: Path = Paths.get(
            "app",
            "probe",
            "build",
            "tmp",
            "kotlin-classes",
            "debugUnitTest",
        )
        val SAFE_DIRECT_OUTPUT_SUFFIX: Path = Paths.get(
            "app",
            "probe",
            "build",
            "tmp",
            "kotlin-classes",
            "debug",
        )
        val SAFE_JAR_SUFFIX: Path = Paths.get(
            "app",
            "probe",
            "build",
            "intermediates",
            "runtime_app_classes_jar",
            "debug",
            "bundleDebugClassesToRuntimeJar",
            "classes.jar",
        )
        val SAFE_CLASS_SUFFIX: Path = Paths.get(
            "com",
            "aneb",
            "probe",
            "ui",
            "ReleaseSafeComponentActivity.class",
        )
        val TEST_CLASS_SUFFIX: Path = Paths.get(
            "com",
            "aneb",
            "probe",
            "ui",
            "MainActivityNewIntentOverloadGuardTest.class",
        )
    }
}
