package nl.plantscan.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.Executors

class PhotoActivity : AppCompatActivity() {
    private val forest = Color.rgb(27, 82, 61)
    private val dark = Color.rgb(34, 56, 44)
    private val muted = Color.rgb(98, 113, 103)
    private val pastel = Color.rgb(227, 242, 216)
    private val bg = Color.rgb(247, 249, 245)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PlantStore
    private lateinit var root: LinearLayout
    private var capture: Uri? = null
    private var secondOf: PlantObservation? = null
    private var busy = false

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = capture
        if (success && uri != null) recognize(uri)
        else Toast.makeText(this, "Geen foto gemaakt", Toast.LENGTH_SHORT).show()
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_:Exception) {}
            recognize(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=PlantStore(this).apply{load()}
        window.statusBarColor=forest
        window.navigationBarColor=bg
        home()
        if (!getSharedPreferences("plantscan",MODE_PRIVATE).getBoolean("photo_guide_seen",false))
            handler.post { tutorial() }
    }
    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) store.load()
    }
    override fun onDestroy() {worker.shutdownNow();super.onDestroy()}

    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun background(color:Int,r:Int=19)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(r).toFloat()
    }
    private fun label(value:String,size:Float=15f,color:Int=dark,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color)
        typeface=android.graphics.Typeface.create("sans-serif",
            if(bold)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun gap(box:LinearLayout,n:Int=12) {
        box.addView(View(this),LinearLayout.LayoutParams(1,dp(n)))
    }
    private fun panel():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;background=background(Color.WHITE,21)
        elevation=dp(2).toFloat()
        setPadding(dp(17),dp(18),dp(17),dp(18))
    }
    private fun button(title:String,secondary:Boolean=false,onTap:()->Unit)=Button(this).apply {
        text=title;isAllCaps=false;textSize=15f
        setTextColor(if(secondary)forest else Color.WHITE)
        background=background(if(secondary)pastel else forest,13)
        setPadding(dp(12),dp(9),dp(12),dp(9))
        minHeight=dp(50)
        setOnClickListener {onTap()}
    }
    private fun screen(title:String,subtitle:String) {
        val scroll=ScrollView(this).apply{setBackgroundColor(bg);isFillViewport=true}
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(19),dp(24),dp(19),dp(26))
        }
        scroll.addView(root);setContentView(scroll)
        root.addView(label("✿  PLANTSCAN AI",13f,forest,true))
        gap(root,10);root.addView(label(title,29f,dark,true))
        gap(root,4);root.addView(label(subtitle,14f,muted))
        gap(root,18)
    }
    private fun home() {
        screen("Ontdek je planten","Een foto, slimme herkenning en online soortinformatie.")
        val hero=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(212,235,194),Color.rgb(243,248,230))).apply {
                cornerRadius=dp(24).toFloat()
            }
            setPadding(dp(22),dp(24),dp(22),dp(24))
        }
        hero.addView(label("🌿  HERKEN EN ONTDEK",13f,forest,true))
        gap(hero,9)
        hero.addView(label("Welke plant is dit?",25f,forest,true))
        gap(hero,8)
        hero.addView(label("Maak een scherpe foto van het blad of de bloem. Wij zoeken daarna de plantensoort op.",14f,dark))
        gap(hero,17)
        hero.addView(button("📷  Maak een plantenfoto"){takePhoto()})
        gap(hero,8)
        hero.addView(button("▧  Foto uit galerij",true){pickPhoto()})
        root.addView(hero)
        gap(root,20)
        root.addView(label("Mijn planten",20f,dark,true))
        gap(root,11)
        val mine=panel()
        val names=store.records.map{it.scientific}.filter{it!="Onbekend"}.distinct().size
        mine.addView(label(store.records.size.toString()+" waarnemingen · "+names+" soorten",17f,forest,true))
        gap(mine,8)
        mine.addView(label(store.projectName,13f,muted))
        gap(mine,13)
        mine.addView(button("Bekijk mijn planten",true){library()})
        root.addView(mine)
        gap(root,17)
        root.addView(label("Meer mogelijkheden",20f,dark,true))
        gap(root,11)
        val features=panel()
        features.addView(button("🎥  Video-inventarisatie",true){
            startActivity(Intent(this,MainActivity::class.java))
        })
        gap(features,8)
        features.addView(button("ⓘ  Zo maak je een goede foto",true){tutorial()})
        gap(features,8)
        features.addView(button("📋  PDF en projectbeheer",true){
            startActivity(Intent(this,MainActivity::class.java))
        })
        root.addView(features)
        gap(root,17)
        root.addView(label("Herkenning met Iris 9 (CC BY 4.0). Voor elke foto wordt vóór het resultaat online de soortnaam in iNaturalist opgezocht. Dit is geen tweede beeldherkennings-AI.",12f,muted))
    }

    private fun tutorial() {
        val c=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(10),dp(22),dp(12))
        }
        c.addView(label("🌱",39f,forest))
        gap(c,9)
        c.addView(label("Zo maak je een goede plantenfoto",22f,forest,true))
        gap(c,16)
        c.addView(label("1  Eén plant tegelijk",16f,dark,true))
        c.addView(label("Laat één plant goed zien. Fotografeer bij voorkeur het blad én de bloem.",14f,muted))
        gap(c,13)
        c.addView(label("2  Scherp bij daglicht",16f,dark,true))
        c.addView(label("Houd je telefoon stil en voorkom drukke achtergronden.",14f,muted))
        gap(c,13)
        c.addView(label("3  Een tweede foto helpt",16f,dark,true))
        c.addView(label("Maak bij twijfel een tweede foto van dezelfde plant, vanuit een andere hoek.",14f,muted))
        gap(c,12)
        c.addView(label("Je vindt deze uitleg later onder Meer mogelijkheden.",12f,muted))
        AlertDialog.Builder(this).setView(ScrollView(this).apply{addView(c)})
            .setPositiveButton("Foto maken"){_,_->
                markTutorialSeen();takePhoto()
            }.setNegativeButton("Sluiten"){_,_->markTutorialSeen()}.show()
    }
    private fun markTutorialSeen() {
        getSharedPreferences("plantscan",MODE_PRIVATE).edit().putBoolean("photo_guide_seen",true).apply()
    }

    private fun takePhoto(second:PlantObservation?=null) {
        secondOf=second
        try {
            val folder=getExternalFilesDir(Environment.DIRECTORY_PICTURES)?:filesDir
            val file=File(folder,"plantscan_"+System.currentTimeMillis()+".jpg")
            capture=FileProvider.getUriForFile(this,packageName+".files",file)
            camera.launch(capture!!)
        }catch(e:Exception){problem(e)}
    }
    private fun pickPhoto(second:PlantObservation?=null) {
        secondOf=second
        gallery.launch(arrayOf("image/*"))
    }
    private fun chooseSecond(p:PlantObservation) {
        AlertDialog.Builder(this).setTitle("Tweede foto van dezelfde plant")
            .setItems(arrayOf("Foto maken","Foto kiezen")){_,which->
                if(which==0)takePhoto(p) else pickPhoto(p)
            }.show()
    }
    private fun loadPhoto(uri:Uri):Bitmap {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,bounds)}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2000)sample*=2
        val options=BitmapFactory.Options().apply{inSampleSize=sample}
        val photo=contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,options)}
            ?:throw IllegalArgumentException("Kan deze foto niet lezen")
        val orientation=try {
            contentResolver.openInputStream(uri).use{input->
                if(input==null)ExifInterface.ORIENTATION_NORMAL
                else ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)
            }
        }catch(_:Exception){ExifInterface.ORIENTATION_NORMAL}
        val angle=when(orientation) {
            ExifInterface.ORIENTATION_ROTATE_90->90f
            ExifInterface.ORIENTATION_ROTATE_180->180f
            ExifInterface.ORIENTATION_ROTATE_270->270f
            else->0f
        }
        if(angle==0f)return photo
        val output=Bitmap.createBitmap(photo,0,0,photo.width,photo.height,
            Matrix().apply{postRotate(angle)},true)
        if(output!==photo)photo.recycle()
        return output
    }

    private fun recognize(uri:Uri) {
        if(busy)return
        busy=true
        screen("Plant herkennen","AI-analyse en verplichte online soortencontrole.")
        val box=panel();root.addView(box)
        box.addView(ProgressBar(this),LinearLayout.LayoutParams(dp(50),dp(50)))
        gap(box,16)
        val status=label("Foto voorbereiden...",16f,forest,true)
        box.addView(status)
        gap(box,7)
        box.addView(label("De foto blijft op je telefoon. We vragen alleen de voorgestelde soortnamen op bij iNaturalist.",13f,muted))
        worker.execute{
            var photo:Bitmap?=null
            var prior:Bitmap?=null
            try {
                photo=loadPhoto(uri)
                val target=secondOf?.let { chosen ->
                    store.records.firstOrNull { it.id == chosen.id } ?: chosen
                }
                if(target!=null)prior=BitmapFactory.decodeFile(target.photo)
                runOnUiThread{status.text="Iris 9 analyseert je foto..."}
                val guesses=IrisClassifier(this).use {it.identifyMany(listOfNotNull(photo,prior))}
                runOnUiThread{status.text="Online soortnamen controleren..."}
                val check=OnlineTaxonomy.check(guesses)
                val top=guesses.firstOrNull()
                val recognized=if((top?.probability?:0f)>=0.85f)top!!.name else "Onbekend"
                val photoFile=store.writePhoto(photo)
                runOnUiThread{
                    val obs=if(target!=null) target.apply{
                        scientific=recognized;confidence=top?.probability?:0f
                        alternatives=guesses;this.photo=photoFile
                        dutch=if(check.scientific.equals(top?.name,true))check.dutch else ""
                        confirmed=false;manual=true;sourceType="photo"
                    } else PlantObservation(
                        scientific=recognized,
                        dutch=if(check.scientific.equals(top?.name,true))check.dutch else "",
                        confidence=top?.probability?:0f,
                        alternatives=guesses,firstMs=0,lastMs=0,
                        samples=mutableListOf(),rect=RectF(0f,0f,1f,1f),
                        photo=photoFile,manual=true,sourceType="photo"
                    ).also{store.records.add(it)}
                    store.save()
                    busy=false;secondOf=null
                    showResult(obs,check)
                }
            }catch(e:Exception){
                runOnUiThread{
                    busy=false
                    AlertDialog.Builder(this).setTitle("Herkenning niet voltooid")
                        .setMessage((e.localizedMessage?:"Onbekende fout")+
                            "\n\nZonder voltooide online controle tonen we geen definitief fotoresultaat.")
                        .setPositiveButton("Opnieuw proberen"){_,_->recognize(uri)}
                        .setNegativeButton("Terug"){_,_->home()}
                        .show()
                }
            }finally{photo?.recycle();prior?.recycle()}
        }
    }

    private fun showResult(p:PlantObservation,check:OnlineTaxonCheck?) {
        screen("Herkenningsresultaat",
            if(check!=null)"Iris 9 + online soortnamenregister" else "Eerder opgeslagen waarneming")
        val photo=BitmapFactory.decodeFile(p.photo)
        if(photo!=null) {
            val view=ImageView(this).apply{
                setImageBitmap(photo)
                scaleType=ImageView.ScaleType.CENTER_CROP
                background=background(pastel,20)
                clipToOutline=true
            }
            root.addView(view,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(230)))
            gap(root,16)
        }
        val result=panel()
        result.addView(label(p.dutch.ifBlank{p.scientific},24f,forest,true))
        if(p.dutch.isNotBlank()){gap(result,3);result.addView(label(p.scientific,13f,muted))}
        gap(result,8)
        result.addView(label("Modelscore "+(p.confidence*100).toInt()+"% · "+
            if(p.confirmed)"Door jou bevestigd" else "Nog zelf controleren",13f,muted))
        gap(result,12)
        if(check!=null) {
            result.addView(label(if(check.matched)"✓ Online soortnaam gevonden"
                else "ⓘ Online geen exacte soortnaam gevonden",14f,forest,true))
            gap(result,6)
            result.addView(label(check.note,12f,muted))
            if(check.link.isNotBlank()){
                gap(result,10)
                result.addView(button("Bekijk iNaturalist",true){
                    startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(check.link)))
                })
            }
        }else result.addView(label("Opgeslagen voorstel. Je kunt de soort nog controleren of wijzigen.",13f,muted))
        root.addView(result)
        gap(root,13)
        val candidates=panel()
        candidates.addView(label("Andere mogelijke soorten",18f,dark,true))
        gap(candidates,7)
        p.alternatives.take(5).forEachIndexed{idx,g->
            candidates.addView(label((idx+1).toString()+". "+g.name+" · "+
                (g.probability*100).toInt()+"%",13f,dark))
            gap(candidates,6)
        }
        root.addView(candidates)
        gap(root,13)
        root.addView(button("📷  Tweede foto van deze plant",true){chooseSecond(p)})
        gap(root,8)
        root.addView(button("✎  Soort corrigeren",true){correct(p)})
        gap(root,8)
        root.addView(button("✓  Handmatig bevestigen"){
            if(p.scientific=="Onbekend")correct(p)
            else{p.confirmed=true;store.save();library()}
        })
        gap(root,8)
        root.addView(button("← Terug naar start",true){home()})
    }

    private fun correct(p:PlantObservation) {
        val c=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(10))
        }
        c.addView(label("Wetenschappelijke naam",13f,muted))
        val species=EditText(this).apply{setText(p.scientific);setSingleLine(true)}
        c.addView(species)
        c.addView(label("Nederlandse naam",13f,muted))
        val dutch=EditText(this).apply{setText(p.dutch);setSingleLine(true)}
        c.addView(dutch)
        c.addView(label("Aantal",13f,muted))
        val count=EditText(this).apply{
            inputType=InputType.TYPE_CLASS_NUMBER
            setText(p.count.toString())
        }
        c.addView(count)
        val confirmed=CheckBox(this).apply{
            text="Ik heb deze soort gecontroleerd"
            isChecked=p.confirmed
        }
        c.addView(confirmed)
        AlertDialog.Builder(this).setTitle("Plant aanpassen").setView(c)
            .setPositiveButton("Bewaren"){_,_->
                p.scientific=species.text.toString().trim().ifBlank{"Onbekend"}
                p.dutch=dutch.text.toString().trim()
                p.count=count.text.toString().toIntOrNull()?.coerceAtLeast(1)?:1
                p.confirmed=confirmed.isChecked
                store.save();library()
            }.setNegativeButton("Annuleren",null).show()
    }

    private fun library() {
        store.load()
        screen("Mijn planten","Herkenningen uit foto's en video's.")
        root.addView(button("📷  Nieuwe plant fotograferen"){takePhoto()})
        gap(root,13)
        if(store.records.isEmpty()){
            val empty=panel()
            empty.addView(label("Nog geen planten opgeslagen.",16f,muted))
            root.addView(empty)
        }
        store.records.asReversed().forEach { p->
            val card=panel()
            val preview=BitmapFactory.decodeFile(p.photo)
            if(preview!=null) {
                val image=ImageView(this).apply{
                    setImageBitmap(preview);scaleType=ImageView.ScaleType.CENTER_CROP
                    background=background(pastel,12);clipToOutline=true
                }
                card.addView(image,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(140)))
                gap(card,9)
            }
            card.addView(label(p.dutch.ifBlank{p.scientific},17f,forest,true))
            gap(card,5)
            card.addView(label(
                (if(p.sourceType=="photo")"Foto" else "Video")+" · "+
                    (if(p.confirmed)"Bevestigd" else "Controle nodig"),12f,muted))
            gap(card,11)
            card.addView(button("Bekijk waarneming",true){showResult(p,null)})
            root.addView(card);gap(root,13)
        }
        root.addView(button("← Terug naar start",true){home()})
    }
    private fun problem(e:Exception) {
        AlertDialog.Builder(this).setTitle("Er ging iets mis")
            .setMessage(e.localizedMessage?:e.javaClass.simpleName)
            .setPositiveButton("OK",null).show()
    }
}
