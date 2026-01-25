package com.example.config

import java.io.File
import java.io.FileInputStream
import java.util.*

/**
 * Конфигурация приложения для тестирования API
 */
object AppConfig {
    
    private val properties = Properties()
    private var initialized = false
    
    // Значения по умолчанию
    private const val DEFAULT_HOST = "https://iceja.net"
    private const val DEFAULT_TIMEOUT = 180L
    
    init {
        loadConfig()
    }
    
    private fun loadConfig() {
        try {
            // Сначала загружаем из файла application.properties
            val configFile = File("src/main/resources/application.properties")
            if (configFile.exists()) {
                FileInputStream(configFile).use { properties.load(it) }
                println("✓ Loaded configuration from: ${configFile.absolutePath}")
            } else {
                // Или из ресурсов
                val resourceStream = javaClass.classLoader.getResourceAsStream("application.properties")
                if (resourceStream != null) {
                    properties.load(resourceStream)
                    println("✓ Loaded configuration from classpath resources")
                } else {
                    println("⚠ No application.properties found, using defaults")
                }
            }
            
            // Переопределяем системными свойствами
            System.getProperties().forEach { key, value ->
                if (key is String && value is String) {
                    properties.setProperty(key, value)
                }
            }
            
            // Переопределяем переменными окружения
            System.getenv().forEach { key, value ->
                properties.setProperty(key, value)
            }
            
            initialized = true
            
        } catch (e: Exception) {
            println("⚠ Error loading configuration: ${e.message}")
            println("⚠ Using default values")
        }
    }
    
    /**
     * Получить значение свойства с поддержкой переопределения
     */
    fun getProperty(key: String, defaultValue: String = ""): String {
        val value = when (key) {
            "api.host" -> properties.getProperty(key) 
                ?: System.getProperty("api.host") 
                ?: System.getenv("API_HOST") 
                ?: defaultValue.ifEmpty { DEFAULT_HOST }
            
            else -> properties.getProperty(key) 
                ?: System.getProperty(key) 
                ?: System.getenv(key.uppercase().replace(".", "_")) 
                ?: defaultValue
        }
        
        // Логирование загруженных значений (только для ключевых свойств)
        if (key.startsWith("api.") || key.startsWith("connection.")) {
            println("Config: $key = $value")
        }
        
        return value
    }
    
    /**
     * Получить числовое значение свойства
     */
    fun getIntProperty(key: String, defaultValue: Int = 0): Int {
        return getProperty(key, defaultValue.toString()).toIntOrNull() ?: defaultValue
    }
    
    /**
     * Получить значение Long свойства
     */
    fun getLongProperty(key: String, defaultValue: Long = 0L): Long {
        return getProperty(key, defaultValue.toString()).toLongOrNull() ?: defaultValue
    }
    
    /**
     * Получить булево значение свойства
     */
    fun getBooleanProperty(key: String, defaultValue: Boolean = false): Boolean {
        return getProperty(key, defaultValue.toString()).toBoolean()
    }
    
    /**
     * Перезагрузить конфигурацию
     */
    fun reload() {
        properties.clear()
        loadConfig()
    }
    
    /**
     * Получить полный URL для эндпоинта
     */
    fun getFullUrl(endpointKey: String): String {
        val host = getProperty("api.host")
        val endpoint = getProperty(endpointKey)
        
        // Убедимся что host не заканчивается на /, а endpoint начинается с /
        val cleanHost = host.removeSuffix("/")
        val cleanEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        
        return "$cleanHost$cleanEndpoint"
    }
    
    /**
     * Показать текущую конфигурацию
     */
    fun printConfig() {
        println("\n=== Current Configuration ===")
        println("Active Host: ${getProperty("api.host")}")
        println("Connection Timeout: ${getProperty("connection.timeout.seconds")}s")
        println("Test Output Dir: ${getProperty("test.output.dir")}")
        println("Save Files: ${getProperty("test.save-files")}")
        println("============================\n")
    }
}

/**
 * Константы для конфигурации
 */
object ConfigConstants {
    // API хосты
    const val HOST_PRODUCTION = "https://iceja.net"
    const val HOST_LOCAL = "http://localhost:8080"
    const val HOST_LOCAL_IP = "http://127.0.0.1:8080"
    
    // Ключи свойств
    const val API_HOST = "api.host"
    const val TIMEOUT = "connection.timeout.seconds"
    const val OUTPUT_DIR = "test.output.dir"
    const val SAVE_FILES = "test.save-files"
}