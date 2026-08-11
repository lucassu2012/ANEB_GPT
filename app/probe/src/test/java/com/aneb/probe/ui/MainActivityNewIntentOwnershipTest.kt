package com.aneb.probe.ui

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import org.junit.Test

class MainActivityNewIntentOwnershipTest {
    @Test
    fun `new intent ownership is sealed in the safe base and fixed helpers`() {
        val loader = javaClass.classLoader
            ?: throw AssertionError("S1_TEST_CLASSLOADER_ABSENT")
        val safeBase = try {
            Class.forName("com.aneb.probe.ui.ReleaseSafeComponentActivity", false, loader)
        } catch (_: ClassNotFoundException) {
            throw AssertionError("S1_NEW_INTENT_BASE_ABSENT")
        }

        requireFact(Modifier.isAbstract(safeBase.modifiers), "S1_NEW_INTENT_BASE_NOT_ABSTRACT")
        val componentActivity = Class.forName("androidx.activity.ComponentActivity", false, loader)
        requireFact(
            safeBase.superclass == componentActivity,
            "S1_NEW_INTENT_BASE_SUPERCLASS_MISMATCH",
        )

        val mainActivity = Class.forName("com.aneb.probe.ui.MainActivity", false, loader)
        requireFact(
            mainActivity.superclass == safeBase,
            "S1_MAINACTIVITY_SUPERCLASS_MISMATCH",
        )
        requireFact(Modifier.isFinal(mainActivity.modifiers), "S1_MAINACTIVITY_NOT_FINAL")
        requireFact(
            mainActivity.declaredMethods.count { it.name == "onNewIntent" } == 0,
            "S1_MAINACTIVITY_DECLARED_ON_NEW_INTENT",
        )

        val safeDeclaredCallbacks = safeBase.declaredMethods.filter { it.name == "onNewIntent" }
        requireFact(safeDeclaredCallbacks.size == 1, "S1_BASE_TOTAL_CALLBACK_COUNT")
        val safeOldCallbacks = safeDeclaredCallbacks.filter {
            it.name == "onNewIntent" &&
                it.parameterTypes.map(Class<*>::getName) == listOf("android.content.Intent")
        }
        requireFact(safeOldCallbacks.size == 1, "S1_BASE_OLD_CALLBACK_COUNT")
        val safeOldCallback = safeOldCallbacks.single()
        requireFact(Modifier.isFinal(safeOldCallback.modifiers), "S1_BASE_OLD_CALLBACK_NOT_FINAL")
        requireFact(
            Modifier.isProtected(safeOldCallback.modifiers),
            "S1_BASE_OLD_CALLBACK_NOT_PROTECTED",
        )
        requireFact(!Modifier.isStatic(safeOldCallback.modifiers), "S1_BASE_OLD_CALLBACK_STATIC")
        requireFact(safeOldCallback.returnType == Void.TYPE, "S1_BASE_OLD_CALLBACK_RETURN_TYPE")
        requireFact(
            safeBase.declaredMethods.none {
                it.name == "onNewIntent" &&
                    it.parameterTypes.map(Class<*>::getName) == listOf(
                        "android.content.Intent",
                        "android.app.ComponentCaller",
                    )
            },
            "S1_BASE_NEW_CALLBACK_PRESENT",
        )

        val resolvedOld = resolveDeclaredCallback(
            start = mainActivity,
            parameterTypeNames = listOf("android.content.Intent"),
        ) ?: throw AssertionError("S1_OLD_CALLBACK_RESOLUTION_MISSING")
        requireFact(resolvedOld.declaringClass == safeBase, "S1_OLD_CALLBACK_OWNER_MISMATCH")

        val intentClass = Class.forName("android.content.Intent", false, loader)
        val componentCaller = try {
            Class.forName("android.app.ComponentCaller", false, loader)
        } catch (_: ClassNotFoundException) {
            throw AssertionError("S1_COMPONENT_CALLER_CLASS_ABSENT")
        }
        val resolvedNew = try {
            mainActivity.getMethod("onNewIntent", intentClass, componentCaller)
        } catch (_: NoSuchMethodException) {
            throw AssertionError("S1_NEW_CALLBACK_RESOLUTION_MISSING")
        }
        requireFact(
            resolvedNew.declaringClass.name == "android.app.Activity",
            "S1_NEW_CALLBACK_OWNER_MISMATCH",
        )

        val componentCallerReference = "android/app/ComponentCaller"
            .toByteArray(StandardCharsets.US_ASCII)
        requireFact(
            occurrenceCount(classBytes(safeBase, loader), componentCallerReference) == 0,
            "S1_SAFE_BASE_COMPONENT_CALLER_REFERENCE",
        )
        requireFact(
            occurrenceCount(classBytes(mainActivity, loader), componentCallerReference) == 0,
            "S1_MAINACTIVITY_COMPONENT_CALLER_REFERENCE",
        )

        val helpers = Class.forName("com.aneb.probe.ui.NewIntentDispatchOrderKt", false, loader)
        assertFixedHelper(helpers, "dispatchReleaseNewIntent", 3, "S1_RELEASE_HELPER")
        assertFixedHelper(helpers, "dispatchDebugNewIntent", 2, "S1_DEBUG_HELPER")
        requireFact(
            helpers.declaredMethods.none {
                (it.name.startsWith("dispatchReleaseNewIntent") ||
                    it.name.startsWith("dispatchDebugNewIntent")) &&
                    it.name.contains("\$default")
            },
            "S1_HELPER_DEFAULT_OVERLOAD_PRESENT",
        )
    }

    private fun resolveDeclaredCallback(
        start: Class<*>,
        parameterTypeNames: List<String>,
    ): Method? {
        var current: Class<*>? = start
        while (current != null) {
            val matches = current.declaredMethods.filter {
                it.name == "onNewIntent" &&
                    it.parameterTypes.map(Class<*>::getName) == parameterTypeNames
            }
            requireFact(matches.size <= 1, "S1_OLD_CALLBACK_RESOLUTION_AMBIGUOUS")
            if (matches.size == 1) return matches.single()
            current = current.superclass
        }
        return null
    }

    private fun assertFixedHelper(
        owner: Class<*>,
        name: String,
        arity: Int,
        codePrefix: String,
    ) {
        val named = owner.declaredMethods.filter { it.name == name }
        requireFact(named.size == 1, "${codePrefix}_COUNT")
        val method = named.single()
        requireFact(method.parameterCount == arity, "${codePrefix}_ARITY")
        requireFact(Modifier.isStatic(method.modifiers), "${codePrefix}_NOT_STATIC")
        requireFact(method.returnType == Void.TYPE, "${codePrefix}_RETURN_TYPE")
    }

    private fun classBytes(type: Class<*>, loader: ClassLoader): ByteArray {
        val resourceName = type.name.replace('.', '/') + ".class"
        val stream = loader.getResourceAsStream(resourceName)
            ?: throw AssertionError("S1_CLASS_RESOURCE_ABSENT_${type.simpleName}")
        return stream.use { it.readBytes() }
    }

    private fun occurrenceCount(bytes: ByteArray, needle: ByteArray): Int {
        var count = 0
        var index = 0
        while (index <= bytes.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) count += 1
            index += 1
        }
        return count
    }

    private fun requireFact(condition: Boolean, code: String) {
        if (!condition) throw AssertionError(code)
    }
}
