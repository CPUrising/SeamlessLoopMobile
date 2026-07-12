package com.cpu.seamlessloopmobile.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cpu.seamlessloopmobile.model.*
import androidx.room.TypeConverters

@Database(
    entities = [
        SongEntity::class, 
        Artist::class, 
        Album::class, 
        LoopPoint::class, 
        UserRating::class, 
        Playlist::class, 
        PlaylistItem::class, 
        PlaylistFolder::class, 
        PlayQueueItem::class
    ], 
    version = 13,
    exportSchema = false
)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playQueueDao(): PlayQueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val externalDir = context.getExternalFilesDir(null)
                val dbFile = if (externalDir != null) {
                    java.io.File(externalDir, "databases/seamless_loop_db")
                } else {
                    java.io.File(context.filesDir, "databases/seamless_loop_db")
                }
                
                if (dbFile.parentFile?.exists() == false) {
                    dbFile.parentFile?.mkdirs()
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbFile.absolutePath
                )
                .fallbackToDestructiveMigration() 
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
