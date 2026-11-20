package com.aneb.probe.engine

/**
 * Cross-language deterministic PCG32 (XSH-RR 64/32) matching
 * `tools/aneb-ai-behavior-model/src/aneb_behavior_model/rng.py` bit-for-bit.
 */
class Pcg32(seed: Long, sequence: Long = 54L) {
    private var state: ULong = 0UL
    private val increment: ULong = ((sequence.toULong() shl 1) or 1UL)

    init {
        nextUInt32()
        state += seed.toULong()
        nextUInt32()
    }

    fun nextUInt32(): UInt {
        val oldState = state
        state = oldState * MULTIPLIER + increment
        val xorShifted = (((oldState shr 18) xor oldState) shr 27).toUInt()
        val rotation = (oldState shr 59).toInt() and 31
        val negRotation = (-rotation) and 31
        return (xorShifted shr rotation) or (xorShifted shl negRotation)
    }

    fun random(): Double = nextUInt32().toDouble() / 4_294_967_296.0

    fun randBelow(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val uBound = bound.toUInt()
        val threshold = (0u - uBound) % uBound
        while (true) {
            val candidate = nextUInt32()
            if (candidate >= threshold) {
                return (candidate % uBound).toInt()
            }
        }
    }

    fun <T> choice(values: List<T>): T {
        require(values.isNotEmpty()) { "cannot choose from an empty sequence" }
        return values[randBelow(values.size)]
    }

    private companion object {
        const val MULTIPLIER: ULong = 6364136223846793005UL
    }
}
