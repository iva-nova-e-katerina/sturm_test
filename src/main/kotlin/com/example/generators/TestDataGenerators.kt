package com.example.generators

import com.example.config.AppConfig
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sin

/**
 * Генераторы тестовых данных для всех трёх API
 */
object TestDataGenerators {

    private val OUTPUT_DIR = File(AppConfig.getProperty("test.output.dir", "test_output")).apply { mkdirs() }
    private val ARCHIVES_DIR = File(OUTPUT_DIR, "archives").apply { mkdirs() }
    private val RESULTS_DIR = File(OUTPUT_DIR, "results").apply { mkdirs() }
    private val LOGS_DIR = File(OUTPUT_DIR, "logs").apply { mkdirs() }

    private val SAVE_FILES = AppConfig.getBooleanProperty("test.save-files", true)
    private val CLEANUP_DAYS = AppConfig.getIntProperty("test.cleanup-old-files.days", 1)

    // ==============================
    // POLYNOMIAL SOLVER API GENERATORS
    // ==============================

    /**
     * Генерация данных для полиномиального решателя
     */
    fun generatePolynomialData(numPolynomials: Int = 3, maxDegree: Int = 3): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val result = StringBuilder()
        result.append("# Polynomial test data generated $timestamp\n")
        result.append("# Host: ${AppConfig.getProperty("api.host")}\n")
        result.append("# Number of polynomials: $numPolynomials\n")
        result.append("# Maximum degree: $maxDegree\n\n")

        for (i in 1..numPolynomials) {
            val degree = (2..maxDegree).random()
            result.append("ID=$i")

            for (j in 0..degree) {
                val coeff = ((-100..100).random() * 0.1)
                result.append(" ${String.format("%.6e", coeff)}")
            }
            result.append("\n")
        }

        return result.toString()
    }

    /**
     * Создание ZIP файла с одним файлом полиномов
     */
    fun createPolynomialZipFile(fileName: String, content: String): File {
        val zipFile = File(ARCHIVES_DIR, fileName)

        if (SAVE_FILES) {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val entry = ZipEntry("polynomials.txt")
                    zos.putNextEntry(entry)
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
            println("✓ Created polynomial ZIP: ${zipFile.absolutePath}")
        } else {
            println("⚠ File saving disabled (test.save-files=false)")
        }

        return zipFile
    }

    // ==============================
    // CUBIC SPLINE API GENERATORS
    // ==============================

    /**
     * Генерация CSV данных для сплайн-интерполяции
     */
    fun generateSplineData(numPoints: Int = 8, functionName: String = "quadratic"): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val result = StringBuilder()
        result.append("# Spline test data generated $timestamp\n")
        result.append("# Host: ${AppConfig.getProperty("api.host")}\n")
        result.append("# Function: $functionName\n")
        result.append("# Number of points: $numPoints\n\n")
        result.append("x,y\n")

        val function: (Double) -> Double = when (functionName.lowercase()) {
            "linear" -> { x -> 2 * x + 1 }
            "quadratic" -> { x -> x * x }
            "sine" -> { x -> sin(x) }
            "exponential" -> { x -> kotlin.math.exp(x * 0.5) }
            else -> { x -> x * x }
        }

        for (i in 0 until numPoints) {
            val x = i * 1.0
            val y = function(x)
            result.append("${String.format("%.6f", x)},${String.format("%.6f", y)}\n")
        }

        return result.toString()
    }

    /**
     * Создание ZIP файла с одним CSV файлом
     */
    fun createSplineZipFile(fileName: String, content: String): File {
        val zipFile = File(ARCHIVES_DIR, fileName)

        if (SAVE_FILES) {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val entry = ZipEntry("spline_data.csv")
                    zos.putNextEntry(entry)
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
            println("✓ Created spline ZIP: ${zipFile.absolutePath}")
        } else {
            println("⚠ File saving disabled (test.save-files=false)")
        }

        return zipFile
    }

    // ==============================
    // FOURIER TRANSFORM API GENERATORS
    // ==============================

    /**
     * Генерация данных для преобразования Фурье
     */
    fun generateFourierData(numSignals: Int = 2, sampleSize: Int = 8): String {
        require(sampleSize.isPowerOfTwo()) { "Размер выборки должен быть степенью двойки" }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val result = StringBuilder()
        result.append("# Fourier signal data generated $timestamp\n")
        result.append("# Host: ${AppConfig.getProperty("api.host")}\n")
        result.append("# Number of signals: $numSignals\n")
        result.append("# Sample size: $sampleSize (2^${kotlin.math.log2(sampleSize.toDouble()).toInt()})\n\n")

        for (i in 1..numSignals) {
            val periods = (1..2).random()
            val threshold = (1..10).random() * 0.01

            result.append("id=$i,periods=$periods,threshold=${String.format("%.2f", threshold)}")

            for (j in 0 until sampleSize) {
                val t = j * 2 * Math.PI / sampleSize
                val value = sin(t + i * 0.5) * 0.5 + 0.5
                result.append("|${String.format("%.6f", value)}")
            }
            result.append("\n")
        }

        return result.toString()
    }

    /**
     * Создание ZIP файла с одним файлом данных Фурье
     */
    fun createFourierZipFile(fileName: String, content: String): File {
        val zipFile = File(ARCHIVES_DIR, fileName)

        if (SAVE_FILES) {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val entry = ZipEntry("fourier_signals.txt")
                    zos.putNextEntry(entry)
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
            println("✓ Created Fourier ZIP: ${zipFile.absolutePath}")
        } else {
            println("⚠ File saving disabled (test.save-files=false)")
        }

        return zipFile
    }

    // ==============================
    // UTILITY FUNCTIONS
    // ==============================

    private fun Int.isPowerOfTwo(): Boolean {
        return this > 0 && (this and (this - 1)) == 0
    }

    /**
     * Сохранить результаты в файл
     */
    fun saveResults(fileName: String, content: String): File {
        if (!SAVE_FILES) {
            return File("/dev/null") // заглушка если сохранение отключено
        }

        val resultFile = File(RESULTS_DIR, fileName)
        resultFile.writeText(content)
        println("✓ Saved results: ${resultFile.absolutePath} (${content.length} chars)")
        return resultFile
    }

    /**
     * Сохранить бинарные данные (например, ZIP)
     */
    fun saveBinaryResults(fileName: String, data: ByteArray): File {
        if (!SAVE_FILES) {
            return File("/dev/null")
        }

        val resultFile = File(RESULTS_DIR, fileName)
        resultFile.writeBytes(data)
        println("✓ Saved binary results: ${resultFile.absolutePath} (${data.size} bytes)")
        return resultFile
    }

    /**
     * Сохранить лог
     */
    fun saveLog(fileName: String, content: String): File {
        val logFile = File(LOGS_DIR, fileName)
        logFile.writeText(content)
        return logFile
    }

    /**
     * Очистка старых тестовых файлов
     */
    fun cleanupOldFiles(daysOld: Int = CLEANUP_DAYS) {
        if (!SAVE_FILES) return

        val cutoff = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
        var deletedCount = 0

        listOf(ARCHIVES_DIR, RESULTS_DIR, LOGS_DIR).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) {
                        file.delete()
                        deletedCount++
                    }
                }
            }
        }

        if (deletedCount > 0) {
            println("✓ Cleaned up $deletedCount old files (older than $daysOld days)")
        }
    }

    /**
     * Получить информацию о текущей конфигурации
     */
    fun getConfigInfo(): String {
        return """
            Test Configuration:
            - Host: ${AppConfig.getProperty("api.host")}
            - Save Files: $SAVE_FILES
            - Output Directory: ${OUTPUT_DIR.absolutePath}
            - Cleanup Days: $CLEANUP_DAYS
            - Timestamp: ${LocalDateTime.now()}
        """.trimIndent()
    }
}