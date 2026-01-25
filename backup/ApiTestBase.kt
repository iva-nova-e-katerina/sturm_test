package org.example.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

open class ApiTestBase {

    companion object {
        protected val BASE_URL = "https://iceja.net"
        protected val API_BASE_URL = "$BASE_URL/api/v1"

        protected val objectMapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())

        protected val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @TempDir
    protected lateinit var tempDir: Path

    protected fun createValidCsvContent(signalId: Int = 1, type: String = "cubic"): String {
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

    protected fun createCsvFile(filename: String, content: String = createValidCsvContent()): File {
        val file = tempDir.resolve(filename).toFile()
        file.writeText(content)
        return file
    }

    protected fun createZipFile(csvFile: File, zipFilename: String = "data.zip"): File {
        val zipFile = tempDir.resolve(zipFilename).toFile()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry(csvFile.name)
            zos.putNextEntry(entry)
            zos.write(csvFile.readBytes())
            zos.closeEntry()
        }

        return zipFile
    }

    protected fun createZipFileWithCsvContent(
        csvContent: String = createValidCsvContent(),
        zipFilename: String = "data.zip",
        csvFilename: String = "data.csv"
    ): File {
        val csvFile = createCsvFile(csvFilename, csvContent)
        return createZipFile(csvFile, zipFilename)
    }

    protected fun executeWebApiProcess(zipFile: File): Response {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/spline/process")
            .post(requestBody)
            .build()

        return httpClient.newCall(request).execute()
    }

    protected fun executeRestApi(zipFile: File): Response {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$API_BASE_URL/spline/execute")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .build()

        return httpClient.newCall(request).execute()
    }

    protected fun downloadResult(executionId: String): Response {
        val request = Request.Builder()
            .url("$API_BASE_URL/spline/download/$executionId")
            .get()
            .build()

        return httpClient.newCall(request).execute()
    }

    protected fun checkHealth(): Response {
        val request = Request.Builder()
            .url("$BASE_URL/spline/health")
            .get()
            .build()

        return httpClient.newCall(request).execute()
    }

    protected inline fun <reified T> parseJsonResponse(response: Response): T {
        val responseBody = response.body?.string() ?: throw IllegalStateException("Response body is null")
        return objectMapper.readValue(responseBody)
    }

    protected fun extractExecutionIdFromResponse(response: Response): String {
        val jsonResponse: Map<String, Any> = parseJsonResponse(response)
        return jsonResponse["executionId"] as? String ?: throw IllegalStateException("No executionId in response")
    }
}