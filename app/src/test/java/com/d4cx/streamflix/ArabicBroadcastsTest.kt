package com.d4cx.streamflix
import org.junit.Assert.*
import org.junit.Test

class ArabicBroadcastsTest {
 @Test fun discoveryCollectsAllMatchingIndexesWithoutRecommendingOtherShows(){
  val html="""<div class='result-item'><a href='https://watch.stardima.com/watch/tvshows/bears/'>كرتون الدببة&nbsp;الثلاثة مدبلج</a></div>
   <article><a href='https://watch.stardima.com/watch/seasons/bears-2/'>الدببة الثلاثة الموسم الثاني</a></article>
   <a href='https://watch.stardima.com/watch/tvshows/unrelated/'>العم جدو</a>
   <a href='https://watch.stardima.com/watch/tvshows/sub/'>الدببة الثلاثة مترجم</a>"""
  val links=ArabicBroadcasts.discoverLinks(html,"الدببة الثلاثة")
  assertEquals(2,links.size)
 }
 @Test fun parsesMultipleSeasonsWithExtraCssClassesAndKeepsAlternateServers(){
  val html="""<ul class='episodios expanded'><li><div class='numerando'>1 - 01</div><a href='https://watch.stardima.com/watch/episodes/a/'>One</a></li></ul>
  <ul class='episodios'><li><div class='numerando'>2 - 14</div><a href='https://watch.stardima.com/watch/episodes/b/'>Fourteen</a></li>
  <li><div class='numerando'>1 - 01</div><a href='https://watch.stardima.com/watch/episodes/alternate/'>Alternate</a></li></ul>"""
  val rows=ArabicBroadcasts.mergeRows(ArabicBroadcasts.parseList(html))
  assertEquals(2,rows.size);assertEquals(1,rows[0].alternatePages.size)
  assertEquals(2,rows[1].season);assertEquals(14,rows[1].number)
 }
 @Test fun followsOnlySeasonSectionLinks(){
  val html="""<div id='seasons'><a href='https://watch.stardima.com/watch/seasons/a/'>Season 2</a></div><div id='cast'><a href='https://watch.stardima.com/watch/seasons/unrelated/'>Recommended</a></div>"""
  assertEquals(listOf("https://watch.stardima.com/watch/seasons/a/"),ArabicBroadcasts.seasonLinks(html))
 }
 @Test fun ignoresFooterSeasonsEvenWithoutCastSection(){
  val html="""<div id='seasons'><div><a href='https://watch.stardima.com/watch/seasons/a/'>Season 2</a></div></div><footer><a href='https://watch.stardima.com/watch/seasons/unrelated/'>Other show</a></footer>"""
  assertEquals(listOf("https://watch.stardima.com/watch/seasons/a/"),ArabicBroadcasts.seasonLinks(html))
 }
 @Test fun broadcastOrderDoesNotInventStoryEpisodes(){
  val html="""<ul class='episodios'>
   <li><div class='numerando'>2 - 4</div><div class='episodiotitle'><a href='https://watch.stardima.com/watch/episodes/b/'>Paired broadcast</a></div></li>
   <li><div class='numerando'>1 - 3</div><div class='episodiotitle'><a href='https://watch.stardima.com/watch/episodes/a/'>Arabic broadcast</a></div></li>
   <li><div class='numerando'>1 - 3</div><div class='episodiotitle'><a href='https://watch.stardima.com/watch/episodes/a/'>Duplicate</a></div></li>
   <li><div class='numerando'>1 - 1</div><div class='episodiotitle'><a href='https://watch.stardima.com/watch/episodes/sub/'>مترجم</a></div></li>
  </ul><a href='https://watch.stardima.com/watch/episodes/unrelated/'>Recommendation</a>"""
  val rows=ArabicBroadcasts.parseList(html)
  assertEquals(2,rows.size);assertEquals(1,rows[0].season);assertEquals(3,rows[0].number)
  assertTrue(rows[1].label.contains("Paired broadcast"))
 }
 @Test fun unpacksSourceDataWithoutExecutingJavascript(){
  val packed="""eval(function(p,a,c,k,e,d){}('0.1={2:[{3:\'4://5/6\'}]}',10,7,'player|source|sources|src|https|example.com|video.mp4'.split('|'),0,{}))"""
  val decoded=ArabicBroadcasts.unpack(packed)!!
  assertTrue(decoded.contains("https://example.com/video.mp4"))
  assertNull(ArabicBroadcasts.unpack("window.location='untrusted';"))
 }
}
