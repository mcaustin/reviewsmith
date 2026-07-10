class UserRepository {
    fun loadUser(id: String): User? = store[id]
    fun loadUserByEmail(email: String): User? = store.values.find { it.email == email }
    fun fetchUserData(id: String): User? = store[id]
}
