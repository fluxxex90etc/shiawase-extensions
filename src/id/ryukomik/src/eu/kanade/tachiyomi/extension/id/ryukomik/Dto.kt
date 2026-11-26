package eu.kanade.tachiyomi.extension.id.ryukomik

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for api.ryukomik.com. Every field has a default so a missing key never
// fails parsing; field names cover the full key sets verified live 2026-11-26
// (the shared jsonInstance must not see an unknown key).

@Serializable
class KomikuHomeResponse(
    val success: Boolean = false,
    val message: String = "",
    val data: KomikuHomeData = KomikuHomeData(),
)

@Serializable
class KomikuHomeData(
    val terbaru: List<KomikuHomeItem> = emptyList(),
    val populer_manga: List<KomikuHomeItem> = emptyList(),
    val populer_manhwa: List<KomikuHomeItem> = emptyList(),
    val populer_manhua: List<KomikuHomeItem> = emptyList(),
)

// Union of the "terbaru" and "populer_*" item shapes: populer items have no
// slug/waktu but add views/tipe; terbaru items add up.
@Serializable
class KomikuHomeItem(
    val title: String = "",
    val slug: String = "",
    val link: String = "",
    val image: String = "",
    val genre: String = "",
    val waktu: String = "",
    val origin: String = "",
    @SerialName("chapter_terbaru") val chapterTerbaru: String = "",
    @SerialName("chapter_link") val chapterLink: String = "",
    val up: String = "",
    val views: String = "",
    val tipe: String = "",
)

@Serializable
class KiryuuHomeResponse(
    val success: Boolean = false,
    val data: KiryuuHomeData = KiryuuHomeData(),
)

@Serializable
class KiryuuHomeData(
    val terbaru: List<KiryuuHomeItem> = emptyList(),
    val populer_manga: List<KiryuuHomeItem> = emptyList(),
    val populer_manhwa: List<KiryuuHomeItem> = emptyList(),
    val populer_manhua: List<KiryuuHomeItem> = emptyList(),
)

@Serializable
class KiryuuHomeItem(
    val source: String = "",
    val title: String = "",
    val slug: String = "",
    val image: String = "",
    @SerialName("detail_link") val detailLink: String = "",
    val description: String = "",
    @SerialName("type_genre") val typeGenre: String = "",
    val info: String = "",
    @SerialName("chapter_awal") val chapterAwal: String = "",
    @SerialName("chapter_terbaru") val chapterTerbaru: String = "",
    val chapters: List<KiryuuHomeChapterItem> = emptyList(),
)

@Serializable
class KiryuuHomeChapterItem(
    val title: String = "",
    val link: String = "",
    val time: String = "",
    @SerialName("time_iso") val timeIso: String = "",
)

@Serializable
class KomikuListResponse(
    val success: Boolean = false,
    val source: String = "",
    val meta: ListMeta = ListMeta(),
    val filters: ListFilters = ListFilters(),
    val data: List<KomikuListItem> = emptyList(),
)

@Serializable
class ListMeta(
    @SerialName("totalKomik") val totalKomik: Int = 0,
    @SerialName("currentPage") val currentPage: Int = 1,
    @SerialName("totalPages") val totalPages: Int = 1,
)

@Serializable
class ListFilters(
    val types: List<FilterOption> = emptyList(),
    val alphabet: List<FilterOption> = emptyList(),
)

@Serializable
class FilterOption(
    val label: String = "",
    val url: String = "",
)

@Serializable
class KomikuListItem(
    val title: String = "",
    val type: String = "",
    val genre: String = "",
    val status: String = "",
    val image: String = "",
    val link: String = "",
)

@Serializable
class SearchResponse(
    val success: Boolean = false,
    val total: Int = 0,
    val query: String = "",
    val data: List<SearchItem> = emptyList(),
)

@Serializable
class SearchItem(
    val title: String = "",
    val image: String = "",
    @SerialName("detail_link") val detailLink: String = "",
    val update: String = "",
)
