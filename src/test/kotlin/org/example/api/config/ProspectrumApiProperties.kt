package org.example.api.config

import java.io.InputStream
import java.util.Properties

data class ProspectrumApiProperties(
    val baseUrl: String,
    val timeoutSeconds: Int
) {
    companion object {
        fun loadFromPropertiesFile(filename: String): ProspectrumApiProperties {
            val props = Properties()
            val inputStream: InputStream = ProspectrumApiProperties::class.java.classLoader.getResourceAsStream(filename)
                ?: throw IllegalArgumentException("File $filename not found in classpath")
            props.load(inputStream)
            return ProspectrumApiProperties(
                baseUrl = props.getProperty("prospectrum.base.url") ?: throw IllegalArgumentException("prospectrum.base.url not found"),
                timeoutSeconds = props.getProperty("prospectrum.timeout.seconds")?.toInt() ?: 30
            )
        }
    }
}