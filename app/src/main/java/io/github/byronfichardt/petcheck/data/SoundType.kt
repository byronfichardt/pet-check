package io.github.byronfichardt.petcheck.data

/** The kinds of sound an event can be stored as. [key] is what goes into the database and Firestore. */
enum class SoundType(val key: String) {
    BARK("bark"),
    HOWL("howl"),
    WHINE("whine"),
    GROWL("growl"),
    MEOW("meow"),
    OTHER("other");

    val isPet: Boolean get() = this != OTHER

    companion object {
        val PET_TYPES: List<SoundType> = entries.filter { it.isPet }

        fun fromKey(key: String?): SoundType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}
