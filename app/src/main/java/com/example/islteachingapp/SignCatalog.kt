package com.example.islteachingapp

import android.content.Context

/** A sign entry loaded from the editable assets/signs.csv content catalog. */
data class SignCatalogEntry(
    val displayName: String,
    val classifierLabel: String,
    val referenceAsset: String,
    val videoAsset: String
)

/**
 * Loads and validates the editable sign catalog before any sign is offered to a learner.
 * Validation is intentionally strict: a typo must not silently make a sign impossible to pass.
 */
object SignCatalog {

    fun load(context: Context): List<SignCatalogEntry> {
        val lines = context.assets.open("signs.csv").bufferedReader().use { it.readLines() }
        require(lines.isNotEmpty()) { "signs.csv is empty." }

        val expectedHeader = "display_name,classifier_label,reference_asset,video_asset,enabled"
        require(lines.first().trim().equals(expectedHeader, ignoreCase = true)) {
            "signs.csv has an unexpected header. Expected: $expectedHeader"
        }

        val modelLabels = context.assets.open("labels.txt").bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.toSet()
        require(modelLabels.isNotEmpty()) { "labels.txt contains no classifier labels." }

        val seenDisplayNames = mutableSetOf<String>()
        val seenClassifierLabels = mutableSetOf<String>()
        val enabledEntries = mutableListOf<SignCatalogEntry>()

        lines.drop(1).forEachIndexed { index, line ->
            val lineNumber = index + 2
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed

            val fields = trimmed.split(',')
            require(fields.size == 5) {
                "signs.csv row $lineNumber must contain exactly 5 comma-separated fields."
            }

            val displayName = fields[0].trim()
            val classifierLabel = fields[1].trim()
            val referenceAsset = fields[2].trim()
            val videoAsset = fields[3].trim()
            val enabledText = fields[4].trim()

            require(displayName.isNotEmpty()) { "signs.csv row $lineNumber has an empty display_name." }
            require(classifierLabel.isNotEmpty()) { "signs.csv row $lineNumber has an empty classifier_label." }
            require(seenDisplayNames.add(displayName.lowercase())) {
                "signs.csv row $lineNumber repeats display_name '$displayName'."
            }
            require(seenClassifierLabels.add(classifierLabel.lowercase())) {
                "signs.csv row $lineNumber repeats classifier_label '$classifierLabel'."
            }
            require(modelLabels.any { it.equals(classifierLabel, ignoreCase = true) }) {
                "signs.csv row $lineNumber: classifier_label '$classifierLabel' is not present in labels.txt."
            }
            require(enabledText.equals("true", ignoreCase = true) || enabledText.equals("false", ignoreCase = true)) {
                "signs.csv row $lineNumber: enabled must be true or false."
            }

            validateAsset(context, referenceAsset, "reference_asset", lineNumber, required = true)
            validateAsset(context, videoAsset, "video_asset", lineNumber, required = false)

            if (enabledText.equals("true", ignoreCase = true)) {
                enabledEntries += SignCatalogEntry(displayName, classifierLabel, referenceAsset, videoAsset)
            }
        }

        require(enabledEntries.isNotEmpty()) { "No signs are enabled in signs.csv." }
        return enabledEntries
    }

    private fun validateAsset(
        context: Context,
        path: String,
        field: String,
        lineNumber: Int,
        required: Boolean
    ) {
        if (path.isEmpty() && !required) return
        require(path.isNotEmpty()) { "signs.csv row $lineNumber has an empty $field." }
        require(!path.startsWith('/') && path.split('/').none { it == ".." || it == "." || it.isBlank() }) {
            "signs.csv row $lineNumber has an invalid $field path '$path'."
        }
        if (field == "reference_asset") {
            require(path.endsWith(".json", ignoreCase = true)) {
                "signs.csv row $lineNumber: reference_asset must point to a JSON file."
            }
        } else {
            require(path.endsWith(".mp4", ignoreCase = true)) {
                "signs.csv row $lineNumber: video_asset must point to an MP4 file."
            }
        }
        try {
            context.assets.open(path).use { /* Existence check only; media is loaded by its existing component. */ }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "signs.csv row $lineNumber: $field '$path' was not found in app/src/main/assets/.", e
            )
        }
    }
}
