"""Cross-language deterministic PCG32 random number generator.

Python's high-level sampling helpers are deliberately not used for published
traces. PCG32 has a small, explicit algorithm that can be ported byte-for-byte
to Kotlin and Go.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import TypeVar

T = TypeVar("T")
_MASK_32 = (1 << 32) - 1
_MASK_64 = (1 << 64) - 1


class Pcg32:
    """PCG XSH-RR 64/32 with an explicit stream selector."""

    def __init__(self, seed: int, sequence: int = 54) -> None:
        self._state = 0
        self._increment = ((sequence & _MASK_64) << 1 | 1) & _MASK_64
        self.next_uint32()
        self._state = (self._state + (seed & _MASK_64)) & _MASK_64
        self.next_uint32()

    def next_uint32(self) -> int:
        old_state = self._state
        self._state = (
            old_state * 6364136223846793005 + self._increment
        ) & _MASK_64
        xor_shifted = (((old_state >> 18) ^ old_state) >> 27) & _MASK_32
        rotation = (old_state >> 59) & 31
        return (
            (xor_shifted >> rotation)
            | (xor_shifted << ((-rotation) & 31))
        ) & _MASK_32

    def random(self) -> float:
        return self.next_uint32() / float(1 << 32)

    def randbelow(self, bound: int) -> int:
        if bound <= 0:
            raise ValueError("bound must be positive")
        threshold = ((1 << 32) - bound) % bound
        while True:
            candidate = self.next_uint32()
            if candidate >= threshold:
                return candidate % bound

    def choice(self, values: Sequence[T]) -> T:
        if not values:
            raise ValueError("cannot choose from an empty sequence")
        return values[self.randbelow(len(values))]

    def weighted_choice(self, weights: dict[T, float]) -> T:
        positive = [(key, float(weight)) for key, weight in weights.items() if weight > 0]
        if not positive:
            raise ValueError("at least one weight must be positive")
        total = sum(weight for _, weight in positive)
        target = self.random() * total
        cumulative = 0.0
        for key, weight in positive:
            cumulative += weight
            if target < cumulative:
                return key
        return positive[-1][0]

