package kz.kairat.organizer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The chosen mail/share app receives the saved file only after the user selects it. */
internal object BackupSharing {
    fun intent(uri:Uri,lang:String):Intent = Intent(Intent.ACTION_SEND).apply {
        type="application/zip"
        putExtra(Intent.EXTRA_STREAM,uri)
        putExtra(Intent.EXTRA_SUBJECT,AppStrings.t(lang,"backup_subject"))
        clipData=ClipData.newRawUri("Organizer Pro backup",uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun share(context:Context,uri:Uri,lang:String) {
        context.startActivity(Intent.createChooser(intent(uri,lang),AppStrings.t(lang,"backup_share_title")))
    }
}
