class CheckoutService(private val calculator: PriceCalculator) {
    fun checkout(items: List<Item>): Money = calculator.checkoutTotal(items)
}
