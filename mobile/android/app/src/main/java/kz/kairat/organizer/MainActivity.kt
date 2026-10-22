package kz.kairat.organizer

import android.os.Bundle
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity:ComponentActivity(){
    private var ready=false
    private var pinRequest by androidx.compose.runtime.mutableStateOf("")
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("require_pin",false))pinRequest=java.util.UUID.randomUUID().toString()
        openOrganizer()
    }
    private fun openOrganizer(){
        try{AttachmentStore.initialize(this)}catch(failure:RestoreJournal.RecoveryRequired){
            val ru=SettingsStore(this).language=="ru"
            AlertDialog.Builder(this).setMessage(failure.message).setCancelable(false)
                .setPositiveButton(if(ru)"Повторить" else "Retry"){_,_->openOrganizer()}
                .setNegativeButton(if(ru)"Закрыть" else "Close"){_,_->finish()}.show()
            return
        }
        ready=true
        WindowCompat.setDecorFitsSystemWindows(window,true)
        ReminderNotifications.ensureChannel(this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1001)
        setContent{OrganizerApp(pinRequest)}
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))scheduleReminders()
    }
    override fun onNewIntent(newIntent:android.content.Intent){
        super.onNewIntent(newIntent);setIntent(newIntent)
        if(newIntent.getBooleanExtra("require_pin",false))pinRequest=java.util.UUID.randomUUID().toString()
    }
    override fun onResume(){
        super.onResume()
        if(!ready)return
        scheduleReminders()
    }
    private fun scheduleReminders(){
        ReminderNotifications.ensureChannel(this)
        ReminderScheduler.rescheduleAll(this)
        if(OrganizerCalendarRepository.hasCalendarPermission(this)) BirthdayScheduler.rescheduleAll(this)
    }
}
