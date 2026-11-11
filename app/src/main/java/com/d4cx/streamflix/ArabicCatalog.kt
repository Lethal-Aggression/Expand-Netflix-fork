package com.d4cx.streamflix

/** Browse the provider's live pages; never eagerly download its entire library. */
object ArabicCatalog {
 data class Page(val items:List<CatalogItem>,val hasNext:Boolean)
 private val cache=object:LinkedHashMap<String,Pair<Long,Page>>(16,.75f,true){
  override fun removeEldestEntry(entry:MutableMap.MutableEntry<String,Pair<Long,Page>>?)=size>12
 }
 private val finalPages=java.util.concurrent.ConcurrentHashMap<String,Int>()
 fun page(kind:String,page:Int):Page {
  require(page>=1)
  val key="$kind:$page"
  synchronized(cache){cache[key]?.let{if(System.currentTimeMillis()-it.first<300000)return it.second}}
  val paths=when(kind){"movies"->listOf("movies");"series"->listOf("tvshows");else->listOf("movies","tvshows")}
  val results=paths.map{path->
   if(page>(finalPages[path]?:Int.MAX_VALUE))Page(emptyList(),false) else {
    val url="https://watch.stardima.com/watch/$path/"+if(page>1)"page/$page/" else ""
    val result=parse(ArabicBroadcasts.request(url),page)
    if(!result.hasNext)finalPages[path]=page
    result
   }
  }
  val interleaved=ArrayList<CatalogItem>()
  for(i in 0 until (results.maxOfOrNull{it.items.size}?:0))results.forEach{it.items.getOrNull(i)?.let(interleaved::add)}
  val result=Page(interleaved.distinctBy{it.id},results.any{it.hasNext})
  synchronized(cache){cache[key]=System.currentTimeMillis() to result}
  return result
 }
 internal fun parse(html:String,page:Int):Page {
  val items=ArrayList<CatalogItem>()
  val archive=Regex("""<div[^>]*id=['"]archive-content['"][^>]*>""").find(html)
  var content=html
  if(archive!=null){
   val start=archive.range.last+1;var depth=1;var end=html.length
   for(tag in Regex("""</?div\b[^>]*>""").findAll(html,start)){
    depth+=if(tag.value.startsWith("</"))-1 else 1
    if(depth==0){end=tag.range.first;break}
   }
   content=html.substring(start,end)
  }
  for(article in Regex("""<article\b[^>]*>(.*?)</article>""",RegexOption.DOT_MATCHES_ALL).findAll(content)){
   val block=article.groupValues[1]
   val link=Regex("""href=['"](https://watch[.]stardima[.]com/watch/(movies|tvshows)/[^'"]+)['"]""").find(block)?:continue
   val title=Regex("""<h[23][^>]*>(.*?)</h[23]>""",RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
    ?:Regex("""<img[^>]*alt=['"]([^'"]+)['"]""").find(block)?.groupValues?.get(1)?:continue
   val name=title.replace(Regex("<[^>]+>"),"").replace("&amp;","&").replace("&#8211;","–").trim()
   if(name.isBlank())continue
   val poster=Regex("""<img[^>]*(?:data-src|src)=['"](https://[^'"]+)['"]""").find(block)?.groupValues?.get(1)?:""
   val year=Regex("""<span[^>]*>\s*((?:19|20)\d{2})\s*</span>""").find(block)?.groupValues?.get(1)?:""
   items.add(CatalogItem("arabic:"+link.groupValues[1],name,if(link.groupValues[2]=="movies")"movie" else "series",year,poster,genres=listOf("Arabic")))
  }
  val hasNext=Regex("""href=['"]https://watch[.]stardima[.]com/watch/(?:movies|tvshows)/page/${page+1}/['"]""").containsMatchIn(html)
  return Page(items.distinctBy{it.id},hasNext)
 }
}
