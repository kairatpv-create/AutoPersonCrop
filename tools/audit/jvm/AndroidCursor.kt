package android.database
class Cursor(private val rows:List<List<Any?>>):java.io.Closeable {
    private var pos=-1
    fun moveToNext():Boolean{pos++;return pos<rows.size}
    fun moveToFirst():Boolean{pos=0;return rows.isNotEmpty()}
    fun getLong(i:Int)=(rows[pos][i] as? Number)?.toLong()?:0L
    fun getInt(i:Int)=getLong(i).toInt()
    fun getDouble(i:Int)=(rows[pos][i] as? Number)?.toDouble()?:0.0
    fun getString(i:Int)=rows[pos][i]?.toString().orEmpty()
    fun isNull(i:Int)=rows[pos][i]==null
    override fun close(){}
}
