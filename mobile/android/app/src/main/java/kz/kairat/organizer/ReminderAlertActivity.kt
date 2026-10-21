package kz.kairat.organizer

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
