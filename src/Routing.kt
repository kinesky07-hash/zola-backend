package com.zola

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

private class ZolaApiService(private val client: HttpClient) {
    suspend fun getFxRates(): JsonObject {
        val key = requiredApiKey("EXCHANGERATE_API_KEY")
        val response = getJson(
            "https://v6.exchangerate-api.com/v6/$key/latest/USD",
            "ExchangeRate API"
        )
        val rates = response["conversion_rates"]?.jsonObject
            ?.mapValues {
                it.value.jsonPrimitive.doubleOrNull
                    ?: error("Exchange rate response contained a non-numeric rate")
            }
            ?: error("Exchange rate response did not contain conversion_rates")
        return buildJsonObject {
            put("base", JsonPrimitive("USD"))
            put("rates", buildJsonObject {
                rates.forEach { (currency, rate) -> put(currency, JsonPrimitive(rate)) }
            })
        }
    }

    suspend fun getMetalsSpot(): JsonObject {
        val key = requiredApiKey("UNIRATE_API_KEY")
        val response = getJson("https://api.unirateapi.com/api/commodities/rates", "UniRate API") {
            parameter("api_key", key)
            parameter("from", "USD")
            parameter("format", "json")
        }
        val rates = response["rates"]?.jsonObject
            ?.mapValues {
                it.value.jsonPrimitive.doubleOrNull
                    ?: error("Metals response contained a non-numeric rate")
            }
            ?: error("Metals response did not contain rates")
        return buildJsonObject {
            put("currency", JsonPrimitive("USD"))
            put("rates", buildJsonObject {
                rates.forEach { (symbol, rate) -> put(symbol, JsonPrimitive(rate)) }
            })
        }
    }

    suspend fun getCpiData(): JsonObject {
        val response = getJson(
            "https://sdmx.oecd.org/public/rest/data/" +
                "OECD.SDD.TPS,DSD_PRICES@DF_PRICES_HICP,1.0/" +
                ".M.HICP.CPI.PA._T.N.GY?startPeriod=2025-06",
            "OECD SDMX API"
        ) {
            header(HttpHeaders.Accept, "application/json")
        }
        val series = response["dataSets"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("series")?.jsonObject
            ?: error("Invalid OECD SDMX response structure")
        val observations = series.values
            .flatMap { seriesEntry ->
                seriesEntry.jsonObject["observations"]?.jsonObject?.entries.orEmpty()
            }
            .mapNotNull { (index, values) ->
                val observation = values.jsonArray.firstOrNull()?.jsonPrimitive?.doubleOrNull
                index.toIntOrNull()?.let { it to observation }
            }
            .filter { (_, value) -> value != null && value in -50.0..50.0 }
        val yoyValue = observations.maxByOrNull { it.first }?.second
            ?: error("Could not find a plausible CPI value")
        val monthlyRate = Math.pow(1 + yoyValue / 100.0, 1.0 / 12.0) - 1
        return buildJsonObject {
            put("yoyPercent", JsonPrimitive(yoyValue))
            put("period", JsonPrimitive("Latest"))
            put("monthlyRate", JsonPrimitive(monthlyRate))
            put("source", JsonPrimitive("OECD SDMX"))
        }
    }

    private suspend fun getJson(
        url: String,
        provider: String,
        configure: HttpRequestBuilder.() -> Unit = {}
    ): JsonObject {
        val response = client.get(url) {
            configure()
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("$provider returned ${response.status.value}: ${body.take(200)}")
        }
        return Json.parseToJsonElement(body).jsonObject
    }
}

private fun requiredApiKey(name: String): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable: $name")

fun Application.configureRouting() {
    val zolaApiService = ZolaApiService(httpClient)

    routing {
        get("/") {
            call.respondText("Hello, World!")
        }

        get("/api/health") {
            call.respond(mapOf("ok" to true))
        }

        get("/api/fred/usd-index") {
            val apiKey = System.getenv("FRED_API_KEY")
                ?: return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Missing FRED_API_KEY")
                )

            val response = httpClient.get("https://api.stlouisfed.org/fred/series") {
                parameter("series_id", "DTWEXBGS")
                parameter("api_key", apiKey)
                parameter("file_type", "json")
            }

            call.respondText(
                response.bodyAsText(),
                ContentType.Application.Json,
                response.status
            )
        }

        get("/api/rates") {
            call.respond(zolaApiService.getFxRates())
        }

        get("/api/metals") {
            call.respond(zolaApiService.getMetalsSpot())
        }

        get("/api/economy/cpi") {
            call.respond(zolaApiService.getCpiData())
        }
    }
}