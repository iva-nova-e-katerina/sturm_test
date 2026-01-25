package org.example.api

import com.example.generators.TestDataGenerators
import org.junit.jupiter.api.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AllApiIntegrationTest {

    private val apis = listOf(
        Triple("Polynomial", "/api/polynomials/upload", "/api/polynomials/health"),
        Triple("Spline", "/api/spline/upload", "/api/spline/health"),
        Triple("Fourier", "/api/fourier/upload", "/api/v1/fourier/health")
    )

    @Test
    @Order(1)
    @DisplayName("1. Test All API Health Endpoints")
    fun testAllHealthEndpoints() {
        println("=== Testing All Health Endpoints ===\n")

        val apiTest = ApiTestBase()

        apis.forEach { (name, _, healthEndpoint) ->
            try {
                val isHealthy = apiTest.checkHealth(healthEndpoint)
                val status = if (isHealthy) "✓ HEALTHY" else "✗ UNHEALTHY"
                println("$name API: $status")

                if (!isHealthy) {
                    println("   WARNING: $name API is not healthy")
                }
            } catch (e: Exception) {
                println("$name API: ✗ ERROR (${e.message})")
            }
        }

        apiTest.cleanup()
        println("\n✓ All health checks completed")
    }

    @Test
    @Order(2)
    @DisplayName("2. Test All APIs Basic Functionality")
    fun testAllApisBasicFunctionality() {
        println("=== Testing All APIs Basic Functionality ===\n")

        apis.forEach { (name, endpoint, healthEndpoint) ->
            println("\n--- Testing $name API ---")

            val apiTest = ApiTestBase()

            // Generate appropriate test data
            val testFile = when (name) {
                "Polynomial" -> {
                    val data = TestDataGenerators.generatePolynomialData(2, 3)
                    TestDataGenerators.createPolynomialZipFile(
                        "temp_${name.lowercase()}.zip",
                        data
                    )
                }
                "Spline" -> {
                    val data = TestDataGenerators.generateSplineData(6)
                    TestDataGenerators.createSplineZipFile(
                        "temp_${name.lowercase()}.zip",
                        data
                    )
                }
                "Fourier" -> {
                    val data = TestDataGenerators.generateFourierData(2, 8)
                    TestDataGenerators.createFourierZipFile(
                        "temp_${name.lowercase()}.zip",
                        data
                    )
                }
                else -> throw IllegalArgumentException("Unknown API: $name")
            }

            try {
                println("Uploading test file: ${testFile.name}")
                val response = apiTest.uploadToWebApi(endpoint, testFile)

                println("Response code: ${response.code}")

                if (response.isSuccessful) {
                    val bodyBytes = response.body?.bytes()
                    if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                        println("✓ SUCCESS: Received ${bodyBytes.size} bytes")

                        // Extract and show basic info
                        val extractedFiles = apiTest.extractAndValidateZip(bodyBytes)
                        extractedFiles.forEach { (fileName, content) ->
                            println("  Extracted: $fileName (${content.lines().size} lines)")
                        }
                    } else {
                        println("✓ SUCCESS: Empty response body")
                    }
                } else {
                    println("✗ FAILED: ${response.code} - ${response.body?.string()}")
                }
            } catch (e: Exception) {
                println("✗ ERROR: ${e.message}")
            } finally {
                // Cleanup
                if (testFile.exists()) {
                    testFile.delete()
                }
                apiTest.cleanup()
            }
        }

        println("\n✓ All API tests completed")
    }
}