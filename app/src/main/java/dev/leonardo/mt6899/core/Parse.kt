package dev.leonardo.mt6899.core

object Parse {
    private val intRe = Regex("-?\\d+")
    private val wsRe = Regex("\\s+")
    private val oppRe = Regex("""\[(\d+)\*?]\s*freq:\s*(\d+),\s*volt:\s*(\d+),\s*vsram:\s*(\d+)""")
    private val limitRe = Regex(
        """^\s*(\d+)\s+(\S+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(\d+)\s+(\d+)\s*$""",
        RegexOption.MULTILINE
    )
    private val oppIdxRe = Regex("""\[GPU\s+OPP]\s+Index:\s*(-?\d+)""")
    private val ceilRe = Regex("""\[PPM Ceiling]\s+LimitIndex:\s*(-?\d+),\s*Limiter:\s*(-?\d+)""")
    private val floorRe = Regex("""\[PPM Floor]\s+LimitIndex:\s*(-?\d+),\s*Limiter:\s*(-?\d+)""")

    fun firstLine(raw: String?): String =
        raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: ""

    fun intOf(raw: String?): Long? = intRe.find(firstLine(raw))?.value?.toLongOrNull()

    fun longs(raw: String?): List<Long> {
        val s = raw?.trim() ?: return emptyList()
        return wsRe.split(s).mapNotNull { it.toLongOrNull() }
    }

    fun words(raw: String?): List<String> {
        val s = raw?.trim() ?: return emptyList()
        return wsRe.split(s).filter { it.isNotEmpty() }
    }

    fun oppTable(raw: String?): List<GpuOpp> =
        oppRe.findAll(raw ?: "").map {
            GpuOpp(it.groupValues[1].toInt(), it.groupValues[2].toLong(), it.groupValues[3].toLong())
        }.toList()

    fun limitTable(raw: String?): List<GpuLimit> =
        limitRe.findAll(raw ?: "").map {
            val g = it.groupValues
            GpuLimit(g[1].toInt(), g[2], g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6] != "0", g[7] != "0")
        }.toList()

    fun gpuOppIndex(raw: String?): Int? = oppIdxRe.find(raw ?: "")?.groupValues?.get(1)?.toIntOrNull()

    fun ppmCeiling(raw: String?): Pair<Int, Int>? = pair(ceilRe.find(raw ?: ""))

    fun ppmFloor(raw: String?): Pair<Int, Int>? = pair(floorRe.find(raw ?: ""))

    private fun pair(m: MatchResult?): Pair<Int, Int>? {
        if (m == null) return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return Pair(a, b)
    }

    /** "mq-deadline [kyber] none" -> ("kyber", ["mq-deadline","kyber","none"]) */
    fun scheduler(raw: String?): Pair<String, List<String>> {
        val w = words(raw)
        val cur = w.firstOrNull { it.startsWith("[") }?.trim('[', ']') ?: ""
        return Pair(cur, w.map { it.trim('[', ']') })
    }

    fun gedCurrent(raw: String?): Pair<Int, Long>? {
        val l = longs(firstLine(raw))
        if (l.size < 2) return null
        return Pair(l[0].toInt(), l[1])
    }

    /** Compara o valor pedido com o lido (aceita "[valor]" em listas de scheduler). */
    fun same(want: String, got: String): Boolean {
        val w = want.trim()
        if (w.isEmpty()) return false
        if (got.contains("[$w]") || firstLine(got) == w) return true
        val a = w.toLongOrNull()
        val b = intOf(got)
        return a != null && b != null && a == b
    }
}
