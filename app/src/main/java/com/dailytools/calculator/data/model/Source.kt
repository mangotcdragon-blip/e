package com.dailytools.calculator.data.model

enum class Source(val label: String) {
    E621("e621"),
    RULE34("Rule34"),
}

enum class Rating(val label: String, val tag: String?) {
    ALL("All", null),
    SAFE("Safe", "rating:s"),
    QUESTIONABLE("Questionable", "rating:q"),
    EXPLICIT("Explicit", "rating:e"),
}

enum class MediaTypeFilter(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEOS("Videos"),
}

enum class SortOrder(val label: String, val tag: String?) {
    NEWEST("Newest", null),
    SCORE("Top Score", "order:score"),
    RANDOM("Random", "order:random"),
}
