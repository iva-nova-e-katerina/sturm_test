package org.example.api.config

import java.io.InputStream
import java.util.Properties

data class SimplexApiProperties(
    val baseUrl: String,
    val timeoutSeconds: Int,
    val executableName: String,
    val timeoutMinutes: Int,
    val processorCount: Int,
    val batchSize: Int
) {
    companion object {
        fun loadFromPropertiesFile(filename: String): SimplexApiProperties {
            val props = Properties()

            // Поддерживаем оба формата (key=value и key: value)
            SimplexApiProperties::class.java.classLoader.getResourceAsStream(filename)?.use { inputStream ->
                // Читаем файл как строки
                val lines = inputStream.reader().readLines()
                lines.forEach { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val delimiter = if (line.contains("=")) "=" else ":"
                        val parts = line.split(delimiter, limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            props[key] = value
                        }
                    }
                }
            } ?: throw IllegalArgumentException("File $filename not found in classpath")

            return SimplexApiProperties(
                baseUrl = props.getProperty("simplex.api.base.url")
                    ?: throw IllegalArgumentException("simplex.api.base.url not found"),
                timeoutSeconds = props.getProperty("simplex.api.timeout.seconds")?.toInt() ?: 30,
                executableName = props.getProperty("simplex.executable-name", "simplex"),
                timeoutMinutes = props.getProperty("simplex.timeout-minutes", "30").toInt(),
                processorCount = props.getProperty("simplex.processor-count", "1").toInt(),
                batchSize = props.getProperty("simplex.batch-size", "1").toInt()
            )
        }
    }
}