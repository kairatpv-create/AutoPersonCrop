package android.database.sqlite

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import java.sql.Connection
import java.sql.DriverManager

/** Executes the real application's SQL on SQLite JDBC, emulating Android's insert return value. */
class SQLiteDatabase(private val c:Connection) {
    companion object{const val CONFLICT_IGNORE=4;const val CONFLICT_REPLACE=5}
    private var successful=false
    fun execSQL(sql:String,args:Array<out Any?>?=null){c.prepareStatement(sql).use{s->args?.forEachIndexed{i,v->s.setObject(i+1,v)};s.execute()}}
    fun rawQuery(sql:String,args:Array<String>?):Cursor = c.prepareStatement(sql).use{s->
        args?.forEachIndexed{i,v->s.setString(i+1,v)}
        s.executeQuery().use{r->
            val rows=mutableListOf<List<Any?>>()
            while(r.next())rows.add((1..r.metaData.columnCount).map{r.getObject(it)})
            Cursor(rows)
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
    fun beginTransaction(){c.autoCommit=false;successful=false}
    fun setTransactionSuccessful(){successful=true}
    fun endTransaction(){if(successful)c.commit()else c.rollback();c.autoCommit=true}
}
open class SQLiteOpenHelper(private val context:Context,private val name:String,factory:Any?,private val version:Int){
    private var opened:SQLiteDatabase?=null
    val writableDatabase:SQLiteDatabase get(){
        opened?.let{return it}
        Class.forName("org.sqlite.JDBC")
        val file=java.io.File(context.sandbox,name)
        val fresh=!file.exists()
        val db=SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:"+file.absolutePath))
        opened=db
        if(fresh){onCreate(db);db.execSQL("PRAGMA user_version=$version")}
        onOpen(db)
        return db
    }
    val readableDatabase:SQLiteDatabase get()=writableDatabase
    open fun onCreate(db:SQLiteDatabase){}
    open fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    open fun onOpen(db:SQLiteDatabase){}
}
