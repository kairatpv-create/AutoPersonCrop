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

class MainActivity:ComponentActivity(){
    private var ready=false
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
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
        setContent{OrganizerApp()}
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))scheduleReminders()
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
