package com.zola

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `test health endpoint`() = testApplication {
        install(ContentNegotiation) {
            json()
        }
        routing {
            get("/api/health") {
                call.respond(mapOf("ok" to true))
            }
        }
        // verify health endpoint returns 200
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

}
