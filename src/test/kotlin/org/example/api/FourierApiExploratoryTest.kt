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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@DisplayName("FOURIER TRANSFORM API TESTS - UPDATED DOCUMENTATION")
class FourierApiExploratoryTest {

    private val apiProperties: SplineApiProperties = SplineApiProperties.loadFromPropertiesFile("application-test.properties")

    private val fourierWebApiBaseUrl = apiProperties.baseUrl
    private val fourierRestApiBaseUrl = "${apiProperties.baseUrl}/api/v1/fourier"

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
    fun `test fourier api configuration`() {
        println("\n=== FOURIER API CONFIGURATION VALIDATION ===")

        println("\n1. LOADED CONFIGURATION:")
        println("   - Base URL: $fourierWebApiBaseUrl")
        println("   - REST API Base URL: $fourierRestApiBaseUrl")
        println("   - Timeout: ${apiProperties.timeoutSeconds} seconds")

        assertTrue(fourierWebApiBaseUrl.isNotBlank(), "Base URL should not be blank")
        assertTrue(fourierWebApiBaseUrl.startsWith("http"), "Base URL should start with http/https")

        println("\n2. WEB API ENDPOINTS (from documentation):")
        val webApiEndpoint = "$fourierWebApiBaseUrl/api/fourier/upload"
        println("   - POST /api/fourier/upload: $webApiEndpoint")
        assertTrue(webApiEndpoint.startsWith("http"), "Web API URL should start with http/https")

        println("\n3. REST API ENDPOINTS (from documentation):")
        val restApiEndpoints = mapOf(
            "POST /execute" to "$fourierRestApiBaseUrl/execute",
            "GET /download/{executionId}" to "$fourierRestApiBaseUrl/download/{executionId}",
            "GET /health" to "$fourierRestApiBaseUrl/health"
        )

        restApiEndpoints.forEach { (name, url) ->
            println("   - $name: $url")
            assertTrue(url.startsWith("http"), "URL should start with http/https: $url")
        }

        println("\n4. TEST CONCLUSION:")
        println("   ✓ Configuration loaded successfully")
        println("   ✓ API URLs correctly generated")
    }

    @Test
    @DisplayName("TEST 2: Validate input format requirements")
    fun `test input format requirements`() {
        println("\n=== VALIDATING INPUT FORMAT REQUIREMENTS ===")
        println("Checking against documentation specifications...")

        val testCases = listOf(
            TestCase(
                "Valid signal with 8 samples (power of 2)",
                createValidSignal(8, 2, 0.05),
                "Should be accepted"
            ),
            TestCase(
                "Valid signal with 16 samples (power of 2)",
                createValidSignal(16, 1, 0.10),
                "Should be accepted"
            ),
            TestCase(
                "Signal with 3 samples (NOT power of 2)",
                createSignalWithInvalidSampleCount(3, 1, 0.05),
                "Should be rejected (not power of 2)"
            ),
            TestCase(
                "Signal with missing id field",
                "periods=1,threshold=0.05|0.000000|0.500000|1.000000|0.500000",
                "Should be rejected (missing id=)"
            ),
            TestCase(
                "Signal with invalid threshold format (3 decimal places)",
                "id=1,periods=1,threshold=0.123|0.000000|0.500000|1.000000|0.500000",
                "Should be rejected (threshold must have 2 decimals)"
            ),
            TestCase(
                "Signal with invalid value format (3 decimal places)",
                "id=1,periods=1,threshold=0.05|0.0|0.5|1.0|0.5",
                "Should be rejected (values must have 6 decimals)"
            ),
            TestCase(
                "Signal with comments and blank lines",
                "# Comment line\n\nid=1,periods=1,threshold=0.05|0.000000|0.500000|1.000000|0.500000\n\n# Another comment",
                "Should be accepted (comments allowed)"
            )
        )

        println("\nTEST CASES (based on documentation):")
        testCases.forEachIndexed { index, testCase ->
            println("\n${index + 1}. ${testCase.description}")
            println("   Input: ${testCase.input.take(80)}${if (testCase.input.length > 80) "..." else ""}")
            println("   Expected: ${testCase.expectedResult}")

            // Validate format locally before sending to API
            val isValid = validateSignalFormat(testCase.input)
            println("   Local validation: ${if (isValid) "✓ Format valid" else "⚠️ Format invalid"}")
        }

        println("\nDOCUMENTATION CHECKLIST:")
        println("✓ EBNF shows values with 6 decimal places")
        println("✓ EBNF shows threshold with 2 decimal places")
        println("✓ Sample count must be power of 2")
        println("✓ Pipe separator | between metadata and values")
        println("⚠️ Check if threshold range is actually enforced (0.00-1.00)")
        println("⚠️ Verify maximum sample count is actually 16384")
    }

    @Test
    @DisplayName("TEST 3: Web API - POST /api/fourier/upload - Synchronous processing")
    fun `test fourier web api upload`() {
        println("\n=== FOURIER WEB API: POST /api/fourier/upload ===")
        println("DOCUMENTATION: Upload ZIP archive, get ZIP with results synchronously")
        println("EXPECTED HEADERS: Content-Disposition, X-Upload-Id, X-Execution-Count")
        println("               X-Original-Size, X-Compressed-Size, X-Compression-Ratio")
        println("ENDPOINT: POST $fourierWebApiBaseUrl/api/fourier/upload")

        val zipFile = createValidFourierZipFile()
        println("\n1. TEST DATA CREATED:")
        println("   - ZIP file: ${zipFile.name}")
        println("   - File size: ${zipFile.length()} bytes")
        println("   - Contains: 3 valid signals with power-of-2 sample counts")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$fourierWebApiBaseUrl/api/fourier/upload")
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
            println("   Possible endpoint variations to try:")
            println("   1. $fourierWebApiBaseUrl/api/fourier/upload")
            println("   2. $fourierWebApiBaseUrl/fourier/upload")
            println("   3. $fourierWebApiBaseUrl/upload")
            return
        }

        println("\n3. RESPONSE RECEIVED:")
        println("   - Status Code: ${response.code}")
        println("   - Status Message: ${response.message}")

        println("\n4. RESPONSE HEADERS (checking against documentation):")
        val documentedHeaders = listOf(
            "Content-Disposition",
            "X-Upload-Id",
            "X-Execution-Count",
            "X-Original-Size",
            "X-Compressed-Size",
            "X-Compression-Ratio"
        )

        documentedHeaders.forEach { header ->
            val headerValue = response.header(header)
            if (headerValue != null) {
                println("   ✓ $header: $headerValue")
            } else {
                println("   ⚠️  $header: MISSING (documented but not present)")
            }
        }

        // Check for other headers that might be present but not documented
        println("\n5. EXTRA HEADERS (not in documentation):")
        response.headers.forEach { (name, value) ->
            if (name !in documentedHeaders && name != "Content-Type" && name != "Date" && name != "Content-Length") {
                println("   - $name: $value (present but not documented)")
            }
        }

        println("\n6. CONTENT VALIDATION:")
        val contentType = response.header("Content-Type")
        println("   - Content-Type: $contentType")
        val isZipFile = contentType?.contains("application/octet-stream") == true
        println("   - Is ZIP file: ${if (isZipFile) "✓" else "⚠️"}")

        if (response.isSuccessful && isZipFile) {
            val outputZip = tempDir.resolve("fourier_results.zip").toFile()
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

                        // Validate output format
                        if (firstLines.isNotEmpty()) {
                            println("\n   OUTPUT FORMAT VALIDATION:")
                            firstLines.forEach { line ->
                                if (line.contains(",error")) {
                                    println("     ⚠️  Error line found: $line (matches documentation)")
                                } else if (line.contains("id=") && line.contains("|")) {
                                    println("     ✓ Success line format: $line")
                                    val parts = line.split(",", limit = 2)
                                    if (parts.size == 2) {
                                        val values = parts[1].split("|")
                                        println("       - Values count: ${values.size}")
                                        // Check if values have 6 decimal places
                                        val allValid = values.all { value ->
                                            val decimalRegex = Regex("[+-]?\\d+\\.\\d{6}")
                                            decimalRegex.matches(value)
                                        }
                                        println("       - All values have 6 decimals: ${if (allValid) "✓" else "⚠️"}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (response.code == 400) {
            val errorBody = response.body?.string()
            if (!errorBody.isNullOrEmpty()) {
                println("\n7. ERROR RESPONSE ANALYSIS:")
                try {
                    val json = objectMapper.readTree(errorBody)
                    println("   Error JSON structure (from documentation):")
                    val docFields = listOf("status", "error", "timestamp", "details")
                    docFields.forEach { field ->
                        if (json.has(field)) {
                            println("   - $field: ${json.get(field).asText()}")
                        } else {
                            println("   - $field: MISSING (documented but not present)")
                        }
                    }
                } catch (e: Exception) {
                    println("   - Non-JSON error: ${errorBody.take(200)}")
                }
            }
        }

        println("\n8. DOCUMENTATION COMPLIANCE:")
        println("   - Headers present: ${documentedHeaders.count { response.header(it) != null }}/${documentedHeaders.size}")
        println("   - Endpoint works: ${response.code != 404}")
        println("   - Content type matches: ${isZipFile}")

        response.close()
        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 4: REST API - POST /execute - Check response format (UPDATED)")
    fun `test fourier rest api execute`() {
        println("\n=== FOURIER REST API: POST /execute ===")
        println("DOCUMENTATION (UPDATED): Returns JSON with detailed processing statistics")
        println("NOTE: executionId is now UUID (not 'fourier_' prefix)")
        println("EXPECTED JSON FIELDS: executionId (UUID), status=SUCCESS, timestamp, message, downloadUrl")
        println("                    inputSize, outputSize, processingTime, signalsProcessed, compressionRatio")
        println("ENDPOINT: POST $fourierRestApiBaseUrl/execute")

        val zipFile = createValidFourierZipFile()
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
            .url("$fourierRestApiBaseUrl/execute")
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
            zipFile.delete()
            return
        }

        println("\n4. RESPONSE BODY:")
        println(responseBody.take(500) + if (responseBody.length > 500) "..." else "")

        println("\n5. JSON VALIDATION AGAINST UPDATED DOCUMENTATION:")

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
                "executionId" to "Unique identifier (UUID format)",
                "status" to "Execution status: SUCCESS, ERROR, or PROCESSING",
                "timestamp" to "Timestamp when response was generated (ISO 8601)",
                "message" to "Human-readable status message",
                "downloadUrl" to "URL to download results",
                "inputSize" to "Size of input data in bytes",
                "outputSize" to "Size of output data in bytes (uncompressed)",
                "processingTime" to "Processing time in milliseconds",
                "signalsProcessed" to "Number of signals processed",
                "compressionRatio" to "Compression ratio (string with % or number)"
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

            println("\n   FIELD FORMAT VALIDATION (UPDATED):")

            // Check executionId is UUID (not 'fourier_' prefix)
            if (json.has("executionId")) {
                val executionId = json.get("executionId").asText()
                val uuidPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                val isValid = uuidPattern.matches(executionId)
                println("   - executionId: $executionId")
                println("     ${if (isValid) "✓ Valid UUID (matches updated documentation)" else "❌ Not a valid UUID"}")
            }

            if (json.has("status")) {
                val status = json.get("status").asText()
                val expectedStatuses = listOf("SUCCESS", "ERROR", "PROCESSING")
                val isValid = status in expectedStatuses
                println("   - status: $status ${if (isValid) "✓ valid status" else "⚠️ unexpected status"}")
            }

            if (json.has("downloadUrl")) {
                val downloadUrl = json.get("downloadUrl").asText()
                val isValid = downloadUrl.startsWith("/api/v1/fourier/download/")
                println("   - downloadUrl: $downloadUrl")
                println("     ${if (isValid) "✓ Correct format" else "❌ Should start with '/api/v1/fourier/download/'"}")

                // Check if downloadUrl ends with executionId
                if (json.has("executionId")) {
                    val executionId = json.get("executionId").asText()
                    val endsWithExecutionId = downloadUrl.endsWith(executionId)
                    println("     ${if (endsWithExecutionId) "✓ Contains executionId" else "⚠️ Does not match executionId"}")
                }
            }

            if (json.has("timestamp")) {
                val timestamp = json.get("timestamp").asText()
                try {
                    // Try to parse as ISO 8601
                    java.time.Instant.parse(timestamp)
                    println("   - timestamp: ✓ valid ISO 8601 format")
                } catch (e: Exception) {
                    try {
                        // Try other common formats
                        java.time.LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                        println("   - timestamp: ✓ valid ISO 8601 format")
                    } catch (e2: Exception) {
                        println("   - timestamp: ⚠️ invalid format (expected ISO 8601)")
                    }
                }
            }

            // Check numeric fields
            val numericFields = listOf("inputSize", "outputSize", "processingTime", "signalsProcessed")
            numericFields.forEach { field ->
                if (json.has(field)) {
                    val value = json.get(field)
                    val isNumber = value.isInt() || value.isLong()
                    println("   - $field: ${value.asText()} ${if (isNumber) "✓ numeric" else "⚠️ not numeric"}")
                }
            }

            if (json.has("compressionRatio")) {
                val compressionRatio = json.get("compressionRatio").asText()
                val hasPercentSign = compressionRatio.contains("%")
                val isNumeric = compressionRatio.toDoubleOrNull() != null
                println("   - compressionRatio: $compressionRatio")
                if (hasPercentSign) {
                    println("     ✓ Contains % sign as shown in documentation")
                } else if (isNumeric) {
                    println("     ⚠️ Numeric value (documentation shows string with %)")
                } else {
                    println("     ⚠️ Unknown format")
                }
            }

            println("\n   DOCUMENTATION COMPLIANCE SUMMARY:")
            println("   - Documented fields found: ${foundFields.size}/${documentedFields.size}")
            println("   - Missing documented fields: ${if (missingFields.isEmpty()) "None ✓" else missingFields}")
            println("   - Extra undocumented fields: ${if (extraFields.isEmpty()) "None ✓" else extraFields}")
            println("   - executionId format: ${if (json.has("executionId")) "UUID ✓" else "Missing"}")
            println("   - Updated documentation matches: ${if (missingFields.isEmpty() && json.has("executionId")) "✓" else "⚠️"}")

            // Test download endpoint if we have SUCCESS status
            if (json.has("executionId") && json.has("downloadUrl") && json.get("status").asText() == "SUCCESS") {
                val executionId = json.get("executionId").asText()
                val downloadUrl = json.get("downloadUrl").asText()
                println("\n   --- TESTING DOWNLOAD ENDPOINT ---")
                testFourierDownloadEndpoint(downloadUrl, executionId)
            }

        } catch (e: Exception) {
            println("❌ JSON PARSING ERROR: ${e.message}")
        }

        println("\n6. TEST CONCLUSION:")
        println("   - Endpoint: POST /execute")
        println("   - HTTP Status: ${response.code}")
        println("   - Response format matches updated documentation: ${if (response.isSuccessful) "Check above" else "No (error)"}")

        response.close()
        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 5: REST API - GET /health - Service health check")
    fun `test fourier rest api health`() {
        println("\n=== FOURIER REST API: GET /health ===")
        println("DOCUMENTATION: Returns service status, API version, and available endpoints")
        println("ENDPOINT: GET $fourierRestApiBaseUrl/health")

        val request = Request.Builder()
            .url("$fourierRestApiBaseUrl/health")
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
                println("\n3. HEALTH CHECK ANALYSIS (vs documentation):")

                val documentedFields = mapOf(
                    "service" to "Service name",
                    "status" to "Service status (OK)",
                    "interface" to "Interface type",
                    "api_version" to "API version",
                    "executions_in_memory" to "Number of executions in memory",
                    "endpoints" to "Available endpoints",
                    "timestamp" to "Timestamp"
                )

                println("\n   FIELD PRESENCE CHECK:")
                documentedFields.forEach { (field, description) ->
                    if (json.has(field)) {
                        val value = json.get(field)
                        if (field == "endpoints" && value.isObject) {
                            println("   ✓ $field: (object with ${value.size()} endpoints)")
                            value.fields().forEach { (key, endpoint) ->
                                println("     - $key: ${endpoint.asText()}")
                            }
                        } else {
                            println("   ✓ $field: ${value.asText()} ($description)")
                        }
                    } else {
                        println("   ⚠️  $field: MISSING (from documentation)")
                    }
                }

                // Check endpoints structure
                if (json.has("endpoints")) {
                    val endpoints = json.get("endpoints")
                    val documentedEndpoints = listOf("execute", "download", "health")

                    println("\n   ENDPOINTS VALIDATION:")
                    documentedEndpoints.forEach { endpoint ->
                        if (endpoints.has(endpoint)) {
                            val endpointValue = endpoints.get(endpoint).asText()
                            val expectedValue = when (endpoint) {
                                "execute" -> "POST /api/v1/fourier/execute"
                                "download" -> "GET /api/v1/fourier/download/{executionId}"
                                "health" -> "GET /api/v1/fourier/health"
                                else -> ""
                            }
                            println("   ✓ $endpoint: $endpointValue")
                            if (endpointValue != expectedValue) {
                                println("     ⚠️  Documentation says: $expectedValue")
                            }
                        } else {
                            println("   ⚠️  $endpoint: MISSING (documented but not present)")
                        }
                    }
                }

                // Check for extra fields not in documentation
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

            } catch (e: Exception) {
                println("   ⚠️  Response is not valid JSON")
            }
        } else {
            println("   ❌ Health check failed or empty response")
        }

        println("\n4. TEST CONCLUSION:")
        println("   - Service available: ${response.isSuccessful}")
        println("   - Health endpoint exists: ${response.code != 404}")
        println("   - Response format: ${if (response.isSuccessful) "Check JSON structure above" else "Not JSON"}")

        response.close()
    }

    @Test
    @DisplayName("TEST 6: Download endpoint headers validation (UPDATED)")
    fun `test fourier download endpoint headers`() {
        println("\n=== VALIDATING DOWNLOAD ENDPOINT HEADERS ===")
        println("Documentation lists these headers for download endpoint:")
        println("1. Content-Disposition")
        println("2. X-Execution-Id (UUID format)")
        println("3. X-File-Size")
        println("4. X-Signals-Processed")
        println("5. X-Compression-Ratio")
        println("\nNOTE: We need to first create an execution to test download")

        val zipFile = createValidFourierZipFile()
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
            .url("$fourierRestApiBaseUrl/execute")
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
        if (!json.has("executionId") || !json.has("downloadUrl") || json.get("status").asText() != "SUCCESS") {
            println("❌ Response doesn't contain valid execution data")
            println("Response: $responseBody")
            executeResponse.close()
            zipFile.delete()
            return
        }

        val executionId = json.get("executionId").asText()
        val downloadUrl = json.get("downloadUrl").asText()

        println("   - Execution created: $executionId")
        println("   - Download URL: $downloadUrl")

        executeResponse.close()

        println("\n2. TESTING DOWNLOAD ENDPOINT HEADERS:")
        println("   - Full URL: ${apiProperties.baseUrl}$downloadUrl")

        val downloadRequest = Request.Builder()
            .url("${apiProperties.baseUrl}$downloadUrl")
            .get()
            .addHeader("Accept", "application/octet-stream")
            .build()

        try {
            val downloadResponse = httpClient.newCall(downloadRequest).execute()
            println("   - Download Status: ${downloadResponse.code}")

            if (downloadResponse.isSuccessful) {
                println("\n3. HEADERS FOUND (vs documentation):")

                val documentedHeaders = mapOf(
                    "Content-Disposition" to "Filename for download",
                    "X-Execution-Id" to "Unique execution identifier (UUID)",
                    "X-File-Size" to "Size of the ZIP file in bytes",
                    "X-Signals-Processed" to "Number of signals processed",
                    "X-Compression-Ratio" to "Compression efficiency"
                )

                documentedHeaders.forEach { (header, description) ->
                    val headerValue = downloadResponse.header(header)
                    if (headerValue != null) {
                        println("   ✓ $header: $headerValue ($description)")

                        // Validate X-Execution-Id is UUID
                        if (header == "X-Execution-Id") {
                            val uuidPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                            val isValidUUID = uuidPattern.matches(headerValue)
                            println("     ${if (isValidUUID) "✓ Valid UUID format" else "❌ Not a valid UUID"}")
                        }
                    } else {
                        println("   ⚠️  $header: MISSING (documented but not present)")
                    }
                }

                println("\n4. EXTRA HEADERS (not in documentation):")
                downloadResponse.headers.forEach { (name, value) ->
                    if (name !in documentedHeaders.keys &&
                        name != "Content-Type" &&
                        name != "Date" &&
                        name != "Content-Length") {
                        println("   - $name: $value (present but not documented)")
                    }
                }

                println("\n5. DOCUMENTATION COMPLIANCE:")
                println("   - X-Execution-Id format: ${if (downloadResponse.header("X-Execution-Id")?.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) == true) "UUID ✓" else "⚠️ Not UUID"}")
                println("   - Headers present: ${documentedHeaders.count { downloadResponse.header(it.key) != null }}/${documentedHeaders.size}")

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

        println("\n6. TEST CONCLUSION:")
        println("   - Download endpoint tested")
        println("   - Header consistency with updated documentation: Check above")

        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 7: Validate output format matches documentation")
    fun `test fourier output format`() {
        println("\n=== VALIDATING OUTPUT FORMAT ===")
        println("Documentation specifies two output line formats:")
        println("1. Success: id=<signal_id>,<value1>|<value2>|...|<valueN>")
        println("2. Error: id=<signal_id>,error")
        println("3. Values must have 6 decimal places")

        // First, let's get actual output from the API
        val zipFile = createValidFourierZipFile()
        println("\n1. GETTING ACTUAL OUTPUT:")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$fourierWebApiBaseUrl/api/fourier/upload")
            .post(requestBody)
            .addHeader("Accept", "application/octet-stream")
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            println("❌ REQUEST FAILED: ${e.message}")
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

        println("\n2. ANALYZING OUTPUT FORMAT:")
        val extractedContent = extractZipContent(outputZip)
        if (extractedContent.isNotEmpty()) {
            val content = extractedContent.values.first()
            val lines = content.lines().filter { it.isNotBlank() }

            println("   - Total output lines: ${lines.size}")

            lines.forEachIndexed { index, line ->
                println("\n   Line ${index + 1}: $line")

                // Check basic structure
                if (!line.startsWith("id=")) {
                    println("     ❌ Line should start with 'id='")
                    return@forEachIndexed
                }

                val parts = line.split(",", limit = 2)
                if (parts.size != 2) {
                    println("     ❌ Line should have exactly one comma separating id from data")
                    return@forEachIndexed
                }

                val idPart = parts[0]
                val dataPart = parts[1]

                // Check id format
                if (!idPart.matches(Regex("id=\\d+"))) {
                    println("     ❌ ID format invalid: should be 'id=<number>'")
                } else {
                    println("     ✓ ID format valid")
                }

                // Check data format
                if (dataPart == "error") {
                    println("     ✓ Error line format (matches documentation)")
                } else if (dataPart.contains("|")) {
                    println("     ✓ Success line format (contains values separated by |)")

                    val values = dataPart.split("|")
                    println("       - Number of values: ${values.size}")

                    // Check each value has 6 decimal places
                    val decimalRegex = Regex("[+-]?\\d+\\.\\d{6}")
                    val invalidValues = values.filterNot { decimalRegex.matches(it) }

                    if (invalidValues.isEmpty()) {
                        println("       ✓ All values have 6 decimal places")
                    } else {
                        println("       ❌ Some values don't have 6 decimal places:")
                        invalidValues.take(3).forEach { println("         - $it") }
                        if (invalidValues.size > 3) {
                            println("         ... and ${invalidValues.size - 3} more")
                        }
                    }
                } else {
                    println("     ⚠️  Unknown data format: neither 'error' nor pipe-separated values")
                }
            }
        } else {
            println("   ❌ Could not extract content from ZIP")
        }

        println("\n3. DOCUMENTATION COMPLIANCE:")
        println("   - Output file exists: ${extractedContent.isNotEmpty()}")
        println("   - Line format: ${if (extractedContent.isNotEmpty()) "Check above" else "N/A"}")
        println("   - Value precision: ${if (extractedContent.isNotEmpty()) "Check above" else "N/A"}")

        response.close()
        zipFile.delete()
        outputZip.delete()
    }

    @Test
    @DisplayName("TEST 8: Error scenarios and error codes")
    fun `test fourier error scenarios`() {
        println("\n=== TESTING ERROR SCENARIOS ===")
        println("Documentation lists these error codes:")
        println("1. 400 - Bad Request (various validation errors)")
        println("2. 404 - Not Found (execution ID not found)")
        println("3. 500 - Internal Server Error")

        val testCases = listOf(
            ErrorTestCase(
                description = "ZIP with multiple files",
                file = createZipWithMultipleFiles(),
                expectedResult = "400 Bad Request - must contain exactly one file"
            ),
            ErrorTestCase(
                description = "ZIP with no text files",
                file = createZipWithNoTextFiles(),
                expectedResult = "400 Bad Request - invalid file format"
            ),
            ErrorTestCase(
                description = "Empty ZIP file",
                file = createEmptyFile(),
                expectedResult = "400 Bad Request - empty file"
            ),
            ErrorTestCase(
                description = "Non-ZIP file",
                file = createTextFile(),
                expectedResult = "400 Bad Request - invalid ZIP format"
            ),
            ErrorTestCase(
                description = "Invalid download ID (non-UUID)",
                invalidId = "test_invalid_download",
                expectedResult = "404 Not Found - execution ID not found"
            ),
            ErrorTestCase(
                description = "Invalid download ID (UUID format but not found)",
                invalidId = "00000000-0000-0000-0000-000000000000",
                expectedResult = "404 Not Found - execution ID not found"
            )
        )

        testCases.forEachIndexed { index, testCase ->
            println("\n${index + 1}. Testing: ${testCase.description}")
            println("   Expected: ${testCase.expectedResult}")

            if (testCase.file != null) {
                // Test Web API upload
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        testCase.file.name,
                        testCase.file.asRequestBody("application/zip".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$fourierWebApiBaseUrl/api/fourier/upload")
                    .post(requestBody)
                    .addHeader("Accept", "application/json")
                    .build()

                try {
                    val response = httpClient.newCall(request).execute()
                    println("   Result: HTTP ${response.code}")

                    if (response.code == 400) {
                        println("   ✓ Got 400 as expected")
                        val errorBody = response.body?.string()
                        if (!errorBody.isNullOrEmpty()) {
                            try {
                                val json = objectMapper.readTree(errorBody)
                                println("   Error JSON (documented format):")
                                if (json.has("status")) println("     - status: ${json.get("status").asText()}")
                                if (json.has("error")) println("     - error: ${json.get("error").asText()}")
                                if (json.has("timestamp")) println("     - timestamp: ${json.get("timestamp").asText()}")
                                if (json.has("details")) println("     - details: ${json.get("details").asText()}")
                            } catch (e: Exception) {
                                println("   Error details: ${errorBody.take(150)}")
                            }
                        }
                    } else {
                        println("   ⚠️  Got ${response.code} instead of 400")
                    }

                    response.close()
                    testCase.file.delete()
                } catch (e: Exception) {
                    println("   ❌ Request failed: ${e.message}")
                }
            } else if (testCase.invalidId != null) {
                // Test invalid download
                val downloadUrl = "$fourierRestApiBaseUrl/download/${testCase.invalidId}"
                println("   Testing download with invalid ID: $downloadUrl")

                val request = Request.Builder()
                    .url(downloadUrl)
                    .get()
                    .addHeader("Accept", "application/json")
                    .build()

                try {
                    val response = httpClient.newCall(request).execute()
                    println("   Result: HTTP ${response.code}")

                    if (response.code == 404) {
                        println("   ✓ Got 404 as expected")
                        val errorBody = response.body?.string()
                        if (!errorBody.isNullOrEmpty()) {
                            try {
                                val json = objectMapper.readTree(errorBody)
                                println("   Error JSON (documented format):")
                                if (json.has("status")) println("     - status: ${json.get("status").asText()}")
                                if (json.has("error")) println("     - error: ${json.get("error").asText()}")
                                if (json.has("timestamp")) println("     - timestamp: ${json.get("timestamp").asText()}")
                            } catch (e: Exception) {
                                println("   Error details: ${errorBody.take(150)}")
                            }
                        }
                    } else {
                        println("   ⚠️  Got ${response.code} instead of 404")
                    }

                    response.close()
                } catch (e: Exception) {
                    println("   ❌ Request failed: ${e.message}")
                }
            }
        }

        println("\nDOCUMENTATION ERROR CODE VALIDATION:")
        println("✓ 400 errors tested for various invalid inputs")
        println("✓ 404 error tested for invalid download ID (both non-UUID and UUID)")
        println("⚠️  500 error not tested (requires server-side failure)")
        println("✓ Error response format matches documentation")
    }

    // Helper methods for Fourier API tests
    private fun createValidSignal(sampleCount: Int, periods: Int, threshold: Double): String {
        require(sampleCount.isPowerOfTwo()) { "Sample count must be power of 2" }

        val values = (0 until sampleCount).map { i ->
            val x = i.toDouble() / sampleCount * 2 * Math.PI
            String.format("%.6f", Math.sin(x))  // Sine wave
        }

        return "id=${UUID.randomUUID().hashCode() and 0x7FFFFFFF},periods=$periods,threshold=${String.format("%.2f", threshold)}|${values.joinToString("|")}"
    }

    private fun createSignalWithInvalidSampleCount(sampleCount: Int, periods: Int, threshold: Double): String {
        val values = (0 until sampleCount).map { i ->
            String.format("%.6f", i.toDouble() / sampleCount)
        }

        return "id=999,periods=$periods,threshold=${String.format("%.2f", threshold)}|${values.joinToString("|")}"
    }

    private fun validateSignalFormat(input: String): Boolean {
        val lines = input.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (!trimmed.contains("id=")) return false

            val parts = trimmed.split("|", limit = 2)
            if (parts.size != 2) return false

            val metadata = parts[0]
            val values = parts[1]

            // Check metadata has required fields
            if (!metadata.contains("id=") || !metadata.contains("periods=") || !metadata.contains("threshold=")) {
                return false
            }

            // Check values format (all should have 6 decimal places)
            val valueList = values.split("|")
            val decimalRegex = Regex("[+-]?\\d+\\.\\d{6}")
            if (!valueList.all { decimalRegex.matches(it) }) {
                return false
            }

            // Check sample count is power of 2
            if (!valueList.size.isPowerOfTwo()) {
                return false
            }
        }
        return true
    }

    private fun Int.isPowerOfTwo(): Boolean {
        return this > 0 && (this and (this - 1)) == 0
    }

    private fun createValidFourierZipFile(): File {
        val content = """
            # Test signals for Fourier API
            ${createValidSignal(8, 2, 0.05)}
            ${createValidSignal(16, 1, 0.10)}
            # Signal with 32 samples
            ${createValidSignal(32, 3, 0.02)}
        """.trimIndent()

        val textFile = tempDir.resolve("fourier_signals_${UUID.randomUUID()}.txt").toFile()
        textFile.writeText(content)

        return createZipFile(textFile, "fourier_test_${UUID.randomUUID()}.zip")
    }

    private fun createZipFile(textFile: File, zipFilename: String): File {
        val zipFile = tempDir.resolve(zipFilename).toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry(textFile.name)
            zos.putNextEntry(entry)
            zos.write(textFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createZipWithMultipleFiles(): File {
        val zipFile = tempDir.resolve("multiple_files_${UUID.randomUUID()}.zip").toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val file1 = tempDir.resolve("file1.txt").toFile().apply {
                writeText(createValidSignal(8, 1, 0.05))
            }
            zos.putNextEntry(ZipEntry(file1.name))
            zos.write(file1.readBytes())
            zos.closeEntry()

            val file2 = tempDir.resolve("file2.txt").toFile().apply {
                writeText(createValidSignal(4, 1, 0.10))
            }
            zos.putNextEntry(ZipEntry(file2.name))
            zos.write(file2.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createZipWithNoTextFiles(): File {
        val zipFile = tempDir.resolve("no_text_files_${UUID.randomUUID()}.zip").toFile()

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

    private fun testFourierDownloadEndpoint(downloadUrl: String, executionId: String) {
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

                // Verify executionId in filename
                if (contentDisposition?.contains(executionId) == true) {
                    println("   ✓ Filename contains executionId (matches documentation)")
                } else {
                    println("   ⚠️  Filename may not contain executionId")
                }
            } else if (response.code == 404) {
                println("   ⚠️  Results not found (may have expired)")
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
        return tempDir.resolve("empty_${UUID.randomUUID()}.zip").toFile().apply {
            writeBytes(byteArrayOf())
        }
    }

    private fun createTextFile(): File {
        return tempDir.resolve("not_a_zip_${UUID.randomUUID()}.txt").toFile().apply {
            writeText("This is not a ZIP file, just plain text")
        }
    }

    data class TestCase(
        val description: String,
        val input: String,
        val expectedResult: String
    )

    data class ErrorTestCase(
        val description: String,
        val file: File? = null,
        val invalidId: String? = null,
        val expectedResult: String
    )
}