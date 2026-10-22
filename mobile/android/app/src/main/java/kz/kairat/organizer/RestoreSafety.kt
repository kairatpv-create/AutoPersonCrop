package kz.kairat.organizer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Independent, verified pre-import snapshots. Never replaced/deleted by the restore journal. */
internal object RestoreSafety {
    const val DIRECTORY="restore-safety"
    private const val MANIFEST="snapshot.json"
    private const val MAX_MANIFEST=8*1024*1024
    class Failure(message:String,cause:Throwable):IOException(message,cause)

    fun capture(context:Context,source:SQLiteDatabase,lang:String,io:AttachmentRestoreFiles,insideRestore:Boolean=false):File {
        // Native pre-images stay local; Android excludes this directory from automatic cloud backup.
        val parent=File(context.noBackupFilesDir,DIRECTORY)
        val id=UUID.randomUUID().toString()
        val stage=File(parent,".pending-$id");val ready=File(parent,"before-restore-$id")
        var created=false
        try{
            require(source.inTransaction()==insideRestore)
            require(!Files.isSymbolicLink(parent.toPath()))
            if(!parent.exists())io.makeDirectory(parent)
            require(parent.isDirectory&&!ready.exists())
            io.makeDirectory(stage);created=true
            val entries=JSONArray()
            fun record(file:File){
                if(entries.length()>=20_000)throw IOException("Too many safety files")
                entries.put(JSONObject().put("path",file.relativeTo(stage).invariantSeparatorsPath)
                    .put("size",file.length()).put("sha256",hash(file)))
            }
            // The source transaction pins one SQL snapshot and blocks writers while files are copied.
            // Read through SQLite, including committed WAL frames; never copy a live .db alone.
            if(!insideRestore)source.beginTransaction()
            val tables:JSONObject
            try{
                tables=copyDatabase(source,File(stage,"organizer.db"))
                io.syncFile(File(stage,"organizer.db"))
                record(File(stage,"organizer.db"))
                io.write(File(stage,"settings.json"),BackupManager.exportedSettings(SettingsStore(context)).toString().toByteArray(Charsets.UTF_8))
                record(File(stage,"settings.json"))
                val attachments=File(context.filesDir,"attachments")
                require(!Files.isSymbolicLink(attachments.toPath()))
                require(!attachments.exists()||attachments.isDirectory)
                if(attachments.exists()){
                    Files.walkFileTree(attachments.toPath(),object:SimpleFileVisitor<Path>(){
                        private fun target(path:Path)=File(stage,"attachments").toPath().resolve(attachments.toPath().relativize(path)).toFile()
                        override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult{
                            io.makeDirectory(target(dir));return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(path:Path,attrs:BasicFileAttributes):FileVisitResult{
                            require(attrs.isRegularFile&&!attrs.isSymbolicLink)
                            val destination=target(path);val expected=hash(path.toFile())
                            io.copyFile(path.toFile(),destination)
                            require(hash(destination)==expected)
                            record(destination);return FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(dir:Path,failure:IOException?):FileVisitResult{
                            if(failure!=null)throw failure
                            io.syncDirectory(target(dir));return FileVisitResult.CONTINUE
                        }
                    })
                }
            }finally{
                // No source changes: rollback the read-only work. This is not an import commit.
                if(!insideRestore)source.endTransaction()
            }
            val version=source.rawQuery("PRAGMA user_version",null).use{check(it.moveToFirst());it.getInt(0)}
            val manifest=JSONObject().put("format","organizer-restore-safety").put("version",1).put("id",id)
                .put("createdAt",System.currentTimeMillis()).put("databaseVersion",version).put("tables",tables).put("files",entries)
            val bytes=manifest.toString().toByteArray(Charsets.UTF_8)
            if(bytes.size>MAX_MANIFEST)throw IOException("Safety manifest too large")
            io.write(File(stage,MANIFEST),bytes)
            verify(stage)
            io.syncDirectory(stage);io.syncDirectory(parent);io.syncDirectory(context.noBackupFilesDir)
            io.move(stage,ready);io.syncDirectory(parent)
            return ready
        }catch(failure:Exception){
            if(created&&stage.exists())try{io.remove(stage)}catch(cleanup:Exception){failure.addSuppressed(cleanup)}
            throw Failure(if(AppStrings.normalizeLanguage(lang)=="ru")
                "Не удалось создать и проверить страховочную копию. Восстановление отменено. Данные не изменены."
                else "Could not create and verify the safety copy. Restore cancelled. Your data has not been changed.",failure)
        }
    }
    private fun quote(name:String)="\""+name.replace("\"","\"\"")+"\""
    private fun copyDatabase(source:SQLiteDatabase,file:File):JSONObject {
        val target=SQLiteDatabase.openOrCreateDatabase(file,null)
        try{
            target.rawQuery("PRAGMA journal_mode=DELETE",null).use{require(it.moveToFirst()&&it.getString(0).equals("delete",true))}
            val schema=source.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT GLOB 'sqlite_*' AND name!='android_metadata' ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 1 ELSE 2 END,name",null).use{c->
                buildList{while(c.moveToNext())add(Triple(c.getString(0),c.getString(1),c.getString(2)))}
            }
            val counts=JSONObject()
            target.beginTransaction()
            try{
                schema.filter{it.first=="table"}.forEach{(type,name,sql)->
                    require(type=="table"&&!sql.startsWith("CREATE VIRTUAL",true))
                    target.execSQL(sql)
                    counts.put(name,copyRows(source,target,name))
                }
                val sequence=source.rawQuery("SELECT 1 FROM sqlite_master WHERE name='sqlite_sequence'",null).use{it.moveToFirst()}
                if(sequence){target.execSQL("DELETE FROM sqlite_sequence");counts.put("sqlite_sequence",copyRows(source,target,"sqlite_sequence"))}
                schema.filter{it.first!="table"}.forEach{target.execSQL(it.third)}
                val version=source.rawQuery("PRAGMA user_version",null).use{check(it.moveToFirst());it.getInt(0)}
                target.execSQL("PRAGMA user_version=$version")
                target.setTransactionSuccessful()
            }finally{target.endTransaction()}
            return counts
        }finally{target.close()}
    }
    private fun copyRows(source:SQLiteDatabase,target:SQLiteDatabase,table:String):Long {
        var count=0L
        source.rawQuery("SELECT * FROM ${quote(table)}",null).use{c->
            val sql="INSERT INTO ${quote(table)} (${c.columnNames.joinToString(","){quote(it)}}) VALUES (${c.columnNames.joinToString(","){"?"}})"
            while(c.moveToNext()){
                val values=Array<Any?>(c.columnCount){i->when(c.getType(i)){
                    Cursor.FIELD_TYPE_NULL->null;Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);Cursor.FIELD_TYPE_BLOB->c.getBlob(i)
                    else->c.getString(i)
                }}
                target.execSQL(sql,values);count++
            }
        }
        return count
    }
    /** Read-only verification, also used to inspect saved snapshots after a process restart. */
    fun verify(directory:File):JSONObject {
        require(directory.isDirectory&&!Files.isSymbolicLink(directory.toPath()))
        val manifestFile=File(directory,MANIFEST)
        require(manifestFile.isFile&&!Files.isSymbolicLink(manifestFile.toPath())&&manifestFile.length()<=MAX_MANIFEST)
        val manifest=JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.getString("format")=="organizer-restore-safety"&&manifest.getInt("version")==1)
        val entries=manifest.getJSONArray("files");val names=HashSet<String>()
        for(i in 0 until entries.length()){
            val item=entries.getJSONObject(i);val path=item.getString("path")
            require(names.add(path)&&path.isNotBlank()&&!path.startsWith("/")&&path.split('/').none{it.isBlank()||it=="."||it==".."||it.contains('\\')})
            val file=File(directory,path);var component=file.toPath()
            while(component!=directory.toPath()){require(!Files.isSymbolicLink(component));component=component.parent}
            require(file.isFile&&file.length()==item.getLong("size")&&hash(file)==item.getString("sha256"))
        }
        require("organizer.db" in names&&"settings.json" in names)
        val database=SQLiteDatabase.openDatabase(File(directory,"organizer.db").path,null,SQLiteDatabase.OPEN_READONLY)
        try{
            database.rawQuery("PRAGMA integrity_check",null).use{c->require(c.moveToFirst()&&c.getString(0)=="ok"&&!c.moveToNext())}
            database.rawQuery("PRAGMA user_version",null).use{require(it.moveToFirst()&&it.getInt(0)==manifest.getInt("databaseVersion"))}
            val tables=manifest.getJSONObject("tables")
            tables.keys().forEach{name->database.rawQuery("SELECT COUNT(*) FROM ${quote(name)}",null).use{require(it.moveToFirst()&&it.getLong(0)==tables.getLong(name))}}
        }finally{database.close()}
        BackupValidation.settings(JSONObject(File(directory,"settings.json").readText(Charsets.UTF_8)),"en")
        return manifest
    }
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;if(n>0)digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
