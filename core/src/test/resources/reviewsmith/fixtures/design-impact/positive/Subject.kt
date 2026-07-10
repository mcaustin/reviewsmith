class PriceCalculator {
    fun total(items: List<Item>, currency: Currency): Money =
        items.fold(Money.zero(currency)) { acc, i -> acc + i.price }
}
