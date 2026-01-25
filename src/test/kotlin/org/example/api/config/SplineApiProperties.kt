package org.example.api.config

import java.io.File
import java.io.FileInputStream
import java.util.*

data class SplineApiProperties(
    val baseUrl: String = "https://iceja.net",
    val timeoutSeconds: Int = 30,
    val maxFileSizeMb: Int = 100,
    val enableDetailedLogging: Boolean = false
) {
    companion object {
        fun loadFromPropertiesFile(propertiesPath: String = "config.properties"): SplineApiProperties {
            val properties = Properties()
            val file = File(propertiesPath)

            if (file.exists()) {
                FileInputStream(file).use { input ->
                    properties.load(input)
                }
            } else {
                // Try to load from classpath (for tests)
                val classpathStream = SplineApiProperties::class.java.classLoader
                    .getResourceAsStream(propertiesPath)
                if (classpathStream != null) {
                    classpathStream.use { input ->
                        properties.load(input)
                    }
                }
            }

            return SplineApiProperties(
                baseUrl = properties.getProperty("spline.api.base-url", "https://iceja.net"),
                timeoutSeconds = properties.getProperty("spline.api.timeout-seconds", "30").toInt(),
                maxFileSizeMb = properties.getProperty("spline.api.max-file-size-mb", "100").toInt(),
                enableDetailedLogging = properties.getProperty("spline.api.enable-detailed-logging", "false").toBoolean()
            )
        }
    }

    fun getApiBaseUrl(): String = "$baseUrl/api/v1"
    fun getWebApiUrl(endpoint: String): String = "$baseUrl$endpoint"
    fun getRestApiUrl(endpoint: String): String = "${getApiBaseUrl()}$endpoint"
    fun getTimeoutMillis(): Long = timeoutSeconds * 1000L
    fun getMaxFileSizeBytes(): Long = maxFileSizeMb * 1024L * 1024L
}