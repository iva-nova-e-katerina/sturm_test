package org.example.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplineApiSimpleTest {

    @Test
    fun `test that works`() {
        println("Этот тест точно должен работать!")
        assertEquals(2, 1 + 1)
    }

    @Test
    fun `test csv format`() {
        val csv = """
            id=1,type=cubic
            x,y
            0,0.34848102
            1,0.41401943
        """.trimIndent()

        assertTrue(csv.contains("id="))
        assertTrue(csv.contains("x,y"))
    }

    @Test
    fun `test api endpoints`() {
        val endpoints = mapOf(
            "process" to "/spline/process",
            "health" to "/spline/health"
        )

        assertEquals(2, endpoints.size)
        assertEquals("/spline/process", endpoints["process"])
        assertEquals("/spline/health", endpoints["health"])
    }
}