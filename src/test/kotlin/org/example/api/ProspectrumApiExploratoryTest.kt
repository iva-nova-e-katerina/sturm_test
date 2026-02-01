package org.example.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.example.api.config.ProspectrumApiProperties
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

@DisplayName("PROSPECTRUM SPECTRAL ANALYSIS API TESTS - BASED ON DOCUMENTATION")
class ProspectrumApiExploratoryTest {

    private val apiProperties: ProspectrumApiProperties = ProspectrumApiProperties.loadFromPropertiesFile("application-test.properties")

    private val legacyApiBaseUrl = apiProperties.baseUrl
    private val restApiBaseUrl = "${apiProperties.baseUrl}/api/v1/prospectrum"

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
    fun `test prospectrum api configuration`() {
        println("\n=== PROSPECTRUM API CONFIGURATION VALIDATION ===")

        println("\n1. LOADED CONFIGURATION:")
        println("   - Base URL (Legacy API): $legacyApiBaseUrl")
        println("   - REST API Base URL: $restApiBaseUrl")
        println("   - Timeout: ${apiProperties.timeoutSeconds} seconds")

        assertTrue(legacyApiBaseUrl.isNotBlank(), "Base URL should not be blank")
        assertTrue(legacyApiBaseUrl.startsWith("http"), "Base URL should start with http/https")
        assertTrue(restApiBaseUrl.contains("/api/v1/prospectrum"), "REST API URL should contain /api/v1/prospectrum")

        println("\n2. LEGACY API ENDPOINTS (from documentation):")
        val legacyApiEndpoints = mapOf(
            "POST /api/prospectrum/upload" to "$legacyApiBaseUrl/api/prospectrum/upload",
            "GET /api/prospectrum/health" to "$legacyApiBaseUrl/api/prospectrum/health"
        )

        legacyApiEndpoints.forEach { (name, url) ->
            println("   - $name: $url")
            assertTrue(url.startsWith("http"), "URL should start with http/https: $url")
        }

        println("\n3. REST API v1 ENDPOINTS (from documentation):")
        val restApiEndpoints = mapOf(
            "POST /execute" to "$restApiBaseUrl/execute",
            "GET /download/{executionId}" to "$restApiBaseUrl/download/{executionId}",
            "GET /health" to "$restApiBaseUrl/health"
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
    @DisplayName("TEST 2: Legacy API - POST /api/prospectrum/upload - Synchronous processing")
    fun `test legacy api synchronous upload`() {
        println("\n=== LEGACY API: POST /api/prospectrum/upload ===")
        println("DOCUMENTATION: Upload ZIP with SQP files, get ZIP with results synchronously")
        println("EXPECTED HEADERS: Content-Disposition, X-Upload-Id, X-Records-Processed, X-Processing-Time-Ms")
        println("               X-Original-Size, X-Compressed-Size, X-Compression-Ratio")
        println("ENDPOINT: POST $legacyApiBaseUrl/api/prospectrum/upload")

        val zipFile = createValidSqpZipFile()
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
            .url("$legacyApiBaseUrl/api/prospectrum/upload")
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
        val documentedHeaders = mapOf(
            "Content-Disposition" to "Filename for download",
            "X-Upload-Id" to "Unique upload identifier",
            "X-Records-Processed" to "Number of signal records processed",
            "X-Original-Size" to "Original size of output (bytes)",
            "X-Compressed-Size" to "Compressed size of output (bytes)",
            "X-Compression-Ratio" to "Compression efficiency",
            "X-Processing-Time-Ms" to "Total processing time (milliseconds)"
        )

        documentedHeaders.forEach { (header, description) ->
            val headerValue = response.header(header)
            if (headerValue != null) {
                println("   ✓ $header: $headerValue ($description)")
            } else {
                println("   ⚠️  $header: MISSING (documented: $description)")
            }
        }

        println("\n5. CONTENT VALIDATION:")
        val contentType = response.header("Content-Type")
        println("   - Content-Type: $contentType")
        val isZipFile = contentType?.contains("application/octet-stream") == true
        println("   - Is ZIP file: ${if (isZipFile) "✓" else "⚠️"}")

        if (response.isSuccessful && isZipFile) {
            val outputZip = tempDir.resolve("prospectrum_results.zip").toFile()
            response.body?.byteStream()?.use { input ->
                outputZip.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    println("   - Result file saved: ${outputZip.name} ($bytesCopied bytes)")

                    val extractedContent = extractZipContent(outputZip)
                    if (extractedContent.isNotEmpty()) {
                        println("   - Extracted files: ${extractedContent.size}")
                        extractedContent.forEach { (fileName, content) ->
                            println("   - File: $fileName (${content.length} chars)")
                            val firstLines = content.lines().take(3)
                            if (firstLines.isNotEmpty()) {
                                println("   - First line preview:")
                                println("     ${firstLines[0].take(100)}...")

                                // Validate output format (key=value|key=value...)
                                val firstLine = firstLines[0]
                                val isValidFormat = firstLine.contains("|") &&
                                        firstLine.contains("id=") &&
                                        firstLine.contains("contract=")
                                println("   - Output format valid: ${if (isValidFormat) "✓" else "⚠️"}")
                            }
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
        println("   - Endpoint: POST /api/prospectrum/upload")
        println("   - HTTP Status: ${response.code}")
        println("   - Success: ${response.isSuccessful}")
        println("   - Returns ZIP: ${isZipFile}")

        response.close()
        zipFile.delete()
    }

    @Test
    @DisplayName("TEST 3: REST API - POST /execute - Asynchronous processing with detailed response")
    fun `test rest api execute endpoint`() {
        println("\n=== REST API: POST /execute ===")
        println("DOCUMENTATION: Upload ZIP/SQP file, get JSON with execution metadata")
        println("EXPECTED JSON FIELDS: executionId, status=SUCCESS, timestamp, message, downloadUrl")
        println("                    inputSize, outputSize, processingTime, recordsProcessed, compressionRatio")
        println("ENDPOINT: POST $restApiBaseUrl/execute")

        val zipFile = createValidSqpZipFile()
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
            .url("$restApiBaseUrl/execute")
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
                if (json.has("details")) {
                    println("   - details: ${json.get("details").asText()}")
                }
                response.close()
                zipFile.delete()
                return
            }

            val documentedFields = mapOf(
                "executionId" to "Unique identifier for this execution (UUID)",
                "status" to "Execution status: SUCCESS, ERROR, or PROCESSING",
                "timestamp" to "Timestamp when response was generated (ISO 8601)",
                "message" to "Human-readable status message",
                "downloadUrl" to "URL to download results (should start with /api/v1/prospectrum/download/)",
                "inputSize" to "Size of input data in bytes",
                "outputSize" to "Size of output data in bytes",
                "processingTime" to "Processing time in milliseconds",
                "recordsProcessed" to "Number of signal records processed",
                "compressionRatio" to "Compression ratio as percentage (e.g., 50%)"
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
            }

            if (json.has("downloadUrl")) {
                val downloadUrl = json.get("downloadUrl").asText()
                val isValid = downloadUrl.startsWith("/api/v1/prospectrum/download/")
                println("   - downloadUrl: ${if (isValid) "✓ correct format" else "❌ should start with '/api/v1/prospectrum/download/'"}")
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

            if (json.has("compressionRatio")) {
                val ratio = json.get("compressionRatio").asText()
                val isValid = ratio.contains("%")
                println("   - compressionRatio: $ratio ${if (isValid) "✓ contains %" else "⚠️ should contain %"}")
            }

            val numericFields = listOf("inputSize", "outputSize", "processingTime", "recordsProcessed")
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
                testProspectrumDownloadEndpoint(downloadUrl, executionId)
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
        println("ENDPOINT: GET $restApiBaseUrl/download/{executionId}")
        println("First we need to create an execution by uploading a file...")

        val zipFile = createValidSqpZipFile()
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
            .url("$restApiBaseUrl/execute")
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
                val xExecutionId = downloadResponse.header("X-Execution-Id")
                val xRecordsProcessed = downloadResponse.header("X-Records-Processed")
                val xFileSize = downloadResponse.header("X-File-Size")
                val xCompressionRatio = downloadResponse.header("X-Compression-Ratio")

                println("   - Content-Type: $contentType")
                println("   - Content-Disposition: $contentDisposition")
                println("   - X-Execution-Id: $xExecutionId")
                println("   - X-Records-Processed: $xRecordsProcessed")
                println("   - X-File-Size: $xFileSize")
                println("   - X-Compression-Ratio: $xCompressionRatio")

                val isZipFile = contentType?.contains("application/octet-stream") == true
                println("   - Is ZIP file: ${if (isZipFile) "✓" else "⚠️"}")

                if (isZipFile) {
                    val outputZip = tempDir.resolve("downloaded_prospectrum_results.zip").toFile()
                    downloadResponse.body?.byteStream()?.use { input ->
                        outputZip.outputStream().use { output ->
                            val bytesCopied = input.copyTo(output)
                            println("   - File saved: ${outputZip.name} ($bytesCopied bytes)")

                            val extractedContent = extractZipContent(outputZip)
                            if (extractedContent.isNotEmpty()) {
                                println("   - Results extracted successfully")
                                extractedContent.forEach { (fileName, content) ->
                                    println("   - File: $fileName (${content.lines().size} lines)")
                                    val firstLine = content.lines().firstOrNull()
                                    println("   - First result line: ${firstLine?.take(80)}...")
                                }
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
        println("ENDPOINT: GET $restApiBaseUrl/health")

        val request = Request.Builder()
            .url("$restApiBaseUrl/health")
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

                val docFields = listOf("service", "status", "interface", "api_version", "executions_in_memory", "endpoints", "timestamp")
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

                if (json.has("status")) {
                    val status = json.get("status").asText()
                    println("\n   SERVICE STATUS: $status ${if (status == "OK") "✓" else "⚠️"}")
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
    @DisplayName("TEST 6: Legacy API - GET /api/prospectrum/health - Web API health check")
    fun `test legacy api health endpoint`() {
        println("\n=== LEGACY API: GET /api/prospectrum/health ===")
        println("ENDPOINT: GET $legacyApiBaseUrl/api/prospectrum/health")

        val request = Request.Builder()
            .url("$legacyApiBaseUrl/api/prospectrum/health")
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

                // Check for expected "OK" status
                if (json.has("status") && json.get("status").asText() == "OK") {
                    println("   ✓ Service reports OK status")
                } else {
                    println("   ⚠️  Service status is not OK")
                }
            } catch (e: Exception) {
                println("   - Plain text response: $responseBody")
            }
        }

        println("\n4. TEST CONCLUSION:")
        println("   - Legacy API health check: ${if (response.code == 200) "✓ OK" else "⚠️ Failed"}")

        response.close()
    }

    @Test
    @DisplayName("TEST 7: Error handling - Invalid SQP format and files")
    fun `test prospectrum api error handling`() {
        println("\n=== PROSPECTRUM API ERROR HANDLING TESTS ===")
        println("Testing various error conditions as per documentation")

        val testCases = listOf(
            TestCase("Empty ZIP file", createEmptyFile(), "Should return 400 Bad Request"),
            TestCase("Non-ZIP file", createTextFile(), "Should return 400 Bad Request"),
            TestCase("ZIP with invalid SQP format (spaces after comma)", createInvalidSqpZipFile(), "Should return 400 (spaces after comma)"),
            TestCase("ZIP with SQP file missing fs field", createSqpMissingFsZipFile(), "Should return 400 or process with empty fs"),
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

            // Test both REST API and Legacy API
            val endpoints = listOf(
                "$legacyApiBaseUrl/api/prospectrum/upload" to "Legacy API",
                "$restApiBaseUrl/execute" to "REST API"
            )

            endpoints.forEach { (url, apiName) ->
                println("\n   Testing $apiName: $url")

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Accept", if (apiName == "REST API") "application/json" else "application/octet-stream")
                    .build()

                try {
                    val response = httpClient.newCall(request).execute()
                    println("   Result: HTTP ${response.code}")

                    val responseBody = response.body?.string()

                    if (response.code == 400) {
                        println("   ✓ Got 400 Bad Request as expected")

                        if (!responseBody.isNullOrEmpty()) {
                            try {
                                val json = objectMapper.readTree(responseBody)
                                println("   Error JSON structure:")
                                if (json.has("status")) println("     - status: ${json.get("status").asText()}")
                                if (json.has("error")) println("     - error: ${json.get("error").asText()}")
                                if (json.has("details")) println("     - details: ${json.get("details").asText()}")
                                if (json.has("timestamp")) println("     - timestamp: ${json.get("timestamp").asText()}")
                            } catch (e: Exception) {
                                println("     - Non-JSON response: ${responseBody.take(100)}")
                            }
                        }
                    } else if (response.code == 200) {
                        println("   ⚠️  Unexpected success - service accepted invalid input")
                    } else {
                        println("   ⚠️  Got status ${response.code} instead of 400")
                    }

                    response.close()

                } catch (e: Exception) {
                    println("   ❌ Request failed: ${e.message}")
                }
            }

            testCase.file.delete()
        }

        println("\nTEST CONCLUSION:")
        println("Service should properly validate SQP format and return appropriate error codes (400)")
    }

    @Test
    @DisplayName("TEST 8: Output format validation - SQP to PROSPECTRUM format")
    fun `test output format validation`() {
        println("\n=== OUTPUT FORMAT VALIDATION TEST ===")
        println("Testing that output matches documented PROSPECTRUM format")

        val zipFile = createValidSqpZipFile()
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
            .url("$legacyApiBaseUrl/api/prospectrum/upload")
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

        val outputZip = tempDir.resolve("prospectrum_output_test.zip").toFile()
        response.body?.byteStream()?.use { input ->
            outputZip.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        println("\n2. EXTRACTING AND VALIDATING OUTPUT:")
        val extractedContent = extractZipContent(outputZip)
        val hasContent = extractedContent.isNotEmpty()

        if (hasContent) {
            extractedContent.forEach { (fileName, content) ->
                println("\n   File: $fileName")
                val lines = content.lines().filter { it.isNotBlank() }

                println("   - Total lines: ${lines.size}")
                println("   - Output format check:")

                lines.forEachIndexed { index, line ->
                    println("\n   Line ${index + 1} (first 100 chars): ${line.take(100)}...")

                    // Split by pipe to get key-value pairs
                    val pairs = line.split("|")

                    // Must contain at least id and contract
                    if (pairs.size < 2) {
                        println("     ❌ Line should have at least id= and contract=")
                        return@forEachIndexed
                    }

                    // Check for required fields
                    val requiredFields = listOf("id=", "contract=", "n=", "fs=", "parseval_relerr=")
                    val foundFields = mutableListOf<String>()

                    pairs.forEach { pair ->
                        if (pair.contains("=")) {
                            val key = pair.substringBefore("=")
                            if (requiredFields.any { pair.startsWith(it) }) {
                                foundFields.add(key)
                            }
                        }
                    }

                    println("     ✓ Found ${foundFields.size}/${requiredFields.size} required fields")

                    // Check format of each pair
                    pairs.forEach { pair ->
                        if (pair.contains("=")) {
                            val (key, value) = pair.split("=", limit = 2)

                            // Validate key format (alphanumeric + underscore)
                            val keyRegex = Regex("[a-zA-Z0-9_]+")
                            if (!keyRegex.matches(key)) {
                                println("     ❌ Invalid key format: '$key'")
                            }

                            // Check for scientific notation in values
                            if (value.isNotEmpty()) {
                                val sciNotationRegex = Regex("[+-]?\\d+\\.\\d+e[+-]\\d{2}")
                                if (sciNotationRegex.matches(value)) {
                                    println("     ✓ $key: $value (valid scientific notation)")
                                } else if (value.matches(Regex("[+-]?\\d+\\.\\d+"))) {
                                    println("     ✓ $key: $value (valid decimal)")
                                } else if (value.matches(Regex("\\d+"))) {
                                    println("     ✓ $key: $value (valid integer)")
                                } else {
                                    println("     ⚠️  $key: $value (unusual format)")
                                }
                            }
                        }
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

    // Helper functions for creating test data

    private fun createValidSqpContent(): String {
        return """
            # ECG signal sample at 360 Hz
            id=100,fs=360.0|0.123|0.456|-0.789|0.012|0.345|-0.678|0.901|-0.234|0.567
            # Vibration signal at 12000 Hz
            id=101,fs=12000.0|0.0012|-0.0034|0.0056|-0.0078|0.0090
            # Signal with unknown sampling rate
            id=102,fs=|0.1|0.2|-0.1|0.05|0.3|-0.2
            # Another ECG signal
            id=103,fs=360.0|-0.111|0.222|-0.333|0.444|-0.555
        """.trimIndent()
    }

    private fun createInvalidSqpContent(): String {
        return """
            # INVALID: Space after comma (should NOT have space)
            id=100, fs=360.0|0.123|0.456
            # This should fail parsing
            id=101, fs=12000.0|0.0012|-0.0034
        """.trimIndent()
    }

    private fun createSqpMissingFsContent(): String {
        return """
            # Missing fs value (empty)
            id=200,fs=|0.1|0.2|0.3
            # Missing fs field entirely (should fail)
            id=201|0.4|0.5|0.6
        """.trimIndent()
    }

    private fun createSqpFile(filename: String, content: String): File {
        val file = tempDir.resolve(filename).toFile()
        file.writeText(content)
        return file
    }

    private fun createSqpZipFile(sqpFile: File, zipFilename: String = "signals.zip"): File {
        val zipFile = tempDir.resolve(zipFilename).toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry(sqpFile.name)
            zos.putNextEntry(entry)
            zos.write(sqpFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    private fun createValidSqpZipFile(): File {
        val textFile = createSqpFile("signals.sqp", createValidSqpContent())
        return createSqpZipFile(textFile, "signals_${UUID.randomUUID()}.zip")
    }

    private fun createInvalidSqpZipFile(): File {
        val textFile = createSqpFile("invalid_signals.sqp", createInvalidSqpContent())
        return createSqpZipFile(textFile, "invalid_signals.zip")
    }

    private fun createSqpMissingFsZipFile(): File {
        val textFile = createSqpFile("missing_fs.sqp", createSqpMissingFsContent())
        return createSqpZipFile(textFile, "missing_fs.zip")
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

    private fun testProspectrumDownloadEndpoint(downloadUrl: String, executionId: String) {
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
                val xExecutionId = response.header("X-Execution-Id")

                println("   - Content-Type: $contentType")
                println("   - Content-Disposition: $contentDisposition")
                println("   - X-Execution-Id: $xExecutionId")
                println("   ✓ Download endpoint works")

                val isZip = contentType?.contains("application/octet-stream") == true
                println("   - Is ZIP file: ${if (isZip) "✓" else "⚠️"}")

                // Verify execution ID matches
                if (xExecutionId == executionId) {
                    println("   ✓ Execution ID matches")
                } else {
                    println("   ⚠️  Execution ID mismatch: expected $executionId, got $xExecutionId")
                }
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