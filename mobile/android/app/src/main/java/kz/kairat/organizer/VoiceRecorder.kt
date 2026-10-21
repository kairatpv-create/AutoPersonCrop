package kz.kairat.organizer

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

data class RecordedVoice(val file:File,val durationMs:Long)

@Suppress("DEPRECATION")
class VoiceRecorder(private val context:Context){
    private var recorder:MediaRecorder?=null
    private var file:File?=null
    private var startedAt=0L
    val isRecording:Boolean get()=recorder!=null
    fun start(){
        check(recorder==null)
        val out=File(context.cacheDir,"voice_${System.currentTimeMillis()}.m4a")
        val r=if(Build.VERSION.SDK_INT>=31) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000); r.setAudioSamplingRate(44_100); r.setOutputFile(out.absolutePath)
        r.prepare(); r.start(); recorder=r; file=out; startedAt=SystemClock.elapsedRealtime()
    }
    fun stop():RecordedVoice?{
        val r=recorder?:return null; val f=file
        val duration=(SystemClock.elapsedRealtime()-startedAt).coerceAtLeast(0L)
        return try{r.stop(); if(f!=null&&f.exists()&&f.length()>0)RecordedVoice(f,duration)else null}catch(_:Exception){f?.delete();null}finally{runCatching{r.release()};recorder=null;file=null;startedAt=0L}
    }
    fun cancel(){val r=recorder?:return;runCatching{r.stop()};runCatching{r.release()};file?.delete();recorder=null;file=null;startedAt=0L}
}
