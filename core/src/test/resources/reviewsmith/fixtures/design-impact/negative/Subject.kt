class PriceCalculator {
    private fun total(items: List<Item>, currency: Currency): Money =
        items.fold(Money.zero(currency)) { acc, i -> acc + i.price }

    fun checkoutTotal(items: List<Item>): Money = total(items, Currency.USD)
}
