package toby.helpers

interface AsyncOperation<T> {
    suspend fun execute(typeValue: String?, query: String, httpHelper: HttpHelper): T
}