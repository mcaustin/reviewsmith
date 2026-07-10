fun nonEmpty(items: List<String>): List<String> {
    val result = mutableListOf<String>()
    for (item in items) {
        if (item.isNotEmpty()) {
            result.add(item)
        }
    }
    return result
}
