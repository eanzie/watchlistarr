package model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class ItemSpec extends AnyFlatSpec with Matchers {
  "Item.matches" should "match to another identical item" in {
    val item1 = Item(punch, List(punch, punch), punch)
    val item2 = item1

    item1.matches(item2) shouldBe true
    item2.matches(item1) shouldBe true
  }

  it should "ignore the title" in {
    val item1 = Item(punch, List(punch, punch), punch)
    val item2 = item1.copy(title = punch)

    item1.matches(item2) shouldBe true
    item2.matches(item1) shouldBe true
  }

  it should "fail if the categories do not match" in {
    val item1 = Item(punch, List(punch, punch), punch)
    val item2 = item1.copy(category = punch)

    item1.matches(item2) shouldBe false
    item2.matches(item1) shouldBe false
  }

  it should "succeed if one of the guids match" in {
    val item1 = Item(punch, List(punch, punch), punch)
    val item2 = item1.copy(guids = List(punch, item1.guids.head, punch))
    val item3 = item1.copy(guids = List(punch, item1.guids.last, punch))

    item1.matches(item2) shouldBe true
    item2.matches(item1) shouldBe true
    item1.matches(item3) shouldBe true
    item3.matches(item1) shouldBe true
    item2.matches(item3) shouldBe false
    item3.matches(item2) shouldBe false
  }

  it should "fail if the guids do not match" in {
    val item1 = Item(punch, List(punch, punch), punch)
    val item2 = item1.copy(guids = List(punch))

    item1.matches(item2) shouldBe false
    item2.matches(item1) shouldBe false
  }

  // PlexTokenSync matches via a matchKeys index rather than comparing every pair, so the two
  // must agree or the sync would start adding titles that are already there.
  "Item.matchKeys" should "agree with Item.matches for every pair" in {
    val shared = punch
    val items = List(
      Item(punch, List(punch, shared), "movie"),
      Item(punch, List(shared, punch), "movie"),
      Item(punch, List(shared), "show"),
      Item(punch, List(punch), "movie"),
      Item(punch, List(punch, punch), "show")
    )

    for {
      a <- items
      b <- items
    } withClue(s"$a vs $b: ") {
      val viaIndex = b.matchKeys.exists(a.matchKeys.contains)
      viaIndex shouldBe a.matches(b)
    }
  }

  "Item.getTvdbId" should "return a tvdbId" in {
    val item = Item(punch, List(punch, "tvdb://12345"), punch)

    item.getTvdbId shouldBe Some(12345L)
  }

  it should "return none if there is no tvdbId" in {
    val item = Item(punch, List(punch, "tmdb://12345"), punch)

    item.getTvdbId shouldBe None
  }

  it should "return none if there is a badly formatted tvdbId" in {
    val item = Item(punch, List(punch, "tvdb://l2345"), punch)

    item.getTvdbId shouldBe None
  }

  "Item.getTmdbId" should "return a tmdbId" in {
    val item = Item(punch, List(punch, "tmdb://12345"), punch)

    item.getTmdbId shouldBe Some(12345L)
  }

  it should "return none if there is no tmdbId" in {
    val item = Item(punch, List(punch, "tvdb://12345"), punch)

    item.getTmdbId shouldBe None
  }

  it should "return none if there is a badly formatted tmdbId" in {
    val item = Item(punch, List(punch, "tmdb://l2345"), punch)

    item.getTmdbId shouldBe None
  }

  private def punch: String = UUID.randomUUID().toString.take(5)
}
