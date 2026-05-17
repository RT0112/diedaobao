package com.falldetector.diedaobao.db

import android.content.Context
import androidx.room.*

/**
 * 跌倒事件实体（供反馈系统使用）
 * v0.30.9 新增
 */
@Entity(tableName = "fall_events")
data class FallEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "ff_time_ms")
    val ffTimeMs: Long = 0,
    
    @ColumnInfo(name = "impact_strength")
    val impactStrength: Float = 0f,
    
    @ColumnInfo(name = "ml_probability")
    val mlProbability: Float = 0f,
    
    @ColumnInfo(name = "physics_score")
    val physicsScore: Float = 0f,
    
    @ColumnInfo(name = "weighted_score")
    val weightedScore: Float = 0f,
    
    @ColumnInfo(name = "decision_path")
    val decisionPath: String = "",
    
    @ColumnInfo(name = "sensor_data_json")
    val sensorDataJson: String? = null,
    
    @ColumnInfo(name = "device_model")
    val deviceModel: String = android.os.Build.MODEL,
    
    @ColumnInfo(name = "android_version")
    val androidVersion: String = android.os.Build.VERSION.RELEASE,
    
    @ColumnInfo(name = "app_version")
    val appVersion: String = "",
    
    @ColumnInfo(name = "version_code")
    val versionCode: Int = 0,
    
    @ColumnInfo(name = "sensitivity_level")
    val sensitivityLevel: Int = 4,
    
    @ColumnInfo(name = "scene_category")
    var sceneCategory: String? = null,
    
    @ColumnInfo(name = "scene_description")
    var sceneDescription: String? = null,
    
    @ColumnInfo(name = "feedback_submitted")
    var feedbackSubmitted: Boolean = false
) {
    /** 格式化时间 */
    fun timeStr(): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    /** 简短摘要 */
    fun summary(): String = "${timeStr()} | FF=${ffTimeMs}ms 冲击=${String.format("%.1f", impactStrength)}g ML=${String.format("%.2f", mlProbability)}"
}

/**
 * FallEvent DAO
 */
@Dao
interface FallEventDao {
    @Insert
    suspend fun insert(event: FallEvent): Long
    
    @Query("SELECT * FROM fall_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 50): List<FallEvent>
    
    @Query("SELECT * FROM fall_events WHERE id = :id")
    suspend fun getById(id: Long): FallEvent?
    
    @Update
    suspend fun update(event: FallEvent)
    
    @Query("UPDATE fall_events SET feedback_submitted = 1 WHERE id = :id")
    suspend fun markFeedbackSubmitted(id: Long)
    
    @Query("DELETE FROM fall_events WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("SELECT COUNT(*) FROM fall_events")
    suspend fun getCount(): Int
}

/**
 * App 数据库
 */
@Database(entities = [FallEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fallEventDao(): FallEventDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fall_detection.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
