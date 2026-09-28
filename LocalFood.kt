package com.aethernova.nova.data

/**
 * Offline food table of common everyday foods (UK + South Indian staples). Used for quick-add chips and as a
 * fallback when there is no internet or the AI quota is used up. Values are per "unit".
 * Approximate values — good enough for daily tracking, not lab precision.
 */
object LocalFood {
    data class Item(
        val keys: List<String>, val name: String, val unit: String,
        val kcal: Int, val protein: Double, val carbs: Double, val fat: Double,
        /** Unit is this many pieces (e.g. "10 nuts"): "7 almonds" then means 0.7 of a unit, not 7 units. */
        val pieces: Int = 1,
        /** Unit weight/volume in g or ml, so "300 g kappa" or "150ml milk" scale correctly. 0 = not weight-based. */
        val grams: Int = 0,
    )

    val items = listOf(
        Item(listOf("egg", "eggs", "mutta"), "Egg", "1 large", 72, 6.3, 0.4, 4.8),
        Item(listOf("banana", "bananas", "pazham"), "Banana", "1 medium", 105, 1.3, 27.0, 0.4),
        Item(listOf("milk", "paal"), "Whole milk", "250 ml glass", 160, 8.3, 12.0, 9.0, grams = 250),
        Item(listOf("almond", "almonds", "badam"), "Almonds", "10 nuts", 70, 2.5, 2.5, 6.0, pieces = 10),
        Item(listOf("cashew", "cashews"), "Cashews", "10 nuts", 90, 2.5, 5.0, 7.0, pieces = 10),
        Item(listOf("dosa", "dosas", "atta dosa"), "Atta dosa", "1 medium", 110, 3.5, 20.0, 2.0),
        Item(listOf("chicken", "chicken breast"), "Chicken breast (cooked)", "100 g", 165, 31.0, 0.0, 3.6, grams = 100),
        Item(listOf("fish curry", "fish", "meen"), "Fish curry", "1 bowl (~150 g fish)", 260, 30.0, 6.0, 12.0),
        Item(listOf("squid", "kanava"), "Squid (cooked)", "100 g", 175, 18.0, 8.0, 7.0, grams = 100),
        Item(listOf("beef"), "Beef curry", "1 bowl (~120 g)", 300, 28.0, 6.0, 18.0),
        Item(listOf("rice", "choru"), "Rice (60 g dry)", "1 portion cooked", 215, 4.3, 47.0, 0.4),
        Item(listOf("oats"), "Oats (40 g)", "1 bowl", 150, 5.0, 27.0, 2.8, grams = 40),
        Item(listOf("wrap", "wraps", "chapati", "chapathi"), "Whole-wheat wrap", "1 wrap", 130, 4.5, 22.0, 3.0),
        // Only plain skyr / Greek counts as high-protein; a bare "yogurt" is ordinary yogurt (v1.0.1 fix)
        Item(listOf("skyr", "greek yogurt", "plain greek", "greek yoghurt"), "Skyr / Greek yogurt (plain)", "150 g pot", 95, 16.0, 6.0, 0.3, grams = 150),
        Item(listOf("flavour yogurt", "flavoured yogurt", "flavored yogurt", "fruit yogurt", "strawberry yogurt", "vanilla yogurt",
            "mango yogurt", "flavour yoghurt", "flavoured yoghurt", "fruit yoghurt"), "Flavoured yogurt", "150 g pot", 140, 6.0, 21.0, 3.5, grams = 150),
        Item(listOf("yogurt", "yoghurt", "curd", "thairu", "dahi"), "Yogurt / curd (plain)", "150 g", 95, 5.5, 7.0, 5.0, grams = 150),
        Item(listOf("kappa", "tapioca", "cassava", "kappa puzhukku"), "Kappa (tapioca), cooked", "250 g plate", 420, 3.0, 95.0, 4.0, grams = 250),
        Item(listOf("bru", "coffee", "chai", "tea with milk"), "Coffee / tea with milk", "1 cup (~100 ml milk)", 70, 3.3, 6.0, 3.5),
        Item(listOf("biryani"), "Buffalo biryani", "1 plate", 650, 32.0, 75.0, 22.0),
        Item(listOf("apple"), "Apple", "1 medium", 95, 0.5, 25.0, 0.3),
        Item(listOf("orange"), "Orange", "1 medium", 62, 1.2, 15.0, 0.2),
        Item(listOf("pear"), "Pear", "1 medium", 100, 0.6, 27.0, 0.2),
        Item(listOf("pickle", "achar"), "Pickle (drained)", "1 tbsp", 35, 1.0, 1.0, 3.0),
    )

    val quickAdd = listOf("egg", "banana", "milk", "almond", "dosa", "chicken", "fish curry", "skyr", "rice", "wrap")
        .map { k -> items.first { k in it.keys } }

    private val numberWords = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "half" to 0.5, "onnu" to 1.0, "randu" to 2.0, "moonu" to 3.0,
    )

    /**
     * Very small offline parser: "had 3 dosas, 2 eggs and a glass of milk".
     * Returns matched items with quantities. Anything it can't match is ignored.
     */
    fun parse(text: String): List<Pair<Item, Double>> {
        val lower = text.lowercase()
        val found = mutableListOf<Pair<Item, Double>>()
        val used = mutableSetOf<String>()
        val taken = mutableListOf<IntRange>()
        // longest keys first so "fish curry" wins over "fish"
        val keyed = items.flatMap { item -> item.keys.map { it to item } }.sortedByDescending { it.first.length }
        for ((key, item) in keyed) {
            if (item.name in used) continue
            val m = Regex("""(?:(\d+(?:\.\d+)?)|\b(a|an|one|two|three|four|five|six|half|onnu|randu|moonu)\b)?\s*(?:glass(?:es)? of |bowl(?:s)? of |cups? of |plates? of )?\b${Regex.escape(key)}\b""")
                .findAll(lower).firstOrNull { r -> taken.none { it.first <= r.range.last && r.range.first <= it.last } } ?: continue
            taken += m.range   // "greek yogurt" must not also count as a plain "yogurt"
            // "300 g kappa" / "150ml milk" / "200 grams of chicken" -> scale by the unit weight
            val weighed = if (item.grams > 0) Regex("""(\d+(?:\.\d+)?)\s*(g|gm|grams?|ml)\s+(?:of\s+)?(?:\w+\s+)?${Regex.escape(key)}\b""").find(lower)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.let { it / item.grams } else null
            val number = m.groupValues[1].toDoubleOrNull() ?: numberWords[m.groupValues[2]]
            val qty = weighed ?: when {
                number == null -> 1.0
                item.pieces > 1 && m.groupValues[1].isNotEmpty() -> number / item.pieces   // "7 almonds" = 7 nuts
                else -> number
            }
            found += item to qty
            used += item.name
        }
        return found
    }

    data class Detail(val found: List<Pair<Item, Double>>, val unmatched: List<String>)

    private val filler = Regex("""\b(i|i've|ive|just|had|have|ate|eaten|eat|drank|drink|log|logged|some|a|an|the|my|for|lunch|dinner|breakfast|snack|today|and|with|of|bit|little|kazhichu|also|then|now|plus)\b""")

    /** Like [parse], but also returns the parts of the message it could not match, so nothing is silently dropped. */
    fun parseDetailed(text: String): Detail {
        val found = parse(text)
        val keys = items.flatMap { it.keys }
        val unmatched = text.lowercase().split(Regex(""",|;|\+|&|\n|\band\b|\bwith\b|\bplus\b"""))
            .map { it.trim() }
            .filter { seg -> seg.isNotBlank() && keys.none { k -> Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(seg) } }
            .map { it.replace(filler, " ").replace(Regex("""\b\d+(\.\d+)?\s*(g|kg|ml|l|grams?|pcs?|pieces?)?\b"""), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 }
        return Detail(found, unmatched.distinct())
    }

    fun toEntry(item: Item, qty: Double, source: String, day: String, now: Long) = FoodEntry(
        timestamp = now, day = day, name = item.name,
        portion = "${fmt(qty)} × ${item.unit}",
        kcal = (item.kcal * qty).toInt(), proteinG = item.protein * qty,
        carbsG = item.carbs * qty, fatG = item.fat * qty, source = source, liver = LiverScore.local(item.name),
    )

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')
}
