package nl.plantscan.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Candidate is a model suggestion, not a validated botanical determination. */
data class SpeciesGuess(val name: String, val probability: Float)

data class PlantObservation(
    val id: String = UUID.randomUUID().toString(),
    var scientific: String,
    var dutch: String = "",
    var confidence: Float,
    var alternatives: List<SpeciesGuess> = emptyList(),
    var firstMs: Long,
    var lastMs: Long,
    var samples: MutableList<Long> = mutableListOf(),
    var rect: RectF = RectF(0f, 0f, 1f, 1f),
    var photo: String = "",
    var count: Int = 1,
    var group: Boolean = false,
    var confirmed: Boolean = false,
    var notes: String = "",
    var manual: Boolean = false
) {
    fun reviewNeeded(): Boolean = !confirmed && (scientific=="Onbekend" || group || confidence < 0.85f ||
            alternatives.size > 1 && confidence - alternatives[1].probability < 0.25f)
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("scientific", scientific); put("dutch", dutch)
        put("confidence", confidence)
        put("alternatives", JSONArray().apply { alternatives.forEach {
            put(JSONObject().put("name", it.name).put("probability", it.probability))
        } })
        put("firstMs",firstMs);put("lastMs",lastMs)
        put("samples", JSONArray().apply {samples.forEach { put(it) }})
        put("rect", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom))
        put("photo",photo);put("count",count);put("group",group)
        put("confirmed",confirmed);put("notes",notes);put("manual",manual)
    }
    companion object {
        fun fromJson(o:JSONObject):PlantObservation {
            val a=o.optJSONArray("alternatives")?:JSONArray()
            val guesses=(0 until a.length()).map { a.getJSONObject(it) }.map {
                SpeciesGuess(it.optString("name"),it.optDouble("probability").toFloat())
            }
            val ts=o.optJSONArray("samples")?:JSONArray()
            val r=o.optJSONArray("rect")?:JSONArray().put(0).put(0).put(1).put(1)
            return PlantObservation(
                id=o.getString("id"),scientific=o.optString("scientific","Onbekend"),
                dutch=o.optString("dutch"),confidence=o.optDouble("confidence").toFloat(),
                alternatives=guesses,firstMs=o.optLong("firstMs"),lastMs=o.optLong("lastMs"),
                samples=(0 until ts.length()).map{ts.optLong(it)}.toMutableList(),
                rect=RectF(r.optDouble(0).toFloat(),r.optDouble(1).toFloat(),r.optDouble(2).toFloat(),r.optDouble(3).toFloat()),
                photo=o.optString("photo"),count=o.optInt("count",1),group=o.optBoolean("group"),
                confirmed=o.optBoolean("confirmed"),notes=o.optString("notes"),manual=o.optBoolean("manual")
            )
        }
    }
}

class PlantStore(private val context: Context) {
    private val file = File(context.filesDir,"last_project.json")
    val snapshots = File(context.filesDir,"snapshots").also {it.mkdirs()}
    var projectName: String = "Mijn tuin"
    var videoUri: String = ""
    val records: MutableList<PlantObservation> = mutableListOf()
    fun save() {
        val j=JSONObject().put("projectName",projectName).put("videoUri",videoUri)
            .put("records",JSONArray().apply {records.forEach{put(it.toJson())}})
        file.writeText(j.toString())
    }
    fun load() {
        if (!file.exists()) return
        try {
            val j=JSONObject(file.readText()); projectName=j.optString("projectName","Mijn tuin")
            videoUri=j.optString("videoUri")
            records.clear();val a=j.optJSONArray("records")?:JSONArray()
            for(i in 0 until a.length()) records.add(PlantObservation.fromJson(a.getJSONObject(i)))
        } catch (_:Exception) { /* corrupted file doesn't crash app */ }
    }
    fun writePhoto(bitmap: Bitmap):String {
        val path=File(snapshots, UUID.randomUUID().toString()+".jpg")
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,82,it) }
        return path.absolutePath
    }
    fun clearForNewVideo(uri:String) {
        videoUri=uri;records.clear();save()
    }
}

/** Iris 9: https://huggingface.co/brunopaiva15/iris (CC BY 4.0). */

/** Iris 10: fused Iris 9 + botanical encoder, both offline (brunopaiva15, CC BY 4.0).
 * If the optional encoder cannot be loaded, Iris 9 remains functional.
 * Scores are model suggestions, never validated botanical determinations.
 */
class IrisClassifier(context:Context):Closeable {
    private val base:Interpreter
    private val labels:List<String>
    private val names:JSONObject
    private val meta9:JSONObject
    private val data9=ByteBuffer.allocateDirect(320*320*3*4).order(ByteOrder.nativeOrder())
    private val data10=ByteBuffer.allocateDirect(320*320*3*4).order(ByteOrder.nativeOrder())
    private var encoder:Interpreter?=null
    private var references:FloatArray?=null
    private var rowSpecies:IntArray=IntArray(0)
    private var iris9ToSpecies:IntArray=IntArray(0)
    private var speciesIds:List<String> = emptyList()
    private var temperature=100.0
    private var fusionWeight=0.5
    private var fusionFloor=1.0e-6
    private val outdoorIds: Set<String>

    init {
        val modelBytes=context.assets.open("plants.tflite").use{it.readBytes()}
        val model=ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        model.put(modelBytes).rewind()
        base=Interpreter(model,Interpreter.Options().setNumThreads(2))
        labels=context.assets.open("labels.txt").bufferedReader().use{it.readLines().filter(String::isNotBlank)}
        meta9=JSONObject(context.assets.open("model.json").bufferedReader().use{it.readText()})
        names=meta9.getJSONObject("species")
        require(labels.size==1569){"Onverwachte grootte van het Iris 9-model"}
        val mask=meta9.optJSONObject("masks")?.optJSONArray("outdoor")
        outdoorIds=if(mask==null) emptySet() else (0 until mask.length()).map{mask.getString(it)}.toSet()
        try {
            val meta=JSONObject(context.assets.open("iris10.json").bufferedReader().use{it.readText()})
            val ids=meta.getJSONArray("especes")
            val rows=meta.getJSONArray("lignes")
            val mapping=meta.getJSONArray("iris9")
            val dim=meta.getInt("dim")
            require(dim==1024 && mapping.length()==labels.size)
            val refBytes=context.assets.open("iris10-references.bin").use{it.readBytes()}
            require(refBytes.size == rows.length()*dim*2){"Ongeldige referentievectoren"}
            val shorts=ByteBuffer.wrap(refBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val allRefs=FloatArray(shorts.remaining()) {android.util.Half.toFloat(shorts.get())}
            val encoderBytes=context.assets.open("iris10.tflite").use{it.readBytes()}
            val encoderBuffer=ByteBuffer.allocateDirect(encoderBytes.size).order(ByteOrder.nativeOrder())
            encoderBuffer.put(encoderBytes).rewind()
            val enc=Interpreter(encoderBuffer,Interpreter.Options().setNumThreads(3))
            require(enc.getOutputTensor(0).shape().last()==dim){"Onjuiste modeluitvoer"}
            speciesIds=(0 until ids.length()).map{ids.getString(it)}
            rowSpecies=IntArray(rows.length()){rows.getInt(it)}
            iris9ToSpecies=IntArray(mapping.length()){mapping.getInt(it)}
            require(rowSpecies.all{it in speciesIds.indices})
            require(iris9ToSpecies.all{it in speciesIds.indices})
            references=allRefs
            temperature=meta.optDouble("temperature",100.0)
            fusionWeight=meta.optJSONObject("fusion")?.optDouble("poids_iris9",0.5)?:0.5
            fusionFloor=meta.optJSONObject("fusion")?.optDouble("plancher",1.0e-6)?:1.0e-6
            encoder=enc
        }catch(e:Exception){
            // Never fail a whole inventory only because the additional model
            // is missing or incompatible; the original classifier remains valid.
            encoder?.close();encoder=null;references=null
            speciesIds=emptyList();rowSpecies=IntArray(0);iris9ToSpecies=IntArray(0)
        }
    }

    private fun square(source:Bitmap):Bitmap {
        val size=min(source.width,source.height)
        return Bitmap.createBitmap(source,(source.width-size)/2,(source.height-size)/2,size,size)
    }
    private fun feed(bitmap:Bitmap,buffer:ByteBuffer,normalized:Boolean) {
        val pixels=IntArray(320*320)
        bitmap.getPixels(pixels,0,320,0,0,320,320)
        buffer.rewind()
        val factor=if(normalized)1f/255f else 1f
        for(pixel in pixels) {
            buffer.putFloat(Color.red(pixel)*factor)
            buffer.putFloat(Color.green(pixel)*factor)
            buffer.putFloat(Color.blue(pixel)*factor)
        }
        buffer.rewind()
    }
    private fun prepare9(image:Bitmap):Bitmap {
        val sq=square(image)
        val half=if(sq.width>448) Bitmap.createScaledBitmap(sq,448,448,true) else sq
        val scaled=Bitmap.createScaledBitmap(half,366,366,true)
        val patch=Bitmap.createBitmap(scaled,23,23,320,320)
        if(sq!==image && sq!==half) sq.recycle()
        if(half!==image && half!==scaled)half.recycle()
        if(scaled!==image && scaled!==patch)scaled.recycle()
        return patch
    }
    private fun prepare10(image:Bitmap):Bitmap {
        val longest=max(image.width,image.height)
        val resized=if(longest>384) {
            val scale=384f/longest
            Bitmap.createScaledBitmap(image,max(1,(image.width*scale).toInt()),
                max(1,(image.height*scale).toInt()),true)
        } else image
        val sq=square(resized)
        val scaled=Bitmap.createScaledBitmap(sq,320,320,true)
        val patch=if(scaled===image)scaled.copy(Bitmap.Config.ARGB_8888,false) else scaled
        if(sq!==image && sq!==resized && sq!==patch)sq.recycle()
        if(resized!==image && resized!==patch)resized.recycle()
        return patch
    }
    private fun fusedScores(baseScores:FloatArray, source:Bitmap):FloatArray? {
        val enc=encoder?:return null
        val ref=references?:return null
        if(speciesIds.isEmpty())return null
        val bitmap=prepare10(source)
        val output=Array(1){FloatArray(1024)}
        try {
            feed(bitmap,data10,true)
            enc.run(data10,output)
        }finally{bitmap.recycle()}
        val vector=output[0]
        var normSq=0.0
        for(v in vector)normSq+=v*v
        if(normSq<1.0e-12)return null
        val norm=kotlin.math.sqrt(normSq).toFloat()
        for(i in vector.indices)vector[i]/=norm
        val best=FloatArray(speciesIds.size){Float.NEGATIVE_INFINITY}
        for(row in rowSpecies.indices) {
            var dot=0f
            val offset=row*1024
            for(k in 0 until 1024)dot+=ref[offset+k]*vector[k]
            val species=rowSpecies[row]
            if(dot>best[species])best[species]=dot
        }
        val highest=best.maxOrNull()?:return null
        val encoderProb=FloatArray(best.size)
        var total=0.0
        for(i in best.indices) {
            val s=kotlin.math.exp(temperature*(best[i]-highest))
            encoderProb[i]=s.toFloat()
            total+=s
        }
        if(total<=0.0)return null
        for(i in encoderProb.indices)encoderProb[i]=(encoderProb[i].toDouble()/total).toFloat()
        val baseGrouped=FloatArray(speciesIds.size)
        for(i in baseScores.indices)baseGrouped[iris9ToSpecies[i]]+=baseScores[i]
        val logs=DoubleArray(speciesIds.size)
        for(i in logs.indices) {
            logs[i]=fusionWeight*kotlin.math.ln(max(baseGrouped[i].toDouble(),fusionFloor))+
                (1.0-fusionWeight)*kotlin.math.ln(max(encoderProb[i].toDouble(),fusionFloor))
        }
        val maxLog=logs.maxOrNull()?:return null
        val scores=FloatArray(logs.size)
        var divisor=0.0
        for(i in scores.indices) {
            // A soft outdoor-context prior, not a hard ban on rare cultivars.
            val weight=if(outdoorIds.isNotEmpty() && speciesIds[i] !in outdoorIds)0.25 else 1.0
            val n=kotlin.math.exp(logs[i]-maxLog)*weight
            scores[i]=n.toFloat()
            divisor+=n
        }
        if(divisor<=0.0)return null
        for(i in scores.indices)scores[i]=(scores[i].toDouble()/divisor).toFloat()
        return scores
    }
    @Synchronized fun identify(source:Bitmap):List<SpeciesGuess> {
        val img9=prepare9(source)
        val baseResult=Array(1){FloatArray(labels.size)}
        try {
            feed(img9,data9,false) // Iris 9 needs 0–255, not 0–1.
            base.run(data9,baseResult)
        }finally{img9.recycle()}
        val scores=try{fusedScores(baseResult[0],source)}catch(_:Exception){null}
        return if(scores!=null) {
            scores.indices.sortedByDescending{scores[it]}.take(5).map{
                SpeciesGuess(names.optString(speciesIds[it],speciesIds[it]),scores[it])
            }
        } else {
            val p=baseResult[0]
            p.indices.sortedByDescending{p[it]}.take(5).map{
                SpeciesGuess(names.optString(labels[it],labels[it]),p[it])
            }
        }
    }
    override fun close(){encoder?.close();base.close()}
}

object PlantRegions {
    /** Cheap green-vegetation proposals. These are NOT instance-segmentation. */
    fun candidates(bitmap:Bitmap):List<RectF> {
        val w=bitmap.width;val h=bitmap.height
        val gx=20; val gy=20
        val cols=(w+gx-1)/gx; val rows=(h+gy-1)/gy
        val green=BooleanArray(cols*rows)
        val px=IntArray(w*h)
        bitmap.getPixels(px,0,w,0,0,w,h)
        for(y in 0 until rows) for(x in 0 until cols) {
            var seen=0;var total=0
            for(py in y*gy until min(h,(y+1)*gy) step 3)
                for(qx in x*gx until min(w,(x+1)*gx) step 3) {
                    val c=px[py*w+qx]; val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
                    if(g>55 && g>r*1.08f && g>b*1.07f && g-max(r,b)>9) seen++
                    total++
                }
            green[y*cols+x]= total>0 && seen>=total*0.17f
        }
        val visited=BooleanArray(green.size)
        val regions=mutableListOf<Pair<Int,RectF>>()
        for(start in green.indices) {
            if(!green[start]||visited[start])continue
            val queue=ArrayDeque<Int>();queue.add(start);visited[start]=true
            var ct=0;var left=cols;var top=rows;var right=0;var bottom=0
            while(queue.isNotEmpty()) {
                val i=queue.removeFirst();val x=i%cols;val y=i/cols
                ct++;left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y)
                for((xx,yy) in arrayOf(x-1 to y,x+1 to y,x to y-1,x to y+1)) {
                    if(xx !in 0 until cols||yy !in 0 until rows)continue
                    val j=yy*cols+xx
                    if(green[j]&&!visited[j]){visited[j]=true;queue.add(j)}
                }
            }
            if(ct>=5) {
                val pad=0.05f
                regions.add(ct to RectF(
                    max(0f,left*gx.toFloat()/w-pad),max(0f,top*gy.toFloat()/h-pad),
                    min(1f,(right+1)*gx.toFloat()/w+pad),min(1f,(bottom+1)*gy.toFloat()/h+pad)
                ))
            }
        }
        // A connected hedge or lawn isn't one plant; mark a region provisional.
        val sorted=regions.sortedByDescending {it.first}.take(3).map {it.second}
        return sorted // no full-frame hallucination when vegetation was not found
    }
    fun crop(bitmap:Bitmap,r:RectF):Bitmap {
        val x=(r.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
        val y=(r.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
        val width=((r.right-r.left)*bitmap.width).toInt().coerceAtLeast(1).coerceAtMost(bitmap.width-x)
        val height=((r.bottom-r.top)*bitmap.height).toInt().coerceAtLeast(1).coerceAtMost(bitmap.height-y)
        return Bitmap.createBitmap(bitmap,x,y,width,height)
    }
    fun overlap(a:RectF,b:RectF):Float {
        val l=max(a.left,b.left);val t=max(a.top,b.top)
        val r=min(a.right,b.right);val bot=min(a.bottom,b.bottom)
        if(r<=l||bot<=t)return 0f
        val intersection=(r-l)*(bot-t)
        val union=a.width()*a.height()+b.width()*b.height()-intersection
        return if(union>0)intersection/union else 0f
    }
}

class VideoScanner(private val context:Context,private val store:PlantStore) {
    fun scan(uri:Uri,onProgress:(String)->Unit):List<PlantObservation> {
        val r=MediaMetadataRetriever()
        val classifier=IrisClassifier(context)
        try {
            r.setDataSource(context,uri)
            val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
            require(duration>0){"Video heeft geen leesbare duur"}
            val step=max(4000L,duration/70L)
            val items=mutableListOf<PlantObservation>()
            var t=0L;var frameNo=0
            while(t<duration && frameNo<70) {
                onProgress("Videobeeld ${frameNo+1} · ${t/1000}s / ${duration/1000}s")
                val raw=r.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST)
                if(raw!=null) {
                    val scale=min(1f,1000f/max(raw.width,raw.height))
                    val frame=Bitmap.createScaledBitmap(raw,(raw.width*scale).toInt().coerceAtLeast(1),
                        (raw.height*scale).toInt().coerceAtLeast(1),true)
                    if(frame!==raw)raw.recycle()
                    for(region in PlantRegions.candidates(frame)) {
                        // A cheap contiguous-frame overlap is a *provisional* track,
                        // not evidence of separate individuals or a reliable count.
                        val recent=items.lastOrNull { it.lastMs>=t-12000L &&
                            PlantRegions.overlap(it.rect,region)>0.58f }
                        if(recent!=null) {
                            recent.lastMs=t;recent.samples.add(t);continue
                        }
                        val patch=PlantRegions.crop(frame,region)
                        val guesses=classifier.identify(patch)
                        val name=if((guesses.firstOrNull()?.probability?:0f)<0.85f) "Onbekend" else guesses[0].name
                        val photo=store.writePhoto(patch)
                        patch.recycle()
                        items.add(PlantObservation(scientific=name,
                            confidence=guesses.firstOrNull()?.probability?:0f,alternatives=guesses,
                            firstMs=t,lastMs=t,samples= mutableListOf(t),rect=region,photo=photo,
                            group=(region.width()>0.60f && region.height()>0.50f)))
                    }
                    frame.recycle()
                }
                frameNo++;t+=step
            }
            return items.sortedBy{it.firstMs}
        } finally {r.release();classifier.close()}
    }
    fun classifyManual(bitmap:Bitmap,t:Long,rect:RectF):PlantObservation {
        IrisClassifier(context).use {classifier->
            val patch=PlantRegions.crop(bitmap,rect)
            val g=classifier.identify(patch)
            val photo=store.writePhoto(patch);patch.recycle()
            return PlantObservation(scientific=if(g.isEmpty() || g[0].probability<0.85f)"Onbekend" else g[0].name,
                confidence=g[0].probability,alternatives=g,firstMs=t,lastMs=t,
                samples=mutableListOf(t),rect=rect,photo=photo,manual=true)
        }
    }
}

fun formatTime(ms:Long):String {
    val s=ms/1000L
    return "%02d:%02d".format(s/60,s%60)
}
