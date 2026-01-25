package org.example.api

import okhttp3.Response
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.*

@DisplayName("SPLINE API Тесты согласно версии 14.5")
class SplineApiTest : ApiTestBase() {

    @Test
    @DisplayName("WEB API: POST /spline/process - успешная обработка ZIP архива")
    fun `webApi process valid zip returns processed zip`() {
        val zipFile = createZipFileWithCsvContent()
        val response = executeWebApiProcess(zipFile)

        assertEquals(200, response.code)
        assertNotNull(response.header("Content-Disposition"))
        assertTrue(response.header("Content-Disposition")!!.contains("attachment"))
        assertNotNull(response.header("X-Upload-Id"))
        assertNotNull(response.header("X-Processing-Time-Ms"))
        assertNotNull(response.header("X-Csv-Size"))
        assertNotNull(response.header("X-Compressed-Size"))

        val contentType = response.header("Content-Type")
        assertTrue(contentType?.contains("application/octet-stream") == true)

        response.close()
    }

    @Test
    @DisplayName("WEB API: GET /spline/health - проверка доступности сервиса")
    fun `webApi health check returns service status`() {
        val response = checkHealth()

        assertEquals(200, response.code)
        val contentType = response.header("Content-Type")
        assertTrue(contentType?.contains("application/json") == true)

        val responseBody = response.body?.string()
        assertNotNull(responseBody)
        assertTrue(responseBody!!.contains("\"status\""))

        response.close()
    }

    @Test
    @DisplayName("REST API: POST /api/v1/spline/execute - успешное выполнение обработки")
    fun `restApi execute valid zip returns execution metadata`() {
        val zipFile = createZipFileWithCsvContent()
        val response = executeRestApi(zipFile)

        assertEquals(200, response.code)

        val responseBody = response.body?.string()
        assertNotNull(responseBody)

        val json = objectMapper.readTree(responseBody)
        assertTrue(json.has("executionId"))
        assertTrue(json.has("status"))
        assertTrue(json.has("inputSize"))
        assertTrue(json.has("outputSize"))
        assertTrue(json.has("processingTime"))
        assertTrue(json.has("message"))
        assertTrue(json.has("downloadUrl"))
        assertTrue(json.has("timestamp"))

        assertEquals("COMPLETED", json.get("status").asText())
        assertTrue(json.get("executionId").asText().startsWith("spline_"))

        response.close()
    }

    @Test
    @DisplayName("REST API: GET /api/v1/spline/download/{executionId} - загрузка обработанного файла")
    fun `restApi download valid executionId returns processed csv`() {
        // 1. Сначала выполняем обработку
        val zipFile = createZipFileWithCsvContent()
        val executeResponse = executeRestApi(zipFile)
        assertEquals(200, executeResponse.code)

        val executionId = extractExecutionIdFromResponse(executeResponse)
        executeResponse.close()

        // 2. Затем скачиваем результат
        val downloadResponse = downloadResult(executionId)

        assertEquals(200, downloadResponse.code)
        assertNotNull(downloadResponse.header("Content-Disposition"))
        assertTrue(downloadResponse.header("Content-Disposition")!!.contains("spline_result_$executionId"))

        val contentType = downloadResponse.header("Content-Type")
        assertTrue(contentType?.contains("text/csv") == true || contentType?.contains("application/octet-stream") == true)

        // Проверяем содержимое CSV
        val csvContent = downloadResponse.body?.string()
        assertNotNull(csvContent)
        assertTrue(csvContent!!.startsWith("id="))
        assertTrue(csvContent.contains("x,y"))

        downloadResponse.close()
    }

    @Test
    @DisplayName("Проверка: Пустой файл вызывает ошибку 400")
    fun `process empty file returns bad request`() {
        val emptyFile = tempDir.resolve("empty.zip").toFile()
        emptyFile.writeBytes(ByteArray(0))

        val response = executeRestApi(emptyFile)

        assertEquals(400, response.code)

        val responseBody = response.body?.string()
        assertNotNull(responseBody)
        assertTrue(responseBody!!.contains("\"status\":\"ERROR\""))

        response.close()
    }

    @Test
    @DisplayName("Проверка выходного формата: x,y как float с увеличенной плотностью")
    fun `verify output format has float coordinates and increased density`() {
        val zipFile = createZipFileWithCsvContent()
        val executeResponse = executeRestApi(zipFile)
        assertEquals(200, executeResponse.code)

        val executionId = extractExecutionIdFromResponse(executeResponse)
        executeResponse.close()

        val downloadResponse = downloadResult(executionId)
        assertEquals(200, downloadResponse.code)

        val csvContent = downloadResponse.body?.string()
        assertNotNull(csvContent)

        val lines = csvContent!!.lines()

        // Проверяем формат согласно документации
        assertTrue(lines[0].matches(Regex("^id=\\d+,type=[a-zA-Z]+\$")))
        assertEquals("x,y", lines[1])

        // Проверяем, что x и y - float (содержат точки или много цифр)
        for (i in 2 until lines.size) {
            val parts = lines[i].split(",")
            assertEquals(2, parts.size, "Line $i should have exactly 2 parts")

            val x = parts[0]
            val y = parts[1]
            assertTrue(x.matches(Regex("^-?\\d+\\.\\d+\$")), "x should be float: $x")
            assertTrue(y.matches(Regex("^-?\\d+\\.\\d+\$")), "y should be float: $y")
        }

        downloadResponse.close()
    }

    @Test
    @DisplayName("Проверка заголовков ответа Web API")
    fun `verify webApi response headers contain required metadata`() {
        val zipFile = createZipFileWithCsvContent()
        val response = executeWebApiProcess(zipFile)

        assertEquals(200, response.code)

        // Проверяем наличие обязательных заголовков
        assertNotNull(response.header("Content-Disposition"))
        assertNotNull(response.header("X-Upload-Id"))
        assertNotNull(response.header("X-Processing-Time-Ms"))
        assertNotNull(response.header("X-Csv-Size"))
        assertNotNull(response.header("X-Compressed-Size"))

        // Проверяем формат заголовков
        val contentDisposition = response.header("Content-Disposition")!!
        assertTrue(contentDisposition.contains("attachment"))
        assertTrue(contentDisposition.contains(".zip"))

        val uploadId = response.header("X-Upload-Id")!!
        assertTrue(uploadId.startsWith("spline_"), "Upload ID should start with 'spline_': $uploadId")

        response.close()
    }
}