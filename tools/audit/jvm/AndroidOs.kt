package android.system

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Host adapter uses Linux directory fsync; it does not simulate Android's storage stack. */
object OsConstants{const val O_RDONLY=0}
object Os{
    fun open(path:String,flags:Int,mode:Int):FileChannel=FileChannel.open(Path.of(path),StandardOpenOption.READ)
    fun fsync(fd:FileChannel){fd.force(true)}
    fun close(fd:FileChannel){fd.close()}
}
