package cc.uukanshu

/** Remote source of truth. Mirrors `BASE` in uukanshu-cli. */
const val BASE_URL = "https://uukanshu.cc"

/** Fixed category catalogue: /class_{id}_{page}.html, ids 1..10. */
data class Category(val id: Int, val name: String)

val CATEGORIES = listOf(
    Category(1, "玄幻奇幻"),
    Category(2, "武俠仙俠"),
    Category(3, "現代都市"),
    Category(4, "歷史軍事"),
    Category(5, "科幻小說"),
    Category(6, "遊戲競技"),
    Category(7, "恐怖靈異"),
    Category(8, "言情小說"),
    Category(9, "動漫同人"),
    Category(10, "其他類型"),
)
