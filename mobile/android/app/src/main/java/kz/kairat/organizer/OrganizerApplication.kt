package kz.kairat.organizer
import android.app.Application
class OrganizerApplication:Application(){override fun onCreate(){super.onCreate();AttachmentStore.initialize(this)}}
