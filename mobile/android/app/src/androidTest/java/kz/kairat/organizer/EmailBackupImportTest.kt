package kz.kairat.organizer

import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mail attachment delivery via content URI; no real mailbox or message is used. */
@RunWith(AndroidJUnit4::class)
class EmailBackupImportTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun find(label:String,timeout:Long=15000):AccessibilityNodeInfo {
        val end=android.os.SystemClock.uptimeMillis()+timeout
        while(android.os.SystemClock.uptimeMillis()<end){
            fun walk(n:AccessibilityNodeInfo):AccessibilityNodeInfo?{
                if(n.isVisibleToUser&&n.text?.toString()?.equals(label,true)==true)return n
                for(i in 0 until n.childCount)n.getChild(i)?.let{walk(it)}?.let{return it}
                return null
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let{walk(it)}?.let{return it}
            Thread.sleep(100)
        }
        error("Missing import UI: $label")
    }
    private fun click(label:String){
        var node:AccessibilityNodeInfo?=find(label)
        while(node!=null){if(node.isClickable&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;node=node.parent}
        error("Cannot click $label")
    }
    private fun unlock(){
        find(AppStrings.t("en","unlock"))
        fun field(n:AccessibilityNodeInfo):AccessibilityNodeInfo?{
            if(n.className=="android.widget.EditText")return n
            for(i in 0 until n.childCount)n.getChild(i)?.let{field(it)}?.let{return it}
            return null
        }
        val input=checkNotNull(field(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)))
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"2580")
        }))
        click(AppStrings.t("en","confirm"))
    }
    private fun start(uri:Uri,type:String):BackupImportActivity {
        val implicit=Intent(Intent.ACTION_VIEW).setDataAndType(uri,type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(context.packageManager.queryIntentActivities(implicit,0).any{it.activityInfo.name==BackupImportActivity::class.java.name})
        return instrumentation.startActivitySync(implicit.setClass(context,BackupImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as BackupImportActivity
    }
    private fun backup(db:NoteDatabase,s:SettingsStore)=JSONObject(BackupManager.createBackup(db,s)).apply{remove("createdAt")}.toString()
    private fun uri(file:File)=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    private fun prepare():SettingsStore {
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        AttachmentStore.initialize(context)
        return SettingsStore(context).also{it.language="en";it.fontScale=.85f;it.buttonFontScale=.85f}
    }

    @Test fun malformedEmailAttachmentsLeaveRecordsAndFilesUnchanged(){
        val s=prepare();s.setPinProtectionEnabled(false);val db=NoteDatabase(context)
        val before=backup(db,s)
        val secret=File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes()
        val file=File(context.cacheDir,"synthetic-invalid-mail.json")
        val inputs=listOf("{\"notes\":[]}","not a backup",JSONObject(BackupManager.createBackup(db,s)).put("money","wrong type").toString())
        try{
            for(input in inputs){
                file.writeText(input);val activity=start(uri(file),"application/json")
                try{find("Could not restore the backup");assertEquals(before,backup(db,s))
                    assertArrayEquals(secret,File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes())
                }finally{instrumentation.runOnMainSync{activity.finish()}}
            }
        }finally{file.delete();db.close()}
    }

    @Test fun mailAttachmentRequiresPinAndConsentThenRestoresRecordsAndAttachments(){
        val s=prepare();s.setPin("2580");s.setPinProtectionEnabled(true);val db=NoteDatabase(context)
        val original=BackupManager.createBackup(db,s);val before=backup(db,s)
        val root=JSONObject(original);root.getJSONArray("notes").getJSONObject(0).put("title","Restored from synthetic email")
        val file=File(context.cacheDir,"synthetic-valid-mail.zip")
        val secret=File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes()
        try{
            BackupManager.writeArchiveToUri(context,uri(file),root.toString())
            var activity=start(uri(file),"application/zip")
            try{
                find(AppStrings.t("en","unlock"));assertEquals(before,backup(db,s));unlock()
                find("Restore backup");assertEquals(before,backup(db,s));click(AppStrings.t("en","cancel"))
                instrumentation.waitForIdleSync();assertEquals(before,backup(db,s))
                assertArrayEquals(secret,File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes())
            }finally{instrumentation.runOnMainSync{activity.finish()}}
            activity=start(uri(file),"application/zip")
            try{
                unlock();click("Restore backup");find(AppStrings.t("en","restore_ok"))
                assertEquals("Restored from synthetic email",db.getNotes().first().title)
                assertEquals(19.95,db.getMoneyEntries().single().amount,0.0)
                assertEquals(987.65,db.getProjects().single().budget,0.0)
                val restored=JSONObject(BackupManager.createBackup(db,s))
                assertEquals(JSONObject(original).getJSONArray("attachments").toString(),restored.getJSONArray("attachments").toString())
                assertArrayEquals(secret,File(context.noBackupFilesDir,"protected-settings/credentials.bin").readBytes())
            }finally{instrumentation.runOnMainSync{activity.finish()}}
        }finally{BackupManager.restore(original,db,"en");s.setPinProtectionEnabled(false);file.delete();db.close()}
    }
}
