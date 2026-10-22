package kz.kairat.organizer

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose rendering and navigation, on synthetic emulator data only. */
@RunWith(AndroidJUnit4::class)
class MobileUiTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun waitFor(label:String):AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        while(android.os.SystemClock.uptimeMillis()<deadline){
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull{it.text?.toString()==label&&it.isVisibleToUser}?.let{return it}
            Thread.sleep(100)
        }
        error("Visible UI label missing: $label")
    }
    private fun click(label:String){
        var node:AccessibilityNodeInfo?=waitFor(label)
        while(node!=null){if(node.isClickable&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;node=node.parent}
        error("UI label could not be clicked: $label")
    }
    private fun screenshot(name:String){
        instrumentation.waitForIdleSync()
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null),"synthetic-ui").apply{mkdirs()}.resolve("$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun russianEnglishNavigationAndLargeTextRender(){
        check(InstrumentationRegistry.getArguments().getString("syntheticOnly")=="1")
        for((lang,dark,scale) in listOf(Triple("ru",false,1f),Triple("en",true,1.45f))){
            val settings=SettingsStore(context);settings.language=lang;settings.darkTheme=dark;settings.fontScale=scale;settings.buttonFontScale=scale
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try{
                waitFor(AppStrings.t(lang,"notes"));screenshot("$lang-notes")
                click(AppStrings.t(lang,"money"));waitFor(AppStrings.t(lang,"income"));screenshot("$lang-finances")
                click(AppStrings.t(lang,"projects"));waitFor("Synthetic upgrade project");screenshot("$lang-projects")
                click(AppStrings.t(lang,"settings"));waitFor(AppStrings.t(lang,"button_font"));screenshot("$lang-settings")
            }finally{instrumentation.runOnMainSync{activity.finish()}}
        }
    }
}
