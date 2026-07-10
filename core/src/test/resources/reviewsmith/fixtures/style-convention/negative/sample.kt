class UserRepository {
    fun loadUser(id: String): User? = store[id]
    fun loadUserByEmail(email: String): User? = store.values.find { it.email == email }
    fun loadUsers(ids: List<String>): List<User> = ids.mapNotNull { store[it] }
}
