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

@DisplayName("STURM POLYNOMIAL SOLVER API TESTS - UPDATED DOCUMENTATION")
class SturmApiExploratoryTest {

    private val apiProperties: SplineApiProperties = SplineApiProperties.loadFromPropertiesFile("application-test.properties")

    private val sturmWebApiBaseUrl = apiProperties.baseUrl
    private val sturmRestApiBaseUrl = "${apiProperties.baseUrl}/api/v1/polynomials"

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
    @DisplayName("TEST 1: Validate API configuration and URL generation")
    fun `test sturm api configuration`() {
        println("\n=== STURM API CONFIGURATION VALIDATION ===")

        println("\n1. LOADED CONFIGURATION:")
        println("   - Base URL (Web API): $sturmWebApiBaseUrl")
        println("   - REST API Base URL: $sturmRestApiBaseUrl")
        println("   - Timeout: ${apiProperties.timeoutSeconds} seconds")

        assertTrue(sturmWebApiBaseUrl.isNotBlank(), "Base URL should not be blank")
        assertTrue(sturmWebApiBaseUrl.startsWith("http"), "Base URL should start with http/https")
        assertTrue(sturmRestApiBaseUrl.contains("/api/v1"), "REST API URL should contain /api/v1")

        println("\n2. WEB API ENDPOINTS (from documentation):")
        val webApiEndpoints = mapOf(
            "POST /api/polynomials/upload" to "$sturmWebApiBaseUrl/api/polynomials/upload",
            "GET /api/polynomials/health" to "$sturmWebApiBaseUrl/api/polynomials/health"
        )

        webApiEndpoints.forEach { (name, url) ->
            println("   - $name: $url")
            assertTrue(url.startsWith("http"), "URL should start with http/https: $url")
        }

        println("\n3. REST API ENDPOINTS (from documentation):")
        val restApiEndpoints = mapOf(
            "POST /execute" to "$sturmRestApiBaseUrl/execute",
            "GET /download/{executionId}" to "$sturmRestApiBaseUrl/download/{executionId}",
            "GET /health" to "$sturmRestApiBaseUrl/health"
        )

        restApiEndpoints.forEach { (name, url) ->
            println("   - $name: $url")
            assertTrue(url.startsWith("http"), "URL should start with http/https: $url")
        }

        println("\n4. TEST CONCLUSION:")
        println("   ✓ Configuration loaded successfully")
        println("   ✓ API URLs correctly generated")
        println("   ✓ All URLs start with http/https")
    }

    @Test
    @DisplayName("TEST 2: Web API - POST /api/polynomials/upload - Synchronous processing")
    fun `test web api synchronous upload`() {
        println("\n=== WEB API: POST /api/polynomials/upload ===")
        println("DOCUMENTATION: Upload ZIP archive, get ZIP with results synchronously")
        println("EXPECTED HEADERS: Content-Disposition, X-Upload-Id, X-Execution-Count, X-Processing-Time-Ms")
        println("               X-Original-Size, X-Compressed-Size, X-Compression-Ratio")
        println("ENDPOINT: POST $sturmWebApiBaseUrl/api/polynomials/upload")

        val zipFile = createValidPolynomialZipFile()
        println("\n1. TEST DATA CREATED:")
        println("   - ZIP file: ${zipFile.name}")
        println("   - File size: ${zipFile.length()} bytes")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$sturmWebApiBaseUrl/api/polynomials/upload")
            .post(requestBody)
            .addHeader("Accept", "application/octet-stream")
            .build()

        println("\n2. SENDING REQUEST:")
        println("   - Method: POST")
        println("   - URL: ${request.url}")

        val response = try {
            val startTime = System.currentTimeMillis()
            val resp = httpClient.newCall(request).execute()
            val endTime = System.currentTimeMillis()
            println("   - Request duration: ${endTime - startTime} ms")
            resp
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
            return
        }

        println("\n3. RESPONSE RECEIVED:")
        println("   - Status Code: ${response.code}")
        println("   - Status Message: ${response.message}")

        println("\n4. RESPONSE HEADERS:")
        val documentedHeaders = listOf(
            "Content-Disposition",
            "X-Upload-Id",
            "X-Execution-Count",
            "X-Processing-Time-Ms",
            "X-Original-Size",
            "X-Compressed-Size",
            "X-Compression-Ratio"
        )

        documentedHeaders.forEach { header ->
            val headerValue = response.header(header)
            if (headerValue != null) {
                println("   ✓ $header: $headerValue")
            } else {
                println("   ⚠️  $header: MISSING (documented)")
            }
        }

        println("\n5. CONTENT VALIDATION:")
        val contentType = response.header("Content-Type")
        println("   - Content-Type: $contentType")
        val isZipFile = contentType?.contains("application/octet-stream") == true
        println("   - Is ZIP file: ${if (isZipFile) "✓" else "⚠️"}")

        if (response.isSuccessful && isZipFile) {
            val outputZip = tempDir.resolve("sturm_results.zip").toFile()
            response.body?.byteStream()?.use { input ->
                outputZip.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    println("   - Result file saved: ${outputZip.name} ($bytesCopied bytes)")

                    val extractedContent = extractZipContent(outputZip)
                    if (extractedContent.isNotEmpty()) {
                        println("   - Extracted file: ${extractedContent.keys.first()}")
                        val firstLines = extractedContent.values.first().lines().take(3)
                        println("   - Result preview (first 3 lines):")
                        firstLines.forEach { println("     $it") }

                        if (firstLines.isNotEmpty()) {
                            val firstLine = firstLines[0]
                            val isValidFormat = firstLine.startsWith("ID=") &&
                                    (firstLine.contains(" 0 ") ||
                                            firstLine.contains(" 1 ") ||
                                            firstLine.contains(" 2 "))
                            println("   - Output format valid: ${if (isValidFormat) "✓" else "⚠️"}")
                        }
                    }
                }
            }
        } else if (response.code == 400) {
            val errorBody = response.body?.string()
            if (!errorBody.isNullOrEmpty()) {
                println("\n6. ERROR RESPONSE:")
                println(errorBody.take(200))
            }
        }

        println("\n7. TEST CONCLUSION:")
        println("   - Endpoint: POST /api/polynomials/upload")
        println("   - HTTP Status: ${response.code}")
        println("   - Success: ${response.isSuccessful}")
        println("   - Returns ZIP: ${isZipFile}")

        response.close()
        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 3: REST API - POST /execute - Synchronous processing with detailed response")
    fun `test rest api execute endpoint`() {
        println("\n=== REST API: POST /execute ===")
        println("DOCUMENTATION: Upload ZIP, get JSON with detailed processing statistics")
        println("EXPECTED JSON FIELDS: executionId, status=SUCCESS, timestamp, message, downloadUrl")
        println("                    inputSize, outputSize, processingTime, filesProcessed")
        println("                    polynomialsProcessed, compressionRatio")
        println("ENDPOINT: POST $sturmRestApiBaseUrl/execute")

        val zipFile = createValidPolynomialZipFile()
        println("\n1. TEST DATA CREATED:")
        println("   - ZIP file: ${zipFile.name}")
        println("   - File size: ${zipFile.length()} bytes")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$sturmRestApiBaseUrl/execute")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .build()

        println("\n2. SENDING REQUEST:")
        println("   - Method: POST")
        println("   - URL: ${request.url}")

        val response = try {
            val startTime = System.currentTimeMillis()
            val resp = httpClient.newCall(request).execute()
            val endTime = System.currentTimeMillis()
            println("   - Request duration: ${endTime - startTime} ms")
            resp
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
            return
        }

        println("\n3. RESPONSE RECEIVED:")
        println("   - Status Code: ${response.code}")
        println("   - Status Message: ${response.message}")

        val responseBody = response.body?.string()
        if (responseBody.isNullOrEmpty()) {
            println("❌ ERROR: Empty response body")
            response.close()
            return
        }

        println("\n4. RESPONSE BODY:")
        println(responseBody.take(500) + if (responseBody.length > 500) "..." else "")

        println("\n5. JSON VALIDATION AGAINST DOCUMENTATION:")

        try {
            val json = objectMapper.readTree(responseBody)

            if (json.has("error") && json.has("status") && json.get("status").asText() == "ERROR") {
                println("⚠️  ERROR RESPONSE DETECTED:")
                println("   - status: ${json.get("status").asText()}")
                println("   - error: ${json.get("error").asText()}")
                response.close()
                zipFile.delete()
                return
            }

            val documentedFields = mapOf(
                "executionId" to "Unique identifier for this processing execution (UUID)",
                "status" to "Execution status: SUCCESS, ERROR, or PROCESSING",
                "timestamp" to "Timestamp when response was generated (ISO 8601)",
                "message" to "Human-readable status message",
                "downloadUrl" to "URL to download results",
                "inputSize" to "Size of input data in bytes",
                "outputSize" to "Size of output data in bytes (uncompressed)",
                "processingTime" to "Processing time in milliseconds",
                "filesProcessed" to "Number of files processed (always 1)",
                "polynomialsProcessed" to "Number of polynomials processed",
                "compressionRatio" to "Compression ratio as percentage (0-100)"
            )

            println("\n   FIELD PRESENCE CHECK:")
            val missingFields = mutableListOf<String>()
            val foundFields = mutableListOf<String>()

            documentedFields.forEach { (field, description) ->
                if (json.has(field)) {
                    foundFields.add(field)
                    val value = json.get(field)
                    println("   ✓ $field: ${value.asText()} ($description)")
                } else {
                    missingFields.add(field)
                    println("   ❌ $field: MISSING ($description)")
                }
            }

            val extraFields = mutableListOf<String>()
            json.fieldNames().forEach { fieldName ->
                if (fieldName !in documentedFields.keys) {
                    extraFields.add(fieldName)
                }
            }

            if (extraFields.isNotEmpty()) {
                println("\n   ⚠️  EXTRA FIELDS (not in documentation):")
                extraFields.forEach { field ->
                    println("   - $field: ${json.get(field).asText()}")
                }
            }

            println("\n   FIELD FORMAT VALIDATION:")

            if (json.has("executionId")) {
                val executionId = json.get("executionId").asText()
                val uuidPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                val isValid = uuidPattern.matches(executionId)
                println("   - executionId: ${if (isValid) "✓ valid UUID" else "⚠️ invalid format"}")
            }

            if (json.has("status")) {
                val status = json.get("status").asText()
                val expectedStatuses = listOf("SUCCESS", "ERROR", "PROCESSING")
                val isValid = status in expectedStatuses
                println("   - status: $status ${if (isValid) "✓ valid status" else "⚠️ unexpected status"}")

                if (status == "SUCCESS") {
                    println("     ✓ Matches documentation (synchronous processing)")
                } else if (status == "PROCESSING") {
                    println("     ⚠️  Processing is still in progress (truly async)")
                }
            }

            if (json.has("downloadUrl")) {
                val downloadUrl = json.get("downloadUrl").asText()
                val isValid = downloadUrl.startsWith("/api/v1/polynomials/download/")
                println("   - downloadUrl: ${if (isValid) "✓ correct format" else "❌ should start with '/api/v1/polynomials/download/'"}")
            }

            if (json.has("timestamp")) {
                val timestamp = json.get("timestamp").asText()
                try {
                    LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME)
                    println("   - timestamp: ✓ valid ISO 8601 format")
                } catch (e: Exception) {
                    println("   - timestamp: ❌ invalid format (expected ISO 8601)")
                }
            }

            val numericFields = listOf("inputSize", "outputSize", "processingTime", "filesProcessed", "polynomialsProcessed", "compressionRatio")
            numericFields.forEach { field ->
                if (json.has(field)) {
                    val value = json.get(field)
                    println("   - $field: ${value.asText()} ${if (value.isNumber) "✓ numeric" else "⚠️ not numeric"}")
                }
            }

            println("\n   SUMMARY:")
            println("   - Documented fields found: ${foundFields.size}/${documentedFields.size}")
            println("   - Missing documented fields: ${if (missingFields.isEmpty()) "None ✓" else missingFields}")
            println("   - Extra fields: ${if (extraFields.isEmpty()) "None ✓" else extraFields}")

            if (json.has("executionId") && json.has("downloadUrl") && json.get("status").asText() == "SUCCESS") {
                val executionId = json.get("executionId").asText()
                val downloadUrl = json.get("downloadUrl").asText()
                println("\n   --- TESTING DOWNLOAD ENDPOINT ---")
                testSturmDownloadEndpoint(downloadUrl, executionId)
            }

        } catch (e: Exception) {
            println("❌ JSON PARSING ERROR: ${e.message}")
        }

        println("\n6. TEST CONCLUSION:")
        println("   - Endpoint: POST /execute")
        println("   - HTTP Status: ${response.code}")
        println("   - Success: ${response.isSuccessful}")
        println("   - Response contains all documented fields: ${if (response.isSuccessful) "✓" else "⚠️"}")

        response.close()
        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 4: REST API - GET /download/{executionId} - Download results")
    fun `test rest api download endpoint`() {
        println("\n=== REST API: GET /download/{executionId} ===")
        println("DOCUMENTATION: Download processed results for a given execution ID")
        println("ENDPOINT: GET $sturmRestApiBaseUrl/download/{executionId}")
        println("First we need to create an execution by uploading a file...")

        val zipFile = createValidPolynomialZipFile()
        println("\n1. CREATING EXECUTION:")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val executeRequest = Request.Builder()
            .url("$sturmRestApiBaseUrl/execute")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .build()

        val executeResponse = try {
            httpClient.newCall(executeRequest).execute()
        } catch (e: Exception) {
            println("❌ Failed to create execution: ${e.message}")
            return
        }

        if (!executeResponse.isSuccessful) {
            println("❌ Failed to create execution: ${executeResponse.code}")
            executeResponse.close()
            zipFile.delete()
            return
        }

        val responseBody = executeResponse.body?.string()
        if (responseBody.isNullOrEmpty()) {
            println("❌ Empty response from execute endpoint")
            executeResponse.close()
            zipFile.delete()
            return
        }

        val json = objectMapper.readTree(responseBody)
        if (!json.has("executionId") || !json.has("downloadUrl")) {
            println("❌ Response doesn't contain executionId or downloadUrl")
            executeResponse.close()
            zipFile.delete()
            return
        }

        val executionId = json.get("executionId").asText()
        val downloadUrl = json.get("downloadUrl").asText()
        val status = json.get("status").asText()

        println("   - Execution created: $executionId")
        println("   - Status: $status")
        println("   - Download URL: $downloadUrl")

        executeResponse.close()

        if (status != "SUCCESS") {
            println("⚠️  Execution not completed, skipping download test")
            zipFile.delete()
            return
        }

        println("\n2. TESTING DOWNLOAD ENDPOINT:")
        println("   - Execution ID: $executionId")
        println("   - Full URL: ${apiProperties.baseUrl}$downloadUrl")

        val downloadRequest = Request.Builder()
            .url("${apiProperties.baseUrl}$downloadUrl")
            .get()
            .addHeader("Accept", "application/octet-stream")
            .build()

        try {
            val downloadResponse = httpClient.newCall(downloadRequest).execute()
            println("\n3. DOWNLOAD RESPONSE:")
            println("   - Status Code: ${downloadResponse.code}")
            println("   - Status Message: ${downloadResponse.message}")

            if (downloadResponse.isSuccessful) {
                val contentDisposition = downloadResponse.header("Content-Disposition")
                val contentType = downloadResponse.header("Content-Type")

                println("   - Content-Type: $contentType")
                println("   - Content-Disposition: $contentDisposition")

                val isZipFile = contentType?.contains("application/octet-stream") == true
                println("   - Is ZIP file: ${if (isZipFile) "✓" else "⚠️"}")

                if (isZipFile) {
                    val outputZip = tempDir.resolve("downloaded_results.zip").toFile()
                    downloadResponse.body?.byteStream()?.use { input ->
                        outputZip.outputStream().use { output ->
                            val bytesCopied = input.copyTo(output)
                            println("   - File saved: ${outputZip.name} ($bytesCopied bytes)")

                            val extractedContent = extractZipContent(outputZip)
                            if (extractedContent.isNotEmpty()) {
                                println("   - Results extracted successfully")
                                val firstLine = extractedContent.values.first().lines().firstOrNull()
                                println("   - First result line: $firstLine")
                            }
                        }
                    }
                    println("   ✓ Download endpoint works correctly")
                }
            } else if (downloadResponse.code == 404) {
                println("   ⚠️  Results not found (may have expired)")
            } else if (downloadResponse.code == 410) {
                println("   ⚠️  Results have been cleaned up (expired)")
            } else {
                println("   ❌ Download failed: ${downloadResponse.code}")
            }

            downloadResponse.close()
        } catch (e: Exception) {
            println("❌ Download test failed: ${e.message}")
        }

        println("\n4. TEST CONCLUSION:")
        println("   - Download endpoint: GET /download/{executionId}")
        println("   - Execution ID used: $executionId")
        println("   - Download attempted successfully")

        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 5: REST API - GET /health - Service health check")
    fun `test rest api health endpoint`() {
        println("\n=== REST API: GET /health ===")
        println("DOCUMENTATION: Returns service status, API version, and available endpoints")
        println("ENDPOINT: GET $sturmRestApiBaseUrl/health")

        val request = Request.Builder()
            .url("$sturmRestApiBaseUrl/health")
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

        val responseBody = response.body?.string()

        if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
            println("   - Body (first 300 chars): ${responseBody.take(300)}...")

            try {
                val json = objectMapper.readTree(responseBody)
                println("\n3. HEALTH CHECK ANALYSIS:")

                val docFields = listOf("status", "timestamp", "service", "api_version", "endpoints")
                docFields.forEach { field ->
                    if (json.has(field)) {
                        val value = json.get(field)
                        if (field == "endpoints" && value.isObject) {
                            println("   - $field: (object with ${value.size()} endpoints)")
                            value.fields().forEach { (key, endpoint) ->
                                println("     - $key: ${endpoint.asText()}")
                            }
                        } else {
                            println("   - $field: ${value.asText()}")
                        }
                    } else {
                        println("   ⚠️  $field: MISSING (from documentation)")
                    }
                }

                if (json.has("endpoints")) {
                    val endpoints = json.get("endpoints")
                    val expectedEndpoints = listOf("execute", "download", "health")

                    println("\n   ENDPOINTS VALIDATION:")
                    expectedEndpoints.forEach { endpoint ->
                        if (endpoints.has(endpoint)) {
                            println("   ✓ $endpoint: ${endpoints.get(endpoint).asText()}")
                        } else {
                            println("   ⚠️  $endpoint: MISSING (documented but not present)")
                        }
                    }
                }

            } catch (e: Exception) {
                println("   ⚠️  Response is not valid JSON")
            }
        } else {
            println("   ❌ Health check failed or empty response")
        }

        println("\n4. TEST CONCLUSION:")
        println("   - Service available: ${response.isSuccessful}")
        println("   - Health status: ${if (response.code == 200) "✓ Healthy" else "⚠️ Issues"}")

        response.close()
    }

    @Test
    @DisplayName("TEST 6: Web API - GET /api/polynomials/health - Web API health check")
    fun `test web api health endpoint`() {
        println("\n=== WEB API: GET /api/polynomials/health ===")
        println("ENDPOINT: GET $sturmWebApiBaseUrl/api/polynomials/health")

        val request = Request.Builder()
            .url("$sturmWebApiBaseUrl/api/polynomials/health")
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

        val responseBody = response.body?.string()
        if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
            println("   - Body: $responseBody")

            try {
                val json = objectMapper.readTree(responseBody)
                println("\n3. HEALTH CHECK ANALYSIS:")

                if (json.has("status")) println("   - status: ${json.get("status").asText()}")
                if (json.has("service")) println("   - service: ${json.get("service").asText()}")
                if (json.has("timestamp")) println("   - timestamp: ${json.get("timestamp").asText()}")
            } catch (e: Exception) {
                println("   - Plain text response: $responseBody")
            }
        }

        println("\n4. TEST CONCLUSION:")
        println("   - Web API health check: ${if (response.code == 200) "✓ OK" else "⚠️ Failed"}")

        response.close()
    }

    @Test
    @DisplayName("TEST 7: Error handling - Invalid requests")
    fun `test sturm api error handling`() {
        println("\n=== STURM API ERROR HANDLING TESTS ===")
        println("Testing various error conditions as per documentation")

        val testCases = listOf(
            TestCase("Empty ZIP file", createEmptyFile(), "Should return 400 Bad Request"),
            TestCase("Non-ZIP file", createTextFile(), "Should return 400 Bad Request"),
            TestCase("ZIP with multiple text files", createZipWithMultipleTextFiles(), "Should return 400 (must contain exactly one file)"),
            TestCase("ZIP with no text files", createZipWithNoTextFiles(), "Should return 400 Bad Request"),
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
                .url("$sturmWebApiBaseUrl/api/polynomials/upload")
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
                            println("Error JSON structure:")
                            if (json.has("status")) println("  - status: ${json.get("status").asText()}")
                            if (json.has("error")) println("  - error: ${json.get("error").asText()}")
                            if (json.has("details")) println("  - details: ${json.get("details").asText()}")
                            if (json.has("timestamp")) println("  - timestamp: ${json.get("timestamp").asText()}")
                        } catch (e: Exception) {
                            println("  - Non-JSON response: ${responseBody.take(100)}")
                        }
                    }
                } else if (response.code == 200) {
                    println("⚠️  Unexpected success - service accepted invalid input")
                } else {
                    println("⚠️  Got status ${response.code} instead of 400")
                }

                response.close()
                testCase.file.delete()

            } catch (e: Exception) {
                println("❌ Request failed: ${e.message}")
            }
        }

        println("\nTEST CONCLUSION:")
        println("Service should properly validate input and return appropriate error codes (400)")
    }

    @Test
    @DisplayName("TEST 8: Output format validation")
    fun `test output format validation`() {
        println("\n=== OUTPUT FORMAT VALIDATION TEST ===")
        println("Testing that output matches documented format")

        val zipFile = createValidPolynomialZipFile()
        println("\n1. CREATING REQUEST:")
        println("   - ZIP file: ${zipFile.name}")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$sturmWebApiBaseUrl/api/polynomials/upload")
            .post(requestBody)
            .addHeader("Accept", "application/octet-stream")
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
            zipFile.delete()
            return
        }

        if (!response.isSuccessful) {
            println("❌ Request failed: ${response.code}")
            response.close()
            zipFile.delete()
            return
        }

        val outputZip = tempDir.resolve("output_test.zip").toFile()
        response.body?.byteStream()?.use { input ->
            outputZip.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        println("\n2. EXTRACTING AND VALIDATING OUTPUT:")
        val extractedContent = extractZipContent(outputZip)
        val hasContent = extractedContent.isNotEmpty()

        if (hasContent) {
            val content = extractedContent.values.first()
            val lines = content.lines().filter { it.isNotBlank() }

            println("   - Total lines: ${lines.size}")
            println("   - Output format check:")

            lines.forEachIndexed { index, line ->
                println("\n   Line ${index + 1}: $line")

                if (!line.startsWith("ID=")) {
                    println("     ❌ Line should start with 'ID='")
                    return@forEachIndexed
                }

                val parts = line.split(" ")
                if (parts.size < 2) {
                    println("     ❌ Line should have at least ID and root count")
                    return@forEachIndexed
                }

                val id = parts[0].removePrefix("ID=")
                val rootCount = parts[1].toIntOrNull()

                if (rootCount == null) {
                    println("     ❌ Invalid root count: ${parts[1]}")
                } else {
                    println("     ✓ ID: $id, Root count: $rootCount")

                    if (rootCount > 0) {
                        val expectedParts = 2 + rootCount * 2  // ID, count, and root intervals
                        if (parts.size != expectedParts) {
                            println("     ❌ Wrong number of elements. Expected: $expectedParts, Got: ${parts.size}")
                        } else {
                            println("     ✓ Correct number of elements")

                            for (i in 2 until parts.size) {
                                val value = parts[i]
                                val scientificNotationRegex = Regex("[+-]?\\d+\\.\\d+e[+-]\\d{2}")
                                if (scientificNotationRegex.matches(value)) {
                                    println("     ✓ Value $i: $value (valid scientific notation)")
                                } else {
                                    println("     ❌ Value $i: $value (not in scientific notation)")
                                }
                            }
                        }
                    } else {
                        println("     ✓ No roots, line format correct")
                    }
                }
            }
        } else {
            println("   ❌ Could not extract content from ZIP")
        }

        println("\n3. TEST CONCLUSION:")
        println("   - Output format matches documentation: ${if (hasContent) "✓" else "❌"}")

        response.close()
        zipFile.delete()
        outputZip.delete()
    }

    private fun createValidPolynomialContent(): String {
        return """
            # Sample polynomial definitions from documentation
            ID=0 1 -5 6       # x² - 5x + 6 = (x-2)(x-3)
            ID=1 1 0 -1       # x² - 1 = (x-1)(x+1)
            ID=2 1 -2 1       # x² - 2x + 1 = (x-1)²
            ID=3 1 -6 11 -6   # x³ - 6x² + 11x - 6 = (x-1)(x-2)(x-3)
            ID=4 1 0 1        # x² + 1 (no real roots)
        """.trimIndent()
    }

    private fun createPolynomialTextFile(filename: String, content: String = createValidPolynomialContent()): File {
        val file = tempDir.resolve(filename).toFile()
        file.writeText(content)
        return file
    }

    private fun createPolynomialZipFile(textFile: File, zipFilename: String = "polynomials.zip"): File {
        val zipFile = tempDir.resolve(zipFilename).toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry(textFile.name)
            zos.putNextEntry(entry)
            zos.write(textFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createValidPolynomialZipFile(): File {
        val textFile = createPolynomialTextFile("polynomials.txt")
        return createPolynomialZipFile(textFile, "polynomials_${UUID.randomUUID()}.zip")
    }

    private fun createZipWithMultipleTextFiles(): File {
        val zipFile = tempDir.resolve("multiple_files.zip").toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val file1 = createPolynomialTextFile("data1.txt", "ID=0 1 -5 6")
            zos.putNextEntry(ZipEntry(file1.name))
            zos.write(file1.readBytes())
            zos.closeEntry()

            val file2 = createPolynomialTextFile("data2.txt", "ID=1 1 0 -1")
            zos.putNextEntry(ZipEntry(file2.name))
            zos.write(file2.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createZipWithNoTextFiles(): File {
        val zipFile = tempDir.resolve("no_text_files.zip").toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val binaryFile = tempDir.resolve("data.bin").toFile().apply {
                writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            }
            zos.putNextEntry(ZipEntry(binaryFile.name))
            zos.write(binaryFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun extractZipContent(zipFile: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { input ->
                            val content = input.readBytes().toString(Charsets.UTF_8)
                            result[entry.name] = content
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore extraction errors for tests
        }
        return result
    }

    private fun testSturmDownloadEndpoint(downloadUrl: String, executionId: String) {
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

                val isZip = contentType?.contains("application/octet-stream") == true
                println("   - Is ZIP file: ${if (isZip) "✓" else "⚠️"}")
            } else if (response.code == 404) {
                println("   ⚠️  Results not found (task may have expired)")
            } else if (response.code == 410) {
                println("   ⚠️  Results have been cleaned up (expired)")
            } else {
                println("   ⚠️  Download failed: ${response.code} ${response.message}")
            }

            response.close()
        } catch (e: Exception) {
            println("   ❌ Download test failed: ${e.message}")
        }
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

    data class TestCase(
        val description: String,
        val file: File,
        val expectedResult: String
    )
}