package com.aneb.probe.ui

import com.aneb.probe.BuildConfig
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compiled structural contract for the one framework callback owned by the
 * release-safe base.  The Android JVM stubs cannot execute ComponentActivity's
 * framework callback, so this test verifies the actual compiled call graph
 * instead of pretending that a mocked super call is runtime evidence.
 */
class ReleaseSafeComponentActivityWiringTest {
    @Test
    fun `callback has one build type dispatch and dispatcher owns both branches`() {
        val bytes = classBytes("com.aneb.probe.ui.ReleaseSafeComponentActivity")
        val parsed = ClassFile.parse(bytes)
        val callback = parsed.method("onNewIntent", "(Landroid/content/Intent;)V")
        val callbackInvocations = callback.invocations(parsed)

        val callbackBehaviorInvocations = callbackInvocations.filter {
            it.owner == "com/aneb/probe/ui/ReleaseSafeComponentActivity" ||
                it.owner == "androidx/activity/ComponentActivity"
        }
        assertEquals(
            listOf(
                MethodRef(
                    owner = "com/aneb/probe/ui/ReleaseSafeComponentActivity",
                    name = "dispatchNewIntentByBuildType",
                    descriptor = "(Landroid/content/Intent;)V",
                ),
            ),
            callbackBehaviorInvocations,
        )
        assertTrue(
            callbackInvocations.none {
                it.owner == "androidx/activity/ComponentActivity" && it.name == "onNewIntent"
            },
        )

        val dispatcher = parsed.method(
            "dispatchNewIntentByBuildType",
            "(Landroid/content/Intent;)V",
        )
        val dispatcherInvocations = dispatcher.invocations(parsed)
        val expectedDispatcherInvocations = if (BuildConfig.DEBUG) {
            listOf(
                MethodRef(
                    owner = "com/aneb/probe/ui/NewIntentDispatchOrderKt",
                    name = "dispatchDebugNewIntent",
                    descriptor = "(Ljava/lang/Object;Lkotlin/jvm/functions/Function1;)V",
                ),
                MethodRef(
                    owner = "com/aneb/probe/ui/NewIntentDispatchOrderKt",
                    name = "dispatchReleaseNewIntent",
                    descriptor = "(Ljava/lang/Object;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V",
                ),
            )
        } else {
            listOf(
                MethodRef(
                    owner = "com/aneb/probe/ui/NewIntentDispatchOrderKt",
                    name = "dispatchReleaseNewIntent",
                    descriptor = "(Ljava/lang/Object;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)V",
                ),
            )
        }
        assertEquals(
            expectedDispatcherInvocations,
            dispatcherInvocations.filter { it.owner.endsWith("NewIntentDispatchOrderKt") },
        )
    }

    @Test
    fun `framework bridge invokes the component activity callback exactly once`() {
        val bytes = classBytes("com.aneb.probe.ui.ReleaseSafeComponentActivity")
        val parsed = ClassFile.parse(bytes)
        val bridge = parsed.method(
            "dispatchNewIntentToFramework",
            "(Landroid/content/Intent;)V",
        )

        assertEquals(
            listOf(
                Invocation(
                    opcode = 183,
                    method = MethodRef(
                        owner = "androidx/activity/ComponentActivity",
                        name = "onNewIntent",
                        descriptor = "(Landroid/content/Intent;)V",
                    ),
                ),
            ),
            bridge.invocationInstructions(parsed).filter {
                it.method.owner == "androidx/activity/ComponentActivity" &&
                    it.method.name == "onNewIntent"
            },
        )
    }

    private fun classBytes(name: String): ByteArray {
        val resource = name.replace('.', '/') + ".class"
        val stream = javaClass.classLoader?.getResourceAsStream(resource)
            ?: throw AssertionError("S1_WIRING_CLASS_RESOURCE_ABSENT_$name")
        return stream.use { it.readBytes() }
    }

    private data class MethodRef(
        val owner: String,
        val name: String,
        val descriptor: String,
    )

    private data class Invocation(val opcode: Int, val method: MethodRef)

    private data class MethodCode(val name: String, val descriptor: String, val code: ByteArray) {
        fun invocations(classFile: ClassFile): List<MethodRef> =
            invocationInstructions(classFile).map(Invocation::method)

        fun invocationInstructions(classFile: ClassFile): List<Invocation> {
            val result = mutableListOf<Invocation>()
            var offset = 0
            while (offset < code.size) {
                val opcode = code[offset].toInt() and 0xff
                when (opcode) {
                    182, 183, 184 -> {
                        val index = u2(code, offset + 1)
                        result += Invocation(opcode, classFile.methodRef(index))
                    }
                    185 -> {
                        val index = u2(code, offset + 1)
                        result += Invocation(opcode, classFile.methodRef(index))
                    }
                }
                offset += instructionLength(code, offset)
            }
            return result
        }
    }

    private class ClassFile private constructor(private val constantPool: Array<Cp?>, val methods: List<MethodCode>) {
        fun method(name: String, descriptor: String): MethodCode = methods.single {
            it.name == name && it.descriptor == descriptor
        }

        fun methodRef(index: Int): MethodRef {
            val member = constantPool[index] as? Cp.Member
                ?: throw AssertionError("S1_WIRING_METHOD_REF_ABSENT_$index")
            val owner = className(member.owner)
            val nameType = constantPool[member.nameAndType] as? Cp.NameType
                ?: throw AssertionError("S1_WIRING_NAME_TYPE_ABSENT_$index")
            return MethodRef(
                owner = owner,
                name = utf8(nameType.name),
                descriptor = utf8(nameType.descriptor),
            )
        }

        private fun className(index: Int): String {
            val clazz = constantPool[index] as? Cp.ClassRef
                ?: throw AssertionError("S1_WIRING_CLASS_REF_ABSENT_$index")
            return utf8(clazz.name)
        }

        private fun utf8(index: Int): String = (constantPool[index] as? Cp.Utf8)?.value
            ?: throw AssertionError("S1_WIRING_UTF8_ABSENT_$index")

        companion object {
            fun parse(bytes: ByteArray): ClassFile {
                val reader = Reader(bytes)
                require(reader.u4() == 0xCAFEBABEL) { "S1_WIRING_CLASS_MAGIC" }
                reader.u2()
                reader.u2()
                val pool = arrayOfNulls<Cp>(reader.u2())
                var index = 1
                while (index < pool.size) {
                    when (val tag = reader.u1()) {
                        1 -> pool[index] = Cp.Utf8(reader.bytes(reader.u2()).toString(StandardCharsets.UTF_8))
                        3, 4 -> reader.skip(4)
                        5, 6 -> {
                            reader.skip(8)
                            index++
                        }
                        7 -> pool[index] = Cp.ClassRef(reader.u2())
                        8 -> reader.skip(2)
                        9, 10, 11 -> pool[index] = Cp.Member(reader.u2(), reader.u2())
                        12 -> pool[index] = Cp.NameType(reader.u2(), reader.u2())
                        15 -> reader.skip(3)
                        16, 19, 20 -> reader.skip(2)
                        17, 18 -> reader.skip(4)
                        else -> throw AssertionError("S1_WIRING_UNKNOWN_CONSTANT_TAG_$tag")
                    }
                    index++
                }

                reader.u2()
                reader.u2()
                reader.u2()
                repeat(reader.u2()) { reader.skipInterface() }
                repeat(reader.u2()) { skipMember(reader) }

                val methods = buildList {
                    repeat(reader.u2()) {
                        reader.u2()
                        val name = (pool[reader.u2()] as Cp.Utf8).value
                        val descriptor = (pool[reader.u2()] as Cp.Utf8).value
                        var code: ByteArray? = null
                        repeat(reader.u2()) {
                            val attributeName = (pool[reader.u2()] as Cp.Utf8).value
                            val length = reader.u4().toInt()
                            if (attributeName == "Code") {
                                reader.u2()
                                reader.u2()
                                code = reader.bytes(reader.u4().toInt())
                                repeat(reader.u2()) {
                                    reader.skip(8)
                                }
                                repeat(reader.u2()) { skipAttribute(reader) }
                            } else {
                                reader.skip(length)
                            }
                        }
                        code?.let { add(MethodCode(name, descriptor, it)) }
                    }
                }
                return ClassFile(pool, methods)
            }

            private fun skipMember(reader: Reader) {
                reader.u2()
                reader.u2()
                reader.u2()
                repeat(reader.u2()) { skipAttribute(reader) }
            }

            private fun skipAttribute(reader: Reader) {
                reader.u2()
                reader.skip(reader.u4().toInt())
            }
        }
    }

    private sealed interface Cp {
        data class Utf8(val value: String) : Cp
        data class ClassRef(val name: Int) : Cp
        data class Member(val owner: Int, val nameAndType: Int) : Cp
        data class NameType(val name: Int, val descriptor: Int) : Cp
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun u1(): Int = bytes[offset++].toInt() and 0xff

        fun u2(): Int = (u1() shl 8) or u1()

        fun u4(): Long = (u1().toLong() shl 24) or
            (u1().toLong() shl 16) or
            (u1().toLong() shl 8) or
            u1().toLong()

        fun bytes(length: Int): ByteArray = bytes.copyOfRange(offset, offset + length).also {
            offset += length
        }

        fun skip(length: Int) {
            offset += length
        }

        fun skipInterface() {
            u2()
        }
    }

    companion object {
        private fun u2(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)

        private fun instructionLength(code: ByteArray, offset: Int): Int {
            return when (val opcode = code[offset].toInt() and 0xff) {
                16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188 -> 2
                17, 19, 20, 132, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163,
                164, 165, 166, 167, 168, 178, 179, 180, 181, 182, 183, 184, 187, 189, 192,
                193, 198, 199 -> 3
                185, 186 -> 5
                197 -> 4
                170 -> {
                    val aligned = (offset + 4) and -4
                    val low = i4(code, aligned + 8)
                    val high = i4(code, aligned + 12)
                    aligned + 16 + (high - low + 1) * 4 - offset
                }
                171 -> {
                    val aligned = (offset + 4) and -4
                    val pairs = i4(code, aligned + 4)
                    aligned + 8 + pairs * 8 - offset
                }
                196 -> if ((code[offset + 1].toInt() and 0xff) == 132) 6 else 4
                200, 201 -> 5
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45,
                46, 47, 48, 49, 50, 51, 52, 53, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
                70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88,
                89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105,
                106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121,
                122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 133, 134, 135, 136, 137,
                138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152,
                172, 173, 174, 175, 176, 177, 185, 190, 191, 194, 195 -> 1
                else -> throw AssertionError("S1_WIRING_UNKNOWN_OPCODE_$opcode")
            }
        }

        private fun i4(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
    }
}
