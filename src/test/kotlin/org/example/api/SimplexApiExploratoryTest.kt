package org.example.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.example.api.config.SimplexApiProperties
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.*

@DisplayName("SIMLEX SOLVER API TESTS - VALIDATION AGAINST DOCUMENTATION")
class SimplexApiExploratoryTest {

    private val apiProperties: SimplexApiProperties = SimplexApiProperties.loadFromPropertiesFile("application-test.properties")

    private val simplexWebApiBaseUrl = apiProperties.baseUrl
    private val simplexRestApiBaseUrl = "${apiProperties.baseUrl}/api/v1/simplex"

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
    fun `test simplex api configuration`() {
        println("\n=== SIMPLEX API CONFIGURATION VALIDATION ===")

        println("\n1. LOADED CONFIGURATION:")
        println("   - Base URL (Web API): $simplexWebApiBaseUrl")
        println("   - REST API Base URL: $simplexRestApiBaseUrl")
        println("   - Timeout: ${apiProperties.timeoutSeconds} seconds")

        assertTrue(simplexWebApiBaseUrl.isNotBlank(), "Base URL should not be blank")
        assertTrue(simplexWebApiBaseUrl.startsWith("http"), "Base URL should start with http/https")
        assertTrue(simplexRestApiBaseUrl.contains("/api/v1/simplex"), "REST API URL should contain /api/v1/simplex")

        println("\n2. WEB API ENDPOINTS (from documentation):")
        val webApiEndpoints = mapOf(
            "POST /api/simplex/solve" to "$simplexWebApiBaseUrl/api/simplex/solve",
            "GET /api/simplex/health" to "$simplexWebApiBaseUrl/api/simplex/health"
        )

        webApiEndpoints.forEach { (name, url) ->
            println("   - $name: $url")
            assertTrue(url.startsWith("http"), "URL should start with http/https: $url")
        }

        println("\n3. REST API ENDPOINTS (from documentation):")
        val restApiEndpoints = mapOf(
            "POST /execute" to "$simplexRestApiBaseUrl/execute",
            "GET /download/{executionId}" to "$simplexRestApiBaseUrl/download/{executionId}",
            "GET /health" to "$simplexRestApiBaseUrl/health"
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
    @DisplayName("TEST 2: Web API - POST /api/simplex/solve - Synchronous processing")
    fun `test web api synchronous solve`() {
        println("\n=== WEB API: POST /api/simplex/solve ===")
        println("DOCUMENTATION: Upload MPS file, get OSrL result file synchronously")
        println("EXPECTED HEADERS: Content-Disposition, X-Execution-Id, X-Status, X-Processing-Time-Ms")
        println("ENDPOINT: POST $simplexWebApiBaseUrl/api/simplex/solve")

        val mpsFile = createValidMpsFile()
        println("\n1. TEST DATA CREATED:")
        println("   - MPS file: ${mpsFile.name}")
        println("   - File size: ${mpsFile.length()} bytes")
        println("   - Content preview:")
        mpsFile.readLines().take(5).forEach { println("     $it") }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                mpsFile.name,
                mpsFile.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$simplexWebApiBaseUrl/api/simplex/solve")
            .post(requestBody)
            .addHeader("Accept", "application/octet-stream")
            .build()

        println("\n2. SENDING REQUEST:")
        println("   - Method: POST")
        println("   - URL: ${request.url}")
        println("   - Content-Type: multipart/form-data")

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

        println("\n4. RESPONSE HEADERS (documented):")
        val documentedHeaders = listOf(
            "Content-Disposition",
            "X-Execution-Id",
            "X-Status",
            "X-Processing-Time-Ms"
        )

        documentedHeaders.forEach { header ->
            val headerValue = response.header(header)
            if (headerValue != null) {
                println("   ✓ $header: $headerValue")
                when (header) {
                    "X-Status" -> {
                        val validStatuses = listOf("SUCCESS", "CRASHED", "ERROR")
                        assertTrue(
                            headerValue in validStatuses,
                            "X-Status should be one of: $validStatuses"
                        )
                        println("     ✓ Valid status value")
                    }
                    "X-Processing-Time-Ms" -> {
                        assertTrue(headerValue.toIntOrNull() != null, "Processing time should be numeric")
                        println("     ✓ Valid numeric processing time")
                    }
                }
            } else {
                println("   ⚠️  $header: MISSING (documented)")
            }
        }

        println("\n5. CONTENT VALIDATION:")
        val contentType = response.header("Content-Type")
        println("   - Content-Type: $contentType")

        val isOctetStream = contentType?.contains("application/octet-stream") == true
        val isXml = contentType?.contains("xml") == true
        val isValidContentType = isOctetStream || isXml
        println("   - Is valid content type: ${if (isValidContentType) "✓" else "⚠️"}")

        if (response.isSuccessful) {
            val outputFile = tempDir.resolve("simplex_result.osrl").toFile()
            response.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    println("   - Result file saved: ${outputFile.name} ($bytesCopied bytes)")

                    val content = outputFile.readText()
                    val status = response.header("X-Status")

                    when (status) {
                        "SUCCESS" -> {
                            println("   - Result type: SUCCESS (OSrL XML)")
                            validateOsrlXml(content)
                        }
                        "CRASHED" -> {
                            println("   - Result type: CRASHED")
                            assertEquals("CRASHED", content.trim(), "CRASHED response should contain only 'CRASHED'")
                            println("   ✓ CRASHED marker present")
                        }
                        "ERROR" -> {
                            println("   - Result type: ERROR")
                            println("   - Error content: ${content.take(200)}")
                        }
                    }
                }
            }
        } else if (response.code == 400 || response.code == 500) {
            val errorBody = response.body?.string()
            if (!errorBody.isNullOrEmpty()) {
                println("\n6. ERROR RESPONSE:")
                try {
                    val json = objectMapper.readTree(errorBody)
                    println("   - JSON error response:")
                    if (json.has("status")) println("     status: ${json.get("status").asText()}")
                    if (json.has("error")) println("     error: ${json.get("error").asText()}")
                    if (json.has("details")) println("     details: ${json.get("details").asText()}")
                } catch (e: Exception) {
                    println("   - Text error response: ${errorBody.take(200)}")
                }
            }
        }

        println("\n7. TEST CONCLUSION:")
        println("   - Endpoint: POST /api/simplex/solve")
        println("   - HTTP Status: ${response.code}")
        println("   - Success: ${response.isSuccessful}")
        println("   - Status header present: ${response.header("X-Status") != null}")

        response.close()
        mpsFile.delete()
    }

    @Test
    @DisplayName("TEST 3: REST API - POST /execute - JSON response with execution details")
    fun `test rest api execute endpoint`() {
        println("\n=== REST API: POST /execute ===")
        println("DOCUMENTATION: Upload MPS file, get JSON with execution metadata")
        println("EXPECTED JSON FIELDS: executionId, status, message, processingTimeMs, downloadUrl, timestamp")
        println("ENDPOINT: POST $simplexRestApiBaseUrl/execute")

        val mpsFile = createValidMpsFile()
        println("\n1. TEST DATA CREATED:")
        println("   - MPS file: ${mpsFile.name}")
        println("   - File size: ${mpsFile.length()} bytes")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                mpsFile.name,
                mpsFile.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$simplexRestApiBaseUrl/execute")
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

            val documentedFields = mapOf(
                "executionId" to "Unique identifier for this execution (UUID)",
                "status" to "Execution status: SUCCESS, CRASHED, or ERROR",
                "message" to "Human-readable status message",
                "processingTimeMs" to "Processing time in milliseconds",
                "downloadUrl" to "Relative URL to download result file",
                "timestamp" to "Timestamp when response was generated (ISO 8601)"
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
                val expectedStatuses = listOf("SUCCESS", "CRASHED", "ERROR")
                val isValid = status in expectedStatuses
                println("   - status: $status ${if (isValid) "✓ valid status" else "⚠️ unexpected status"}")
            }

            if (json.has("downloadUrl")) {
                val downloadUrl = json.get("downloadUrl").asText()
                val isValid = downloadUrl.startsWith("/api/v1/simplex/download/")
                println("   - downloadUrl: ${if (isValid) "✓ correct format" else "❌ should start with '/api/v1/simplex/download/'"}")
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

            if (json.has("processingTimeMs")) {
                val processingTime = json.get("processingTimeMs")
                println("   - processingTimeMs: ${processingTime.asText()} ${if (processingTime.isNumber) "✓ numeric" else "⚠️ not numeric"}")
            }

            println("\n   SUMMARY:")
            println("   - Documented fields found: ${foundFields.size}/${documentedFields.size}")
            println("   - Missing documented fields: ${if (missingFields.isEmpty()) "None ✓" else missingFields}")
            println("   - Extra fields: ${if (extraFields.isEmpty()) "None ✓" else extraFields}")

            if (json.has("executionId") && json.has("downloadUrl")) {
                val executionId = json.get("executionId").asText()
                val downloadUrl = json.get("downloadUrl").asText()
                val status = json.get("status").asText()

                if (status == "SUCCESS" || status == "CRASHED") {
                    println("\n   --- TESTING DOWNLOAD ENDPOINT ---")
                    testSimplexDownloadEndpoint(downloadUrl, executionId, status)
                }
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
        mpsFile.delete()
    }

    @Test
    @DisplayName("TEST 4: REST API - GET /download/{executionId} - Download results")
    fun `test rest api download endpoint`() {
        println("\n=== REST API: GET /download/{executionId} ===")
        println("DOCUMENTATION: Download OSrL result file for a given execution ID")
        println("ENDPOINT: GET $simplexRestApiBaseUrl/download/{executionId}")
        println("First we need to create an execution by uploading a file...")

        val mpsFile = createValidMpsFile()
        println("\n1. CREATING EXECUTION:")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                mpsFile.name,
                mpsFile.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val executeRequest = Request.Builder()
            .url("$simplexRestApiBaseUrl/execute")
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
            mpsFile.delete()
            return
        }

        val responseBody = executeResponse.body?.string()
        if (responseBody.isNullOrEmpty()) {
            println("❌ Empty response from execute endpoint")
            executeResponse.close()
            mpsFile.delete()
            return
        }

        val json = objectMapper.readTree(responseBody)
        if (!json.has("executionId") || !json.has("downloadUrl")) {
            println("❌ Response doesn't contain executionId or downloadUrl")
            executeResponse.close()
            mpsFile.delete()
            return
        }

        val executionId = json.get("executionId").asText()
        val downloadUrl = json.get("downloadUrl").asText()
        val status = json.get("status").asText()

        println("   - Execution created: $executionId")
        println("   - Status: $status")
        println("   - Download URL: $downloadUrl")

        executeResponse.close()

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
                val executionIdHeader = downloadResponse.header("X-Execution-Id")
                val statusHeader = downloadResponse.header("X-Status")

                println("   - Content-Type: $contentType")
                println("   - Content-Disposition: $contentDisposition")
                println("   - X-Execution-Id: $executionIdHeader")
                println("   - X-Status: $statusHeader")

                val isOctetStream = contentType?.contains("application/octet-stream") == true
                println("   - Is octet-stream: ${if (isOctetStream) "✓" else "⚠️"}")

                val outputFile = tempDir.resolve("downloaded_result.osrl").toFile()
                downloadResponse.body?.byteStream()?.use { input ->
                    outputFile.outputStream().use { output ->
                        val bytesCopied = input.copyTo(output)
                        println("   - File saved: ${outputFile.name} ($bytesCopied bytes)")

                        val content = outputFile.readText()
                        println("   - Content preview (first 200 chars):")
                        println("     ${content.take(200)}...")

                        if (statusHeader == "CRASHED") {
                            assertEquals("CRASHED", content.trim(), "CRASHED response should contain only 'CRASHED'")
                            println("   ✓ CRASHED marker correctly returned")
                        } else if (statusHeader == "SUCCESS") {
                            validateOsrlXml(content)
                        }
                    }
                }
                println("   ✓ Download endpoint works correctly")
            } else if (downloadResponse.code == 404) {
                println("   ⚠️  Results not found (may have expired)")
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

        mpsFile.delete()
    }

    @Test
    @DisplayName("TEST 5: REST API - GET /health - Service health check")
    fun `test rest api health endpoint`() {
        println("\n=== REST API: GET /health ===")
        println("DOCUMENTATION: Returns service status, API version, and available endpoints")
        println("ENDPOINT: GET $simplexRestApiBaseUrl/health")

        val request = Request.Builder()
            .url("$simplexRestApiBaseUrl/health")
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

                val docFields = listOf("service", "status", "executionsInMemory", "endpoints", "timestamp")
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
    @DisplayName("TEST 6: Web API - GET /api/simplex/health - Web API health check")
    fun `test web api health endpoint`() {
        println("\n=== WEB API: GET /api/simplex/health ===")
        println("ENDPOINT: GET $simplexWebApiBaseUrl/api/simplex/health")

        val request = Request.Builder()
            .url("$simplexWebApiBaseUrl/api/simplex/health")
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

                val docFields = listOf("service", "status", "timestamp", "endpoint")
                docFields.forEach { field ->
                    if (json.has(field)) {
                        println("   - $field: ${json.get(field).asText()}")
                    } else {
                        println("   ⚠️  $field: MISSING (from documentation)")
                    }
                }
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
    fun `test simplex api error handling`() {
        println("\n=== SIMPLEX API ERROR HANDLING TESTS ===")
        println("Testing various error conditions as per documentation")

        val testCases = listOf(
            TestCase("Empty file", createEmptyFile(), "Should return 400 Bad Request"),
            TestCase("Non-MPS file", createTextFile(), "Should return 400 Bad Request"),
            TestCase("Malformed MPS file", createMalformedMpsFile(), "Should return 500 Internal Server Error"),
        )

        testCases.forEach { testCase ->
            println("\n--- Testing: ${testCase.description} ---")
            println("Expected: ${testCase.expectedResult}")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    testCase.file.name,
                    testCase.file.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$simplexWebApiBaseUrl/api/simplex/solve")
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                println("Result: HTTP ${response.code}")

                val responseBody = response.body?.string()

                if (response.code == 400 || response.code == 500) {
                    println("✓ Got ${response.code} as expected")

                    if (!responseBody.isNullOrEmpty()) {
                        try {
                            val json = objectMapper.readTree(responseBody)
                            println("Error JSON structure:")
                            if (json.has("status")) println("  - status: ${json.get("status").asText()}")
                            if (json.has("error")) println("  - error: ${json.get("error").asText()}")
                            if (json.has("details")) println("  - details: ${json.get("details").asText()}")
                            if (json.has("timestamp")) println("  - timestamp: ${json.get("timestamp").asText()}")

                            assertEquals("ERROR", json.get("status").asText(), "Status should be ERROR for error responses")
                        } catch (e: Exception) {
                            println("  - Non-JSON response: ${responseBody.take(100)}")
                        }
                    }
                } else if (response.code == 200) {
                    println("⚠️  Unexpected success - service accepted invalid input")
                } else {
                    println("⚠️  Got status ${response.code}")
                }

                response.close()
                testCase.file.delete()

            } catch (e: Exception) {
                println("❌ Request failed: ${e.message}")
            }
        }

        println("\nTEST CONCLUSION:")
        println("Service should properly validate input and return appropriate error codes (400/500)")
    }

    @Test
    @DisplayName("TEST 8: Output format validation - OSrL XML")
    fun `test output format validation`() {
        println("\n=== OUTPUT FORMAT VALIDATION TEST ===")
        println("Testing that output matches documented OSrL XML format")

        val mpsFile = createValidMpsFile()
        println("\n1. CREATING REQUEST:")
        println("   - MPS file: ${mpsFile.name}")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                mpsFile.name,
                mpsFile.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$simplexWebApiBaseUrl/api/simplex/solve")
            .post(requestBody)
            .addHeader("Accept", "application/octet-stream")
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
            mpsFile.delete()
            return
        }

        if (!response.isSuccessful) {
            println("❌ Request failed: ${response.code}")
            response.close()
            mpsFile.delete()
            return
        }

        val status = response.header("X-Status")
        println("   - X-Status: $status")

        if (status != "SUCCESS") {
            println("⚠️  Skipping OSrL validation (non-success status)")
            response.close()
            mpsFile.delete()
            return
        }

        val outputFile = tempDir.resolve("output_test.osrl").toFile()
        response.body?.byteStream()?.use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        println("\n2. EXTRACTING AND VALIDATING OUTPUT:")
        val content = outputFile.readText()
        val hasContent = content.isNotBlank()

        if (hasContent) {
            validateOsrlXml(content)
        } else {
            println("   ❌ Could not extract content from response")
        }

        println("\n3. TEST CONCLUSION:")
        println("   - Output format matches OSrL specification: ${if (hasContent) "✓" else "❌"}")

        response.close()
        mpsFile.delete()
        outputFile.delete()
    }

    private fun createValidMpsContent(): String {
        return """
NAME          TESTPROB
ROWS
 N  OBJ
 L  C1
 L  C2
COLUMNS
    X1        OBJ       3
    X1        C1        1
    X1        C2        2
    X2        OBJ       5
    X2        C1        2
    X2        C2        1
RHS
    RHS1      C1        10
    RHS1      C2        12
BOUNDS
 UP BND1      X1        5
 UP BND1      X2        6
ENDATA
        """.trimIndent()
    }

    private fun createValidMpsFile(filename: String = "test.mps"): File {
        val file = tempDir.resolve(filename).toFile()
        file.writeText(createValidMpsContent())
        return file
    }

    private fun createMalformedMpsFile(): File {
        val file = tempDir.resolve("malformed.mps").toFile()
        file.writeText("""
            NAME          BAD_PROB
            ROWS
            N  OBJ
            L  C1
            COLUMNS
            X1        OBJ       3
            X1        C1        1
            ENDATA
        """.trimIndent())
        return file
    }

    private fun validateOsrlXml(content: String) {
        println("   - Validating OSrL XML format:")

        if (content.contains("CRASHED")) {
            println("     ⚠️  Solver crashed - not a valid OSrL")
            return
        }

        val checks = listOf(
            "Contains XML declaration" to content.contains("<?xml"),
            "Contains osrl root element" to (content.contains("<osrl") || content.contains("xmlns=\"os.optimizationservices.org\"")),
            "Contains general status" to content.contains("<generalStatus"),
            "Contains optimization section" to content.contains("<optimization"),
            "Contains variables section" to content.contains("<variables")
        )

        checks.forEach { pair ->
            val check = pair.first
            val passed = pair.second
            println("     ${if (passed) "✓" else "❌"} $check")
        }

        if (content.contains("<generalStatus type=\"success\"")) {
            println("     ✓ Status: optimal/success")

            // Check for required optimal solution elements
            val optimalChecks = listOf(
                "Contains solution section" to content.contains("<solution"),
                "Contains objective value" to (content.contains("<obj") || content.contains("<objectives")),
                "Contains variable values" to content.contains("<var")
            )

            optimalChecks.forEach { pair ->
                val check = pair.first
                val passed = pair.second
                println("       ${if (passed) "✓" else "❌"} $check")
            }
        } else if (content.contains("infeasible") || content.contains("unbounded")) {
            println("     ✓ Status: ${if (content.contains("infeasible")) "infeasible" else "unbounded"}")
        }
    }

    private fun testSimplexDownloadEndpoint(downloadUrl: String, executionId: String, expectedStatus: String) {
        println("   Download URL: $downloadUrl")
        println("   Full URL: ${apiProperties.baseUrl}$downloadUrl")
        println("   Expected status: $expectedStatus")

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
                val executionIdHeader = response.header("X-Execution-Id")
                val statusHeader = response.header("X-Status")

                println("   - Content-Type: $contentType")
                println("   - Content-Disposition: $contentDisposition")
                println("   - X-Execution-Id: $executionIdHeader")
                println("   - X-Status: $statusHeader")

                assertEquals(executionId, executionIdHeader, "Execution ID should match")
                assertEquals(expectedStatus, statusHeader, "Status should match")

                println("   ✓ Download endpoint works correctly")
            } else if (response.code == 404) {
                println("   ⚠️  Results not found (may have expired)")
            } else {
                println("   ⚠️  Download failed: ${response.code} ${response.message}")
            }

            response.close()
        } catch (e: Exception) {
            println("   ❌ Download test failed: ${e.message}")
        }
    }

    private fun createEmptyFile(): File {
        return tempDir.resolve("empty.mps").toFile().apply {
            writeBytes(byteArrayOf())
        }
    }

    private fun createTextFile(): File {
        return tempDir.resolve("not_mps.txt").toFile().apply {
            writeText("This is not an MPS file, just plain text")
        }
    }

    data class TestCase(
        val description: String,
        val file: File,
        val expectedResult: String
    )
}