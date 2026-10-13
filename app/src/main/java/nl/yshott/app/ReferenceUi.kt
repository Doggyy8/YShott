package nl.yshott.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val DesignInk = Color(0xFF10334C)
internal val DesignMint = Color(0xFFDDF4EA)
internal val DesignPeach = Color(0xFFFFE7D8)
internal val DesignLavender = Color(0xFFECE5FF)
private val UiDutch = Locale.forLanguageTag("nl-NL")
private val RoundedFont = FontFamily(Font(R.font.nunito, FontWeight.Normal))
internal val ReferenceTypography = Typography().let { t -> t.copy(
    displayLarge=t.displayLarge.copy(fontFamily=RoundedFont),
    headlineLarge=t.headlineLarge.copy(fontFamily=RoundedFont,fontWeight=FontWeight.ExtraBold),
    headlineMedium=t.headlineMedium.copy(fontFamily=RoundedFont,fontWeight=FontWeight.ExtraBold),
    headlineSmall=t.headlineSmall.copy(fontFamily=RoundedFont,fontWeight=FontWeight.Bold),
    titleLarge=t.titleLarge.copy(fontFamily=RoundedFont,fontWeight=FontWeight.Bold),
    titleMedium=t.titleMedium.copy(fontFamily=RoundedFont,fontWeight=FontWeight.Bold),
    titleSmall=t.titleSmall.copy(fontFamily=RoundedFont,fontWeight=FontWeight.Bold),
    bodyLarge=t.bodyLarge.copy(fontFamily=RoundedFont),bodyMedium=t.bodyMedium.copy(fontFamily=RoundedFont),
    bodySmall=t.bodySmall.copy(fontFamily=RoundedFont),labelLarge=t.labelLarge.copy(fontFamily=RoundedFont),
    labelMedium=t.labelMedium.copy(fontFamily=RoundedFont),labelSmall=t.labelSmall.copy(fontFamily=RoundedFont)
) }
private fun dayTitle(day:LocalDate)=day.format(DateTimeFormatter.ofPattern("EEEE d MMMM",UiDutch)).replaceFirstChar { it.titlecase(UiDutch) }
private fun household(data:PlannerData,day:LocalDate,child:String=""):String? =
    data.residence.firstOrNull { it.status=="active" && (child.isEmpty() || it.child==child) }?.let { PlannerRules.residenceAt(it,day) }
private fun houseColor(name:String)=if(name.equals("Mama",true)) DesignPeach else DesignMint
internal fun referenceSymbol(text:String):Int=when {
    text.contains("🏊") || text.contains("zwem",true) -> 6
    text.contains("🏫") || text.contains("school",true) -> 5
    text.contains("tas",true) || text.contains("🎒") -> 7
    text.contains("zon",true) || text.contains("☀") -> 4
    text.contains("ster",true) || text.contains("⭐") -> 8
    text.contains("🎁") -> 9
    text.contains("👩") -> 1
    text.contains("👧") -> 2
    text.contains("👨") -> 0
    else -> 3
}
private object ReferenceArt {
    private var atlas: android.graphics.Bitmap? = null
    private val frames = mutableMapOf<Int, androidx.compose.ui.graphics.ImageBitmap>()
    @Synchronized fun frame(context:android.content.Context,index:Int):androidx.compose.ui.graphics.ImageBitmap =
        frames.getOrPut(index) {
            val source=atlas ?: BitmapFactory.decodeResource(context.resources,R.drawable.child_pictograms).also { atlas=it }
            val w=source.width/4; val h=source.height/3
            android.graphics.Bitmap.createBitmap(source,(index%4)*w,(index/4)*h,w,h).asImageBitmap()
        }
}
@Composable internal fun ReferencePicto(index:Int,description:String?,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val bitmap=remember(index) { ReferenceArt.frame(context,index) }
    Image(bitmap,description,modifier)
}
@Composable internal fun ReferenceHeader(data:PlannerData,child:Boolean,person:String,onChild:(String)->Unit,onParent:()->Unit,onListen:()->Unit,onNotifications:()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal=18.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Home,"YShott",Modifier.size(34.dp),tint=MaterialTheme.colorScheme.primary)
        Text("YShott",Modifier.weight(1f),fontWeight=FontWeight.ExtraBold,fontSize=22.sp)
        if(child) {
            ReferencePicto(2,"Jouw profiel",Modifier.size(42.dp).clip(CircleShape).background(DesignLavender))
            ReferenceListen("Uitleg beluisteren",onListen,48)
            IconButton(onClick=onParent,modifier=Modifier.size(32.dp)) { Icon(Icons.Outlined.Lock,"Ouderinstellingen openen met pincode",Modifier.size(21.dp)) }
        } else {
            Box {
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { expanded=true }.padding(horizontal=11.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Text("Bij "+(household(data,LocalDate.now())?:"ons gezin").lowercase(UiDutch),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                    Icon(Icons.Outlined.ExpandMore,null,Modifier.size(20.dp))
                }
                DropdownMenu(expanded,{expanded=false}) {
                    data.people.filter { it.child }.forEach { p -> DropdownMenuItem(
                        text={Text("Kindmodus: "+p.name)},onClick={expanded=false;onChild(p.id)}) }
                }
            }
            IconButton(onClick=onNotifications,modifier=Modifier.size(36.dp)) { Icon(Icons.Outlined.Notifications,"Meldingen",Modifier.size(28.dp)) }
        }
    }
}
@Composable internal fun ReferenceNav(child:Boolean,page:String,onPage:(String)->Unit) {
    val pages=if(child) listOf("today","tasks","rewards","chat") else listOf("today","agenda","chat","more")
    val names=if(child) listOf("Mijn dag","Klusjes","Beloningen","Chat") else listOf("Vandaag","Agenda","Chat","Meer")
    val icons=if(child) listOf(Icons.Outlined.WbSunny,Icons.Outlined.CheckBox,Icons.Outlined.CardGiftcard,Icons.Outlined.ChatBubbleOutline)
        else listOf(Icons.Outlined.Home,Icons.Outlined.CalendarMonth,Icons.Outlined.ChatBubbleOutline,Icons.Outlined.Menu)
    Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=1.dp) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal=12.dp,vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            pages.forEachIndexed { i,p ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if(page==p) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onPage(p) }.padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    if(child && i in listOf(0,2)) ReferencePicto(if(i==0)4 else 9,null,Modifier.size(31.dp))
                    else Icon(icons[i],null,Modifier.size(29.dp),tint=MaterialTheme.colorScheme.primary)
                    Text(names[i],fontSize=11.sp,fontWeight=if(page==p)FontWeight.Bold else FontWeight.Medium,maxLines=1)
                }
            }
        }
    }
}
@Composable internal fun ReferenceListen(description:String,onListen:()->Unit,size:Int=50) {
    FilledIconButton(onClick=onListen,modifier=Modifier.size(size.dp),shape=CircleShape,
        colors=IconButtonDefaults.filledIconButtonColors(containerColor=DesignInk,contentColor=Color.White)) {
        Icon(Icons.Outlined.VolumeUp,description,Modifier.size((size*0.55).dp))
    }
}
@Composable private fun ReferenceCard(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.surface,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(7.dp),content=content)
}
@Composable internal fun ReferenceChildCard(index:Int,title:String,color:Color,onListen:()->Unit,portrait:Int?=null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(horizontal=10.dp,vertical=6.dp).heightIn(min=86.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        if(portrait!=null) {
            ReferencePicto(portrait,null,Modifier.size(49.dp).clip(CircleShape).background(Color.White))
            ReferencePicto(3,null,Modifier.size(45.dp))
        } else ReferencePicto(index,null,Modifier.width(103.dp).height(88.dp))
        Text(title,Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=DesignInk)
        ReferenceListen("Luister: "+title,onListen,48)
    }
}
@Composable internal fun ReferenceChildTask(t:Task,speak:(String)->Unit,onDone:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(DesignMint).padding(10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ReferencePicto(referenceSymbol(t.title+" "+t.emoji),null,Modifier.width(105.dp).height(100.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(t.title,fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=DesignInk)
            ReferenceListen("Luister: "+t.title,{speak(t.title+". "+if(t.status=="pending")"Je ouder kijkt het nog na." else "Tik op het vinkje als je klaar bent.")},40)
        }
        if(t.status=="open") FilledIconButton(onClick=onDone,modifier=Modifier.size(53.dp),shape=CircleShape,
            colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF169966),contentColor=Color.White)) {
            Icon(Icons.Outlined.Check,"Klusje klaar",Modifier.size(34.dp))
        } else {
            Icon(if(t.status=="pending") Icons.Outlined.HourglassEmpty else Icons.Outlined.CheckCircle,
                if(t.status=="pending")"Wacht op je ouder" else "Klaar",Modifier.size(38.dp),tint=DesignInk)
        }
    }
}
@Composable internal fun ReferenceEventCard(e:Event,data:PlannerData,darkTime:Boolean,onEdit:()->Unit) {
    val person=data.people.find { it.id==e.person }
    val busy=e.title=="Bezet"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick=onEdit).heightIn(min=74.dp).height(IntrinsicSize.Min),
        verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(69.dp).fillMaxHeight().background(if(darkTime) if(busy) Color(0xFFA2AFBE) else DesignInk else Color.Transparent).padding(vertical=15.dp),
            contentAlignment=Alignment.Center) { Text(e.time,fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=if(darkTime&&!busy)Color.White else MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f).padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(e.title,fontSize=18.sp,fontWeight=FontWeight.ExtraBold)
            if(busy) Text("🔒 Gedeelde beschikbaarheid",fontSize=12.sp)
            else {
                if(person!=null) Text(person.name,Modifier.clip(RoundedCornerShape(30.dp)).background(Color(person.color).copy(alpha=0.28f)).padding(horizontal=12.dp,vertical=2.dp),
                    color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,fontSize=12.sp)
                if(e.note.isNotBlank()) Text(e.note,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(e.place.isNotBlank()) Text(e.place,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val icon=when { e.emoji=="🏊" -> Icons.Outlined.Pool; e.pickUp.isNotBlank()||e.dropOff.isNotBlank() -> Icons.Outlined.DirectionsCar; else -> Icons.Outlined.Event }
        Icon(icon,"Afspraak bewerken",Modifier.padding(end=13.dp).size(26.dp),tint=MaterialTheme.colorScheme.primary)
    }
}
@Composable internal fun ReferenceSection(title:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.ExtraBold)
        TextButton(onClick=onClick) { Text("Alles",fontSize=12.sp);Icon(Icons.Outlined.ChevronRight,null,Modifier.size(17.dp)) }
    }
}
@Composable internal fun ReferenceToday(data:PlannerData,child:Boolean,active:String,speak:(String)->Unit,
    onAgenda:()->Unit,onTasks:()->Unit,onCook:(String)->Unit,onMeal:()->Unit,onEdit:(Event)->Unit,onDone:(Task)->Unit,onSettings:()->Unit) {
    val today=LocalDate.now()
    val r=data.residence.firstOrNull { it.status=="active" && (!child||it.child==active) }
    val stay=r?.let { PlannerRules.residenceAt(it,today) }
    val events=data.events.filter { PlannerRules.occurs(it,today)&&(!child||it.person==active||it.relatedChild==active) }.sortedBy { it.time }
    val tasks=data.tasks.filter { it.status=="open"&&(!child||it.person==active) }
    if(child) {
        Row(verticalAlignment=Alignment.CenterVertically) { ReferencePicto(4,null,Modifier.size(78.dp));Text("Mijn dag",fontSize=32.sp,fontWeight=FontWeight.ExtraBold) }
        if(stay!=null) ReferenceChildCard(3,"Bij "+stay.lowercase(UiDutch),houseColor(stay),{speak("Je bent vandaag bij "+stay)},if(stay.equals("Mama",true))1 else 0)
        events.take(2).forEach { e -> ReferenceChildCard(referenceSymbol(e.title+" "+e.emoji),e.title,if(referenceSymbol(e.title+" "+e.emoji)==6)DesignLavender else Color(0xFFE2F0FC),
            {speak(e.title+". Om "+e.time+". "+e.note)}) }
        tasks.take(1).forEach { ReferenceChildTask(it,speak,{onDone(it)}) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFF2C9)).padding(horizontal=12.dp,vertical=5.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            ReferencePicto(8,null,Modifier.size(67.dp));Text("Goed gedaan!",Modifier.weight(1f),fontWeight=FontWeight.ExtraBold,fontSize=22.sp,color=DesignInk)
            ReferenceListen("Goed gedaan!",{speak("Goed gedaan! Jij helpt mee.")},46)
        }
    } else {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(dayTitle(today),fontSize=14.sp);Text("Onze dag",fontSize=30.sp,fontWeight=FontWeight.ExtraBold) }
            FilledTonalIconButton(onClick=onSettings,shape=RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.Edit,"Vandaag aanpassen") }
        }
        if(r!=null&&stay!=null&&"residence" in data.settings.homeCards) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(houseColor(stay)).padding(15.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Home,null,Modifier.size(36.dp),tint=DesignInk)
                val name=data.people.find { it.id==r.child }?.name?:"Kind"
                Box(Modifier.size(44.dp).background(Color(0xFF9CDAC5),CircleShape),contentAlignment=Alignment.Center) { Text(name.take(1),fontWeight=FontWeight.ExtraBold,fontSize=21.sp,color=DesignInk) }
                Column {
                    Text(name+" is bij "+stay.lowercase(UiDutch),fontWeight=FontWeight.ExtraBold,fontSize=18.sp,color=DesignInk)
                    val next=(1..r.days.size+1).map { today.plusDays(it.toLong()) }.firstOrNull { PlannerRules.residenceAt(r,it)!=stay }
                    if(next!=null) Text("Volgende wissel: "+next.format(DateTimeFormatter.ofPattern("EEEE",UiDutch))+" "+r.switchTime,fontSize=12.sp,color=DesignInk)
                }
            }
        }
        if("agenda" in data.settings.homeCards) {
            ReferenceSection("Vandaag",onAgenda)
            if(events.isEmpty()) ReferenceCard { Text("Geen afspraken vandaag") }
            events.take(6).forEach { e -> ReferenceEventCard(e,data,false,{onEdit(e)}) }
        }
        if("tasks" in data.settings.homeCards) ReferenceCard(Modifier.clickable(onClick=onTasks)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FactCheck,null,Modifier.size(27.dp));Text("Nog te doen",fontSize=19.sp,fontWeight=FontWeight.ExtraBold)
            }
            tasks.firstOrNull()?.let { t -> Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={onDone(t)}) { Icon(Icons.Outlined.RadioButtonUnchecked,"Taak afronden") };Text(t.title,fontSize=16.sp)
            } }
            Row { Text(tasks.size.toString()+" taken open",Modifier.weight(1f),fontSize=12.sp);Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp)) }
        }
        if("meal" in data.settings.homeCards) {
            val meal=data.meals.firstOrNull { it.date==today.toString()&&it.slot=="Diner" }
            ReferenceCard {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Restaurant,null,Modifier.size(29.dp));Text("Vanavond",fontSize=19.sp,fontWeight=FontWeight.ExtraBold)
                }
                Text(meal?.title?:"Wat eten we vandaag?",Modifier.padding(start=41.dp),fontSize=17.sp)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={if(meal?.recipe?.isNotEmpty()==true)onCook(meal.recipe) else onMeal()},modifier=Modifier.weight(1f)) {
                        Text(if(meal?.recipe?.isNotEmpty()==true)"Recept bekijken" else "Maaltijd toevoegen");Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))
                    }
                    FilledIconButton(onClick=onMeal,modifier=Modifier.size(48.dp),shape=CircleShape) { Icon(Icons.Outlined.Add,"Maaltijd toevoegen") }
                }
            }
        }
    }
}
@Composable internal fun ReferenceAgendaTitle(onAdd:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("Agenda",Modifier.weight(1f),fontSize=30.sp,fontWeight=FontWeight.ExtraBold)
        FilledIconButton(onClick=onAdd,modifier=Modifier.size(49.dp),shape=CircleShape) { Icon(Icons.Outlined.Add,"Toevoegen",Modifier.size(30.dp)) }
    }
}
@Composable internal fun ReferenceViewSwitch(selected:String,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        listOf("Dag","Week","Maand").forEach { label -> Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
            .background(if(selected==label)DesignInk else MaterialTheme.colorScheme.surface).clickable { onSelect(label) }.padding(vertical=10.dp),contentAlignment=Alignment.Center) {
            Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,color=if(selected==label)Color.White else MaterialTheme.colorScheme.primary)
        } }
    }
}
@Composable internal fun ReferenceCalendar(day:LocalDate,view:String,data:PlannerData,onDay:(LocalDate)->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
    val month=YearMonth.from(day);val first=day.minusDays((day.dayOfWeek.value-1).toLong())
    val cells=if(view=="Maand")PlannerRules.monthCells(month) else if(view=="Week")(0..6).map { first.plusDays(it.toLong()) } else listOf(day)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        IconButton(onClick={onDay(when(view){"Maand"->day.minusMonths(1);"Week"->day.minusWeeks(1);else->day.minusDays(1)})}) { Icon(Icons.Outlined.ChevronLeft,"Vorige periode") }
        Text(if(view=="Maand")day.format(DateTimeFormatter.ofPattern("MMMM yyyy",UiDutch)).replaceFirstChar { it.titlecase(UiDutch) }
            else if(view=="Week")first.dayOfMonth.toString()+" – "+first.plusDays(6).format(DateTimeFormatter.ofPattern("d MMMM",UiDutch)) else dayTitle(day),
            fontWeight=FontWeight.ExtraBold,fontSize=17.sp)
        IconButton(onClick={onDay(when(view){"Maand"->day.plusMonths(1);"Week"->day.plusWeeks(1);else->day.plusDays(1)})}) { Icon(Icons.Outlined.ChevronRight,"Volgende periode") }
    }
    if(view=="Maand")Row(Modifier.fillMaxWidth()) { listOf("Ma","Di","Wo","Do","Vr","Za","Zo").forEach { Text(it,Modifier.weight(1f),textAlign=TextAlign.Center,fontSize=13.sp,fontWeight=FontWeight.Bold) } }
    cells.chunked(if(view=="Dag")1 else 7).forEach { week ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            week.forEach { d ->
                val stay=household(data,d);val selected=d==day
                val fill=if(selected)DesignInk else if(view=="Maand"&&stay!=null&&YearMonth.from(d)==month)houseColor(stay) else MaterialTheme.colorScheme.surface
                val ink=if(selected)Color.White else if(YearMonth.from(d)!=month)Color(0xFF9DA9B8) else MaterialTheme.colorScheme.primary
                Column(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(fill).clickable { onDay(d) }
                    .semantics { contentDescription=dayTitle(d)+(if(stay!=null)", bij "+stay else "") }.height(if(view=="Week")64.dp else 43.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    if(view=="Week")Text(d.format(DateTimeFormatter.ofPattern("EE",UiDutch)),fontSize=12.sp,color=ink)
                    Text(d.dayOfMonth.toString(),fontSize=if(view=="Week")21.sp else 15.sp,fontWeight=FontWeight.Bold,color=ink)
                    if(data.events.any { PlannerRules.occurs(it,d) })Box(Modifier.size(5.dp).background(Color(0xFF9E66DA),CircleShape))
                }
            }
        }

    }
    if(view=="Week") {
        Row(Modifier.fillMaxWidth().padding(top=8.dp).clip(RoundedCornerShape(10.dp))) {
            val segments=mutableListOf<Pair<String?,Int>>()
            cells.forEach { d ->
                val name=household(data,d)
                if(segments.lastOrNull()?.first==name&&segments.isNotEmpty()) {
                    val last=segments.removeAt(segments.lastIndex);segments.add(name to last.second+1)
                } else segments.add(name to 1)
            }
            segments.forEach { (name,count) -> Box(Modifier.weight(count.toFloat()).height(24.dp)
                .background(name?.let(::houseColor)?:MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
                Text(name?.let { "Bij "+it.lowercase(UiDutch) }?:"",fontSize=11.sp,fontWeight=FontWeight.Bold,color=DesignInk)
            } }
        }
        Text("Verblijf "+(data.people.find { it.id==data.residence.firstOrNull { r->r.status=="active" }?.child }?.name?:""),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else if(view=="Maand") {
        Row(Modifier.padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(15.dp)) {
            listOf("Papa","Mama").forEach { name -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(15.dp).background(houseColor(name),CircleShape));Text(name,fontSize=12.sp)
            } }
        }
    }
    }
}
@Composable internal fun ReferenceDayTitle(day:LocalDate,data:PlannerData) {
    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(dayTitle(day),Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.ExtraBold)
        household(data,day)?.let { name -> Text("Bij "+name.lowercase(UiDutch),Modifier.clip(RoundedCornerShape(22.dp)).background(houseColor(name)).padding(horizontal=12.dp,vertical=5.dp),
            color=DesignInk,fontSize=12.sp,fontWeight=FontWeight.Bold) }
    }
}
