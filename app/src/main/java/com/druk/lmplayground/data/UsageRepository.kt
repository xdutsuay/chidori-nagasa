package com.druk.lmplayground.data

class UsageRepository(private val dao: UsageEventDao) {
    suspend fun record(event: UsageEventEntity) {
        dao.insert(event)
    }

    suspend fun since(sinceMs: Long): List<UsageEventEntity> = dao.since(sinceMs)

    suspend fun clear() = dao.deleteAll()
}
