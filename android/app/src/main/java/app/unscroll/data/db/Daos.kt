package app.unscroll.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyUsageDao {
    @Query("SELECT * FROM daily_usage WHERE dayStart >= :from AND dayStart < :toExclusive")
    suspend fun between(from: Long, toExclusive: Long): List<DailyUsage>

    @Query("DELETE FROM daily_usage WHERE dayStart = :dayStart")
    suspend fun deleteDay(dayStart: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<DailyUsage>)

    @Query("SELECT * FROM daily_usage")
    suspend fun allRows(): List<DailyUsage>
}

@Dao
interface SessionDao {
    @Query("DELETE FROM sessions WHERE dayStart = :dayStart")
    suspend fun deleteDay(dayStart: Long)

    @Insert
    suspend fun insertAll(rows: List<Session>)

    @Query("SELECT * FROM sessions")
    suspend fun all(): List<Session>

    @Query("UPDATE sessions SET endMillis = :endMillis WHERE id = :id")
    suspend fun updateEnd(id: Long, endMillis: Long)

    @Query(
        "SELECT * FROM sessions WHERE packageName = :packageName AND dayStart >= :from AND dayStart < :toExclusive ORDER BY startMillis",
    )
    suspend fun forPackage(packageName: String, from: Long, toExclusive: Long): List<Session>
}

@Dao
interface AppCategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: AppCategory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AppCategory)

    @Query("SELECT * FROM app_categories WHERE packageName = :packageName")
    suspend fun find(packageName: String): AppCategory?

    @Query("SELECT * FROM app_categories")
    suspend fun all(): List<AppCategory>

    @Query("DELETE FROM app_categories")
    suspend fun deleteAll()

    @Query("UPDATE app_categories SET categoryKey = :toKey, updatedAt = :now WHERE categoryKey = :fromKey")
    suspend fun moveAll(fromKey: String, toKey: String, now: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<Category>)

    @Insert
    suspend fun insert(row: Category)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: Category)

    @Query("SELECT * FROM categories ORDER BY position")
    suspend fun all(): List<Category>

    @Query("SELECT * FROM categories WHERE `key` = :key")
    suspend fun find(key: String): Category?

    @Query("DELETE FROM categories WHERE `key` = :key AND custom = 1")
    suspend fun deleteCustom(key: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(MAX(position), 0) FROM categories")
    suspend fun maxPosition(): Int
}

@Dao
interface DailyTotalDao {
    @Query("SELECT * FROM daily_totals WHERE dayStart = :dayStart")
    suspend fun find(dayStart: Long): DailyTotal?

    @Query("SELECT * FROM daily_totals WHERE dayStart >= :from AND dayStart < :toExclusive")
    suspend fun between(from: Long, toExclusive: Long): List<DailyTotal>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyTotal)

    @Query("SELECT * FROM daily_totals")
    suspend fun all(): List<DailyTotal>
}

@Dao
interface NotificationCountDao {
    @Query(
        "UPDATE notification_counts SET count = count + 1 WHERE dayStart = :dayStart AND packageName = :packageName",
    )
    suspend fun bump(dayStart: Long, packageName: String): Int

    @Insert
    suspend fun insert(row: NotificationCount)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NotificationCount)

    @Query("SELECT * FROM notification_counts WHERE dayStart >= :from AND dayStart < :toExclusive")
    suspend fun between(from: Long, toExclusive: Long): List<NotificationCount>

    @Query("SELECT * FROM notification_counts")
    suspend fun all(): List<NotificationCount>
}

@Dao
interface UsageLimitDao {
    @Query("SELECT * FROM usage_limits WHERE targetKey = :targetKey")
    suspend fun find(targetKey: String): UsageLimit?

    @Query("SELECT * FROM usage_limits")
    suspend fun all(): List<UsageLimit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UsageLimit)

    @Query("DELETE FROM usage_limits WHERE targetKey = :targetKey")
    suspend fun delete(targetKey: String)

    @Query("DELETE FROM usage_limits")
    suspend fun deleteAll()
}

@Dao
interface InstantBlockDao {
    @Query("SELECT * FROM instant_blocks WHERE targetKey = :targetKey")
    suspend fun find(targetKey: String): InstantBlock?

    @Query("SELECT * FROM instant_blocks")
    suspend fun all(): List<InstantBlock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: InstantBlock)

    @Query("DELETE FROM instant_blocks WHERE targetKey = :targetKey")
    suspend fun delete(targetKey: String)

    @Query("DELETE FROM instant_blocks WHERE untilMillis <= :now AND untilMillis < :permanent")
    suspend fun deleteExpired(now: Long, permanent: Long)

    @Query("DELETE FROM instant_blocks")
    suspend fun deleteAll()
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY id")
    suspend fun all(): List<BlockSchedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun find(id: Long): BlockSchedule?

    @Query("SELECT * FROM schedules WHERE kind = :kind LIMIT 1")
    suspend fun findKind(kind: String): BlockSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BlockSchedule): Long

    @Query("DELETE FROM schedules WHERE id = :id AND kind = 'window'")
    suspend fun deleteWindow(id: Long)

    @Query("DELETE FROM schedules WHERE kind = :kind AND id != :keep")
    suspend fun deleteKindExcept(kind: String, keep: Long)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()
}

@Dao
interface FocusRoutineDao {
    @Query("SELECT * FROM focus_routines ORDER BY startMinute, id")
    suspend fun all(): List<FocusRoutine>

    @Query("SELECT * FROM focus_routines WHERE id = :id")
    suspend fun find(id: Long): FocusRoutine?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FocusRoutine): Long

    @Query("DELETE FROM focus_routines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM focus_routines")
    suspend fun deleteAll()
}

@Dao
interface WebDailyDao {
    @Query("SELECT * FROM web_daily WHERE dayStart >= :from AND dayStart < :toExclusive")
    suspend fun between(from: Long, toExclusive: Long): List<WebDaily>

    @Query(
        "UPDATE web_daily SET durationMillis = durationMillis + :delta, visitCount = visitCount + :visits WHERE dayStart = :dayStart AND domain = :domain",
    )
    suspend fun bump(dayStart: Long, domain: String, delta: Long, visits: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: WebDaily)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WebDaily)

    @Query("SELECT * FROM web_daily")
    suspend fun all(): List<WebDaily>
}

@Dao
interface DailyOpenDao {
    @Query("SELECT * FROM daily_opens WHERE dayStart = :dayStart")
    suspend fun forDay(dayStart: Long): List<DailyOpen>

    @Query(
        "UPDATE daily_opens SET count = count + 1 WHERE dayStart = :dayStart AND targetKey = :targetKey",
    )
    suspend fun bump(dayStart: Long, targetKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: DailyOpen)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DailyOpen)

    @Query("SELECT * FROM daily_opens")
    suspend fun all(): List<DailyOpen>
}

@Dao
interface CooldownDao {
    @Query("SELECT * FROM cooldowns")
    suspend fun all(): List<Cooldown>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: Cooldown)

    @Query("DELETE FROM cooldowns WHERE targetKey = :targetKey")
    suspend fun delete(targetKey: String)

    @Query("DELETE FROM cooldowns WHERE untilMillis <= :now")
    suspend fun deleteExpired(now: Long)
}

@Dao
interface ExtraGrantDao {
    @Query("SELECT * FROM extra_grants WHERE dayStart = :dayStart")
    suspend fun forDay(dayStart: Long): List<ExtraGrant>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ExtraGrant)

    @Query("SELECT * FROM extra_grants")
    suspend fun all(): List<ExtraGrant>

    @Query(
        "UPDATE extra_grants SET sessionCeilingMillis = 0 WHERE dayStart = :dayStart AND targetKey = :targetKey",
    )
    suspend fun clearCeiling(dayStart: Long, targetKey: String)
}

@Dao
interface LimitWarningDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LimitWarning): Long

    @Query("SELECT * FROM limit_warnings")
    suspend fun all(): List<LimitWarning>

    @Query("DELETE FROM limit_warnings WHERE dayStart = :dayStart AND targetKey = :targetKey")
    suspend fun deleteFor(dayStart: Long, targetKey: String)
}
