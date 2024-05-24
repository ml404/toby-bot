package toby.dto.web.dnd

import com.google.gson.annotations.SerializedName

data class Feature(
    val index: String?,
    @SerializedName("class")
    val classInfo: ApiInfo?,
    val name: String?,
    val level: Int?,
    val prerequisites: List<String?>,
    val desc: List<String>?,
    val url: String?
)