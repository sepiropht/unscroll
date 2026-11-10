package app.unscroll.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DailyUsage::class,
        Session::class,
        AppCategory::class,
        Category::class,
        DailyTotal::class,
        NotificationCount::class,
        UsageLimit::class,
        InstantBlock::class,
        BlockSchedule::class,
        WebDaily::class,
        DailyOpen::class,
        Cooldown::class,
        ExtraGrant::class,
        LimitWarning::class,
        FocusRoutine::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class UnscrollDatabase : RoomDatabase() {
    abstract fun dailyUsage(): DailyUsageDao
    abstract fun sessions(): SessionDao
    abstract fun appCategories(): AppCategoryDao
    abstract fun categories(): CategoryDao
    abstract fun dailyTotals(): DailyTotalDao
    abstract fun notificationCounts(): NotificationCountDao
    abstract fun usageLimits(): UsageLimitDao
    abstract fun instantBlocks(): InstantBlockDao
    abstract fun schedules(): ScheduleDao
    abstract fun webDaily(): WebDailyDao
    abstract fun dailyOpens(): DailyOpenDao
    abstract fun cooldowns(): CooldownDao
    abstract fun extraGrants(): ExtraGrantDao
    abstract fun limitWarnings(): LimitWarningDao
    abstract fun focusRoutines(): FocusRoutineDao

    companion object {
        @Volatile
        private var instance: UnscrollDatabase? = null

        fun get(context: Context): UnscrollDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UnscrollDatabase::class.java,
                    "unscroll.db",
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
        }
    }
}
