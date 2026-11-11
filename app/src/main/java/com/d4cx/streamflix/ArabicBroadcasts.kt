package com.d4cx.streamflix

import android.text.Html
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class ArabicBroadcast(val page:String,val label:String,val thumbnail:String?,val season:Int,val number:Int,val alternatePages:List<String> = emptyList())

/** Provider broadcast order is deliberately separate from canonical story-episode IDs. */
object ArabicBroadcasts {
 private const val SITE="https://watch.stardima.com"
 private val directories=mapOf(
  "tt1178180" to "/watch/seasons/the-marvelous-misadventures-of-flapjack-cartoon-ar/",
  "tt1305826" to "/watch/tvshows/adventure_time/",
  "tt1942683" to "/watch/seasons/the-amazing-world-of-gumball-cartoon-network-arabi/",
  "tt0206512" to "/watch/tvshows/spongebob-squarepants-arabic/"
 )
 private data class Cached(val time:Long,val rows:List<ArabicBroadcast>)
 private val cache=ConcurrentHashMap<String,Cached>()
 private val searches=mapOf("tt3718778" to "خلف حائط الحديقة","tt1710308" to "العرض العادي","tt0220880" to "كوردج","tt1140100" to "تشاودر","tt0760437" to "بن تن","tt3061050" to "كلارنس","tt3042608" to "العم جدو","tt4839610" to "الدببة الثلاثة","tt3061046" to "ستيفن يونيفرس","tt0115157" to "مختبر دكستر","tt0175058" to "فتيات القوة","tt0278238" to "ساموراي جاك")
 fun supports(id:String)=directories.containsKey(id)||searches.containsKey(id)
 // Verified show indexes, not guessed episode URLs. A show may be split across several indexes.
 private val additionalDirectories=mapOf(
  "tt0175058" to listOf("/watch/tvshows/the-powerpuff-girls-cartoon-network-arabic/"),
  "tt3042608" to listOf("/watch/tvshows/uncle-grandpa-cartoon-network-arabic/", "/watch/tvshows/العم-جدو/", "/watch/seasons/كرتون-العم-جدو-الجزء-الخامس-مدبلج/"),
  "tt4839610" to listOf("/watch/tvshows/كرتون-الدببة-الثلاثة-we-bare-bears-مدبلج/", "/watch/tvshows/we-bare-bears-cartoon-network-arabic/", "/watch/tvshows/كرتون-الدببة-الثلاثة-مدبلج/")
 )
 private fun normalize(s:String)=text(s).lowercase().replace(Regex("[\\s\\u00a0]+")," ").trim()
 internal fun discoverLinks(html:String,term:String):List<String> {
  val wanted=normalize(term)
  val links=LinkedHashSet<String>()
  // Search results use both article cards and result-item divs. Match each link's own title.
  for(match in Regex("""<a\b[^>]*href=['"](https://watch[.]stardima[.]com/watch/(?:tvshows|seasons)/[^'"]+)['"][^>]*>(.*?)</a>""",RegexOption.DOT_MATCHES_ALL).findAll(html)) {
   val title=normalize(match.groupValues[2])
   if(title.contains(wanted)&&!title.contains("مترجم"))links.add(match.groupValues[1])
  }
  return links.toList()
 }
 private fun discover(id:String,title:String?):List<String> {
  val term=searches[id]?:title?.substringBefore(" (")?.trim()?.takeIf{it.length>=3}?:return emptyList()
  val found=LinkedHashSet<String>()
  for(page in 1..3){
   val path=if(page==1)"/watch/" else "/watch/page/$page/"
   val html=try{request(SITE+path+"?s="+URLEncoder.encode(term,"UTF-8"))}catch(e:Exception){if(found.isEmpty())throw e else break}
   val links=discoverLinks(html,term);found.addAll(links)
   if(!Regex("""(?:page-numbers|next).*?/page/${page+1}/""").containsMatchIn(html))break
  }
  return found.toList()
 }
 // Follow only the current show's season index, never footer recommendations.
 internal fun seasonLinks(html:String):List<String> {
  val opening=Regex("""<(div|section)[^>]*id=['"]seasons['"][^>]*>""").find(html)?:return emptyList()
  val start=opening.range.last+1;var depth=1;var end=html.length
  for(tag in Regex("""</?${opening.groupValues[1]}\b[^>]*>""").findAll(html,start)) {
   depth+=if(tag.value.startsWith("</"))-1 else 1
   if(depth==0){end=tag.range.first;break}
  }
  val section=html.substring(start,end)
  return Regex("""href=['"](https://watch[.]stardima[.]com/watch/seasons/[^'"]+)['"]""").findAll(section).map{it.groupValues[1]}.distinct().take(20).toList()
 }
 internal fun mergeRows(rows:List<ArabicBroadcast>):List<ArabicBroadcast> {
  return rows.distinctBy{it.page}.groupBy{if(it.number>0)"${it.season}:${it.number}" else it.page}.values.map{group->
   group.first().copy(alternatePages=group.drop(1).map{it.page})
  }.sortedWith(compareBy({it.season},{it.number},{it.label}))
 }
 private fun text(s:String)=s.replace(Regex("<[^>]+>")," ").replace("&amp;","&").replace("&nbsp;"," ").replace("&#160;"," ").replace("&#8211;","–").replace(Regex("[ ]+")," ").trim()
 internal fun request(url:String,body:String?=null):String {
  val c=URL(url).openConnection() as HttpURLConnection
  c.connectTimeout=6000;c.readTimeout=6000;c.setRequestProperty("User-Agent","Mozilla/5.0");c.setRequestProperty("Referer",SITE+"/")
  try{if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");c.outputStream.use{it.write(body.toByteArray())}}
   check(c.responseCode in 200..299){"Provider unavailable (${c.responseCode})"}
   return c.inputStream.buffered().use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096);while(true){if(Thread.currentThread().isInterrupted)throw InterruptedException();val n=input.read(buf);if(n<0)break;check(out.size()+n<=2*1024*1024){"Provider response too large"};out.write(buf,0,n)};out.toString("UTF-8")}
  }finally{c.disconnect()}
 }
 fun list(id:String,title:String?=null):List<ArabicBroadcast>{
  cache[id]?.let{if(System.currentTimeMillis()-it.time<3600000)return it.rows}
  val roots=if(id.startsWith("arabic:"))listOf(id.removePrefix("arabic:")) else additionalDirectories[id]?:directories[id]?.let{listOf(it)}?:discover(id,title)
  val queue=java.util.ArrayDeque<String>();roots.forEach{queue.add(if(it.startsWith("/"))SITE+it else it)}
  val visited=HashSet<String>();val all=ArrayList<ArabicBroadcast>();var failure:Exception?=null
  while(queue.isNotEmpty()&&visited.size<24){
   if(Thread.currentThread().isInterrupted)throw InterruptedException()
   val url=queue.removeFirst();if(!visited.add(url))continue
   try{
    val html=request(java.net.URI.create(url).toASCIIString())
    all.addAll(parseList(html))
    seasonLinks(html).filter{it !in visited}.forEach{queue.add(it)}
   }catch(e:Exception){failure=e}
  }
  val rows=mergeRows(all)
  if(rows.isNotEmpty()&&failure==null)cache[id]=Cached(System.currentTimeMillis(),rows)
  if(rows.isEmpty()&&failure!=null)throw failure
  return rows
 }
 internal fun parseList(html:String):List<ArabicBroadcast>{
  val rows=ArrayList<ArabicBroadcast>()
  for(block in Regex("""<ul[^>]*class=['"][^'"]*\bepisodios\b[^'"]*['"][^>]*>(.*?)</ul>""",RegexOption.DOT_MATCHES_ALL).findAll(html)){
   for(item in Regex("<li\\b[^>]*>(.*?)</li>",RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1])){
    val s=item.groupValues[1];val page=Regex("href=['\"](https://watch[.]stardima[.]com/watch/episodes/[^'\"]+)['\"]").find(s)?.groupValues?.get(1)?:continue
    val title=text(Regex("<a[^>]*>(.*?)</a>",RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?:"")
    if(title.contains("مترجم"))continue
    val order=text(Regex("class=['\"]numerando['\"]>(.*?)</div>").find(s)?.groupValues?.get(1)?:"")
    val numbers=Regex("(\\d+)\\s*-\\s*(\\d+)").find(order)
    val season=numbers?.groupValues?.get(1)?.toIntOrNull()?:0
    val episode=numbers?.groupValues?.get(2)?.toIntOrNull()?:0
    val thumb=Regex("<img[^>]*src=['\"]([^'\"]+)").find(s)?.groupValues?.get(1)
    rows.add(ArabicBroadcast(page,"$order · ${title.ifBlank{"Arabic broadcast"}}",thumb,season,episode))
   }
  }
  return rows.distinctBy{it.page}.sortedWith(compareBy({it.season},{it.number},{it.label}))
 }
 fun stream(item:ArabicBroadcast)=StreamItem(name="Arabic broadcast",title=item.label,url="arabic-page:${item.page}",sources=item.alternatePages,quality="Arabic",releaseTitle=item.label,codecTags="Provider broadcast order · may contain paired stories",hasArabicHint=true,isDirect=true)
 private val playbackHeaders=ConcurrentHashMap<String,Map<String,String>>()
 fun headers(url:String)=playbackHeaders[url]?:emptyMap()
 private fun source(html:String):String? {
  val decoded=unpack(html)?:html
  return Regex("""(?:src|file)\s*:\s*['"](https://[^'"]+)['"]""").find(decoded)?.groupValues?.get(1)
    ?:Regex("""<source[^>]*src=['"](https://[^'"]+)['"]""").find(decoded)?.groupValues?.get(1)
 }
 private fun hyper(embed:String):String? {
  val html=request(embed)
  val encoded=Regex("""data-page="([^"]+)"""").find(html)?.groupValues?.get(1)?:return null
  val json=encoded.replace("&quot;","\"").replace("&#039;","'").replace("&amp;","&")
  val video=JSONObject(json).getJSONObject("props").getJSONObject("video")
  val servers=video.getJSONArray("servers")
  for(i in 0 until servers.length()){
   val server=servers.getJSONObject(i)
   // Only public declarative source descriptors; never evaluate scripts or load adverts.
   if(!server.optString("name").equals("Lulustream",true))continue
   val link=JSONObject(request("https://v2.hyperwatching.com/embed/${video.getString("hashid")}/server/${server.getInt("id")}/url")).getString("watch_url")
   val encodedTarget=android.net.Uri.parse(link).getQueryParameter("id")?:continue
   val target=URL(encodedTarget)
   if(target.protocol!="https"||target.host!="lulustream.com")continue
   val media=source(request(encodedTarget))?:continue
   val uri=URL(media)
   if(uri.protocol!="https")continue
   if(playbackHeaders.size>32)playbackHeaders.clear()
   playbackHeaders[media]=mapOf("Referer" to "https://lulustream.com/", "User-Agent" to "Mozilla/5.0")
   return media
  }
  return null
 }
 fun resolveAny(pages:List<String>):String {
  var failure:Exception?=null
  for(page in pages.distinct().take(5))try{return resolve(page)}catch(e:Exception){failure=e}
  throw failure?:IllegalStateException("No episode links available")
 }
 private fun readable(url:String):Boolean {
  val c=URL(url).openConnection() as HttpURLConnection
  c.connectTimeout=5000;c.readTimeout=5000;c.setRequestProperty("Range","bytes=0-511")
  c.setRequestProperty("User-Agent","Mozilla/5.0");headers(url).forEach{(k,v)->c.setRequestProperty(k,v)}
  return try {
   if(c.responseCode !in 200..299)false else {
    val bytes=ByteArray(512);val n=c.inputStream.use{it.read(bytes)}
    val prefix=if(n>0)String(bytes,0,n,Charsets.UTF_8).trimStart() else ""
    n>0&&!prefix.startsWith("<")
   }
  }finally{c.disconnect()}
 }
 fun resolve(page:String):String {
  val u=URL(page);check(u.protocol=="https"&&u.host=="watch.stardima.com"&&(u.path.startsWith("/watch/episodes/")||u.path.startsWith("/watch/movies/"))){"Unsupported episode page"}
  val html=request(page)
  val mediaType=if(u.path.startsWith("/watch/movies/"))"movie" else "tv"
  val post=Regex("""data-post=['"](\d+)['"]""").find(html)?.groupValues?.get(1)?:error("No player found")
  val servers=Regex("""data-nume=['"](\d+)['"]""").findAll(html).map{it.groupValues[1]}.distinct().take(6).toList()
  var reason="No supported video found"
  for(server in servers.ifEmpty{listOf("1")}){
   try{
    val response=JSONObject(request(SITE+"/watch/wp-admin/admin-ajax.php","action=doo_player_ajax&post=$post&nume=$server&type=$mediaType"))
    var embed=response.getString("embed_url")
    if(!embed.startsWith("https://"))embed=String(Base64.decode(embed,Base64.DEFAULT),Charsets.UTF_8)
    val target=URL(embed);check(target.protocol=="https")
    if(target.host in listOf("hyperwatching.com","v2.hyperwatching.com")){hyper(embed)?.let{if(readable(it))return it};continue}
    if(target.host!="watch.stardima.com")continue
    val media=source(request(embed))?:continue
    val url=URL(media)
    if(url.protocol=="https"&&(url.host.endsWith(".googleusercontent.com")||url.host=="watch.stardima.com")&&readable(media))return media
   }catch(e:Exception){reason=e.message?:reason}
  }
  error(reason)
 }
 // Decode a declarative packed source descriptor. Never evaluate JavaScript or load ads.
 internal fun unpack(script:String):String? {
  val match=Regex("""\}\('((?:\\.|[^'])*)',(\d+),(\d+),'([^']*)'\.split""",RegexOption.DOT_MATCHES_ALL).find(script)?:return null
  val radix=match.groupValues[2].toIntOrNull()?:return null;if(radix !in 2..36)return null
  val words=match.groupValues[4].split('|')
  return Regex("""\b[0-9a-zA-Z]+\b""").replace(match.groupValues[1]){token->val n=token.value.toIntOrNull(radix);if(n!=null&&n<words.size&&words[n].isNotEmpty())words[n]else token.value}.replace("\\'","'").replace("\\/","/")
 }
}
