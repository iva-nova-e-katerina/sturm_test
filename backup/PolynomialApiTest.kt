package org.example.api

import com.example.generators.TestDataGenerators
import org.junit.jupiter.api.*
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PolynomialApiTest : ApiTestBase() {

    private val config = ApiConfig(
        name = "Polynomial Solver API",
        webApiEndpoint = "api.polynomial.upload",
        restApiAsyncEndpoint = "api.polynomial.async-upload",
        restApiDownloadEndpoint = "api.polynomial.download",
        healthEndpoint = "api.polynomial.health"
    )

    private lateinit var testFile: File
    private val results = mutableListOf<File>()

    @BeforeAll
    fun setUp() {
        println("\n" + "=".repeat(60))
        println("=== SETTING UP POLYNOMIAL SOLVER API TESTS ===")
        println("=".repeat(60))

        // Очистить старые файлы (старше 1 дня)
        TestDataGenerators.cleanupOldFiles(1)

        val polynomialData = TestDataGenerators.generatePolynomialData(
            numPolynomials = 5,
            maxDegree = 4
        )

        val fileName = "polynomials_${System.currentTimeMillis()}.zip"
        testFile = TestDataGenerators.createPolynomialZipFile(fileName, polynomialData)

        // Также сохраним сырые данные для сравнения
        val rawDataFile = TestDataGenerators.saveResults(
            "polynomials_input_${System.currentTimeMillis()}.txt",
            polynomialData
        )
        results.add(rawDataFile)

        println("\n📁 Test file: ${testFile.absolutePath}")
        println("📊 File size: ${testFile.length()} bytes")
        println("📈 Content preview:")
        polynomialData.lines().take(5).forEach { println("   $it") }
        if (polynomialData.lines().size > 5) {
            println("   ... and ${polynomialData.lines().size - 5} more lines")
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

            val isZipResponse = contentType?.contains("application/octet-stream") == true ||
                    contentType?.contains("application/zip") == true

            Assertions.assertTrue(isZipResponse,
                "Content-Type should be octet-stream or zip, got: $contentType")

            val bodyBytes = response.body?.bytes()
            Assertions.assertNotNull(bodyBytes, "Response body should not be null")

            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                println("📦 Received ${bodyBytes.size} bytes")

                // Сохранить полученный ZIP
                val resultFileName = "polynomials_result_${System.currentTimeMillis()}.zip"
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
                    val extractedFileName = "polynomials_extracted_${System.currentTimeMillis()}_${name.replace(".", "_")}.txt"
                    val extractedFile = TestDataGenerators.saveResults(extractedFileName, content)
                    results.add(extractedFile)

                    // Простой анализ результата
                    analyzePolynomialResults(content)
                }

                println("\n✅ Web API synchronous test completed successfully")
            }
        } else {
            println("\n❌ REQUEST FAILED")
            val errorBody = response.body?.string()
            println("📝 Error response: $errorBody")

            // Сохранить ошибку
            val errorFileName = "polynomials_error_${System.currentTimeMillis()}.txt"
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
    @DisplayName("Test REST API Async Upload")
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

            val taskId = uploadResult["taskId"] as? String
            Assertions.assertNotNull(taskId, "taskId should be present in response")

            if (taskId != null) {
                println("\n🔑 Task ID: $taskId")

                // Сохранить метаданные
                val metadata = uploadResult.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                val metaFile = TestDataGenerators.saveResults(
                    "polynomials_metadata_${System.currentTimeMillis()}.txt",
                    metadata
                )
                results.add(metaFile)

                // Попробовать скачать результат
                try {
                    val fullDownloadUrl = "${config.getFullRestApiDownloadEndpoint()}/$taskId"
                    println("⏳ Waiting for processing...")

                    val (zipBytes, headers) = waitForProcessing(fullDownloadUrl)

                    println("\n📥 Download successful")
                    println("📋 Download headers:")
                    headers.forEach { println("   ${it.first}: ${it.second}") }

                    // Сохранить полученный ZIP
                    val resultFileName = "polynomials_async_result_${System.currentTimeMillis()}.zip"
                    val savedFile = TestDataGenerators.saveBinaryResults(resultFileName, zipBytes)
                    results.add(savedFile)

                    val extractedFiles = extractAndValidateZip(zipBytes)
                    println("\n📄 Extracted ${extractedFiles.size} file(s)")

                    extractedFiles.forEach { (name, content) ->
                        val extractedFileName = "polynomials_async_extracted_${System.currentTimeMillis()}_${name.replace(".", "_")}.txt"
                        val extractedFile = TestDataGenerators.saveResults(extractedFileName, content)
                        results.add(extractedFile)

                        println("   📝 $name (${content.lines().size} lines)")
                        analyzePolynomialResults(content)
                    }

                    println("\n✅ REST API async test completed successfully")

                } catch (e: Exception) {
                    println("\n❌ Download failed: ${e.message}")

                    val errorFile = TestDataGenerators.saveResults(
                        "polynomials_async_error_${System.currentTimeMillis()}.txt",
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

    private fun analyzePolynomialResults(content: String) {
        val lines = content.lines().filter { it.isNotBlank() && !it.startsWith("#") }

        if (lines.isEmpty()) {
            println("     ⚠ No data lines found")
            return
        }

        println("\n     📊 Analysis:")
        println("     ─" + "─".repeat(30))
        println("     📈 Total polynomials: ${lines.size}")

        var totalRoots = 0
        var polynomialsWithRoots = 0

        lines.forEachIndexed { index, line ->
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val id = parts[0].substringAfter("ID=")
                val rootCount = parts[1].toIntOrNull() ?: 0
                totalRoots += rootCount

                if (rootCount > 0) {
                    polynomialsWithRoots++
                    if (index < 3) { // Показываем только первые 3 полинома для краткости
                        println("     • Polynomial ID $id: $rootCount real root(s)")
                        if (rootCount > 0) {
                            val rootPairs = parts.drop(2).chunked(2)
                            rootPairs.take(2).forEachIndexed { i, pair ->
                                if (pair.size == 2) {
                                    val (left, right) = pair
                                    println("       Root ${i + 1}: [$left, $right]")
                                }
                            }
                            if (rootCount > 2) {
                                println("       ... and ${rootCount - 2} more root(s)")
                            }
                        }
                    }
                }
            }
        }

        println()
        println("     📊 Summary:")
        println("     • Polynomials with roots: $polynomialsWithRoots/${lines.size}")
        println("     • Total roots found: $totalRoots")
        if (lines.size > 0) {
            println("     • Average roots per polynomial: %.2f".format(totalRoots.toDouble() / lines.size))
        }
    }

    @Test
    @DisplayName("Test Invalid Input Cases")
    fun testInvalidInput() {
        println("\n🧪 TESTING INVALID INPUT CASES")
        println("-".repeat(40))

        // Тест 1: Пустой ZIP файл
        println("\n1️⃣ Testing empty ZIP file...")
        val emptyFile = File.createTempFile("empty", ".zip")
        emptyFile.writeBytes(byteArrayOf())

        val response1 = uploadToWebApi(config.getFullWebApiEndpoint(), emptyFile)
        println("   Response code: ${response1.code}")

        if (!response1.isSuccessful) {
            println("   ✅ Correctly rejected empty file")
        } else {
            println("   ⚠ Unexpectedly accepted empty file")
        }
        emptyFile.delete()

        // Тест 2: ZIP с несколькими файлами
        println("\n2️⃣ Testing ZIP with multiple files...")
        val multiFile = File.createTempFile("multi", ".zip")
        multiFile.outputStream().use { os ->
            java.util.zip.ZipOutputStream(os).use { zos ->
                repeat(3) { i ->
                    zos.putNextEntry(java.util.zip.ZipEntry("file$i.txt"))
                    zos.write("test content $i".toByteArray())
                    zos.closeEntry()
                }
            }
        }

        val response2 = uploadToWebApi(config.getFullWebApiEndpoint(), multiFile)
        println("   Response code: ${response2.code}")

        if (!response2.isSuccessful) {
            println("   ✅ Correctly rejected ZIP with multiple files")
        } else {
            println("   ⚠ Unexpectedly accepted ZIP with multiple files")
        }
        multiFile.delete()

        // Тест 3: Неверный формат (простой текст)
        println("\n3️⃣ Testing invalid format (plain text)...")
        val textFile = File.createTempFile("invalid", ".txt")
        textFile.writeText("This is not a polynomial file")

        val textZipFile = File.createTempFile("invalid_text", ".zip")
        textZipFile.outputStream().use { os ->
            java.util.zip.ZipOutputStream(os).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("wrong.txt"))
                zos.write(textFile.readBytes())
                zos.closeEntry()
            }
        }

        val response3 = uploadToWebApi(config.getFullWebApiEndpoint(), textZipFile)
        println("   Response code: ${response3.code}")

        if (!response3.isSuccessful) {
            println("   ✅ Correctly rejected invalid format")
        } else {
            println("   ⚠ Unexpectedly accepted invalid format")
        }

        textFile.delete()
        textZipFile.delete()

        println("\n✅ Invalid input tests completed")
    }

    @AfterAll
    fun tearDown() {
        println("\n" + "=".repeat(60))
        println("=== POLYNOMIAL SOLVER API TESTS SUMMARY ===")
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

        val summaryFile = TestDataGenerators.saveResults(
            "polynomials_summary_${System.currentTimeMillis()}.txt",
            summary.toString()
        )

        println(summary.toString())
        println("📝 Summary saved to: ${summaryFile.absolutePath}")
        println("✅ All files preserved (not deleted)")
        println("=".repeat(60))

        cleanup()
    }
}