package com.aethernova.nova.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Barcode lookups via Open Food Facts (free, open database, no key needed).
 * Values are per 100 g/ml as printed on the pack.
 */
object FoodFacts {
    data class Product(
        val barcode: String, val name: String, val brand: String,
        val kcal100: Double, val protein100: Double, val carbs100: Double, val fat100: Double,
        val sugar100: Double?, val servingG: Double?,
    ) {
        /** UK front-of-pack "high" sugar threshold is over 22.5 g per 100 g. */
        val highSugar get() = (sugar100 ?: 0.0) > 22.5
        /** UK front-of-pack thresholds: high fat > 17.5 g/100 g. */
        val liver get() = when {
            highSugar || fat100 > 17.5 -> "red"
            protein100 >= 10 && (sugar100 ?: 0.0) < 5 && fat100 < 3 -> "green"
            else -> "amber"
        }

        fun toEntry(grams: Double, day: String, now: Long) = FoodEntry(
            timestamp = now, day = day, name = listOf(brand, name).filter { it.isNotBlank() }.joinToString(" ").take(60),
            portion = "${grams.toInt()} g", kcal = (kcal100 * grams / 100).toInt(),
            proteinG = protein100 * grams / 100, carbsG = carbs100 * grams / 100, fatG = fat100 * grams / 100, source = "barcode", liver = liver,
        )
    }

    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()

    suspend fun lookup(barcode: String): Product? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands,nutriments,serving_quantity")
            .header("User-Agent", "Nova-HealthBuddy/0.4 (Android; personal use)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            parse(barcode, resp.body!!.string())
        }
    }

    fun parse(barcode: String, json: String): Product? {
        val root = JSONObject(json)
        if (root.optInt("status", 0) != 1) return null
        val p = root.optJSONObject("product") ?: return null
        val n = p.optJSONObject("nutriments") ?: JSONObject()
        fun num(key: String): Double? = if (n.has(key)) n.optDouble(key).takeIf { !it.isNaN() } else null
        val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.let { it / 4.184 } ?: 0.0
        return Product(
            barcode = barcode,
            name = p.optString("product_name").ifBlank { "Scanned item" },
            brand = p.optString("brands").split(',').first().trim(),
            kcal100 = kcal, protein100 = num("proteins_100g") ?: 0.0,
            carbs100 = num("carbohydrates_100g") ?: 0.0, fat100 = num("fat_100g") ?: 0.0,
            sugar100 = num("sugars_100g"),
            servingG = p.optString("serving_quantity").toDoubleOrNull()?.takeIf { it > 0 },
        )
    }
}
