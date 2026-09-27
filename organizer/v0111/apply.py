from pathlib import Path
import sys
root=Path(sys.argv[1])
rem=root/'app/src/main/java/kz/kairat/organizer/ReminderSystem.kt'
alert=root/'app/src/main/java/kz/kairat/organizer/ReminderAlertActivity.kt'
main=root/'app/src/main/java/kz/kairat/organizer/MainActivity.kt'
manifest=root/'app/src/main/AndroidManifest.xml'
styles=root/'app/src/main/res/values/styles.xml'
gradle=root/'app/build.gradle.kts'
strings=root/'app/src/main/java/kz/kairat/organizer/AppStrings.kt'

# Replace reminder subsystem with exact-alarm permission handling and a fresh high-importance alarm channel.
rem.write_text(r'''package kz.kairat.organizer

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.Calendar

object ReminderTime {
    fun minutesOfDay(ms:Long):Int = Calendar.getInstance().apply{timeInMillis=ms}.let{it.get(Calendar.HOUR_OF_DAY)*60+it.get(Calendar.MINUTE)}
    fun sameDay(a:Long,b:Long):Boolean{
        val x=Calendar.getInstance().apply{timeInMillis=a}; val y=Calendar.getInstance().apply{timeInMillis=b}
        return x.get(Calendar.YEAR)==y.get(Calendar.YEAR)&&x.get(Calendar.DAY_OF_YEAR)==y.get(Calendar.DAY_OF_YEAR)
    }
    fun weekdayBit(ms:Long):Int{
        val dow=Calendar.getInstance().apply{timeInMillis=ms}.get(Calendar.DAY_OF_WEEK)
        val mondayIndex=(dow+5)%7
        return 1 shl mondayIndex
    }
    fun nextWeekly(daysMask:Int,baseMinutes:Int,after:Long=System.currentTimeMillis(),skipCurrentDay:Boolean=false):Long{
        val base=Calendar.getInstance().apply{timeInMillis=after}
        val hour=baseMinutes/60; val minute=baseMinutes%60
        for(delta in 0..7){
            if(delta==0 && skipCurrentDay) continue
            val c=(base.clone() as Calendar).apply{
                add(Calendar.DAY_OF_YEAR,delta)
                set(Calendar.HOUR_OF_DAY,hour);set(Calendar.MINUTE,minute);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
            }
            val selected=(daysMask and weekdayBit(c.timeInMillis))!=0
            if(selected && c.timeInMillis>after) return c.timeInMillis
        }
        return after+24*60*60_000L
    }
}

object ReminderScheduler {
    fun schedule(context:Context,entry:ReminderEntry){
        scheduleAt(context,entry.id,entry.triggerAt)
        requestExactAlarmAccessIfNeeded(context)
    }
    private fun scheduleAt(context:Context,id:Long,at:Long){
        val am=context.getSystemService(AlarmManager::class.java)
        val pi=pending(context,id)
        val trigger=at.coerceAtLeast(System.currentTimeMillis()+750L)
        if(Build.VERSION.SDK_INT>=31){
            if(am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi)
        }else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi)
    }
    fun requestExactAlarmAccessIfNeeded(context:Context){
        if(Build.VERSION.SDK_INT<31) return
        val am=context.getSystemService(AlarmManager::class.java)
        if(am.canScheduleExactAlarms()) return
        val activity=findActivity(context)?:return
        runCatching{
            activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }
    private fun findActivity(context:Context):Activity?{
        var c:Context?=context
        while(c is ContextWrapper){
            if(c is Activity) return c
            val base=c.baseContext
            if(base===c) break
            c=base
        }
        return c as? Activity
    }
    fun cancel(context:Context,id:Long){context.getSystemService(AlarmManager::class.java).cancel(pending(context,id))}
    fun cancelAllForTarget(context:Context,targetType:String,targetId:Long,db:NoteDatabase=NoteDatabase(context)){
        db.getReminders().filter{it.targetType==targetType&&it.targetId==targetId}.forEach{r->cancel(context,r.id);ReminderNotifications.cancel(context,r.id);db.deleteReminder(r.id)}
    }
    private fun pending(c:Context,id:Long)=PendingIntent.getBroadcast(c,id.toInt(),Intent(c,ReminderReceiver::class.java).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun rescheduleAll(context:Context){
        val db=NoteDatabase(context)
        val now=System.currentTimeMillis()
        db.getReminders().forEach{r->
            when{
                r.triggerAt>now -> scheduleAt(context,r.id,r.triggerAt)
                r.scheduleType=="weekly" -> {
                    val next=ReminderTime.nextWeekly(r.daysMask,r.baseMinutes,now)
                    db.updateReminderTrigger(r.id,next)
                    scheduleAt(context,r.id,next)
                }
                now-r.triggerAt<=12*60*60_000L -> scheduleAt(context,r.id,now+1500L)
            }
        }
    }

    fun advanceAfterFire(context:Context,r:ReminderEntry){
        val db=NoteDatabase(context)
        val next=when(r.scheduleType){
            "weekly" -> {
                if(r.dayMode=="all_day"){
                    val hourly=r.triggerAt+60*60_000L
                    if(ReminderTime.sameDay(hourly,r.triggerAt)) hourly else ReminderTime.nextWeekly(r.daysMask,r.baseMinutes,r.triggerAt,true)
                }else ReminderTime.nextWeekly(r.daysMask,r.baseMinutes,r.triggerAt+60_000L)
            }
            else -> {
                if(r.dayMode=="all_day"){
                    val hourly=r.triggerAt+60*60_000L
                    if(ReminderTime.sameDay(hourly,r.triggerAt)) hourly else 0L
                }else 0L
            }
        }
        if(next>0L){db.updateReminderTrigger(r.id,next);scheduleAt(context,r.id,next)}
    }

    fun dismissCurrent(context:Context,r:ReminderEntry){
        val db=NoteDatabase(context); cancel(context,r.id)
        when{
            r.scheduleType=="once" -> db.deleteReminder(r.id)
            r.scheduleType=="weekly" && r.dayMode=="all_day" -> {
                val next=ReminderTime.nextWeekly(r.daysMask,r.baseMinutes,System.currentTimeMillis(),true)
                db.updateReminderTrigger(r.id,next);scheduleAt(context,r.id,next)
            }
            r.scheduleType=="weekly" -> {
                val current=db.getReminder(r.id)
                if(current!=null && current.triggerAt<=System.currentTimeMillis()){
                    val next=ReminderTime.nextWeekly(r.daysMask,r.baseMinutes,System.currentTimeMillis())
                    db.updateReminderTrigger(r.id,next);scheduleAt(context,r.id,next)
                }
            }
        }
        ReminderNotifications.cancel(context,r.id)
    }

    fun snooze(context:Context,r:ReminderEntry,minutes:Int){
        cancel(context,r.id)
        val at=System.currentTimeMillis()+minutes*60_000L
        NoteDatabase(context).updateReminderTrigger(r.id,at)
        scheduleAt(context,r.id,at)
        ReminderNotifications.cancel(context,r.id)
    }
}

class ReminderReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val id=intent.getLongExtra("id",0);val r=NoteDatabase(context).getReminder(id)?:return
        ReminderNotifications.show(context,r)
        ReminderScheduler.advanceAfterFire(context,r)
    }
}
class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){ReminderNotifications.ensureChannel(context);ReminderScheduler.rescheduleAll(context)}}

object ReminderNotifications{
    private const val CH="organizer_reminders_v3"
    fun ensureChannel(c:Context){
        if(Build.VERSION.SDK_INT<26) return
        val lang=SettingsStore(c).language
        val nm=c.getSystemService(NotificationManager::class.java)
        val alarmUri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH,AppStrings.t(lang,"reminders"),NotificationManager.IMPORTANCE_HIGH).apply{
            description=AppStrings.t(lang,"notification_channel_desc")
            lockscreenVisibility=Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern=longArrayOf(0,650,250,650,250,900)
            setSound(alarmUri,attrs)
        })
    }
    fun show(c:Context,r:ReminderEntry){
        val lang=SettingsStore(c).language
        val nm=c.getSystemService(NotificationManager::class.java)
        ensureChannel(c)
        val alertIntent=Intent(c,ReminderAlertActivity::class.java).putExtra("id",r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val alertPi=PendingIntent.getActivity(c,r.id.toInt(),alertIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=r.details.ifBlank{r.title.ifBlank{AppStrings.t(lang,"reminder")}}
        val b=NotificationCompat.Builder(c,CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title.ifBlank{AppStrings.t(lang,"reminder")})
            .setContentText(text)
            .setAutoCancel(false)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(alertPi)
            .setFullScreenIntent(alertPi,true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(false)
        if(Build.VERSION.SDK_INT<26){
            if(r.sound)b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) else b.setSilent(true)
            if(r.vibrate)b.setVibrate(longArrayOf(0,650,250,650,250,900))
        }
        nm.notify(r.id.toInt(),b.build())
    }
    fun cancel(c:Context,id:Long){c.getSystemService(NotificationManager::class.java).cancel(id.toInt())}
}

fun launchDateTimePicker(context:Context,initial:Long=System.currentTimeMillis()+3600_000,onSelected:(Long)->Unit){
    val c=Calendar.getInstance().apply{timeInMillis=initial}; DatePickerDialog(context,{_,y,m,d->TimePickerDialog(context,{_,h,min->val out=Calendar.getInstance().apply{set(y,m,d,h,min,0);set(Calendar.MILLISECOND,0)};onSelected(out.timeInMillis)},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show()},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
}

fun launchTimePicker(context:Context,initialMinutes:Int,onSelected:(Int)->Unit){
    val h=(initialMinutes/60).coerceIn(0,23);val m=(initialMinutes%60).coerceIn(0,59)
    TimePickerDialog(context,{_,hour,minute->onSelected(hour*60+minute)},h,m,true).show()
}
''')

# Keep the reminder alert window compact: transparent dialog-like activity capped at 30% of the display height.
alert.write_text(r'''package kz.kairat.organizer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class ReminderAlertActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true)}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val id=intent.getLongExtra("id",0L)
        setContent{
            val db=remember{NoteDatabase(this)}
            val settings=remember{SettingsStore(this)}
            val reminder=remember{id.let{db.getReminder(it)}}
            OrganizerTheme(settings.darkTheme,settings.fontScale,settings.boldText){
                if(reminder==null){finish()}else ReminderAlertContent(reminder,settings.language)
            }
        }
        window.decorView.post{applyCompactWindow()}
    }
    override fun onResume(){super.onResume();window.decorView.post{applyCompactWindow()}}
    private fun applyCompactWindow(){
        val bounds=if(android.os.Build.VERSION.SDK_INT>=30) windowManager.currentWindowMetrics.bounds else android.graphics.Rect(0,0,resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
        val p=window.attributes
        p.width=(bounds.width()*0.94f).toInt()
        p.height=(bounds.height()*0.30f).toInt()
        p.gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y=(12*resources.displayMetrics.density).toInt()
        window.attributes=p
    }
    @Composable private fun ReminderAlertContent(r:ReminderEntry,lang:String){
        var snoozeMenu by remember{mutableStateOf(false)}
        Surface(Modifier.fillMaxSize(),shape=MaterialTheme.shapes.large,tonalElevation=8.dp,color=MaterialTheme.colorScheme.surface){
            Column(Modifier.fillMaxSize().padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(r.title.ifBlank{AppStrings.t(lang,"reminders")},style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth(),maxLines=2)
                Text(r.details.ifBlank{r.title},style=MaterialTheme.typography.bodyMedium,maxLines=3,modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={ReminderScheduler.dismissCurrent(this@ReminderAlertActivity,r);finish()},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)){Text(AppStrings.t(lang,"dismiss_alarm"),maxLines=1)}
                    Button(onClick={snoozeMenu=!snoozeMenu},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)){Text(AppStrings.t(lang,"snooze"),maxLines=1)}
                }
                if(snoozeMenu){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf(10,30,60).forEach{m->OutlinedButton(onClick={ReminderScheduler.snooze(this@ReminderAlertActivity,r,m);finish()},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp)){Text(if(m<60)"$m ${AppStrings.t(lang,"minute_short")}" else "1 ${AppStrings.t(lang,"hour_short")}",maxLines=1)}}
                    }
                }
            }
        }
    }
}
''')

# Reschedule after returning from the system exact-alarm permission screen or after app resume.
main.write_text(r'''package kz.kairat.organizer

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,true)
        ReminderNotifications.ensureChannel(this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1001)
        setContent{OrganizerApp()}
    }
    override fun onResume(){
        super.onResume()
        ReminderNotifications.ensureChannel(this)
        ReminderScheduler.rescheduleAll(this)
    }
}
''')

m=manifest.read_text()
# Dedicated compact floating theme for alert activity and exact-alarm permission-state rescheduling.
old='''        <activity
            android:name=".ReminderAlertActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:showWhenLocked="true"
            android:turnScreenOn="true" />'''
new='''        <activity
            android:name=".ReminderAlertActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:showWhenLocked="true"
            android:turnScreenOn="true"
            android:theme="@style/Theme.MyOrganizer.ReminderAlert" />'''
assert old in m
m=m.replace(old,new,1)
old='''                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />'''
new='''                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />'''
assert old in m
m=m.replace(old,new,1)
manifest.write_text(m)

st=styles.read_text()
old='''    </style>
</resources>'''
new='''    </style>
    <style name="Theme.MyOrganizer.ReminderAlert" parent="Theme.MyOrganizer">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:backgroundDimEnabled">false</item>
        <item name="android:colorAccent">#0A84D8</item>
    </style>
</resources>'''
assert old in st
st=st.replace(old,new,1)
styles.write_text(st)

b=gradle.read_text(); a=strings.read_text()
assert 'versionCode = 53' in b and 'versionName = "0.9.20"' in b
b=b.replace('versionCode = 53','versionCode = 54').replace('versionName = "0.9.20"','versionName = "0.9.21"')
gradle.write_text(b)
assert '0.9.20 (53)' in a
a=a.replace('0.9.20 (53)','0.9.21 (54)')
strings.write_text(a)

# Gates.
r=rem.read_text(); al=alert.read_text(); mn=main.read_text(); m=manifest.read_text(); st=styles.read_text(); b=gradle.read_text(); a=strings.read_text()
assert 'organizer_reminders_v3' in r
assert 'setUsage(AudioAttributes.USAGE_ALARM)' in r
assert 'enableVibration(true)' in r
assert 'canScheduleExactAlarms()' in r
assert 'ACTION_REQUEST_SCHEDULE_EXACT_ALARM' in r
assert 'setExactAndAllowWhileIdle' in r
assert '.setFullScreenIntent(alertPi,true)' in r
assert 'bounds.height()*0.30f' in al
assert 'Theme.MyOrganizer.ReminderAlert' in st
assert 'SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED' in m
assert 'ReminderScheduler.rescheduleAll(this)' in mn
assert 'versionCode = 54' in b and 'versionName = "0.9.21"' in b
assert '0.9.21 (54)' in a
