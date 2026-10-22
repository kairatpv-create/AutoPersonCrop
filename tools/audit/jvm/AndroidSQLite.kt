package android.database.sqlite

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import java.sql.Connection
import java.sql.DriverManager

/** Executes the real application's SQL on SQLite JDBC, emulating Android's insert return value. */
class SQLiteDatabase(private val c:Connection) {
    companion object{
        const val CONFLICT_IGNORE=4;const val CONFLICT_REPLACE=5;const val OPEN_READONLY=1
        fun openOrCreateDatabase(file:java.io.File,factory:Any?):SQLiteDatabase{Class.forName("org.sqlite.JDBC");return SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:"+file.absolutePath))}
        fun openDatabase(path:String,factory:Any?,flags:Int):SQLiteDatabase{
            Class.forName("org.sqlite.JDBC")
            return SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:file:"+java.io.File(path).absolutePath+if(flags==OPEN_READONLY)"?mode=ro" else ""))
        }
    }
    private var successful=false
    private var safetyRows=0
    fun execSQL(sql:String,args:Array<out Any?>?=null){c.prepareStatement(sql).use{s->args?.forEachIndexed{i,v->s.setObject(i+1,v)};s.execute()}
        if(System.getProperty("organizer.audit.haltSafetySql")=="true"&&c.metaData.url.contains("/restore-safety/")&&sql.startsWith("INSERT INTO")&&++safetyRows==2)Runtime.getRuntime().halt(73)
    }
    fun rawQuery(sql:String,args:Array<String>?):Cursor = c.prepareStatement(sql).use{s->
        args?.forEachIndexed{i,v->s.setString(i+1,v)}
        s.executeQuery().use{r->
            val rows=mutableListOf<List<Any?>>()
            while(r.next())rows.add((1..r.metaData.columnCount).map{r.getObject(it)})
            Cursor(rows,Array(r.metaData.columnCount){r.metaData.getColumnName(it+1)})
        }
    }
    fun insertOrThrow(table:String,nullColumnHack:String?,v:ContentValues):Long=insertInternal(table,v,"")
    fun insert(table:String,nullColumnHack:String?,v:ContentValues):Long=try{insertInternal(table,v,"")}catch(e:java.sql.SQLException){-1L}
    fun insertWithOnConflict(table:String,nullColumnHack:String?,v:ContentValues,conflict:Int):Long=try{insertInternal(table,v,if(conflict==CONFLICT_IGNORE)"OR IGNORE" else "OR REPLACE")}catch(e:java.sql.SQLException){-1L}
    private fun insertInternal(table:String,v:ContentValues,mode:String):Long{
        execSQL("INSERT $mode INTO $table (${v.keys.joinToString(",")}) VALUES (${v.keys.joinToString(","){"?"}})",v.values.toTypedArray())
        return rawQuery("SELECT last_insert_rowid()",null).use{it.moveToFirst();it.getLong(0)}
    }
    fun update(table:String,v:ContentValues,where:String,args:Array<String>):Int = c.prepareStatement("UPDATE $table SET ${v.keys.joinToString(","){"$it=?"}} WHERE $where").use{s->
        (v.values.toList()+args.toList()).forEachIndexed{i,x->s.setObject(i+1,x)};s.executeUpdate()
    }
    fun delete(table:String,where:String?,args:Array<String>?):Int=c.prepareStatement("DELETE FROM $table"+(where?.let{" WHERE $it"}?:"")).use{s->args?.forEachIndexed{i,v->s.setString(i+1,v)};s.executeUpdate()}
    fun beginTransaction(){(c as org.sqlite.SQLiteConnection).connectionConfig.transactionMode=org.sqlite.SQLiteConfig.TransactionMode.EXCLUSIVE;c.autoCommit=false;successful=false}
    fun inTransaction()=!c.autoCommit
    fun setTransactionSuccessful(){successful=true}
    fun endTransaction(){
        if(successful){
            c.commit()
            val main=c.metaData.url.endsWith("/organizer.db")&&!c.metaData.url.contains("/restore-safety/")
            if(main&&System.getProperty("organizer.audit.afterCommit")=="halt")Runtime.getRuntime().halt(73)
            if(main&&System.getProperty("organizer.audit.afterCommit")=="throw"){
                c.autoCommit=true
                throw java.io.IOException("Host-only loss of COMMIT acknowledgement")
            }
        }else c.rollback()
        c.autoCommit=true
    }
    fun close(){c.close()}
}
open class SQLiteOpenHelper(private val context:Context,private val name:String,factory:Any?,private val version:Int){
    private var opened:SQLiteDatabase?=null
    val writableDatabase:SQLiteDatabase get(){
        opened?.let{return it}
        Class.forName("org.sqlite.JDBC")
        val file=java.io.File(context.sandbox,name)
        val fresh=!file.exists()
        val db=SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:"+file.absolutePath))
        try{
            if(fresh){onCreate(db);db.execSQL("PRAGMA user_version=$version")}
            onOpen(db)
        }catch(failure:Throwable){db.close();throw failure}
        opened=db
        return db
    }
    val readableDatabase:SQLiteDatabase get()=writableDatabase
    fun close(){opened?.close();opened=null}
    open fun onCreate(db:SQLiteDatabase){}
    open fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    open fun onOpen(db:SQLiteDatabase){}
}
