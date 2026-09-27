package kz.kairat.organizer

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone


data class OrganizerCalendarItem(
    val stableId:String,
    val title:String,
    val startAt:Long,
    val endAt:Long,
    val allDay:Boolean,
    val kind:String, // system | birthday | project | stage
    val calendarName:String="",
    val description:String="",
    val location:String="",
    val projectId:Long?=null,
    val stageId:Long?=null,
    val completed:Boolean=false
)

object OrganizerCalendarRepository {
    fun hasCalendarPermission(context:Context):Boolean = ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED

    fun loadRange(context:Context,db:NoteDatabase,startInclusive:Long,endExclusive:Long,includeSystem:Boolean=true):List<OrganizerCalendarItem>{
        val out=mutableListOf<OrganizerCalendarItem>()
        if(includeSystem && hasCalendarPermission(context)) out += readSystem(context,startInclusive,endExclusive)
        val projects=db.getProjects()
        val projectById=projects.associateBy{it.id}
        projects.filter{it.dueAt in startInclusive until endExclusive}.forEach{p->
            out += OrganizerCalendarItem("project:${p.id}:${p.dueAt}",p.name,p.dueAt,p.dueAt+86_400_000L,true,"project",description=p.description,projectId=p.id,completed=p.status=="completed")
        }
        db.getAllProjectStages().filter{it.dueAt in startInclusive until endExclusive}.forEach{st->
            val p=projectById[st.projectId]
            out += OrganizerCalendarItem("stage:${st.id}:${st.dueAt}",st.title,st.dueAt,st.dueAt+86_400_000L,true,"stage",calendarName=p?.name.orEmpty(),projectId=st.projectId,stageId=st.id,completed=st.done)
        }
        return out.sortedWith(compareBy<OrganizerCalendarItem>{it.startAt}.thenBy{it.title.lowercase(Locale.ROOT)})
    }

    fun readBirthdays(context:Context,startInclusive:Long,endExclusive:Long):List<OrganizerCalendarItem>{
        if(!hasCalendarPermission(context)) return emptyList()
        return readSystem(context,startInclusive,endExclusive).filter{it.kind=="birthday"}.distinctBy{birthdayKey(it)}
    }

    private data class CalendarMeta(val display:String,val account:String,val owner:String)

    private fun readSystem(context:Context,startInclusive:Long,endExclusive:Long):List<OrganizerCalendarItem>{
        return runCatching{
            val resolver=context.contentResolver
            val calendars=mutableMapOf<Long,CalendarMeta>()
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,CalendarContract.Calendars.ACCOUNT_NAME,CalendarContract.Calendars.OWNER_ACCOUNT),
                null,null,null
            )?.use{c->
                while(c.moveToNext()) calendars[c.getLong(0)]=CalendarMeta(c.getString(1).orEmpty(),c.getString(2).orEmpty(),c.getString(3).orEmpty())
            }
            val b=CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b,startInclusive)
            ContentUris.appendId(b,endExclusive)
            val uri=b.build()
            val projection=arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CALENDAR_ID
            )
            buildList{
                resolver.query(uri,projection,null,null,CalendarContract.Instances.BEGIN+" ASC")?.use{c->
                    while(c.moveToNext()){
                        val eventId=c.getLong(0)
                        val rawStart=c.getLong(1)
                        val rawEnd=c.getLong(2)
                        val title=c.getString(3).orEmpty().ifBlank{"—"}
                        val allDay=c.getInt(4)!=0
                        val description=c.getString(5).orEmpty()
                        val location=c.getString(6).orEmpty()
                        val calendarId=c.getLong(7)
                        val meta=calendars[calendarId]?:CalendarMeta("","","")
                        val start=normalizeStart(rawStart,allDay)
                        val end=if(allDay) normalizeStart(rawEnd,true).coerceAtLeast(start+86_400_000L) else rawEnd.coerceAtLeast(start)
                        val birthday=isBirthday(meta,title)
                        add(OrganizerCalendarItem(
                            stableId="system:$eventId:$rawStart",
                            title=title,startAt=start,endAt=end,allDay=allDay,
                            kind=if(birthday)"birthday" else "system",
                            calendarName=meta.display,description=description,location=location
                        ))
                    }
                }
            }
        }.getOrElse{emptyList()}
    }

    private fun normalizeStart(ms:Long,allDay:Boolean):Long{
        if(!allDay) return ms
        val utc=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply{timeInMillis=ms}
        return Calendar.getInstance().apply{
            set(Calendar.YEAR,utc.get(Calendar.YEAR));set(Calendar.MONTH,utc.get(Calendar.MONTH));set(Calendar.DAY_OF_MONTH,utc.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
        }.timeInMillis
    }

    private fun isBirthday(meta:CalendarMeta,title:String):Boolean{
        val joined=(meta.display+" "+meta.account+" "+meta.owner+" "+title).lowercase(Locale.ROOT)
        if(joined.contains("#contacts@group.v.calendar.google.com")) return true
        return listOf("birthday","birthdays","день рождения","дни рождения","туған күн","geburtstag","cumpleaños","anniversaire","doğum günü","生日").any{joined.contains(it)}
    }

    private fun birthdayKey(item:OrganizerCalendarItem):String{
        val c=Calendar.getInstance().apply{timeInMillis=item.startAt}
        return item.title.lowercase(Locale.ROOT)+":"+c.get(Calendar.YEAR)+":"+c.get(Calendar.DAY_OF_YEAR)
    }
}

object OrganizerCalendarDate {
    fun startOfDay(ms:Long):Long=Calendar.getInstance().apply{timeInMillis=ms;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
    fun startOfMonth(ms:Long):Long=Calendar.getInstance().apply{timeInMillis=ms;set(Calendar.DAY_OF_MONTH,1);set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}.timeInMillis
    fun addMonths(ms:Long,months:Int):Long=Calendar.getInstance().apply{timeInMillis=startOfMonth(ms);add(Calendar.MONTH,months)}.timeInMillis
    fun addDays(ms:Long,days:Int):Long=Calendar.getInstance().apply{timeInMillis=startOfDay(ms);add(Calendar.DAY_OF_YEAR,days)}.timeInMillis
    fun dayOfMonth(ms:Long):Int=Calendar.getInstance().apply{timeInMillis=ms}.get(Calendar.DAY_OF_MONTH)
    fun daysInMonth(ms:Long):Int=Calendar.getInstance().apply{timeInMillis=startOfMonth(ms)}.getActualMaximum(Calendar.DAY_OF_MONTH)
    fun mondayOffset(ms:Long):Int{val d=Calendar.getInstance().apply{timeInMillis=startOfMonth(ms)}.get(Calendar.DAY_OF_WEEK);return (d+5)%7}
    fun dayForMonth(monthStart:Long,day:Int):Long=Calendar.getInstance().apply{timeInMillis=startOfMonth(monthStart);set(Calendar.DAY_OF_MONTH,day)}.timeInMillis
    fun sameDay(a:Long,b:Long):Boolean{val x=Calendar.getInstance().apply{timeInMillis=a};val y=Calendar.getInstance().apply{timeInMillis=b};return x.get(Calendar.YEAR)==y.get(Calendar.YEAR)&&x.get(Calendar.DAY_OF_YEAR)==y.get(Calendar.DAY_OF_YEAR)}
}

object BirthdayScheduler {
    private const val PREFS="organizer_birthday_alarms"
    private const val KEY_CODES="codes"
    private const val ACTION="kz.kairat.organizer.BIRTHDAY_ALERT"

    fun rescheduleAll(context:Context){
        if(!OrganizerCalendarRepository.hasCalendarPermission(context)) return
        runCatching{
            cancelScheduled(context)
            val now=System.currentTimeMillis()
            val start=OrganizerCalendarDate.startOfDay(now)
            val end=OrganizerCalendarDate.addDays(start,370)
            val items=OrganizerCalendarRepository.readBirthdays(context,start,end)
            val codes=mutableSetOf<String>()
            items.forEach{item->
                val alert=Calendar.getInstance().apply{
                    timeInMillis=item.startAt
                    add(Calendar.DAY_OF_YEAR,-1)
                    set(Calendar.HOUR_OF_DAY,9);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
                }.timeInMillis
                var trigger=alert
                if(trigger<=now && item.startAt>now && item.startAt-now<=48*60*60_000L) trigger=now+2500L
                if(trigger>now){
                    val code=(item.stableId+":"+item.startAt).hashCode()
                    val pi=pending(context,code,item.title,item.startAt,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val am=context.getSystemService(AlarmManager::class.java)
                    if(Build.VERSION.SDK_INT>=31){
                        if(am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi)
                        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi)
                    }else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,trigger,pi)
                    codes += code.toString()
                }
            }
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putStringSet(KEY_CODES,codes).apply()
        }
    }

    private fun cancelScheduled(context:Context){
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val am=context.getSystemService(AlarmManager::class.java)
        prefs.getStringSet(KEY_CODES,emptySet()).orEmpty().forEach{s->
            val code=s.toIntOrNull()?:return@forEach
            val pi=pending(context,code,"",0L,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if(pi!=null){am.cancel(pi);pi.cancel()}
        }
        prefs.edit().remove(KEY_CODES).apply()
    }

    private fun pending(context:Context,code:Int,title:String,startAt:Long,flags:Int):PendingIntent?{
        val i=Intent(context,BirthdayReceiver::class.java).setAction(ACTION).putExtra("title",title).putExtra("startAt",startAt)
        return PendingIntent.getBroadcast(context,code,i,flags)
    }
}

class BirthdayReceiver:android.content.BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        BirthdayNotifications.show(context,intent.getStringExtra("title").orEmpty(),intent.getLongExtra("startAt",0L))
    }
}

object BirthdayNotifications {
    private const val CH="organizer_birthdays_v1"
    fun ensureChannel(context:Context){
        if(Build.VERSION.SDK_INT<26) return
        val nm=context.getSystemService(NotificationManager::class.java)
        val uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH,AppStrings.t(SettingsStore(context).language,"birthdays"),NotificationManager.IMPORTANCE_HIGH).apply{
            lockscreenVisibility=Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern=longArrayOf(0,350,180,350)
            setSound(uri,attrs)
        })
    }
    fun show(context:Context,title:String,startAt:Long){
        ensureChannel(context)
        val lang=SettingsStore(context).language
        val open=PendingIntent.getActivity(context,7101,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=title.ifBlank{AppStrings.t(lang,"birthdays")}
        val n=NotificationCompat.Builder(context,CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppStrings.t(lang,"birthday_tomorrow"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify((title+startAt).hashCode(),n)
    }
}
