package com.d4cx.streamflix
import org.junit.Assert.*
import org.junit.Test
class ArabicCatalogTest {
 @Test fun excludesRepeatedFeaturedTitlesOutsideTheArchive(){
  val card="""<article><a href='https://watch.stardima.com/watch/movies/film/'><img src='https://image.tmdb.org/a.jpg' alt='Film' /></a><h3>Film</h3></article>"""
  val html=card.replace("/film/","/featured/")+"<div id='archive-content'><div>"+card+"</div></div>"+card.replace("/film/","/sidebar/")
  val items=ArabicCatalog.parse(html,2).items
  assertEquals(1,items.size);assertTrue(items[0].id.endsWith("/film/"))
 }
 @Test fun browsesProviderTitlesWithoutPretendingTheyAreImdbEntries(){
  val html="""<article class="item"><a href="https://watch.stardima.com/watch/tvshows/belle-and-sebastian/"><img src="https://image.tmdb.org/t/p/w780/a.jpg" alt="Belle and Sebastian" /></a><h3 class="title">Belle &amp; Sebastian</h3><span>1981</span></article>
  <article><a href="https://watch.stardima.com/watch/movies/film/"><img src="https://image.tmdb.org/a.jpg" alt="Movie" /></a><h3>Movie</h3></article>
  <a href='https://watch.stardima.com/watch/tvshows/page/2/'>Next</a>"""
  val page=ArabicCatalog.parse(html,1)
  assertEquals(2,page.items.size);assertTrue(page.hasNext)
  assertEquals("series",page.items[0].type);assertEquals("movie",page.items[1].type)
  assertEquals("Belle & Sebastian",page.items[0].name);assertEquals("1981",page.items[0].year)
  assertTrue(page.items[0].id.startsWith("arabic:"))
  assertFalse(ArabicCatalog.parse(html,2).hasNext)
 }
}
