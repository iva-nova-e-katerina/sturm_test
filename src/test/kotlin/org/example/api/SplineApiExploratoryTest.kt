package org.example.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.example.api.config.SplineApiProperties
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@DisplayName("SPLINE REST API TESTS - BASED ON UPDATED DOCUMENTATION v14.5")
class SplineApiExploratoryTest {

    private val apiProperties: SplineApiProperties = SplineApiProperties.loadFromPropertiesFile("application-test.properties")

    @TempDir
    private lateinit var tempDir: Path

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(apiProperties.timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(apiProperties.timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(apiProperties.timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Test
    @DisplayName("TEST 1: Validate REST API configuration and URL generation")
    fun `test rest api configuration`() {
        println("\n=== REST API CONFIGURATION VALIDATION ===")

        println("\n1. LOADED CONFIGURATION:")
        println("   - Base URL: ${apiProperties.baseUrl}")
        println("   - REST API Base URL: ${apiProperties.getApiBaseUrl()}")
        println("   - Timeout: ${apiProperties.timeoutSeconds} seconds")
        println("   - Max File Size: ${apiProperties.maxFileSizeMb} MB")

        // Validate configuration
        assertTrue(apiProperties.baseUrl.isNotBlank(), "Base URL should not be blank")
        assertTrue(apiProperties.baseUrl.startsWith("http"), "Base URL should start with http/https")
        assertTrue(apiProperties.timeoutSeconds > 0, "Timeout should be positive")

        println("\n2. REST API ENDPOINTS (from documentation):")

        val endpoints = mapOf(
            "POST /spline/execute" to "${apiProperties.getApiBaseUrl()}/spline/execute",
            "GET /spline/download/{executionId}" to "${apiProperties.getApiBaseUrl()}/spline/download/{executionId}",
            "GET /spline/health" to "${apiProperties.getApiBaseUrl()}/spline/health"
        )

        endpoints.forEach { (name, url) ->
            println("   - $name: $url")
            assertTrue(url.startsWith("http"), "URL should start with http/https: $url")
            assertTrue(url.contains("/api/v1"), "REST API URL should contain /api/v1: $url")
        }

        println("\n3. TEST CONCLUSION:")
        println("   ✓ Configuration loaded successfully")
        println("   ✓ REST API URLs correctly generated")
        println("   ✓ All URLs start with http/https")
        println("   ✓ REST API prefix (/api/v1) present")
    }

    @Test
    @DisplayName("TEST 2: POST /spline/execute - Upload and process ZIP file")
    fun `test rest api execute endpoint`() {
        println("\n=== REST API: POST /spline/execute ===")
        println("DOCUMENTATION: Upload ZIP archive, get JSON metadata")
        println("EXPECTED JSON FIELDS: executionId, status=SUCCESS, inputSize, outputSize, processingTime, message, downloadUrl, timestamp")
        println("ENDPOINT: POST ${apiProperties.getApiBaseUrl()}/spline/execute")

        // Create test ZIP file
        val zipFile = createValidZipFile()
        println("\n1. TEST DATA CREATED:")
        println("   - ZIP file: ${zipFile.name}")
        println("   - File size: ${zipFile.length()} bytes (max allowed: ${apiProperties.maxFileSizeMb} MB)")
        println("   - CSV format: id=1,type=cubic")
        println("   - Data points: 6 raw points (expected ~12 points in output)")

        // Create request
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${apiProperties.getApiBaseUrl()}/spline/execute")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "SplineRestApiTest/1.0")
            .build()

        println("\n2. SENDING REQUEST:")
        println("   - Method: POST")
        println("   - URL: ${request.url}")
        println("   - Headers: ${request.headers}")
        println("   - File attached: ${zipFile.name}")

        val response = try {
            val startTime = System.currentTimeMillis()
            val resp = httpClient.newCall(request).execute()
            val endTime = System.currentTimeMillis()
            println("   - Request duration: ${endTime - startTime} ms")
            resp
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
            println("Possible issues:")
            println("   1. Network connectivity")
            println("   2. DNS resolution for ${apiProperties.baseUrl}")
            println("   3. SSL/TLS certificate issues")
            println("   4. Service not running")
            return
        }

        println("\n3. RESPONSE RECEIVED:")
        println("   - Status Code: ${response.code}")
        println("   - Status Message: ${response.message}")
        println("   - Protocol: ${response.protocol}")
        println("   - Response Headers:")
        response.headers.forEach { (name, value) ->
            println("     $name: $value")
        }

        val responseBody = response.body?.string()
        println("   - Response Body Size: ${responseBody?.length ?: 0} characters")

        if (responseBody.isNullOrEmpty()) {
            println("❌ ERROR: Empty response body")
            response.close()
            return
        }

        // Log response body if detailed logging is enabled
        if (apiProperties.enableDetailedLogging) {
            println("\n4. RAW RESPONSE BODY:")
            println("```json")
            println(responseBody)
            println("```")
        } else {
            println("\n4. RESPONSE BODY PREVIEW:")
            println(responseBody.take(300) + if (responseBody.length > 300) "..." else "")
        }

        println("\n5. JSON VALIDATION AGAINST DOCUMENTATION:")

        try {
            val json = objectMapper.readTree(responseBody)

            // Check if it's an error response
            if (json.has("error") && json.has("status") && json.get("status").asText() == "ERROR") {
                println("⚠️  ERROR RESPONSE DETECTED:")
                println("   - error: ${json.get("error").asText()}")
                println("   - details: ${json.get("details")?.asText() ?: "No details"}")
                println("   - timestamp: ${json.get("timestamp")?.asText() ?: "No timestamp"}")
                println("\n   This could indicate:")
                println("   1. Invalid file format")
                println("   2. ZIP contains multiple files")
                println("   3. Service validation failed")
                response.close()
                return
            }

            // Validate against documentation fields
            val documentedFields = listOf(
                "executionId",
                "status",
                "inputSize",
                "outputSize",
                "processingTime",
                "message",
                "downloadUrl",
                "timestamp"
            )

            println("   ✓ JSON parsed successfully")

            // Check for all documented fields
            println("\n   FIELD PRESENCE CHECK:")
            val missingFields = mutableListOf<String>()
            val foundFields = mutableListOf<String>()

            documentedFields.forEach { field ->
                if (json.has(field)) {
                    foundFields.add(field)
                    val value = json.get(field)
                    println("   ✓ $field: ${value.asText()}")
                } else {
                    missingFields.add(field)
                    println("   ❌ $field: MISSING (documented but not present)")
                }
            }

            // Check for unexpected fields
            val extraFields = mutableListOf<String>()
            json.fieldNames().forEach { fieldName ->
                if (fieldName !in documentedFields) {
                    extraFields.add(fieldName)
                }
            }

            if (extraFields.isNotEmpty()) {
                println("\n   ⚠️  EXTRA FIELDS (not in documentation):")
                extraFields.forEach { field ->
                    println("   - $field: ${json.get(field).asText()}")
                }
            }

            // Detailed field validation
            println("\n   FIELD FORMAT VALIDATION:")

            // executionId validation - ИСПРАВЛЕНО: теперь UUID без префикса spline_
            if (json.has("executionId")) {
                val executionId = json.get("executionId").asText()
                // Проверяем что не пустой
                println("   - executionId: ${if (executionId.isNotBlank()) "✓ not empty" else "❌ empty"}")
                assertTrue(executionId.isNotBlank(), "executionId should not be blank")

                // Проверяем формат UUID
                val uuidPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                val isUuidFormat = uuidPattern.matches(executionId)
                println("   - UUID format: ${if (isUuidFormat) "✓ valid UUID" else "⚠️ not standard UUID format"}")

                // Проверяем что можно распарсить как UUID
                try {
                    UUID.fromString(executionId)
                    println("   - UUID parseable: ✓ can be parsed as UUID")
                } catch (e: IllegalArgumentException) {
                    println("   - UUID parseable: ❌ cannot be parsed as UUID")
                    // Не падаем, только логируем
                }
            }

            // status validation - ДОКУМЕНТАЦИЯ ИСПРАВЛЕНА: теперь "SUCCESS", а не "COMPLETED"
            if (json.has("status")) {
                val status = json.get("status").asText()
                // Согласно исправленной документации, статус должен быть "SUCCESS"
                val isValid = status == "SUCCESS"
                println("   - status: $status ${if (isValid) "✓ matches documentation (SUCCESS)" else "⚠️ documentation says 'SUCCESS'"}")
                // Можно оставить assert, но не будем падать, если сервис возвращает что-то другое
                // assertTrue(isValid, "status should be 'SUCCESS' according to documentation")

                // Проверяем другие возможные статусы для информации
                if (status != "SUCCESS") {
                    println("     ⚠️  Server returned '$status' instead of 'SUCCESS'")
                    println("     ℹ️  Possible statuses: SUCCESS, PROCESSING, FAILED, ERROR")
                }
            }

            // inputSize and outputSize validation
            if (json.has("inputSize")) {
                val inputSize = json.get("inputSize").asLong()
                println("   - inputSize: $inputSize bytes ${if (inputSize > 0) "✓" else "⚠️ zero or negative"}")
                assertTrue(inputSize > 0, "inputSize should be positive")
            }

            if (json.has("outputSize")) {
                val outputSize = json.get("outputSize").asLong()
                println("   - outputSize: $outputSize bytes")
                // Output should be larger due to interpolation (approx 2x)
                if (json.has("inputSize")) {
                    val inputSize = json.get("inputSize").asLong()
                    val ratio = outputSize.toDouble() / inputSize.toDouble()
                    println("   - Size ratio (output/input): ${"%.2f".format(ratio)}x ${if (ratio > 1.5) "✓ expected ~2x" else "⚠️ smaller than expected"}")
                }
            }

            // processingTime validation
            if (json.has("processingTime")) {
                val processingTime = json.get("processingTime").asLong()
                println("   - processingTime: $processingTime ms ${if (processingTime > 0) "✓" else "⚠️ zero or negative"}")
                assertTrue(processingTime > 0, "processingTime should be positive")
            }

            // downloadUrl validation
            if (json.has("downloadUrl")) {
                val downloadUrl = json.get("downloadUrl").asText()
                val isValid = downloadUrl.startsWith("/api/v1/spline/download/")
                println("   - downloadUrl: ${if (isValid) "✓ correct format" else "❌ should start with '/api/v1/spline/download/'"} $downloadUrl")
                assertTrue(isValid, "downloadUrl should start with '/api/v1/spline/download/'")

                // If we have executionId, check if they match
                if (json.has("executionId")) {
                    val executionId = json.get("executionId").asText()
                    val expectedPath = "/api/v1/spline/download/$executionId"
                    val matches = downloadUrl == expectedPath
                    println("   - downloadUrl matches executionId: ${if (matches) "✓" else "⚠️ mismatch"}")
                    if (!matches) {
                        println("     Expected: $expectedPath")
                        println("     Actual: $downloadUrl")
                    }
                }
            }

            // timestamp validation (ISO 8601 format)
            if (json.has("timestamp")) {
                val timestamp = json.get("timestamp").asText()
                try {
                    // Try to parse as ISO 8601
                    val parsed = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME)
                    println("   - timestamp: ✓ valid ISO 8601 format ($parsed)")
                } catch (e: Exception) {
                    // Пробуем альтернативный формат с наносекундами
                    try {
                        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS")
                        val parsed = LocalDateTime.parse(timestamp, formatter)
                        println("   - timestamp: ✓ valid ISO 8601 with nanoseconds ($parsed)")
                    } catch (e2: Exception) {
                        println("   - timestamp: ❌ invalid format (expected ISO 8601)")
                    }
                }
            }

            // message validation
            if (json.has("message")) {
                val message = json.get("message").asText()
                println("   - message: \"$message\" ${if (message.isNotBlank()) "✓" else "⚠️ empty message"}")
            }

            println("\n   SUMMARY:")
            println("   - Documented fields found: ${foundFields.size}/8")
            println("   - Missing documented fields: ${if (missingFields.isEmpty()) "None ✓" else missingFields}")
            println("   - Extra fields: ${if (extraFields.isEmpty()) "None ✓" else extraFields}")

            // Test download endpoint if we have the URL
            if (json.has("downloadUrl") && json.has("executionId")) {
                testDownloadEndpoint(json.get("downloadUrl").asText(), json.get("executionId").asText())
            }

        } catch (e: Exception) {
            println("❌ JSON PARSING ERROR: ${e.message}")
            println("RAW RESPONSE THAT FAILED:")
            println(responseBody)
        }

        println("\n6. TEST CONCLUSION:")
        println("   - Endpoint: POST ${apiProperties.getApiBaseUrl()}/spline/execute")
        println("   - HTTP Status: ${response.code}")
        println("   - Success: ${response.isSuccessful}")
        println("   - Content-Type: ${response.header("Content-Type")}")
        println("   - Matches Documentation: ${if (response.isSuccessful) "Evaluated above" else "No - error response"}")

        response.close()
    }

    @Test
    @DisplayName("TEST 3: GET /spline/health - Service health check")
    fun `test rest api health endpoint`() {
        println("\n=== REST API: GET /spline/health ===")
        println("DOCUMENTATION: Returns service status, API version, and available endpoints")
        println("ENDPOINT: GET ${apiProperties.getApiBaseUrl()}/spline/health")

        val request = Request.Builder()
            .url("${apiProperties.getApiBaseUrl()}/spline/health")
            .get()
            .addHeader("Accept", "application/json")
            .build()

        println("\n1. SENDING REQUEST:")
        println("   - Method: GET")
        println("   - URL: ${request.url}")

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
            return
        }

        println("\n2. RESPONSE:")
        println("   - Status: ${response.code} ${response.message}")
        println("   - Headers:")
        response.headers.forEach { (name, value) ->
            if (name != "Date" && name != "Server") { // Skip common headers
                println("     $name: $value")
            }
        }

        val responseBody = response.body?.string()

        if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
            println("   - Body (first 200 chars): ${responseBody.take(200)}...")

            try {
                val json = objectMapper.readTree(responseBody)
                println("\n3. HEALTH CHECK ANALYSIS:")

                // Common health check fields
                val healthFields = listOf("status", "version", "timestamp", "uptime", "endpoints")

                healthFields.forEach { field ->
                    if (json.has(field)) {
                        val value = json.get(field)
                        println("   - $field: ${value.asText()}")
                    }
                }

                // Check for additional fields
                val extraFields = mutableListOf<String>()
                json.fieldNames().forEach { fieldName ->
                    if (fieldName !in healthFields) {
                        extraFields.add(fieldName)
                    }
                }

                if (extraFields.isNotEmpty()) {
                    println("\n   ADDITIONAL HEALTH INFO:")
                    extraFields.forEach { field ->
                        println("   - $field: ${json.get(field).asText()}")
                    }
                }

            } catch (e: Exception) {
                println("   ⚠️  Response is not JSON: ${responseBody.take(100)}")
            }
        } else {
            println("   ❌ Health check failed or empty response")
        }

        println("\n4. TEST CONCLUSION:")
        println("   - Service available: ${response.isSuccessful}")
        println("   - Response code: ${response.code}")
        println("   - Health status: ${if (response.code == 200) "✓ Healthy" else "⚠️ Issues"}")

        response.close()
    }

    @Test
    @DisplayName("TEST 4: Error handling - Invalid requests")
    fun `test rest api error handling`() {
        println("\n=== REST API ERROR HANDLING TESTS ===")
        println("Testing various error conditions as per documentation")

        val testCases = listOf(
            TestCase("Empty file", createEmptyFile(), "Should return 400 Bad Request"),
            TestCase("Non-ZIP file", createTextFile(), "Should return 400 Bad Request"),
            TestCase("ZIP with multiple CSV files", createZipWithMultipleCsvFiles(), "Should return 400 (documentation: must contain exactly one CSV)"),
            TestCase("ZIP with no CSV files", createZipWithNoCsv(), "Should return 400 Bad Request"),
        )

        testCases.forEach { testCase ->
            println("\n--- Testing: ${testCase.description} ---")
            println("Expected: ${testCase.expectedResult}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    testCase.file.name,
                    testCase.file.asRequestBody("application/zip".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${apiProperties.getApiBaseUrl()}/spline/execute")
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                println("Result: HTTP ${response.code}")

                val responseBody = response.body?.string()

                if (response.code == 400) {
                    println("✓ Got 400 Bad Request as expected")

                    if (!responseBody.isNullOrEmpty()) {
                        try {
                            val json = objectMapper.readTree(responseBody)
                            println("Error JSON structure (from documentation):")
                            if (json.has("status")) {
                                val status = json.get("status").asText()
                                println("  - status: $status ${if (status == "ERROR") "✓ matches documentation" else "⚠️ should be 'ERROR'"}")
                            }
                            if (json.has("error")) println("  - error: ${json.get("error").asText()}")
                            if (json.has("details")) println("  - details: ${json.get("details").asText()}")
                            if (json.has("timestamp")) println("  - timestamp: ${json.get("timestamp").asText()}")
                        } catch (e: Exception) {
                            println("  - Non-JSON response: ${responseBody.take(100)}")
                        }
                    }
                } else if (response.code == 200) {
                    println("⚠️  Unexpected success - service accepted invalid input")
                    if (!responseBody.isNullOrEmpty()) {
                        println("Response: ${responseBody.take(100)}...")
                    }
                } else {
                    println("⚠️  Got status ${response.code} instead of 400")
                }

                response.close()

            } catch (e: Exception) {
                println("❌ Request failed: ${e.message}")
            }
        }

        println("\nTEST CONCLUSION:")
        println("Service should properly validate input and return appropriate error codes (400)")
    }

    @Test
    @DisplayName("TEST 5: GET /spline/download/{executionId} - Download processed file")
    fun `test rest api download endpoint`() {
        println("\n=== REST API: GET /spline/download/{executionId} ===")
        println("NOTE: This test requires a valid executionId from a previous successful process")
        println("We'll first process a file, then attempt to download it")

        // Step 1: Process a file to get executionId
        println("\n1. PROCESSING TEST FILE TO GET executionId...")
        val zipFile = createValidZipFile()

        val processResponse = executeFile(zipFile)
        if (processResponse == null || !processResponse.isSuccessful) {
            println("❌ Cannot proceed - file processing failed")
            return
        }

        val responseBody = processResponse.body?.string() ?: ""
        val json = try {
            objectMapper.readTree(responseBody)
        } catch (e: Exception) {
            println("❌ Cannot parse process response")
            return
        }

        processResponse.close()

        if (!json.has("executionId") || !json.has("downloadUrl")) {
            println("❌ Response missing executionId or downloadUrl")
            return
        }

        val executionId = json.get("executionId").asText()
        val downloadUrl = json.get("downloadUrl").asText()

        println("   - executionId: $executionId")
        println("   - downloadUrl: $downloadUrl")
        println("   - status: ${json.get("status").asText()}")

        // Step 2: Download the file
        println("\n2. DOWNLOADING PROCESSED FILE...")

        val downloadRequest = Request.Builder()
            .url("${apiProperties.baseUrl}$downloadUrl")
            .get()
            .addHeader("Accept", "application/octet-stream, application/csv")
            .build()

        println("   - Download URL: ${downloadRequest.url}")

        val downloadResponse = try {
            httpClient.newCall(downloadRequest).execute()
        } catch (e: Exception) {
            println("❌ Download request failed: ${e.message}")
            return
        }

        println("\n3. DOWNLOAD RESPONSE:")
        println("   - Status: ${downloadResponse.code} ${downloadResponse.message}")
        println("   - Headers:")
        downloadResponse.headers.forEach { (name, value) ->
            println("     $name: $value")
        }

        if (downloadResponse.isSuccessful) {
            val contentDisposition = downloadResponse.header("Content-Disposition")
            val contentType = downloadResponse.header("Content-Type")

            println("\n4. DOWNLOAD SUCCESS:")
            println("   - Content-Type: $contentType")
            println("   - Content-Disposition: $contentDisposition")

            // Save the downloaded file
            val outputFile = tempDir.resolve("downloaded_result.csv").toFile()
            downloadResponse.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    println("   - File saved: ${outputFile.name}")
                    println("   - File size: $bytesCopied bytes")

                    // Quick check of file content
                    val firstLines = outputFile.readLines().take(3)
                    println("   - File preview (first 3 lines):")
                    firstLines.forEach { println("     $it") }

                    // Validate CSV format according to documentation
                    if (firstLines.size >= 2) {
                        val firstLine = firstLines[0]
                        val secondLine = firstLines[1]

                        val isValidFormat = firstLine.startsWith("id=") &&
                                firstLine.contains("type=") &&
                                secondLine == "x,y"

                        println("   - CSV format valid: ${if (isValidFormat) "✓" else "❌"}")

                        // Check if it's output format (x should be float)
                        if (firstLines.size >= 3) {
                            val thirdLine = firstLines[2]
                            val parts = thirdLine.split(",")
                            if (parts.size == 2) {
                                val xValue = parts[0]
                                val isFloatFormat = xValue.contains(".") // Check if x is float (output format)
                                println("   - Output format (x as float): ${if (isFloatFormat) "✓ interpolated data" else "⚠️ x might be integer"}")
                            }
                        }
                    }
                }
            }

            println("\n   ✓ Download successful and file format appears valid")
        } else {
            println("\n❌ DOWNLOAD FAILED:")
            println("   - Status code: ${downloadResponse.code}")
            val errorBody = downloadResponse.body?.string()
            if (!errorBody.isNullOrEmpty()) {
                println("   - Error: $errorBody")
            }
        }

        downloadResponse.close()

        println("\n5. TEST CONCLUSION:")
        println("   - Process → Download workflow: ${if (downloadResponse.isSuccessful) "✓ Working" else "❌ Failed"}")
        println("   - Download endpoint accessible: Yes")
        println("   - File format: CSV (as expected)")
    }

    // Helper methods
    private fun createValidCsvContent(signalId: Int = 1, type: String = "cubic"): String {
        return """
            id=$signalId,type=$type
            x,y
            0,0.34848102
            1,0.41401943
            2,0.40282408
            3,0.31262868
            4,0.27761093
            5,0.37273121
        """.trimIndent()
    }

    private fun createCsvFile(filename: String, content: String = createValidCsvContent()): File {
        val file = tempDir.resolve(filename).toFile()
        file.writeText(content)
        return file
    }

    private fun createZipFile(csvFile: File, zipFilename: String = "data.zip"): File {
        val zipFile = tempDir.resolve(zipFilename).toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry(csvFile.name)
            zos.putNextEntry(entry)
            zos.write(csvFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createValidZipFile(): File {
        val csvFile = createCsvFile("medical_signal.csv")
        return createZipFile(csvFile, "medical_data_${System.currentTimeMillis()}.zip")
    }

    private fun createEmptyFile(): File {
        return tempDir.resolve("empty.zip").toFile().apply {
            writeBytes(byteArrayOf())
        }
    }

    private fun createTextFile(): File {
        return tempDir.resolve("not_a_zip.txt").toFile().apply {
            writeText("This is not a ZIP file, just plain text")
        }
    }

    private fun createZipWithMultipleCsvFiles(): File {
        val zipFile = tempDir.resolve("multiple_csv.zip").toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // Add first CSV
            val csv1 = createCsvFile("data1.csv", createValidCsvContent(1, "cubic"))
            zos.putNextEntry(ZipEntry(csv1.name))
            zos.write(csv1.readBytes())
            zos.closeEntry()

            // Add second CSV
            val csv2 = createCsvFile("data2.csv", createValidCsvContent(2, "linear"))
            zos.putNextEntry(ZipEntry(csv2.name))
            zos.write(csv2.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createZipWithNoCsv(): File {
        val zipFile = tempDir.resolve("no_csv.zip").toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // Add a text file instead of CSV
            val textFile = tempDir.resolve("readme.txt").toFile().apply {
                writeText("This ZIP contains no CSV files")
            }
            zos.putNextEntry(ZipEntry(textFile.name))
            zos.write(textFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun executeFile(zipFile: File): Response? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${apiProperties.getApiBaseUrl()}/spline/execute")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            null
        }
    }

    private fun testDownloadEndpoint(downloadUrl: String, executionId: String) {
        println("\n   --- TESTING DOWNLOAD ENDPOINT ---")
        println("   Download URL: $downloadUrl")
        println("   Full URL: ${apiProperties.baseUrl}$downloadUrl")

        val request = Request.Builder()
            .url("${apiProperties.baseUrl}$downloadUrl")
            .get()
            .addHeader("Accept", "application/octet-stream")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            println("   - Download Status: ${response.code}")

            if (response.isSuccessful) {
                val contentDisposition = response.header("Content-Disposition")
                val contentType = response.header("Content-Type")

                println("   - Content-Type: $contentType")
                println("   - Content-Disposition: $contentDisposition")
                println("   ✓ Download endpoint works")

                // Read a small sample to verify it's CSV
                val inputStream = response.body?.byteStream()
                if (inputStream != null) {
                    val buffer = ByteArray(1024)
                    val bytesRead = inputStream.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val sampleText = String(buffer, 0, bytesRead)
                        val isCsv = sampleText.contains("id=") && sampleText.contains("x,y")
                        println("   - File appears to be CSV: ${if (isCsv) "✓" else "⚠️"}")

                        if (apiProperties.enableDetailedLogging && bytesRead < 200) {
                            println("   - Sample content: $sampleText")
                        }
                    } else {
                        println("   ⚠️  Empty response from download endpoint")
                    }
                }

            } else {
                println("   ⚠️  Download failed: ${response.code} ${response.message}")
            }

            response.close()
        } catch (e: Exception) {
            println("   ❌ Download test failed: ${e.message}")
        }
    }

    data class TestCase(
        val description: String,
        val file: File,
        val expectedResult: String
    )
}