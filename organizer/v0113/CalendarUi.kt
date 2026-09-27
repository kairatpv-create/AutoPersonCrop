package kz.kairat.organizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@Composable fun OrganizerCalendarPopup(
    db:NoteDatabase,
    lang:String,
    hasSystemCalendarAccess:Boolean,
    refreshKey:Int,
    onRequestCalendarAccess:()->Unit,
    onDismiss:()->Unit,
    onOpenProject:(Long,Boolean)->Unit
){
    val context=LocalContext.current
    var monthStart by rememberSaveable{mutableLongStateOf(OrganizerCalendarDate.startOfMonth(System.currentTimeMillis()))}
    var selectedDay by rememberSaveable{mutableLongStateOf(OrganizerCalendarDate.startOfDay(System.currentTimeMillis()))}
    var events by remember{mutableStateOf<List<OrganizerCalendarItem>>(emptyList())}
    val monthEnd=remember(monthStart){OrganizerCalendarDate.addMonths(monthStart,1)}
    LaunchedEffect(monthStart,hasSystemCalendarAccess,refreshKey){
        events=withContext(Dispatchers.IO){OrganizerCalendarRepository.loadRange(context,db,monthStart,monthEnd,hasSystemCalendarAccess)}
    }
    val selectedEvents=remember(events,selectedDay){events.filter{calendarItemTouchesDay(it,selectedDay)}}

    Popup(
        alignment=Alignment.TopCenter,
        onDismissRequest={},
        properties=PopupProperties(focusable=true,dismissOnBackPress=false,dismissOnClickOutside=false)
    ){
        Surface(
            modifier=Modifier.fillMaxSize(),
            shape=RoundedCornerShape(0.dp),
            tonalElevation=0.dp,
            shadowElevation=0.dp,
            color=MaterialTheme.colorScheme.surface
        ){
            Column(
                Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp)
            ){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    IconButton({
                        val previous=OrganizerCalendarDate.addMonths(monthStart,-1)
                        monthStart=previous
                        selectedDay=previous
                    }){Icon(Icons.Default.ChevronLeft,null)}
                    Text(
                        monthTitle(monthStart,lang),
                        Modifier.weight(1f),
                        style=MaterialTheme.typography.titleMedium,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    TextButton({
                        monthStart=OrganizerCalendarDate.startOfMonth(System.currentTimeMillis())
                        selectedDay=OrganizerCalendarDate.startOfDay(System.currentTimeMillis())
                    }){Text(AppStrings.t(lang,"today"),maxLines=1)}
                    IconButton({
                        val next=OrganizerCalendarDate.addMonths(monthStart,1)
                        monthStart=next
                        selectedDay=next
                    }){Icon(Icons.Default.ChevronRight,null)}
                    IconButton(onDismiss){Icon(Icons.Default.Close,null)}
                }

                if(!hasSystemCalendarAccess){
                    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)){
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=5.dp),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(6.dp)
                        ){
                            Icon(Icons.Default.CalendarMonth,null,Modifier.size(20.dp))
                            Text(
                                AppStrings.t(lang,"calendar_permission"),
                                Modifier.weight(1f),
                                style=MaterialTheme.typography.bodySmall,
                                maxLines=3,
                                overflow=TextOverflow.Ellipsis
                            )
                            TextButton(onRequestCalendarAccess){Text(AppStrings.t(lang,"allow"),maxLines=1)}
                        }
                    }
                }

                CalendarMonthGrid(monthStart,selectedDay,events,lang){selectedDay=it}
                HorizontalDivider()
                Text(dayTitle(selectedDay,lang),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)

                if(selectedEvents.isEmpty()){
                    Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){
                        Text(AppStrings.t(lang,"no_events_day"),style=MaterialTheme.typography.bodyMedium)
                    }
                }else{
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement=Arrangement.spacedBy(4.dp),
                        contentPadding=PaddingValues(bottom=4.dp)
                    ){
                        items(selectedEvents,key={it.stableId}){e->
                            CalendarEventCard(e,lang){
                                e.projectId?.let{onOpenProject(it,e.kind=="stage")}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarMonthGrid(
    monthStart:Long,
    selectedDay:Long,
    events:List<OrganizerCalendarItem>,
    lang:String,
    onSelect:(Long)->Unit
){
    val labels=AppStrings.weekdayLabels(lang)
    val offset=OrganizerCalendarDate.mondayOffset(monthStart)
    val count=OrganizerCalendarDate.daysInMonth(monthStart)
    val today=OrganizerCalendarDate.startOfDay(System.currentTimeMillis())
    Column(verticalArrangement=Arrangement.spacedBy(1.dp)){
        Row(Modifier.fillMaxWidth()){
            labels.forEach{label->
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                    Text(label,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
                }
            }
        }
        repeat(6){row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(1.dp)){
                repeat(7){col->
                    val day=row*7+col-offset+1
                    if(day !in 1..count){
                        Spacer(Modifier.weight(1f).aspectRatio(1.16f))
                    }else{
                        val ms=OrganizerCalendarDate.dayForMonth(monthStart,day)
                        val selected=OrganizerCalendarDate.sameDay(ms,selectedDay)
                        val isToday=OrganizerCalendarDate.sameDay(ms,today)
                        val dayEvents=events.filter{calendarItemTouchesDay(it,ms)}
                        Surface(
                            modifier=Modifier.weight(1f).aspectRatio(1.16f).clip(RoundedCornerShape(8.dp)).clickable{onSelect(ms)},
                            shape=RoundedCornerShape(8.dp),
                            color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.28f)
                        ){
                            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                                Text(day.toString(),style=MaterialTheme.typography.bodyMedium,fontWeight=if(isToday||selected)FontWeight.Bold else FontWeight.Normal)
                                if(dayEvents.isNotEmpty()){
                                    val marker=when{
                                        dayEvents.any{it.kind=="birthday"}->MaterialTheme.colorScheme.tertiary
                                        dayEvents.any{it.kind=="stage"||it.kind=="project"}->MaterialTheme.colorScheme.primary
                                        else->MaterialTheme.colorScheme.secondary
                                    }
                                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom=3.dp).size(5.dp).background(marker,CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarEventCard(e:OrganizerCalendarItem,lang:String,onClick:()->Unit){
    val clickable=e.projectId!=null
    Card(Modifier.fillMaxWidth().then(if(clickable)Modifier.clickable(onClick=onClick) else Modifier)){
        Row(
            Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)
        ){
            Icon(
                when(e.kind){
                    "birthday"->Icons.Default.Cake
                    "stage"->Icons.Default.Checklist
                    "project"->Icons.Default.Work
                    else->Icons.Default.Event
                },
                null,
                Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)){
                Text(e.title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                val meta=calendarMeta(e,lang)
                if(meta.isNotBlank())Text(meta,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(e.description.isNotBlank())Text(e.description,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(e.location.isNotBlank())Text(e.location,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(clickable)Icon(Icons.Default.ChevronRight,null,Modifier.size(18.dp))
        }
    }
}

private fun calendarItemTouchesDay(e:OrganizerCalendarItem,day:Long):Boolean{
    val start=OrganizerCalendarDate.startOfDay(day)
    val end=OrganizerCalendarDate.addDays(start,1)
    return e.startAt<end && e.endAt>start
}

private fun calendarMeta(e:OrganizerCalendarItem,lang:String):String{
    val source=when(e.kind){
        "birthday"->AppStrings.t(lang,"birthdays")
        "stage"->AppStrings.t(lang,"project_stage")+(if(e.calendarName.isNotBlank())" · ${e.calendarName}" else "")
        "project"->AppStrings.t(lang,"project_due")
        else->e.calendarName
    }
    val time=if(e.allDay)AppStrings.t(lang,"all_day") else SimpleDateFormat("HH:mm",AppStrings.locale(lang)).format(Date(e.startAt))
    return listOf(source,time).filter{it.isNotBlank()}.joinToString(" · ")
}

private fun monthTitle(ms:Long,lang:String)=SimpleDateFormat("LLLL yyyy",AppStrings.locale(lang)).format(Date(ms)).replaceFirstChar{if(it.isLowerCase())it.titlecase(AppStrings.locale(lang))else it.toString()}
private fun dayTitle(ms:Long,lang:String)=SimpleDateFormat("d MMMM, EEEE",AppStrings.locale(lang)).format(Date(ms)).replaceFirstChar{if(it.isLowerCase())it.titlecase(AppStrings.locale(lang))else it.toString()}
