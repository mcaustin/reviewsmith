class SignInHandler(private val log: Logger) {
    fun onSignIn(user: User) {
        log.info("User {} signed in", user.id)
    }
}
