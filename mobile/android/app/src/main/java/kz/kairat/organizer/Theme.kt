package kz.kairat.organizer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary=Color(0xFF0A84D8), onPrimary=Color.White,
    primaryContainer=Color(0xFFD9F2FF), onPrimaryContainer=Color(0xFF082D42),
    secondary=Color(0xFF009FD6), onSecondary=Color.White,
    secondaryContainer=Color(0xFFCDEFFF), onSecondaryContainer=Color(0xFF063146),
    tertiary=Color(0xFFFFD43B), onTertiary=Color(0xFF17324A),
    tertiaryContainer=Color(0xFFFFF1A8), onTertiaryContainer=Color(0xFF3E3200),
    background=Color(0xFFF4FBFF), surface=Color(0xFFF8FDFF),
    surfaceVariant=Color(0xFFE3F5FD), outline=Color(0xFF70AFCB)
)
private val Dark = darkColorScheme(
    primary=Color(0xFF5AC8FA), onPrimary=Color(0xFF002E46),
    primaryContainer=Color(0xFF0A4968), onPrimaryContainer=Color(0xFFD5F3FF),
    secondary=Color(0xFF45C8F2), onSecondary=Color(0xFF003544),
    secondaryContainer=Color(0xFF123C50), onSecondaryContainer=Color(0xFFD0F2FF),
    tertiary=Color(0xFFFFD54F), onTertiary=Color(0xFF3D3100),
    tertiaryContainer=Color(0xFF5A4700), onTertiaryContainer=Color(0xFFFFED9A),
    background=Color(0xFF06151D), surface=Color(0xFF081A23),
    surfaceVariant=Color(0xFF102D3A), outline=Color(0xFF5C8EA5)
)
val LocalButtonTextScale=staticCompositionLocalOf{1f}
val LocalContentTextScale=staticCompositionLocalOf{1f}

@Composable fun OrganizerTheme(dark:Boolean,fontScale:Float,bold:Boolean,content: @Composable () -> Unit){
    OrganizerTheme(dark,fontScale,fontScale,bold,content)
}

@Composable fun OrganizerTheme(dark:Boolean=isSystemInDarkTheme(),contentScale:Float=1f,buttonScale:Float=1f,bold:Boolean=false,content: @Composable () -> Unit){
    val density=LocalDensity.current
    val contentTextScale=contentScale.coerceIn(.85f,1.45f)
    val buttonTextScale=buttonScale.coerceIn(.85f,1.45f)
    val weight=if(bold)FontWeight.SemiBold else FontWeight.Normal
    val strong=if(bold)FontWeight.Bold else FontWeight.SemiBold
    val uiBodySize=(14f*buttonTextScale).sp
    val uiTitleSize=(15f*buttonTextScale).sp
    val buttonSize=(15f*buttonTextScale).sp
    val type=Typography(
        displayLarge=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        displayMedium=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        displaySmall=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        titleLarge=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        titleMedium=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        titleSmall=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=uiBodySize,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=uiBodySize,fontWeight=weight),
        bodySmall=TextStyle(fontSize=uiBodySize,fontWeight=weight),
        labelLarge=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelMedium=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelSmall=TextStyle(fontSize=uiBodySize,fontWeight=weight)
    )
    CompositionLocalProvider(
        LocalButtonTextScale provides buttonTextScale,
        LocalContentTextScale provides contentTextScale
    ){MaterialTheme(colorScheme=if(dark)Dark else Light,typography=type,content=content)}
}

/** Text authored or entered by the user. UI/service labels must use MaterialTheme typography instead. */
@Composable fun userTextStyle(sizeSp:Float=14f,strong:Boolean=false):TextStyle{
    val scale=LocalContentTextScale.current.coerceIn(.85f,1.45f)
    val base=if(strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    return base.copy(fontSize=(sizeSp*scale).sp)
}

