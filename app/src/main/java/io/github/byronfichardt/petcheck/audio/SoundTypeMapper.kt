package io.github.byronfichardt.petcheck.audio

import io.github.byronfichardt.petcheck.data.SoundType

data class Classification(val type: SoundType, val confidence: Float)

/**
 * Maps YAMNet's 521 AudioSet classes onto the handful of sound types Pet Check stores.
 * Labels are matched by text, not index. pi/petcheck_pi/classify.py mirrors this file.
 */
class SoundTypeMapper(labels: List<String>) {
    internal val specific: List<Pair<Int, SoundType>>
    internal val generic: List<Pair<Int, SoundType>>

    init {
        val missing = (LABEL_TYPES.keys + GENERIC_LABELS.keys) - labels.toSet()
        require(missing.isEmpty()) { "label list is missing: ${missing.sorted()}" }
        specific = labels.mapIndexedNotNull { i, name -> LABEL_TYPES[name]?.let { i to it } }
        generic = labels.mapIndexedNotNull { i, name -> GENERIC_LABELS[name]?.let { i to it } }
    }

    fun accumulator(): TypeAccumulator = TypeAccumulator(this)

    companion object {
        const val MIN_CONFIDENCE = 0.35f

        val LABEL_TYPES: Map<String, SoundType> = mapOf(
            "Bark" to SoundType.BARK,
            "Yip" to SoundType.BARK,
            "Bow-wow" to SoundType.BARK,
            "Howl" to SoundType.HOWL,
            "Whimper (dog)" to SoundType.WHINE,
            "Growling" to SoundType.GROWL,
            "Meow" to SoundType.MEOW,
            "Caterwaul" to SoundType.MEOW,
        )

        /** Used only when no specific label is confident enough. */
        val GENERIC_LABELS: Map<String, SoundType> = mapOf(
            "Dog" to SoundType.BARK,
            "Cat" to SoundType.MEOW,
        )
    }
}

/** Collects classifier scores over the windows of one event and picks its type. */
class TypeAccumulator internal constructor(private val mapper: SoundTypeMapper) {
    private val specific = FloatArray(SoundType.entries.size)
    private val generic = FloatArray(SoundType.entries.size)

    var windows = 0
        private set

    fun add(scores: FloatArray) {
        windows++
        for ((index, type) in mapper.specific) {
            if (scores[index] > specific[type.ordinal]) specific[type.ordinal] = scores[index]
        }
        for ((index, type) in mapper.generic) {
            if (scores[index] > generic[type.ordinal]) generic[type.ordinal] = scores[index]
        }
    }

    /** Ties resolve in [SoundType.PET_TYPES] order. */
    fun result(minConfidence: Float = SoundTypeMapper.MIN_CONFIDENCE): Classification {
        for (table in arrayOf(specific, generic)) {
            val best = SoundType.PET_TYPES.maxBy { table[it.ordinal] }
            if (table[best.ordinal] >= minConfidence) return Classification(best, table[best.ordinal])
        }
        return Classification(SoundType.OTHER, 0f)
    }
}
