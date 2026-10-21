package kz.kairat.organizer
import android.app.Application
class OrganizerApplication:Application(){
    override fun onCreate(){
        super.onCreate()
        // MainActivity provides a retry/close screen; database opens remain guarded by the journal.
        try{AttachmentStore.initialize(this)}catch(_:RestoreJournal.RecoveryRequired){}
    }
}
