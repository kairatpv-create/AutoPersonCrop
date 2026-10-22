package kz.kairat.organizer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal object BackupImportReader {
    fun read(input:InputStream,cache:File,lang:String,policy:BackupImportLimits.Policy=BackupImportLimits.current()):String {
        try{
            val source=PushbackInputStream(input,2)
            val first=source.read();val second=source.read()
            if(second>=0)source.unread(second)
            if(first>=0)source.unread(first)
            val bytes=if(first==0x50&&second==0x4b)archive(source,cache,lang,policy)
                else ByteArrayOutputStream().also{copy(source,it,policy.jsonBytes.toLong(),lang)}.toByteArray()
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            BackupImportLimits.checkJson(text,lang,policy)
            return text
        }catch(failure:Exception){
            if(failure is IOException&&failure.message==BackupImportLimits.error(lang).message)throw failure
            throw IOException(if(AppStrings.normalizeLanguage(lang)=="ru")
                "Не удалось прочитать резервную копию: файл повреждён или недоступен. Данные не изменены."
                else "Could not read the backup: the file is damaged or unavailable. Your data has not been changed.",failure)
        }
    }
    private fun archive(source:InputStream,cache:File,lang:String,p:BackupImportLimits.Policy):ByteArray {
        val temp=File.createTempFile("organizer-import-",".zip",cache)
        try{
            FileOutputStream(temp).use{copy(source,it,p.archiveBytes,lang)}
            checkDirectory(temp,p,lang)
            ZipFile(temp).use{zip->
                val entries=zip.entries();var count=0;var unpacked=0L;var json:ByteArray?=null
                while(entries.hasMoreElements()){
                    val entry=entries.nextElement();count++
                    if(count>p.zipEntries)throw BackupImportLimits.error(lang)
                    if(!entry.isDirectory&&json!=null)throw IOException("More than one backup document")
                    if(entry.size>p.jsonBytes-unpacked)throw BackupImportLimits.error(lang)
                    val crc=CRC32();val output=ByteArrayOutputStream()
                    val size=zip.getInputStream(entry).use{copy(it,if(entry.isDirectory)null else output,p.jsonBytes-unpacked,lang,crc)}
                    unpacked+=size
                    if(size!=entry.size||crc.value!=entry.crc)throw IOException("Invalid archive size or CRC")
                    if(!entry.isDirectory)json=output.toByteArray()
                }
                return json?:throw IOException("Empty backup archive")
            }
        }finally{temp.delete()}
    }
    // Bound central-directory work before ZipFile allocates its index (PKWARE APPNOTE 4.3.12/16).
    private fun checkDirectory(file:File,p:BackupImportLimits.Policy,lang:String) {
        fun u16(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
        fun u32(b:ByteArray,i:Int)=u16(b,i).toLong() or (u16(b,i+2).toLong() shl 16)
        RandomAccessFile(file,"r").use{input->
            val length=input.length();val tail=ByteArray(minOf(length,65_557).toInt())
            input.seek(length-tail.size);input.readFully(tail)
            val end=(tail.size-22 downTo 0).firstOrNull{i->u32(tail,i)==0x06054b50L&&i+22+u16(tail,i+20)==tail.size}
                ?:throw IOException("Missing archive directory")
            val count=u16(tail,end+10);val size=u32(tail,end+12);val start=u32(tail,end+16)
            if(count>p.zipEntries||size>256*1024)throw BackupImportLimits.error(lang)
            if(u16(tail,end+4)!=0||u16(tail,end+6)!=0||u16(tail,end+8)!=count||start+size!=length-tail.size+end)
                throw IOException("Unsupported or inconsistent archive directory")
            input.seek(start);val header=ByteArray(46)
            repeat(count){
                if(input.filePointer+46>start+size)throw IOException("Truncated archive directory")
                input.readFully(header)
                if(u32(header,0)!=0x02014b50L)throw IOException("Invalid archive directory header")
                val next=input.filePointer+u16(header,28)+u16(header,30)+u16(header,32)
                if(next>start+size)throw IOException("Invalid archive directory length")
                input.seek(next)
            }
            if(input.filePointer!=start+size)throw IOException("Invalid archive directory count")
        }
    }
    /** Reads at most limit + 1 bytes, including when the provider reports no size or available(). */
    private fun copy(input:InputStream,output:java.io.OutputStream?,limit:Long,lang:String,crc:CRC32?=null):Long {
        val buffer=ByteArray(8192);var total=0L
        while(true){
            val allowed=minOf(buffer.size.toLong(),limit-total+1).coerceAtLeast(1).toInt()
            val n=input.read(buffer,0,allowed)
            if(n<0)return total
            if(n==0){val value=input.read();if(value<0)return total;buffer[0]=value.toByte();total++
                if(total>limit)throw BackupImportLimits.error(lang)
                output?.write(value);crc?.update(value);continue}
            total+=n
            if(total>limit)throw BackupImportLimits.error(lang)
            output?.write(buffer,0,n);crc?.update(buffer,0,n)
        }
    }
}
