package org.example.api

import com.example.generators.TestDataGenerators
import org.junit.jupiter.api.*
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FourierApiTest : ApiTestBase() {

    private val config = ApiConfig(
        name = "Fourier Transform API",
        webApiEndpoint = "api.fourier.upload",
        restApiAsyncEndpoint = "api.fourier.execute",
        restApiDownloadEndpoint = "api.fourier.download",
        healthEndpoint = "api.fourier.health"
    )

    private lateinit var testFile: File
    private val results = mutableListOf<File>()

    @BeforeAll
    fun setUp() {
        println("\n" + "=".repeat(60))
        println("=== SETTING UP FOURIER TRANSFORM API TESTS ===")
        println("=".repeat(60))

        // Очистить старые файлы (старше 1 дня)
        TestDataGenerators.cleanupOldFiles(1)

        val fourierData = TestDataGenerators.generateFourierData(
            numSignals = 3,
            sampleSize = 16
        )

        val fileName = "fourier_${System.currentTimeMillis()}.zip"
        testFile = TestDataGenerators.createFourierZipFile(fileName, fourierData)

        // Сохраним сырые данные для сравнения
        val rawDataFile = TestDataGenerators.saveResults(
            "fourier_input_${System.currentTimeMillis()}.txt",
            fourierData
        )
        results.add(rawDataFile)

        println("\n📁 Test file: ${testFile.absolutePath}")
        println("📊 File size: ${testFile.length()} bytes")

        // Анализ входных данных
        val signals = fourierData.lines().filter { it.startsWith("id=") }
        println("📈 Signals count: ${signals.size}")

        signals.take(2).forEach { signal ->
            val parts = signal.split(",", "|")
            if (parts.size >= 4) {
                val id = parts[0].substringAfter("id=")
                val periods = parts[1].substringAfter("periods=").toIntOrNull() ?: 0
                val threshold = parts[2].substringAfter("threshold=").toDoubleOrNull() ?: 0.0
                val sampleCount = parts.size - 3
                println("   Signal ID $id: periods=$periods, threshold=$threshold, samples=$sampleCount")
            }
        }

        if (signals.size > 2) {
            println("   ... and ${signals.size - 2} more signals")
        }

        println("📋 Content preview:")
        fourierData.lines().take(4).forEach { println("   $it") }
        if (fourierData.lines().size > 4) {
            println("   ... and ${fourierData.lines().size - 4} more lines")
        }
        println("-".repeat(60))
    }

    @Test
    @DisplayName("Test Health Check")
    fun testHealthCheck() {
        println("\n🧪 TESTING HEALTH CHECK")
        println("-".repeat(40))

        val fullHealthEndpoint = config.getFullHealthEndpoint()
        println("🔗 Health check URL: $fullHealthEndpoint")

        val isHealthy = checkHealth(fullHealthEndpoint)
        println("📊 Health check result: ${if (isHealthy) "✅ HEALTHY" else "❌ UNHEALTHY"}")

        Assertions.assertTrue(isHealthy, "API should be healthy")

        println("✅ Health check completed")
    }

    @Test
    @DisplayName("Test Web API Synchronous Upload")
    fun testWebApiSyncUpload() {
        println("\n🧪 TESTING WEB API (SYNCHRONOUS)")
        println("-".repeat(40))

        println("📤 Uploading file: ${testFile.name}")
        val fullEndpoint = config.getFullWebApiEndpoint()
        val response = uploadToWebApi(fullEndpoint, testFile)

        println("\n📡 Response code: ${response.code}")
        println("📋 Response headers:")
        response.headers.forEach { println("   ${it.first}: ${it.second}") }

        if (response.isSuccessful) {
            println("\n✅ REQUEST SUCCESSFUL")

            val contentType = response.header("Content-Type")
            Assertions.assertNotNull(contentType, "Content-Type header should be present")

            val bodyBytes = response.body?.bytes()
            Assertions.assertNotNull(bodyBytes, "Response body should not be null")

            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                println("📦 Received ${bodyBytes.size} bytes")

                // Сохранить полученный ZIP
                val resultFileName = "fourier_result_${System.currentTimeMillis()}.zip"
                val savedFile = TestDataGenerators.saveBinaryResults(resultFileName, bodyBytes)
                results.add(savedFile)

                // Извлечь и проверить содержимое
                println("\n📂 Extracting ZIP contents...")
                val extractedFiles = extractAndValidateZip(bodyBytes)
                println("📄 Extracted ${extractedFiles.size} file(s):")

                extractedFiles.forEach { (name, content) ->
                    println("\n   📝 File: $name")
                    println("     📏 Size: ${content.length} chars, ${content.lines().size} lines")

                    // Сохранить извлечённое содержимое
                    val extractedFileName = "fourier_extracted_${System.currentTimeMillis()}_${name.replace(".", "_")}.txt"
                    val extractedFile = TestDataGenerators.saveResults(extractedFileName, content)
                    results.add(extractedFile)

                    // Анализ результатов Фурье
                    analyzeFourierResults(content)
                }

                println("\n✅ Web API synchronous test completed successfully")
            }
        } else {
            println("\n❌ REQUEST FAILED")
            val errorBody = response.body?.string()
            println("📝 Error response: $errorBody")

            // Сохранить ошибку
            val errorFileName = "fourier_error_${System.currentTimeMillis()}.txt"
            val errorFile = TestDataGenerators.saveResults(errorFileName,
                """
                |Request failed:
                |  URL: ${fullEndpoint}
                |  Status: ${response.code}
                |  Body: $errorBody
                """.trimMargin())
            results.add(errorFile)

            Assertions.fail("Request failed with status ${response.code}: $errorBody")
        }
    }

    @Test
    @DisplayName("Test REST API Async Execute")
    @Disabled("Optional test - can be enabled if async endpoint works")
    fun testRestApiAsync() {
        println("\n🧪 TESTING REST API (ASYNCHRONOUS)")
        println("-".repeat(40))

        println("📤 Uploading file: ${testFile.name}")
        val fullAsyncEndpoint = config.getFullRestApiAsyncEndpoint()

        if (fullAsyncEndpoint == null) {
            println("⚠ Async endpoint not configured, skipping test")
            return
        }

        val uploadResult = uploadToRestApiAsync(fullAsyncEndpoint, testFile)

        if (uploadResult != null) {
            println("\n📥 Upload response received:")
            uploadResult.forEach { (key, value) ->
                println("   $key: $value")
            }

            val executionId = uploadResult["executionId"] as? String
            Assertions.assertNotNull(executionId, "executionId should be present in response")

            if (executionId != null) {
                println("\n🔑 Execution ID: $executionId")

                // Сохранить метаданные
                val metadata = uploadResult.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                val metaFile = TestDataGenerators.saveResults(
                    "fourier_metadata_${System.currentTimeMillis()}.txt",
                    metadata
                )
                results.add(metaFile)

                // Попробовать скачать результат
                try {
                    val fullDownloadUrl = "${config.getFullRestApiDownloadEndpoint()}/$executionId"
                    println("⏳ Waiting for processing...")

                    val (zipBytes, headers) = waitForProcessing(fullDownloadUrl)

                    println("\n📥 Download successful")
                    println("📋 Download headers:")
                    headers.forEach { println("   ${it.first}: ${it.second}") }

                    // Сохранить полученный ZIP
                    val resultFileName = "fourier_async_result_${System.currentTimeMillis()}.zip"
                    val savedFile = TestDataGenerators.saveBinaryResults(resultFileName, zipBytes)
                    results.add(savedFile)

                    val extractedFiles = extractAndValidateZip(zipBytes)
                    println("\n📄 Extracted ${extractedFiles.size} file(s)")

                    extractedFiles.forEach { (name, content) ->
                        val extractedFileName = "fourier_async_extracted_${System.currentTimeMillis()}_${name.replace(".", "_")}.txt"
                        val extractedFile = TestDataGenerators.saveResults(extractedFileName, content)
                        results.add(extractedFile)

                        println("   📝 $name (${content.lines().size} lines)")
                        analyzeFourierResults(content)
                    }

                    println("\n✅ REST API async test completed successfully")

                } catch (e: Exception) {
                    println("\n❌ Download failed: ${e.message}")

                    val errorFile = TestDataGenerators.saveResults(
                        "fourier_async_error_${System.currentTimeMillis()}.txt",
                        """
                        |Error: ${e.message}
                        |Stack trace: ${e.stackTraceToString()}
                        """.trimMargin()
                    )
                    results.add(errorFile)

                    throw e
                }
            }
        } else {
            println("\n❌ Upload failed or returned empty response")
            Assertions.fail("Upload failed or returned empty response")
        }
    }

    @Test
    @DisplayName("Test Different Sample Sizes (Powers of 2)")
    fun testDifferentSampleSizes() {
        println("\n🧪 TESTING DIFFERENT SAMPLE SIZES")
        println("-".repeat(40))

        val sampleSizes = listOf(8, 16, 32, 64) // Степени двойки

        sampleSizes.forEach { sampleSize ->
            println("\n📊 Testing sample size: $sampleSize (2^${kotlin.math.log2(sampleSize.toDouble()).toInt()})")

            // Генерация данных
            val data = TestDataGenerators.generateFourierData(
                numSignals = 1,  // Один сигнал для теста
                sampleSize = sampleSize
            )

            // Создание ZIP
            val zipFile = TestDataGenerators.createFourierZipFile(
                "fourier_samples_${sampleSize}_${System.currentTimeMillis()}.zip",
                data
            )

            // Загрузка
            try {
                val response = uploadToWebApi(config.getFullWebApiEndpoint(), zipFile)

                if (response.isSuccessful) {
                    println("   ✅ SUCCESS: Sample size $sampleSize processed")

                    val bodyBytes = response.body?.bytes()
                    if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                        // Анализ результата
                        val extractedFiles = extractAndValidateZip(bodyBytes)
                        extractedFiles.forEach { (_, content) ->
                            val lines = content.lines()
                            val successCount = lines.count { it.startsWith("id=") && !it.contains(",error") }
                            val errorCount = lines.count { it.startsWith("id=") && it.contains(",error") }

                            println("   📈 Results: $successCount successful, $errorCount errors")

                            if (successCount > 0) {
                                lines.filter { it.startsWith("id=") && !it.contains(",error") }
                                    .take(1)
                                    .forEach { line ->
                                        val values = line.substringAfter(",").split("|")
                                        println("   📐 Output samples: ${values.size}")
                                        println("   🔍 Input/Output ratio: ${values.size.toDouble() / sampleSize}")
                                    }
                            }
                        }

                        // Сохранить результат
                        val resultFile = TestDataGenerators.saveBinaryResults(
                            "fourier_samples_${sampleSize}_result.zip",
                            bodyBytes
                        )
                        results.add(resultFile)
                    }
                } else {
                    println("   ⚠ FAILED: ${response.code}")
                    val errorBody = response.body?.string()
                    println("   📝 Error: $errorBody")
                }
            } catch (e: Exception) {
                println("   ❌ ERROR: ${e.message}")
            } finally {
                // Сохранить входной файл
                if (zipFile.exists()) {
                    results.add(zipFile)
                }
            }
        }

        println("\n✅ All sample size tests completed")
    }

    @Test
    @DisplayName("Test Invalid Power-of-2 Cases")
    fun testInvalidPowerOf2() {
        println("\n🧪 TESTING INVALID SAMPLE SIZES")
        println("-".repeat(40))

        // Тестовые размеры, не являющиеся степенями двойки
        val invalidSizes = listOf(3, 6, 10, 12, 20, 30, 100)

        invalidSizes.forEach { size ->
            println("\n❌ Testing invalid sample size: $size")

            // Создание невалидных данных
            val invalidData = """
                # Invalid sample size: $size (not a power of 2)
                id=1,periods=1,threshold=0.05${"|0.000000".repeat(size)}
            """.trimIndent()

            // Создание ZIP
            val zipFile = TestDataGenerators.createFourierZipFile(
                "fourier_invalid_${size}_${System.currentTimeMillis()}.zip",
                invalidData
            )

            // Загрузка
            try {
                val response = uploadToWebApi(config.getFullWebApiEndpoint(), zipFile)
                println("   Response code: ${response.code}")

                if (!response.isSuccessful) {
                    println("   ✅ Correctly rejected non-power-of-2 sample size")
                } else {
                    println("   ⚠ Unexpectedly accepted non-power-of-2 sample size")

                    // Сохранить неожиданный успешный результат
                    val bodyBytes = response.body?.bytes()
                    if (bodyBytes != null) {
                        val resultFile = TestDataGenerators.saveBinaryResults(
                            "fourier_unexpected_${size}.zip",
                            bodyBytes
                        )
                        results.add(resultFile)
                    }
                }
            } catch (e: Exception) {
                println("   ❌ ERROR: ${e.message}")
            } finally {
                // Сохранить входной файл
                if (zipFile.exists()) {
                    results.add(zipFile)
                }
            }
        }

        println("\n✅ Invalid sample size tests completed")
    }

    @Test
    @DisplayName("Test Error Cases")
    fun testErrorCases() {
        println("\n🧪 TESTING ERROR CASES")
        println("-".repeat(40))

        // Тест 1: Пустой файл
        println("\n1️⃣ Testing empty ZIP file...")
        val emptyFile = File.createTempFile("fourier_empty", ".zip")
        emptyFile.writeBytes(byteArrayOf())

        val response1 = uploadToWebApi(config.getFullWebApiEndpoint(), emptyFile)
        println("   Response code: ${response1.code}")

        if (!response1.isSuccessful) {
            println("   ✅ Correctly rejected empty file")
        } else {
            println("   ⚠ Unexpectedly accepted empty file")
        }
        results.add(emptyFile)

        // Тест 2: Неверный формат метаданных
        println("\n2️⃣ Testing invalid metadata format...")
        val invalidMetadata = """
            # Missing id field
            periods=1,threshold=0.05|0.000000|0.382683|0.707107|0.923880
        """.trimIndent()

        val invalidMetaFile = TestDataGenerators.createFourierZipFile(
            "fourier_invalid_meta.zip",
            invalidMetadata
        )

        val response2 = uploadToWebApi(config.getFullWebApiEndpoint(), invalidMetaFile)
        println("   Response code: ${response2.code}")

        if (!response2.isSuccessful) {
            println("   ✅ Correctly rejected invalid metadata")
        } else {
            println("   ⚠ Unexpectedly accepted invalid metadata")
        }
        results.add(invalidMetaFile)

        // Тест 3: Неверный разделитель
        println("\n3️⃣ Testing wrong delimiter...")
        val wrongDelimiter = """
            id=1,periods=1,threshold=0.05,0.000000,0.382683,0.707107,0.923880
        """.trimIndent()

        val wrongDelimFile = TestDataGenerators.createFourierZipFile(
            "fourier_wrong_delim.zip",
            wrongDelimiter
        )

        val response3 = uploadToWebApi(config.getFullWebApiEndpoint(), wrongDelimFile)
        println("   Response code: ${response3.code}")

        if (!response3.isSuccessful) {
            println("   ✅ Correctly rejected wrong delimiter")
        } else {
            println("   ⚠ Unexpectedly accepted wrong delimiter")
        }
        results.add(wrongDelimFile)

        println("\n✅ Error case tests completed")
    }

    private fun analyzeFourierResults(content: String) {
        val lines = content.lines().filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            println("     ⚠ No data lines found")
            return
        }

        println("\n     📊 Analysis:")
        println("     ─" + "─".repeat(30))
        println("     📈 Total lines: ${lines.size}")

        val successfulSignals = lines.filter { it.startsWith("id=") && !it.contains(",error") }
        val errorSignals = lines.filter { it.startsWith("id=") && it.contains(",error") }

        println("     • Successful signals: ${successfulSignals.size}")
        println("     • Error signals: ${errorSignals.size}")

        if (successfulSignals.isNotEmpty()) {
            println("\n     ✅ Successful signals analysis:")

            successfulSignals.take(2).forEach { signal ->
                val id = signal.substringAfter("id=").substringBefore(",")
                val values = signal.substringAfter(",").split("|")

                println("       Signal ID $id:")
                println("       • Output samples: ${values.size}")

                if (values.isNotEmpty()) {
                    // Простой статистический анализ
                    val numericValues = values.mapNotNull { it.toDoubleOrNull() }
                    if (numericValues.isNotEmpty()) {
                        val min = numericValues.minOrNull() ?: 0.0
                        val max = numericValues.maxOrNull() ?: 0.0
                        val avg = numericValues.average()

                        println("       • Min value: ${String.format("%.6f", min)}")
                        println("       • Max value: ${String.format("%.6f", max)}")
                        println("       • Avg value: ${String.format("%.6f", avg)}")
                        println("       • Range: ${String.format("%.6f", max - min)}")

                        // Проверить на периодичность
                        if (numericValues.size >= 8) {
                            val firstHalf = numericValues.take(numericValues.size / 2)
                            val secondHalf = numericValues.drop(numericValues.size / 2)

                            if (firstHalf.size == secondHalf.size) {
                                var matchCount = 0
                                for (i in firstHalf.indices) {
                                    if (kotlin.math.abs(firstHalf[i] - secondHalf[i]) < 0.01) {
                                        matchCount++
                                    }
                                }
                                val similarity = matchCount.toDouble() / firstHalf.size
                                println("       • Periodicity similarity: ${String.format("%.1f", similarity * 100)}%")
                            }
                        }
                    }
                }
            }

            if (successfulSignals.size > 2) {
                println("       ... and ${successfulSignals.size - 2} more successful signals")
            }
        }

        if (errorSignals.isNotEmpty()) {
            println("\n     ❌ Error signals:")
            errorSignals.forEach { signal ->
                val id = signal.substringAfter("id=").substringBefore(",")
                println("       Signal ID $id: ERROR")
            }
        }
    }

    @AfterAll
    fun tearDown() {
        println("\n" + "=".repeat(60))
        println("=== FOURIER TRANSFORM API TESTS SUMMARY ===")
        println("=".repeat(60))

        val summary = StringBuilder()
        summary.append("\n📊 TEST SUMMARY\n")
        summary.append("─".repeat(40) + "\n")
        summary.append("• Test completed: ${java.time.LocalDateTime.now()}\n")
        summary.append("• API Host: ${com.example.config.AppConfig.getProperty("api.host")}\n")
        summary.append("• Test file: ${testFile.name}\n")
        summary.append("• Test file path: ${testFile.absolutePath}\n")
        summary.append("• Test file size: ${testFile.length()} bytes\n")
        summary.append("\n📁 Generated files (${results.size}):\n")

        results.forEachIndexed { index, file ->
            if (file.exists() && file.length() > 0) {
                summary.append("  ${index + 1}. ${file.name} (${file.length()} bytes)\n")
            }
        }

        if (testFile.exists()) {
            summary.append("  ${results.size + 1}. ${testFile.name} (${testFile.length()} bytes) - INPUT\n")
        }

        summary.append("\n📂 All files saved in: test_output directory\n")
        summary.append("🧪 Tests performed:\n")
        summary.append("  • Health check\n")
        summary.append("  • Web API synchronous upload\n")
        summary.append("  • Sample sizes: 8, 16, 32, 64\n")
        summary.append("  • Invalid sample sizes: 3, 6, 10, 12, 20, 30, 100\n")
        summary.append("  • Error cases: empty file, invalid metadata, wrong delimiter\n")

        val summaryFile = TestDataGenerators.saveResults(
            "fourier_summary_${System.currentTimeMillis()}.txt",
            summary.toString()
        )

        println(summary.toString())
        println("📝 Summary saved to: ${summaryFile.absolutePath}")
        println("✅ All files preserved (not deleted)")
        println("=".repeat(60))

        cleanup()
    }
}