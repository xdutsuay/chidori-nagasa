package com.xdutsuay.chidori.computer

/**
 * One-tap offline environment recipes (tarball ids). Bundled catalog only in v0;
 * install applies a marker under recipes/ — real tarball fetch is channel-based later.
 */
data class CosRecipe(
    val id: String,
    val title: String,
    val description: String,
)

object CosRecipeCatalog {
    val BUNDLED: List<CosRecipe> = listOf(
        CosRecipe(
            id = "smoke-shell",
            title = "Smoke shell",
            description = "Minimal COS tools for uname / echo smoke tests",
        ),
        CosRecipe(
            id = "git-travel",
            title = "Travel git",
            description = "Offline git-oriented scratch layout for travel coding",
        ),
    )

    fun find(id: String): CosRecipe? = BUNDLED.find { it.id == id }
}

class CosRecipeStore(
    private val installedIds: MutableSet<String> = mutableSetOf(),
) {
    fun installed(): Set<String> = installedIds.toSet()

    fun isInstalled(id: String): Boolean = id in installedIds

    /** Marks recipe installed if known; returns false if unknown id. */
    fun install(id: String): Boolean {
        if (CosRecipeCatalog.find(id) == null) return false
        installedIds += id
        return true
    }
}
