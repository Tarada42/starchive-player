package com.starchive.player.aa

enum class SortMode(val labelRes: Int) {
    DATE(com.starchive.player.R.string.aa_sort_date),
    NAME(com.starchive.player.R.string.aa_sort_name);

    fun next(): SortMode = when (this) {
        DATE -> NAME
        NAME -> DATE
    }
}
