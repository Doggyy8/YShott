package nl.plantscan.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity:AppCompatActivity() {
    private val forest=Color.rgb(27,79,63)
    private val pastel=Color.rgb(231,243,223)
    private val dark=Color.rgb(34,54,45)
    private val quiet=Color.rgb(94,114,103)
    private val bg=Color.rgb(247,249,245)
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var store:PlantStore
    private lateinit var root:LinearLayout
    private lateinit var scroll:ScrollView
    private var busy=false
    private var captureUri:Uri?=null
    private var video:VideoView?=null
    private var overlay:VideoMarksView?=null
    private var ticker:Runnable?=null
    private var refineTarget:PlantObservation?=null

    private val openVideo=registerForActivityResult(ActivityResultContracts.OpenDocument()){ uri->
        if(uri!=null) {
            try {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            catch(_:Exception){}
            useVideo(uri)
        }
    }
    private val captureVideo=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK) {
            val uri=captureUri ?: result.data?.data
            if(uri!=null)useVideo(uri) else toast("Opgenomen video niet gevonden")
        }
    }
    private val exportPdf=registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri->
        if(uri!=null) {
            worker.execute {
                try {
                    ReportWriter(this).write(uri,store)
                    runOnUiThread {toast("PDF-rapport opgeslagen")}
                }catch(e:Exception){runOnUiThread{error(e)}}
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=PlantStore(this).apply{load()}
        window.statusBarColor=forest
        showHome()
    }
    override fun onDestroy(){
        ticker?.let{handler.removeCallbacks(it)}
        worker.shutdownNow()
        super.onDestroy()
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun background(color:Int,radius:Int=16):GradientDrawable=
        GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Float=15f,color:Int=dark,bold:Boolean=false):TextView = TextView(this).apply{
        this.text=value;textSize=size;setTextColor(color)
        typeface=android.graphics.Typeface.create("sans-serif",if(bold)1 else 0)
        setLineSpacing(dp(3).toFloat(),1.0f)
    }
    private fun gap(parent:LinearLayout,h:Int=12){parent.addView(View(this),LinearLayout.LayoutParams(1,dp(h)))}
    private fun button(caption:String,secondary:Boolean=false,callback:()->Unit):Button = Button(this).apply{
        text=caption;textSize=14f;isAllCaps=false
        setTextColor(if(secondary)forest else Color.WHITE)
        background=background(if(secondary)pastel else forest,13)
        setPadding(dp(12),dp(9),dp(12),dp(9))
        setOnClickListener{callback()}
    }
    private fun card():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        background=background(Color.WHITE,18)
        elevation=dp(2).toFloat()
        setPadding(dp(16),dp(16),dp(16),dp(16))
    }
    private fun mount(title:String,subtitle:String):LinearLayout {
        ticker?.let{handler.removeCallbacks(it)};ticker=null
        video?.stopPlayback();video=null;overlay=null
        scroll=ScrollView(this).apply {setBackgroundColor(bg);isFillViewport=true}
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(25),dp(18),dp(26))}
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text("◉  PLANTSCAN AI",13f,forest,true))
        gap(root,8)
        root.addView(text(title,29f,dark,true))
        gap(root,5)
        root.addView(text(subtitle,14f,quiet))
        gap(root,18)
        return root
    }
    private fun showHome() {
        mount("Mijn planteninventarisatie","Film een tuin, controleer soorten en maak een PDF.")
        val intro=card()
        intro.addView(text(store.projectName,20f,forest,true))
        gap(intro,7)
        intro.addView(text("${store.records.size} waarnemingen  ·  ${store.records.map{it.scientific}.distinct().size} voorgestelde soorten",14f,quiet))
        gap(intro,13)
        intro.addView(button("Projectnaam wijzigen",true){renameProject()})
        root.addView(intro)
        gap(root)
        val actions=card()
        actions.addView(text("Nieuwe video toevoegen",19f,dark,true))
        gap(actions,12)
        actions.addView(button("📹  Video opnemen") {recordVideo()})
        gap(actions,8)
        actions.addView(button("⬆  Video uit galerij kiezen",true) {openVideo.launch(arrayOf("video/*"))})
        root.addView(actions)
        gap(root)
        if(store.videoUri.isNotBlank()) {
            val existing=card()
            existing.addView(text("Laatste videosessie",17f,dark,true))
            gap(existing,10)
            existing.addView(button("▶  Video en markeringen bekijken",true) {showVideo()})
            gap(existing,8)
            existing.addView(button("↻  Video opnieuw analyseren",true) {scanCurrent()})
            root.addView(existing)
            gap(root)
        }
        if(store.records.isNotEmpty()) {
            val review=card()
            review.addView(text("Resultaten controleren",18f,dark,true))
            gap(review,8)
            val uncertain=store.records.count{it.reviewNeeded()}
            review.addView(text("$uncertain waarnemingen met lage score of weinig onderscheid tussen voorgestelde soorten.",13f,quiet))
            gap(review,12)
            review.addView(button("🌿  Bekijk en corrigeer planten") {showResults()})
            gap(review,8)
            review.addView(button("📄  Exporteer PDF",true) {exportReport()})
            root.addView(review)
            gap(root)
        }
        root.addView(text("Privacy: video's en foto's worden lokaal op je toestel verwerkt. De soortherkenning draait offline met Iris 9. Alleen de optionele Nederlandse-naamzoekfunctie gebruikt internet.",12f,quiet))
        gap(root,7)
        root.addView(text("Let op: automatisch getelde exemplaren zijn voorlopige schattingen. Controleer het aantal per plant in de resultatenlijst.",12f,quiet))
    }
    private fun renameProject() {
        val input=EditText(this).apply{setText(store.projectName);setSingleLine(true)}
        AlertDialog.Builder(this).setTitle("Naam van de tuin of het perceel")
            .setView(input).setNegativeButton("Annuleren",null)
            .setPositiveButton("Bewaren"){_,_->store.projectName=input.text.toString().ifBlank{"Mijn tuin"};store.save();showHome()}.show()
    }
    private fun recordVideo() {
        try {
            val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
            val file=File(dir,"plantscan_${System.currentTimeMillis()}.mp4")
            captureUri=FileProvider.getUriForFile(this,"$packageName.files",file)
            val intent=Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply{
                putExtra(MediaStore.EXTRA_OUTPUT,captureUri)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT,900)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            captureVideo.launch(intent)
        } catch(e:Exception){error(e)}
    }
    private fun useVideo(uri:Uri) {
        AlertDialog.Builder(this).setTitle("Video analyseren?")
            .setMessage("De vorige videosessie en bijbehorende waarnemingen worden vervangen. Maak eerst een PDF als je de bestaande resultaten wilt bewaren.")
            .setPositiveButton("Start analyse"){_,_->
                refineTarget=null;store.clearForNewVideo(uri.toString());scanCurrent()
            }.setNegativeButton("Annuleren",null).show()
    }
    private fun scanCurrent() {
        if(busy)return
        val uri=Uri.parse(store.videoUri)
        mount("Video analyseren","De app zoekt voorlopige plantenregio's en vraagt Iris 9 om soortvoorstellen.")
        val panel=card();root.addView(panel)
        val spinner=ProgressBar(this)
        panel.addView(spinner,LinearLayout.LayoutParams(dp(52),dp(52)))
        gap(panel,18)
        val status=text("Model laden...",16f,forest,true)
        panel.addView(status)
        gap(panel,12)
        panel.addView(text("De analyse gebruikt geselecteerde beelden, niet iedere videoframe. Laat de app tijdens de analyse geopend. Het tellen blijft voorlopig totdat je de resultaten controleert.",13f,quiet))
        busy=true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute{
            try {
                val observations=VideoScanner(this,store).scan(uri){s->runOnUiThread{status.text=s}}
                runOnUiThread{
                    store.records.clear();store.records.addAll(observations);store.save()
                    busy=false;window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);showResults()
                }
            }catch(e:Exception){runOnUiThread{busy=false;window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);error(e);showHome()}}
        }
    }
    private fun showResults() {
        mount("Herkenningen controleren","${store.records.size} voorlopige plantwaarnemingen. Bekijk foto's en bevestig of corrigeer iedere telling.")
        root.addView(button("← Startscherm",true){showHome()})
        gap(root,12)
        if(store.videoUri.isNotBlank()) {
            root.addView(button("▶ Video met plantkaders",true){showVideo()})
            gap(root,10)
        }
        root.addView(button("📄 Maak inventarisatierapport") {exportReport()})
        gap(root,14)
        if(store.records.isEmpty()) {
            val empty=card();empty.addView(text("Geen planten gedetecteerd. Open de video en selecteer handmatig een plant op een videobeeld.",15f,quiet));root.addView(empty)
        }
        store.records.sortedWith(compareByDescending<PlantObservation>{it.reviewNeeded()}.thenBy{it.firstMs}).forEach {p->
            val c=card()
            val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            val bmp=try{BitmapFactory.decodeFile(p.photo)}catch(_:Exception){null}
            if(bmp!=null) {
                val image=ImageView(this).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP
                    background=background(pastel,12);clipToOutline=true}
                top.addView(image,LinearLayout.LayoutParams(dp(86),dp(86)))
            }
            val right=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(13),0,0,0)}
            right.addView(text(p.dutch.ifBlank{p.scientific},16f,dark,true))
            if(p.dutch.isNotBlank())right.addView(text(p.scientific,12f,quiet))
            gap(right,5)
            val state=if(p.confirmed)"✓ Handmatig bevestigd" else if(p.reviewNeeded())"⚠ Controle nodig" else "◌ Voorlopig AI-voorstel"
            right.addView(text(state,12f,if(p.reviewNeeded())Color.rgb(155,99,20) else forest))
            right.addView(text("Score ${(p.confidence*100).toInt()}% · ${formatTime(p.firstMs)} · "+
                if(p.group)"Plantengroep" else "Aantal ${p.count} (voorlopig)",12f,quiet))
            top.addView(right,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            c.addView(top)
            gap(c,12)
            c.addView(button("Controleren / wijzigen",true){edit(p)})
            if(store.videoUri.isNotBlank()) {
                gap(c,6)
                c.addView(button("🔎 Extra videobeeld voor betere herkenning",true) {
                    refineTarget=p
                    showVideo()
                })
            }
            if(store.records.any{it.id!=p.id && it.scientific==p.scientific}) {
                gap(c,6)
                c.addView(button("Samenvoegen met dubbele detectie",true){mergePlant(p)})
            }
            root.addView(c);gap(root,12)
        }
    }
    private fun edit(p:PlantObservation) {
        val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        col.addView(text("AI stelt deze soorten voor:",15f,forest,true))
        gap(col,6)
        val scientific=EditText(this).apply{setText(p.scientific);setSingleLine(true);textSize=14f}
        val groups=RadioGroup(this).apply{orientation=RadioGroup.VERTICAL}
        p.alternatives.take(5).forEachIndexed {i,g->
            val b=RadioButton(this).apply {id=10000+i;text="${g.name}  (${(g.probability*100).toInt()}%)";textSize=13f}
            groups.addView(b)
        }
        groups.setOnCheckedChangeListener{_,checked->
            val index=checked-10000
            if(index in p.alternatives.indices) scientific.setText(p.alternatives[index].name)
        }
        col.addView(groups)
        gap(col,8)
        col.addView(text("Wetenschappelijke naam",12f,quiet))
        col.addView(scientific)
        col.addView(text("Nederlandse naam (zelf invullen of opzoeken)",12f,quiet))
        val dutch=EditText(this).apply{setText(p.dutch);setSingleLine(true);textSize=14f}
        col.addView(dutch)
        gap(col,6)
        col.addView(button("Online Nederlandse naam opzoeken",true){
            val q=scientific.text.toString().trim()
            if(q.isNotEmpty())lookupName(q,dutch)
        })
        gap(col,10)
        col.addView(text("Aantal exemplaren (zelf controleren)",12f,quiet))
        val count=EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(p.count.toString());setSingleLine(true)}
        col.addView(count)
        val group=CheckBox(this).apply{text="Plantengroep: aantal niet betrouwbaar te bepalen";isChecked=p.group}
        col.addView(group)
        val confirmed=CheckBox(this).apply{text="Ik heb deze herkenning zelf gecontroleerd";isChecked=p.confirmed}
        col.addView(confirmed)
        col.addView(text("Eigen opmerkingen",12f,quiet))
        val notes=EditText(this).apply{setText(p.notes);minLines=2;maxLines=4}
        col.addView(notes)
        gap(col,6)
        val selection=AlertDialog.Builder(this)
            .setTitle("Plant controleren")
            .setView(ScrollView(this).apply {addView(col)})
            .setPositiveButton("Bewaren",null)
            .setNegativeButton("Sluiten",null)
            .setNeutralButton("Verwijderen",null)
            .create()
        selection.setOnShowListener {
            selection.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                p.scientific=scientific.text.toString().trim().ifBlank{"Onbekend"}
                p.dutch=dutch.text.toString().trim()
                p.count=count.text.toString().toIntOrNull()?.coerceAtLeast(0)?:1
                p.group=group.isChecked;p.confirmed=confirmed.isChecked;p.notes=notes.text.toString()
                store.save();selection.dismiss();showResults()
            }
            selection.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setMessage("Deze detectie verwijderen?")
                    .setNegativeButton("Nee",null).setPositiveButton("Verwijderen"){_,_->
                        store.records.remove(p);store.save();selection.dismiss();showResults()
                    }.show()
            }
        }
        selection.show()
    }
    private fun mergePlant(p:PlantObservation) {
        val choices=store.records.filter{it.id!=p.id && it.scientific==p.scientific}
        if(choices.isEmpty()) {toast("Geen andere detectie van deze soort");return}
        val labels=choices.map {"${formatTime(it.firstMs)} · ${it.dutch.ifBlank{it.scientific}} · " +
            if(it.group) "groep" else "aantal ${it.count}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("Dubbele detectie samenvoegen")
            .setItems(labels){_,which->
                val other=choices[which]
                AlertDialog.Builder(this)
                    .setMessage("Deze twee waarnemingen samenvoegen? Doe dit alleen als je hebt gecontroleerd dat het dezelfde planten betreft.")
                    .setNegativeButton("Annuleren",null)
                    .setPositiveButton("Samenvoegen"){_,_->
                        p.count=if(p.group||other.group) p.count else maxOf(p.count,other.count)
                        p.group=p.group||other.group
                        p.firstMs=minOf(p.firstMs,other.firstMs)
                        p.lastMs=maxOf(p.lastMs,other.lastMs)
                        p.samples=(p.samples+other.samples).distinct().sorted().toMutableList()
                        p.confirmed=false // merging requires renewed human review
                        store.records.remove(other);store.save();showResults()
                    }.show()
            }.setNegativeButton("Sluiten",null).show()
    }
    private fun lookupName(scientific:String,target:EditText) {
        toast("Zoekt gratis soortenregister...")
        worker.execute {
            try {
                val url=URL("https://api.inaturalist.org/v1/taxa/autocomplete?locale=nl&q="+
                    URLEncoder.encode(scientific,"UTF-8"))
                val conn=(url.openConnection() as HttpURLConnection).apply{connectTimeout=7000;readTimeout=7000}
                val obj=try{conn.inputStream.bufferedReader().use{JSONObject(it.readText())}}finally{conn.disconnect()}
                val arr=obj.optJSONArray("results")
                val name=(0 until (arr?.length()?:0)).map{arr!!.getJSONObject(it)}
                    .firstOrNull{it.optString("name").equals(scientific,true)}
                    ?.optString("preferred_common_name","")?:""
                runOnUiThread{if(name.isNotBlank())target.setText(name) else toast("Geen Nederlandse naam gevonden")}
            }catch(_:Exception){runOnUiThread{toast("Online soortenregister niet bereikbaar")}}
        }
    }
    private fun showVideo() {
        if(store.videoUri.isBlank()){showHome();return}
        mount("Video terugkijken",if(refineTarget!=null)
            "Selecteer dezelfde plant op een ander videomoment. Twee handmatig gekozen beelden worden samen beoordeeld."
            else "Gekleurde kaders tonen voorlopige detecties. Tik op een kader om de plant te corrigeren.")
        root.addView(button("← Terug naar resultaten",true){refineTarget=null;showResults()})
        gap(root,14)
        val frame=FrameLayout(this).apply{background=background(Color.BLACK,14)}
        val vv=VideoView(this).apply {
            setVideoURI(Uri.parse(store.videoUri))
            val startAt=refineTarget?.firstMs?.toInt()
            if(startAt!=null)setOnPreparedListener{seekTo(startAt)}
        }
        frame.addView(vv,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(270)))
        val marks=VideoMarksView(this){edit(it)}
        marks.plants=store.records
        frame.addView(marks,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(270)))
        root.addView(frame)
        video=vv;overlay=marks
        gap(root,12)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val play=button("▶ Afspelen") {if(vv.isPlaying)vv.pause() else vv.start()}
        controls.addView(play,LinearLayout.LayoutParams(0,dp(52),1f))
        val crop=button("✚ Selecteer plant",true){selectPlant(vv.currentPosition.toLong())}
        controls.addView(crop,LinearLayout.LayoutParams(0,dp(52),1f))
        root.addView(controls)
        gap(root,10)
        root.addView(text("Gebruik 'Selecteer plant' als de app een plant heeft gemist of meerdere soorten in één kader plaatst.",12f,quiet))
        val tick=object:Runnable{override fun run(){
            if(video===vv){marks.videoTime=vv.currentPosition.toLong();marks.invalidate();handler.postDelayed(this,400)}
        }}
        ticker=tick;handler.post(tick)
    }
    private fun selectPlant(time:Long) {
        video?.pause()
        val uri=Uri.parse(store.videoUri)
        worker.execute {
            try {
                val mmr=MediaMetadataRetriever()
                val bitmap=try{mmr.setDataSource(this,uri)
                    mmr.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?:error("Kon dit videobeeld niet lezen")
                }finally{mmr.release()}
                runOnUiThread{showCropDialog(bitmap,time)}
            }catch(e:Exception){runOnUiThread{error(e)}}
        }
    }
    private fun showCropDialog(bitmap:Bitmap,time:Long) {
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),0)}
        val selector=CropSelectionView(this,bitmap)
        content.addView(selector,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(360)))
        content.addView(text("Sleep een rechthoek rondom één plant en tik op Herkennen.",13f,quiet))
        val d=AlertDialog.Builder(this).setTitle("Plant selecteren · ${formatTime(time)}")
            .setView(content).setNegativeButton("Annuleren",null)
            .setPositiveButton("Herkennen",null).create()
        d.setOnShowListener {d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!selector.validSelection()) {toast("Selecteer een groter gedeelte");return@setOnClickListener}
            val rect=RectF(selector.selection)
            d.dismiss()
            worker.execute {
                try {
                    val existing=refineTarget
                    val prior=if(existing!=null && !existing.group && existing.manual) existing.photo else null
                    val o=VideoScanner(this,store).classifyManual(bitmap,time,rect,prior)
                    runOnUiThread {
                        if(existing!=null) {
                            existing.alternatives=o.alternatives
                            existing.confidence=o.confidence
                            existing.scientific=o.scientific
                            existing.photo=o.photo
                            existing.confirmed=false
                            existing.manual=true
                            existing.rect=o.rect
                            existing.group=false
                            existing.samples.add(time)
                            existing.lastMs=maxOf(existing.lastMs,time)
                            existing.firstMs=minOf(existing.firstMs,time)
                            refineTarget=null
                            toast("Nieuwe herkenning: controleer de voorgestelde soort")
                        }else {
                            store.records.add(o)
                            toast("Plant toegevoegd — controleer de suggesties")
                        }
                        store.save();showResults()
                    }
                }catch(e:Exception){runOnUiThread{error(e)}}
                finally{bitmap.recycle()}
            }
        }}
        d.show()
    }
    private fun exportReport() {
        if(store.records.isEmpty()){toast("Nog geen herkenningen om te exporteren");return}
        exportPdf.launch("PlantScan_${store.projectName.replace(Regex("[^A-Za-z0-9_-]"),"_")}.pdf")
    }
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()
    private fun error(e:Exception){
        AlertDialog.Builder(this).setTitle("Er is iets misgegaan")
            .setMessage(e.localizedMessage?:e.javaClass.simpleName)
            .setPositiveButton("OK",null).show()
    }
}
