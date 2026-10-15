package dev.wind.agent.screen

import android.content.Context

/** Persistent character choice shared by the app controls and the floating pet. */
internal object PetAppearance {
    enum class Character(val assetDirectory: String) {
        WHALE("whale-girl-v1"), CATGIRL("catgirl-v1");
        val label: String get() = when (this) {
            WHALE -> ScreenStrings.whalePet
            CATGIRL -> ScreenStrings.catgirlPet
        }
    }

    fun selected(context: Context): Character {
        val saved = context.applicationContext.getSharedPreferences("pet_appearance", Context.MODE_PRIVATE)
            .getString("character", Character.WHALE.name)
        return Character.values().firstOrNull { it.name == saved } ?: Character.WHALE
    }

    fun choose(context: Context, character: Character) {
        context.applicationContext.getSharedPreferences("pet_appearance", Context.MODE_PRIVATE)
            .edit().putString("character", character.name).apply()
    }
}
