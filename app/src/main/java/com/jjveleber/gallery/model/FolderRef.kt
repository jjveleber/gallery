package com.jjveleber.gallery.model

/** MediaStore's volume name for the device's built-in shared storage. */
const val PRIMARY_VOLUME = "external_primary"

/**
 * A folder in the MediaStore index. [volume] is a MediaStore volume name ([PRIMARY_VOLUME] or a lowercase SD card
 * ID such as "1a2b-3c4d"). [relativePath] is a MediaStore RELATIVE_PATH such as "Pictures/Trip/" (ends with '/').
 */
data class FolderRef(val volume: String, val relativePath: String) {
    /** The last path segment, for titles. */
    val name: String
        get() = relativePath.trim('/').substringAfterLast('/').ifEmpty { "Storage" }

    /** The path shown in the folder list, marked when the folder is not on the built-in storage. */
    val displayPath: String
        get() {
            val path = relativePath.trim('/').ifEmpty { "/" }
            return if (volume == PRIMARY_VOLUME) path else "SD card: $path"
        }
}
