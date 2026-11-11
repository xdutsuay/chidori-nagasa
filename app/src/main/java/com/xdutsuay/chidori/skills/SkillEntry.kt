package com.xdutsuay.chidori.skills

/** One user-authored skill loaded from the device `skills` markdown folder. */
data class SkillEntry(
    val id: String,
    val title: String,
    val body: String,
)
