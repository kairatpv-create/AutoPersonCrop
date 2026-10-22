package android.database
class Cursor(private val rows:List<List<Any?>>,val columnNames:Array<String> = emptyArray()):java.io.Closeable {
    companion object{const val FIELD_TYPE_NULL=0;const val FIELD_TYPE_INTEGER=1;const val FIELD_TYPE_FLOAT=2;const val FIELD_TYPE_STRING=3;const val FIELD_TYPE_BLOB=4}
    val columnCount:Int get()=columnNames.size
    private var pos=-1
    fun moveToNext():Boolean{pos++;return pos<rows.size}
    fun moveToFirst():Boolean{pos=0;return rows.isNotEmpty()}
    fun getLong(i:Int)=(rows[pos][i] as? Number)?.toLong()?:0L
    fun getInt(i:Int)=getLong(i).toInt()
    fun getDouble(i:Int)=(rows[pos][i] as? Number)?.toDouble()?:0.0
    fun getString(i:Int)=rows[pos][i]?.toString().orEmpty()
    fun getBlob(i:Int)=rows[pos][i] as ByteArray
    fun getType(i:Int)=when(rows[pos][i]){null->FIELD_TYPE_NULL;is ByteArray->FIELD_TYPE_BLOB;is Float,is Double->FIELD_TYPE_FLOAT;is Number->FIELD_TYPE_INTEGER;else->FIELD_TYPE_STRING}
    fun isNull(i:Int)=rows[pos][i]==null
    override fun close(){}
}
